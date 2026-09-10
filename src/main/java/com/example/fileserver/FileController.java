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

/**
 * 文件接口层：对外暴露上传、列表、下载、删除四类 REST 接口。
 *
 * <p>本类只负责「协议转换」——解析 HTTP 请求、调用 {@link FileStorageService} 完成真正的存储，
 * 再把结果包装成 JSON 返回；不直接操作文件和数据库。
 *
 * <p>接口一览（统一前缀 {@code /api/files}）：
 * <ul>
 *   <li>{@code POST /} —— multipart 上传，一次可传多个文件</li>
 *   <li>{@code GET /} —— 列出在库文件</li>
 *   <li>{@code GET /{存储名}} —— 下载，响应头带原始文件名</li>
 *   <li>{@code DELETE /{id}} —— 删除单个文件</li>
 *   <li>{@code DELETE /?ids=1,2,3} —— 批量删除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

	private static final Logger log = LoggerFactory.getLogger(FileController.class);

	/** 真正的存储逻辑（落盘 + 写数据库）都委托给它。 */
	private final FileStorageService storage;

	public FileController(FileStorageService storage) {
		this.storage = storage;
	}

	/**
	 * 上传一个或多个文件（同一个 multipart 请求内）。
	 *
	 * <p>字段名用 {@code fileUpload}（兼容旧的 {@code file}）；重复同一个字段名即可一次传多个文件，
	 * 例如 {@code -F fileUpload=@a.pdf -F fileUpload=@b.ofd}。
	 *
	 * <p>每个 part 独立处理：某个文件被拒绝（类型不支持、内容为空等）不会影响同一请求里的其他文件。
	 * 返回的 {@code results} 与请求中的 part 顺序一一对应，调用方按下标就能知道每个文件的结果。
	 *
	 * @param fileUploads 字段名为 {@code fileUpload} 的所有文件 part
	 * @param files       字段名为 {@code file} 的所有文件 part（兼容旧调用方）
	 * @param request     用于把查询参数和表单字段打印到日志
	 * @return 本次上传的汇总结果与每个文件的明细
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public UploadBatchResult upload(
			@RequestParam(name = "fileUpload", required = false) List<MultipartFile> fileUploads,
			@RequestParam(name = "file", required = false) List<MultipartFile> files,
			HttpServletRequest request) {
		printRequestParameters(request);

		// 两个字段名的 part 合并成一个有序列表：先 fileUpload 后 file，保持调用方的书写顺序
		List<MultipartFile> parts = new ArrayList<>();
		if (fileUploads != null) {
			parts.addAll(fileUploads);
		}
		if (files != null) {
			parts.addAll(files);
		}
		// 浏览器在用户没选文件时也会提交一个空 part，这里统一剔除
		parts.removeIf(part -> part == null || part.isEmpty());
		if (parts.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"file part is empty, use form field 'fileUpload' or 'file'");
		}

		List<UploadItem> results = new ArrayList<>(parts.size());
		int uploaded = 0;
		// 逐个文件独立处理并捕获异常，保证「一个失败不拖累其他」
		for (int index = 0; index < parts.size(); index++) {
			MultipartFile part = parts.get(index);
			String name = part.getOriginalFilename();
			try {
				FileStorageService.StoredFile stored = storage.store(
						part.getBytes(), name, part.getContentType());
				results.add(new UploadItem(index, name, true, toUploadResult(stored), null));
				uploaded++;
			} catch (ResponseStatusException e) {
				// 业务性拒绝（例如后缀不在白名单），原因直接回给前端展示
				results.add(new UploadItem(index, name, false, null,
						e.getReason() != null ? e.getReason() : e.getStatusCode().toString()));
			} catch (IOException e) {
				// 写盘等 IO 异常：记录堆栈，但同样只让这一个文件失败
				log.error("Failed to store uploaded part {}", name, e);
				results.add(new UploadItem(index, name, false, null, "保存失败：" + e.getMessage()));
			}
		}

		return new UploadBatchResult(parts.size(), uploaded, parts.size() - uploaded, results);
	}

	/** 把存储层的结果转成对外的上传结果对象（顺带拼出下载地址）。 */
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

	/** 列出所有在库文件（不含已删除的历史记录），按上传时间倒序。 */
	@GetMapping
	public List<FileEntry> list() {
		return storage.listActive().stream()
				.map(FileController::toFileEntry)
				.toList();
	}

	/**
	 * 按存储名下载文件。
	 *
	 * <p>路径变量写成 {@code {filename:.+}} 是为了让正则能匹配到文件名里的点（否则 Spring 会截断扩展名）。
	 * 响应头里的文件名用原始上传名而不是存储名，这样用户下载下来看到的是自己当初的文件名。
	 */
	@GetMapping("/{filename:.+}")
	public ResponseEntity<Resource> download(@PathVariable String filename) throws IOException {
		FileStorageService.DownloadTarget target = storage.downloadTarget(filename);
		return ResponseEntity.ok()
				.contentType(parseMediaType(target.contentType()))
				// filename(name, UTF_8) 会按 RFC 5987 生成 filename*=UTF-8''...，中文名才不会乱码
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment()
								.filename(target.originalName(), StandardCharsets.UTF_8)
								.build()
								.toString())
				.contentLength(target.size())
				.body(new FileSystemResource(target.path()));
	}

	/** 删除单个文件：文件被移入 {@code upload/deleted/日期/}，数据库记录保留为历史。 */
	@DeleteMapping("/{id}")
	public DeleteResult deleteOne(@PathVariable Long id) {
		FileStorageService.DeleteOutcome outcome = storage.delete(id);
		if (!outcome.success()) {
			// 单个删除失败（不存在／已删除）直接以 404 表达，前端按普通错误处理即可
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, outcome.reason());
		}
		return toDeleteResult(List.of(outcome));
	}

	/**
	 * 批量删除，例如 {@code DELETE /api/files?ids=1,2,3}。
	 *
	 * <p>每个 id 独立尝试，失败项只出现在 {@code failed} 里，不会中断其余文件的删除，
	 * 所以前端「删除选中」可以一次性提交全部选中项。
	 */
	@DeleteMapping
	public DeleteResult deleteMany(@RequestParam(name = "ids", required = false) List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query parameter 'ids' is required");
		}
		List<FileStorageService.DeleteOutcome> outcomes = new ArrayList<>();
		// 去重并跳过 null，避免同一个 id 被处理两次（第二次必然报「文件已删除」）
		for (Long id : ids.stream().filter(Objects::nonNull).distinct().toList()) {
			outcomes.add(storage.delete(id));
		}
		if (outcomes.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query parameter 'ids' is required");
		}
		return toDeleteResult(outcomes);
	}

	// ------------------------------------------------------------ 实体转 DTO

	/** 数据库实体 -> 列表项（时间转成毫秒时间戳，前端直接 new Date() 即可）。 */
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

	/** 把逐个文件的删除结果拆成「成功列表 + 失败列表」，并统计总数。 */
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

	/** 拼下载地址；存储名做 URL 编码，避免特殊字符破坏路径。 */
	private static String downloadUrl(String storedName) {
		return "/api/files/" + UriUtils.encodePathSegment(storedName, StandardCharsets.UTF_8);
	}

	/** 解析 Content-Type，解析不出来就退化成二进制流（下载行为不受影响）。 */
	private static MediaType parseMediaType(String contentType) {
		if (StringUtils.hasText(contentType)) {
			try {
				return MediaType.parseMediaType(contentType);
			} catch (Exception ignored) {
				// 忽略非法 Content-Type，走下面的兜底分支
			}
		}
		return MediaType.APPLICATION_OCTET_STREAM;
	}

	/** 把上传请求里的查询参数和表单字段打印出来，方便排查调用方传了什么。 */
	private static void printRequestParameters(HttpServletRequest request) {
		log.info("--- upload request parameters (query + form fields) ---");
		request.getParameterMap().forEach((name, values) ->
				log.info("param: {} = {}", name, String.join(", ", values)));
		log.info("--- end of request parameters ---");
	}

	/**
	 * 只处理本控制器抛出的 {@link ResponseStatusException}，统一成 {@code {"message": "..."}}，
	 * 让前端可以用同一套逻辑读取错误原因。
	 */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("message", exception.getReason() != null ? exception.getReason()
				: exception.getStatusCode().toString());
		return ResponseEntity.status(exception.getStatusCode()).body(body);
	}

	// -------------------------------------------------------------- 对外 DTO

	/** 单个文件上传成功后的信息。 */
	public record UploadResult(Long id, String name, String storedPath, String originalName,
			String contentType, String md5, long size, boolean duplicate, String downloadUrl) {
	}

	/** 一次 multipart 上传中某个 part 的结果，按请求顺序排列。 */
	public record UploadItem(int index, String name, boolean success, UploadResult file, String reason) {
	}

	/** 多文件上传的汇总：总数、成功数、失败数，以及每个文件的明细。 */
	public record UploadBatchResult(int requested, int uploaded, int failed, List<UploadItem> results) {
	}

	/** 列表项（在库文件）。 */
	public record FileEntry(Long id, String name, String originalName, String storedPath,
			long size, String contentType, String md5, long uploadedAt, String downloadUrl) {
	}

	/** 删除结果汇总。 */
	public record DeleteResult(int requested, int deleted, List<DeletedItem> files, List<FailedItem> failed) {
	}

	/** 删除成功的文件，含归档后的新位置。 */
	public record DeletedItem(Long id, String originalName, String storedPath, String archivedPath) {
	}

	/** 删除失败的文件及原因（不存在／已删除／IO 失败等）。 */
	public record FailedItem(Long id, String reason) {
	}
}
