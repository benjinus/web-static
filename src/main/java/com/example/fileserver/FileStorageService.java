package com.example.fileserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores uploaded bytes on disk and their metadata in the H2 database.
 *
 * <p>Layout:
 * <pre>
 * upload/&lt;md5&gt;.&lt;ext&gt;                       live files
 * upload/deleted/&lt;yyyy-MM-dd&gt;/&lt;name&gt;    files deleted on that day
 * </pre>
 */
@Service
public class FileStorageService {

	private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

	private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
			// Office
			"doc", "docx", "xls", "xlsx", "ppt", "pptx",
			// documents
			"pdf", "ofd", "txt",
			// raster images
			"jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "avif", "heic",
			// camera raw
			"arw", "nef", "dng", "rw2");

	/** Sub directory of {@code upload/} that keeps deleted files, partitioned by deletion date. */
	public static final String DELETED_DIR_NAME = "deleted";

	private static final DateTimeFormatter DATE_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final Path workingDir;
	private final Path uploadDir;
	private final Path deletedDir;
	private final ZoneId zone = ZoneId.systemDefault();

	private final FileRecordRepository repository;

	public FileStorageService(FileRecordRepository repository) throws IOException {
		this.repository = repository;
		this.workingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		this.uploadDir = workingDir.resolve("upload");
		this.deletedDir = uploadDir.resolve(DELETED_DIR_NAME);
		Files.createDirectories(uploadDir);
		Files.createDirectories(deletedDir);
		log.info("Upload directory ready: {}", uploadDir);
	}

	public Path getUploadDir() {
		return uploadDir;
	}

	public Path getDeletedDir() {
		return deletedDir;
	}

	// ------------------------------------------------------------------ store

	@Transactional
	public StoredFile store(byte[] content, String originalFilename, String contentType) throws IOException {
		if (content == null || content.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty");
		}
		String extension = extensionOf(originalFilename);
		String extWithoutDot = extension.isEmpty() ? "" : extension.substring(1);
		if (!ALLOWED_EXTENSIONS.contains(extWithoutDot)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"unsupported file type, allowed: " + String.join(", ", ALLOWED_EXTENSIONS));
		}

		String md5 = md5Hex(content);
		String storedName = md5 + extension;
		Path target = uploadDir.resolve(storedName).normalize();
		Files.write(target, content,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);

		String originalName = cleanOriginalName(originalFilename, storedName);
		Instant now = Instant.now();

		// Same content uploaded again reuses the stored bytes: refresh the live row instead of
		// creating a second one, so a stored file is always owned by exactly one live record.
		Optional<FileRecord> existing = repository.findFirstByStoredNameAndDeletedFalse(storedName);
		boolean duplicate = existing.isPresent();
		FileRecord record = existing.orElseGet(() -> new FileRecord(
				storedName, relativePath(target), originalName, contentType, md5, content.length, now));

		record.setOriginalName(originalName);
		record.setStoredName(storedName);
		record.setStoredPath(relativePath(target));
		record.setContentType(contentType);
		record.setMd5(md5);
		record.setSize(content.length);
		record.setUploadedAt(now);
		record.setDeleted(false);
		record.setDeletedAt(null);
		record.setDeletedPath(null);

		FileRecord saved = repository.save(record);
		log.info("Upload saved: id={}, storedPath={}, originalName={}, size={}, duplicate={}",
				saved.getId(), saved.getStoredPath(), originalName, content.length, duplicate);

		return new StoredFile(saved.getId(), storedName, saved.getStoredPath(), originalName,
				contentType, md5, content.length, duplicate);
	}

	// ------------------------------------------------------------------- read

	public List<FileRecord> listActive() {
		return repository.findByDeletedFalseOrderByUploadedAtDescIdDesc();
	}

	/** Resolves a stored file name to the bytes on disk plus the original download name. */
	public DownloadTarget downloadTarget(String storedName) throws IOException {
		Path path = resolveStoredFile(storedName);
		if (!Files.isRegularFile(path)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found: " + storedName);
		}
		FileRecord record = repository.findFirstByStoredNameAndDeletedFalse(storedName)
				.or(() -> repository.findFirstByStoredNameOrderByUploadedAtDesc(storedName))
				.orElse(null);

		String originalName = record != null && StringUtils.hasText(record.getOriginalName())
				? record.getOriginalName()
				: storedName;
		String contentType = record != null && StringUtils.hasText(record.getContentType())
				? record.getContentType()
				: probeContentType(path);
		return new DownloadTarget(path, originalName, contentType, Files.size(path));
	}

	// ----------------------------------------------------------------- delete

	/**
	 * Deletes one file: the bytes are moved into {@code upload/deleted/<today>/} and the
	 * database row is kept as history with {@code deleted=true}.
	 */
	public DeleteOutcome delete(Long id) {
		if (id == null) {
			return DeleteOutcome.failure(null, "缺少文件 id");
		}
		FileRecord record = repository.findById(id).orElse(null);
		if (record == null) {
			return DeleteOutcome.failure(id, "文件不存在");
		}
		if (record.isDeleted()) {
			return DeleteOutcome.failure(id, "文件已删除");
		}

		Path source = uploadDir.resolve(record.getStoredName()).normalize();
		if (!source.startsWith(uploadDir)) {
			return DeleteOutcome.failure(id, "存储路径非法");
		}

		String archivedPath = null;
		try {
			if (Files.isRegularFile(source)) {
				Path dayDir = deletedDir.resolve(LocalDate.now(zone).format(DATE_DIR_FORMAT));
				Files.createDirectories(dayDir);
				Path target = uniqueTarget(dayDir, record.getStoredName());
				// No REPLACE_EXISTING: an archived file must never be overwritten.
				Files.move(source, target);
				archivedPath = relativePath(target);
			} else {
				log.warn("Bytes already missing on disk, marking record {} as deleted anyway: {}", id, source);
			}
		} catch (IOException e) {
			log.error("Failed to archive file {} ({})", id, source, e);
			return DeleteOutcome.failure(id, "删除失败：" + e.getMessage());
		}

		record.setDeleted(true);
		record.setDeletedAt(Instant.now());
		record.setDeletedPath(archivedPath);
		repository.save(record);

		log.info("Deleted file: id={}, originalName={}, archivedPath={}, storedPath={}",
				id, record.getOriginalName(), archivedPath, record.getStoredPath());

		return DeleteOutcome.success(id, record.getOriginalName(), record.getStoredPath(), archivedPath);
	}

	// -------------------------------------------------------------- bootstrap

	/**
	 * Registers files that are already sitting in {@code upload/} but have no live database row —
	 * for example files uploaded before the metadata table existed.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void reconcileWithDatabase() {
		int imported = 0;
		List<String> missing = new ArrayList<>();
		try (Stream<Path> stream = Files.list(uploadDir)) {
			for (Path path : stream.filter(Files::isRegularFile).toList()) {
				String storedName = path.getFileName().toString();
				if (repository.findFirstByStoredNameAndDeletedFalse(storedName).isPresent()) {
					continue;
				}
				try {
					Instant uploadedAt = Files.getLastModifiedTime(path).toInstant();
					long size = Files.size(path);
					FileRecord record = new FileRecord(storedName, relativePath(path), storedName,
							probeContentType(path), md5FromStoredName(storedName), size, uploadedAt);
					repository.save(record);
					imported++;
				} catch (IOException e) {
					log.warn("Could not register existing file {}", path, e);
				}
			}
		} catch (IOException e) {
			log.warn("Could not scan upload directory {}", uploadDir, e);
		}

		for (FileRecord record : repository.findByDeletedFalse()) {
			Path path = uploadDir.resolve(record.getStoredName()).normalize();
			if (!Files.isRegularFile(path)) {
				missing.add(record.getId() + ":" + record.getOriginalName());
			}
		}
		if (imported > 0) {
			log.info("Registered {} pre-existing file(s) from {} into the database", imported, uploadDir);
		}
		if (!missing.isEmpty()) {
			log.warn("{} database record(s) have no file on disk (working directory changed?): {}",
					missing.size(), String.join(", ", missing));
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Guards against path traversal: only a bare file name inside {@code upload/} is accepted.
	 */
	private Path resolveStoredFile(String name) {
		if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name");
		}
		Path resolved = uploadDir.resolve(name).normalize();
		if (!resolved.startsWith(uploadDir)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name");
		}
		return resolved;
	}

	private static Path uniqueTarget(Path dir, String fileName) {
		Path candidate = dir.resolve(fileName);
		if (!Files.exists(candidate)) {
			return candidate;
		}
		int dot = fileName.lastIndexOf('.');
		String base = dot > 0 ? fileName.substring(0, dot) : fileName;
		String extension = dot > 0 ? fileName.substring(dot) : "";
		for (int i = 1; i < 1000; i++) {
			Path next = dir.resolve(base + "-" + System.currentTimeMillis() + "-" + i + extension);
			if (!Files.exists(next)) {
				return next;
			}
		}
		throw new IllegalStateException("Could not find a free archive name for " + fileName);
	}

	/** Path relative to the working directory, always with {@code /} separators. */
	private String relativePath(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		if (normalized.startsWith(workingDir)) {
			return workingDir.relativize(normalized).toString().replace(File.separatorChar, '/');
		}
		return normalized.toString();
	}

	private static String cleanOriginalName(String originalFilename, String fallback) {
		if (originalFilename == null || originalFilename.isBlank()) {
			return fallback;
		}
		String name = originalFilename.replace('\\', '/');
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		name = name.strip();
		if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
			return fallback;
		}
		return name.length() > 512 ? name.substring(0, 512) : name;
	}

	/** Stored names are {@code md5.ext}, so the checksum can be recovered from the name. */
	private static String md5FromStoredName(String storedName) {
		int dot = storedName.lastIndexOf('.');
		String base = dot > 0 ? storedName.substring(0, dot) : storedName;
		return base.matches("[0-9a-fA-F]{32}") ? base.toLowerCase(Locale.ROOT) : null;
	}

	private static String probeContentType(Path file) {
		try {
			String contentType = Files.probeContentType(file);
			if (StringUtils.hasText(contentType)) {
				return contentType;
			}
		} catch (Exception ignored) {
			// fall through
		}
		return "application/octet-stream";
	}

	private static String md5Hex(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			return HexFormat.of().formatHex(digest.digest(content));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 algorithm is not available", e);
		}
	}

	private static String extensionOf(String filename) {
		if (filename == null) {
			return "";
		}
		int dot = filename.lastIndexOf('.');
		int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
		if (dot > slash && dot < filename.length() - 1) {
			String extension = filename.substring(dot + 1);
			if (extension.matches("[A-Za-z0-9]+")) {
				return "." + extension.toLowerCase(Locale.ROOT);
			}
		}
		return "";
	}

	// ------------------------------------------------------------------ types

	public record StoredFile(Long id, String name, String storedPath, String originalName,
			String contentType, String md5, long size, boolean duplicate) {
	}

	public record DownloadTarget(Path path, String originalName, String contentType, long size) {
	}

	public record DeleteOutcome(boolean success, Long id, String originalName, String storedPath,
			String archivedPath, String reason) {

		static DeleteOutcome success(Long id, String originalName, String storedPath, String archivedPath) {
			return new DeleteOutcome(true, id, originalName, storedPath, archivedPath, null);
		}

		static DeleteOutcome failure(Long id, String reason) {
			return new DeleteOutcome(false, id, null, null, null, reason);
		}
	}
}
