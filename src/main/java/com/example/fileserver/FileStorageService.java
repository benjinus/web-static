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
 * 文件存储服务：把「二进制内容」写到磁盘，把「元数据」写进 H2 数据库。
 *
 * <p>磁盘目录结构（相对进程工作目录）：
 * <pre>
 * upload/&lt;md5&gt;.&lt;后缀&gt;                    在库文件，文件名由内容 MD5 + 原后缀组成
 * upload/deleted/&lt;yyyy-MM-dd&gt;/&lt;文件名&gt;   已删除文件，按删除当天日期分目录归档
 * </pre>
 *
 * <p>几个关键设计：
 * <ul>
 *   <li><b>按内容去重</b>：同后缀 + 同内容的文件共用同一个磁盘文件（文件名就是 MD5）。
 *       重复上传不会新增记录，而是刷新原有在库记录，保证「一个磁盘文件只属于一条在库记录」。</li>
 *   <li><b>软删除</b>：删除只是把文件挪进 {@code deleted/日期/}，数据库行保留并标记 {@code deleted=true}，
 *       同时记下归档路径，便于追溯。</li>
 *   <li><b>启动对账</b>：应用启动时扫描 {@code upload/}，把数据库里没有在库记录的既有文件补登记进来，
 *       这样升级前上传的老文件不会从列表里消失。</li>
 * </ul>
 */
@Service
public class FileStorageService {

	private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

