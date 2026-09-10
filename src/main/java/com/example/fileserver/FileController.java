package com.example.fileserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/files")
public class FileController {

	private static final Logger log = LoggerFactory.getLogger(FileController.class);

	private final FileStorageService storage;

	public FileController(FileStorageService storage) {
		this.storage = storage;
	}

	/**
	 * Uploads one or more files in a single multipart request. Repeating the part name
	 * ({@code fileUpload} or {@code file}) sends several files at once.
	 *
	 * <p>Every part is stored independently, so one rejected file never discards the rest;
	 * {@code results} mirrors the request order and carries the outcome of each part.
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public UploadBatchResult upload(
			@RequestParam(name = "fileUpload", required = false) List<MultipartFile> fileUploads,
			@RequestParam(name = "file", required = false) List<MultipartFile> files,
			HttpServletRequest request) {
		printRequestParameters(request);

		List<MultipartFile> parts = new ArrayList<>();
		if (fileUploads != null) {
			parts.addAll(fileUploads);
		}
		if (files != null) {
			parts.addAll(files);
		}
		parts.removeIf(part -> part == null || part.isEmpty());
		if (parts.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"file part is empty, use form field 'fileUpload' or 'file'");
		}

		List<UploadItem> results = new ArrayList<>(parts.size());
		int uploaded = 0;
		for (int index = 0; index < parts.size(); index++) {
			MultipartFile part = parts.get(index);
			String name = part.getOriginalFilename();
			try {
				FileStorageService.StoredFile stored = storage.store(
						part.getBytes(), name, part.getContentType());
				results.add(new UploadItem(index, name, true, toUploadResult(stored), null));
				uploaded++;
			} catch (ResponseStatusException e) {
				results.add(new UploadItem(index, name, false, null,
						e.getReason() != null ? e.getReason() : e.getStatusCode().toString()));
			} catch (IOException e) {
				log.error("Failed to store uploaded part {}", name, e);
				results.add(new UploadItem(index, name, false, null, "保存失败：" + e.getMessage()));
			}
		}

		return new UploadBatchResult(parts.size(), uploaded, parts.size() - uploaded, results);
	}

	private static UploadResult toUploadResult(FileStorageService.StoredFile stored) {
		return new UploadResult(
				stored.id(),
				stored.name(),
				stored.storedPath(),
				stored.originalName(),
				stored.contentType(),
				stored.md5(),
				stored.size(),
				stored.duplicate(),
				downloadUrl(stored.name()));
	}

	@GetMapping
	public List<FileEntry> list() {
		return storage.listActive().stream()
				.map(FileController::toFileEntry)
				.toList();
	}

	/** Downloads by stored file name, always offering the original client file name. */
	@GetMapping("/{filename:.+}")
	public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
		FileStorageService.DownloadTarget target = storage.downloadTarget(filename);
		return ResponseEntity.ok()
				.contentType(parseMediaType(target.contentType()))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment()
								.filename(target.originalName(), StandardCharsets.UTF_8)
								.build()
								.toString())
				.contentLength(target.size())
				.body(new FileSystemResource(target.path()));
	}

	/** Deletes a single file; its bytes move to {@code upload/deleted/<date>/}. */
	@DeleteMapping("/{id}")
	public DeleteResult deleteOne(@PathVariable Long id) {
		FileStorageService.DeleteOutcome outcome = storage.delete(id);
		if (!outcome.success()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, outcome.reason());
		}
		return toDeleteResult(List.of(outcome));
	}

	/**
	 * Deletes several files at once, e.g. {@code DELETE /api/files?ids=1,2,3}. Every id is
	 * attempted independently so one failure never blocks the others.
	 */
	@DeleteMapping
	public DeleteResult deleteMany(@RequestParam(name = "ids", required = false) List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query parameter 'ids' is required");
		}
		List<FileStorageService.DeleteOutcome> outcomes = new ArrayList<>();
		for (Long id : ids.stream().filter(Objects::nonNull).distinct().toList()) {
			outcomes.add(storage.delete(id));
		}
		if (outcomes.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query parameter 'ids' is required");
		}
		return toDeleteResult(outcomes);
	}

	// ---------------------------------------------------------------- mapping

	private static FileEntry toFileEntry(FileRecord record) {
		return new FileEntry(
				record.getId(),
				record.getStoredName(),
				record.getOriginalName(),
				record.getStoredPath(),
				record.getSize(),
				record.getContentType(),
				record.getMd5(),
				record.getUploadedAt() != null ? record.getUploadedAt().toEpochMilli() : 0L,
				downloadUrl(record.getStoredName()));
	}

	private static DeleteResult toDeleteResult(List<FileStorageService.DeleteOutcome> outcomes) {
		List<DeletedItem> deleted = new ArrayList<>();
		List<FailedItem> failed = new ArrayList<>();
		for (FileStorageService.DeleteOutcome outcome : outcomes) {
			if (outcome.success()) {
				deleted.add(new DeletedItem(outcome.id(), outcome.originalName(),
						outcome.storedPath(), outcome.archivedPath()));
			} else {
				failed.add(new FailedItem(outcome.id(), outcome.reason()));
			}
		}
		return new DeleteResult(outcomes.size(), deleted.size(), deleted, failed);
	}

	private static String downloadUrl(String storedName) {
		return "/api/files/" + UriUtils.encodePathSegment(storedName, StandardCharsets.UTF_8);
	}

	private static MediaType parseMediaType(String contentType) {
		if (StringUtils.hasText(contentType)) {
			try {
				return MediaType.parseMediaType(contentType);
			} catch (Exception ignored) {
				// fall through to octet-stream
			}
		}
		return MediaType.APPLICATION_OCTET_STREAM;
	}

	private static void printRequestParameters(HttpServletRequest request) {
		log.info("--- upload request parameters (query + form fields) ---");
		request.getParameterMap().forEach((name, values) ->
				log.info("param: {} = {}", name, String.join(", ", values)));
		log.info("--- end of request parameters ---");
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("message", exception.getReason() != null ? exception.getReason()
				: exception.getStatusCode().toString());
		return ResponseEntity.status(exception.getStatusCode()).body(body);
	}

	// ------------------------------------------------------------------ types

	public record UploadResult(Long id, String name, String storedPath, String originalName,
			String contentType, String md5, long size, boolean duplicate, String downloadUrl) {
	}

	/** Outcome of one part of a multipart upload, in request order. */
	public record UploadItem(int index, String name, boolean success, UploadResult file, String reason) {
	}

	public record UploadBatchResult(int requested, int uploaded, int failed, List<UploadItem> results) {
	}

	public record FileEntry(Long id, String name, String originalName, String storedPath,
			long size, String contentType, String md5, long uploadedAt, String downloadUrl) {
	}

	public record DeleteResult(int requested, int deleted, List<DeletedItem> files, List<FailedItem> failed) {
	}

	public record DeletedItem(Long id, String originalName, String storedPath, String archivedPath) {
	}

	public record FailedItem(Long id, String reason) {
	}
}
