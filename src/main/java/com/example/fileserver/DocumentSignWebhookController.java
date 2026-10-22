package com.example.fileserver;

import java.io.IOException;
import java.util.Collections;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 显式开启的测试接收端；验证签名及投递时间后可靠留存原始凭据。 */
@RestController
@ConditionalOnProperty(name = "webhook.document-sign.enabled", havingValue = "true")
public class DocumentSignWebhookController {

	static final int MAX_BODY_BYTES = 1024 * 1024;
	private final ObjectMapper mapper;
	private final DocumentSignWebhookStorage storage;
	private final DocumentSignWebhookVerifier verifier;

	public DocumentSignWebhookController(ObjectMapper mapper, DocumentSignWebhookStorage storage,
			DocumentSignWebhookVerifier verifier) {
		this.mapper = mapper;
		this.storage = storage;
		this.verifier = verifier;
	}

	@PostMapping("/api/webhooks/document-sign")
	public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
		String eventId = requiredHeader(request, "X-Document-Sign-Event-Id", 255);
		String timestamp = requiredHeader(request, "X-Document-Sign-Timestamp", 255);
		String signature = requiredHeader(request, "X-Document-Sign-Signature", 4096);
		// 不依赖 Content-Length；流式/伪造长度的请求同样受限。
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
		}
		JsonNode json;
		try {
			json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
		} catch (JacksonException invalid) {
			throw badRequest("invalid JSON");
		}
		if (json == null || !json.isObject()) {
			throw badRequest("JSON object required");
		}
		for (String field : new String[] { "eventId", "jobId", "operation", "resultAvailable", "state", "timestamp" }) {
			if (!json.hasNonNull(field)) {
				throw badRequest("missing field: " + field);
			}
		}
		for (String field : new String[] { "eventId", "jobId", "operation", "state" }) {
			if (!json.get(field).isString() || json.get(field).asString().isBlank()) {
				throw badRequest("nonblank string required: " + field);
			}
		}
		if (!json.get("resultAvailable").isBoolean() || !eventId.equals(json.get("eventId").asString())) {
			throw badRequest("invalid resultAvailable or mismatched eventId");
		}
		validateUuid(eventId);
		validateUuid(json.get("jobId").asString());
		if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "QUARANTINED").contains(json.get("state").asString())) {
			throw badRequest("invalid terminal state");
		}
		try {
			if (!json.get("timestamp").isString()) {
				throw badRequest("timestamp must be an ISO instant");
			}
			Instant.parse(json.get("timestamp").asString());
		} catch (DateTimeParseException invalid) {
			throw badRequest("timestamp must be an ISO instant");
		}
		// 每次重试先独立验签，不能用数据库中的已接收记录绕过认证。
		verifier.verify(eventId, timestamp, signature, body);
		try {
			storage.capture(new DocumentSignWebhookEvent(eventId, body, timestamp, signature));
		} catch (ResponseStatusException conflict) {
			throw conflict;
		} catch (RuntimeException unavailable) {
			// 不回显原始请求、签名或数据库异常，非成功状态让发送方重试。
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "webhook storage unavailable");
		}
		return ResponseEntity.noContent().build();
	}

	private static String requiredHeader(HttpServletRequest request, String name, int maxLength) {
		var values = Collections.list(request.getHeaders(name));
		if (values.size() != 1 || values.getFirst().isBlank() || values.getFirst().length() > maxLength) {
			throw badRequest("missing or invalid header: " + name);
		}
		return values.getFirst();
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static void validateUuid(String value) {
		try {
			if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
				throw badRequest("invalid UUID");
			}
		} catch (IllegalArgumentException invalid) {
			throw badRequest("invalid UUID");
		}
	}
}