	/**
	 * 允许上传的后缀白名单（不含点，全小写）。
	 * 注意：页面脚本顶部的 {@code ALLOWED_EXTENSIONS} 和文件选择框的 {@code accept} 是它的镜像，
	 * 增删这里时那两处要一起改。
	 */
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
			// Office 文档
			"doc", "docx", "xls", "xlsx", "ppt", "pptx",
			// 普通文档
			"pdf", "ofd", "txt",
			// 位图图片
			"jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff", "avif", "heic",
			// 相机 RAW
			"arw", "nef", "dng", "rw2");

	/** {@code upload/} 下用于存放已删除文件的子目录名。 */
	public static final String DELETED_DIR_NAME = "deleted";

	/** 归档目录的日期分层格式，例如 {@code 2026-09-10}。 */
	private static final DateTimeFormatter DATE_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	/** 进程工作目录，用于把绝对路径换算成入库的相对路径。 */
	private final Path workingDir;
	/** 在库文件目录：{@code <工作目录>/upload}。 */
	private final Path uploadDir;
	/** 已删除文件根目录：{@code <工作目录>/upload/deleted}。 */
	private final Path deletedDir;
	/** 计算「今天」用的时区，决定文件归档到哪个日期目录。 */
	private final ZoneId zone = ZoneId.systemDefault();

	/** 元数据仓储（Spring Data JPA）。 */
	private final FileRecordRepository repository;

	public FileStorageService(FileRecordRepository repository) throws IOException {
		this.repository = repository;
		// 以进程工作目录为基准，保证 bootRun 和 java -jar 的行为一致（各自在当前目录下建 upload/）
		this.workingDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
		this.uploadDir = workingDir.resolve("upload");
		this.deletedDir = uploadDir.resolve(DELETED_DIR_NAME);
		// 提前建好目录，后续流程不必反复判断是否存在
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

	// ------------------------------------------------------------------ 保存

	/**
	 * 保存一个上传的文件：先校验类型、按内容算 MD5 落盘，再写入/刷新数据库记录。
	 *
	 * @param content          文件二进制内容
	 * @param originalFilename 客户端提供的原始文件名（用于取后缀并入库展示）
	 * @param contentType      客户端提供的 MIME 类型，可能为空
	 * @return 保存结果，含记录 id、存储名、存储路径以及是否为重复内容
	 * @throws ResponseStatusException 内容为空或后缀不在白名单时抛出（400）
	 */
	@Transactional
	public StoredFile store(byte[] content, String originalFilename, String contentType) throws IOException {
		if (content == null || content.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty");
		}
		// 后缀决定白名单校验结果，也决定最终存储名
		String extension = extensionOf(originalFilename);
		String extWithoutDot = extension.isEmpty() ? "" : extension.substring(1);
		if (!ALLOWED_EXTENSIONS.contains(extWithoutDot)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"unsupported file type, allowed: " + String.join(", ", ALLOWED_EXTENSIONS));
		}

		// 存储名 = 内容 MD5 + 原后缀：同内容同后缀天然复用同一个文件
		String md5 = md5Hex(content);
		String storedName = md5 + extension;
		Path target = uploadDir.resolve(storedName).normalize();
		Files.write(target, content,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);

		String originalName = cleanOriginalName(originalFilename, storedName);
		Instant now = Instant.now();

		// 同一份内容再次上传时复用磁盘文件：刷新已有在库记录，而不是插入第二条，
		// 这样能保证「一个磁盘文件只被一条在库记录持有」，删除时不会互相影响。
		Optional<FileRecord> existing = repository.findFirstByStoredNameAndDeletedFalse(storedName);
		boolean duplicate = existing.isPresent();
		FileRecord record = existing.orElseGet(() -> new FileRecord(
				storedName, relativePath(target), originalName, contentType, md5, content.length, now));

		// 统一赋值：新增和刷新走同一段逻辑，顺便把可能残留的删除标记清掉
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

	// ------------------------------------------------------------------ 查询

	/** 列出所有在库文件，按上传时间倒序（最新的排最前）。 */
	public List<FileRecord> listActive() {
		return repository.findByDeletedFalseOrderByUploadedAtDescIdDesc();
	}

	/**
	 * 解析下载请求：定位磁盘文件，并取出应该回给客户端的原始文件名。
	 *
	 * <p>文件名优先取在库记录里的原始名；万一没有记录（例如手工放进 upload/ 的文件还没被登记），
	 * 就退化成存储名，保证下载始终可用。
	 *
	 * @param storedName 存储名（不含目录）
	 * @throws ResponseStatusException 文件名为非法路径（400）或文件不存在（404）
	 */
	public DownloadTarget downloadTarget(String storedName) throws IOException {
		Path path = resolveStoredFile(storedName);
		if (!Files.isRegularFile(path)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file not found: " + storedName);
		}
		// 先找在库记录；若该文件已被删除，则回退到最近一条历史记录，至少还能拿到原始文件名
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

	// ------------------------------------------------------------------ 删除

	/**
	 * 删除一个文件（软删除）：
	 * 把磁盘文件移动到 {@code upload/deleted/<今天>/}，数据库记录保留并标记为已删除。
	 *
	 * <p>先移动文件再改数据库：如果移动失败（例如权限问题），记录不会被标记成已删除，
	 * 调用方会收到失败原因，状态不会自相矛盾。
	 *
	 * @return 删除结果；失败时 {@code success=false} 并带上原因，不抛异常，方便批量删除时逐条汇报
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
			// 幂等：重复删除只回报状态，不做任何改动
			return DeleteOutcome.failure(id, "文件已删除");
		}

		Path source = uploadDir.resolve(record.getStoredName()).normalize();
		// 防御性校验：库里的存储名如果被篡改，也不能让它指向 upload/ 之外
		if (!source.startsWith(uploadDir)) {
			return DeleteOutcome.failure(id, "存储路径非法");
		}

		String archivedPath = null;
		try {
			if (Files.isRegularFile(source)) {
				// 归档目录按「删除当天」分日期，不同日期删除的文件互不混杂
				Path dayDir = deletedDir.resolve(LocalDate.now(zone).format(DATE_DIR_FORMAT));
				Files.createDirectories(dayDir);
				Path target = uniqueTarget(dayDir, record.getStoredName());
				// 不使用 REPLACE_EXISTING：同名归档文件绝不能被覆盖，宁可改名
				Files.move(source, target);
				archivedPath = relativePath(target);
			} else {
				// 磁盘上已经没有这个文件（例如被手工删掉），仍然把记录标记为已删除，避免列表里留着僵尸项
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

	// ------------------------------------------------------------ 启动时对账

	/**
	 * 应用启动就绪后与磁盘对账（{@link ApplicationReadyEvent}）。
	 *
	 * <p>做两件事：
	 * <ol>
	 *   <li>把 {@code upload/} 里存在、但数据库中没有在库记录的文件补登记一条记录
	 *       （覆盖「引入数据库之前上传的老文件」这种场景），原始名取存储名，上传时间取文件修改时间。</li>
	 *   <li>反过来检查有没有记录找不到对应文件，只打警告日志、<b>不自动改数据</b>，
	 *       避免工作目录搞错时误把正常记录标记成已删除。</li>
	 * </ol>
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void reconcileWithDatabase() {
		int imported = 0;
		List<String> missing = new ArrayList<>();
		// upload/deleted 是子目录，isRegularFile 过滤后不会被当成待登记的文件
		try (Stream<Path> stream = Files.list(uploadDir)) {
			for (Path path : stream.filter(Files::isRegularFile).toList()) {
				String storedName = path.getFileName().toString();
				// 已有在库记录就跳过；注意只判断「在库」，被删除过的文件重新出现时应重新登记
				if (repository.findFirstByStoredNameAndDeletedFalse(storedName).isPresent()) {
					continue;
				}
				try {
					Instant uploadedAt = Files.getLastModifiedTime(path).toInstant();
					long size = Files.size(path);
					// 存储名是 md5.后缀，所以原始名先沿用存储名，md5 可以直接从文件名反推
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

		// 只体检不治疗：记录在库但磁盘缺文件，通常是工作目录变了，这里仅提示
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

	// ------------------------------------------------------------ 内部工具方法

	/**
	 * 把存储名解析成 {@code upload/} 内的绝对路径，同时防目录穿越。
	 *
	 * <p>只接受「裸文件名」：带 {@code /}、{@code \} 或 {@code ..} 一律拒绝，
	 * 解析后再校验一次结果是否仍在 {@code upload/} 下，双保险。
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

	/**
	 * 在归档目录里找一个不冲突的文件名。
	 *
	 * <p>正常情况下直接用原存储名；只有当同一天重复删除了同一个文件（先传、删、再传、再删）时，
	 * 才会退化成 {@code 原名-时间戳-序号.后缀}，确保归档文件永远不互相覆盖。
	 */
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

	/**
	 * 转成相对工作目录的路径（统一用 {@code /} 分隔）后入库，
	 * 这样数据库里存的是可读的 {@code upload/xxx.pdf}，而不是带机器信息的绝对路径。
	 */
	private String relativePath(Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		if (normalized.startsWith(workingDir)) {
			return workingDir.relativize(normalized).toString().replace(File.separatorChar, '/');
		}
		return normalized.toString();
	}

	/**
	 * 清洗客户端传来的原始文件名：去掉目录部分（某些客户端会带完整路径）、去空白，
	 * 非法或为空时退回存储名，并截断到数据库列宽（512）以内。
	 */
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

	/** 存储名格式是 {@code md5.后缀}，因此可以从文件名反推 MD5；不像就返回 null。 */
	private static String md5FromStoredName(String storedName) {
		int dot = storedName.lastIndexOf('.');
		String base = dot > 0 ? storedName.substring(0, dot) : storedName;
		return base.matches("[0-9a-fA-F]{32}") ? base.toLowerCase(Locale.ROOT) : null;
	}

	/** 探测 MIME 类型；探测不到（新格式如 avif/raw 常常探测不出）就用二进制流兜底。 */
	private static String probeContentType(Path file) {
		try {
			String contentType = Files.probeContentType(file);
			if (StringUtils.hasText(contentType)) {
				return contentType;
			}
		} catch (Exception ignored) {
			// 探测失败不致命，走下面的兜底返回值
		}
		return "application/octet-stream";
	}

	/** 计算内容的 MD5（十六进制小写），用于生成存储名和判断重复上传。 */
	private static String md5Hex(byte[] content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			return HexFormat.of().formatHex(digest.digest(content));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 algorithm is not available", e);
		}
	}

	/**
	 * 取文件后缀（含点，小写）。后缀必须全是字母数字，否则视为没有后缀；
	 * 同时用最后一个路径分隔符兜住 {@code archive.tar.gz} 这类多点文件名（取最后一段）。
	 */
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

	// ------------------------------------------------------------ 内部数据载体

	/** 保存成功后的结果（id 为数据库主键）。 */
	public record StoredFile(Long id, String name, String storedPath, String originalName,
			String contentType, String md5, long size, boolean duplicate) {
	}

	/** 下载所需的信息：磁盘路径 + 应回给客户端的原始文件名。 */
	public record DownloadTarget(Path path, String originalName, String contentType, long size) {
	}

	/** 单个文件的删除结果；失败时 success=false 且只有 reason 有值。 */
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
