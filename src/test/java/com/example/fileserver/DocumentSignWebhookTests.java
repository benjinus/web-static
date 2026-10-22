package com.example.fileserver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
		"webhook.document-sign.enabled=true",
		"webhook.document-sign.signing-secret=test-only-secret",
		"spring.datasource.url=jdbc:h2:mem:webhook-tests;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DocumentSignWebhookTests {
	@Autowired MockMvc mvc;
	@Autowired DocumentSignWebhookEventRepository repository;
	@MockitoBean FileStorageService fileStorage;

	@BeforeEach
	void clean() { repository.deleteAll(); }

	static byte[] body(String id) {
		return ("{\n  \"eventId\":\"" + id + "\", \"jobId\":\"00000000-0000-0000-0000-000000009402\",\"operation\":\"sign\","
				+ "\"resultAvailable\":true,\"state\":\"SUCCEEDED\",\"timestamp\":\"2026-10-22T04:00:00Z\"\n}")
				.getBytes(StandardCharsets.UTF_8);
	}

	static MockHttpServletRequestBuilder request(String id, byte[] body) {
		return requestAt(id, body, java.time.Instant.now().toString());
	}

	static MockHttpServletRequestBuilder requestAt(String id, byte[] body, String timestamp) {
		return post("/api/webhooks/document-sign").contentType("application/json")
				.header("X-Document-Sign-Event-Id", id)
				.header("X-Document-Sign-Timestamp", timestamp)
				.header("X-Document-Sign-Signature", sign(id, timestamp, body))
				.content(body);
	}

	// 测试签名器独立用 DataOutputStream 构造协议帧；另有固定摘要向量锁定协议。
	static String sign(String id, String timestamp, byte[] body) {
		try {
			var bytes = new java.io.ByteArrayOutputStream();
			var output = new java.io.DataOutputStream(bytes);
			for (byte[] frame : new byte[][] {
					"rhc-office.document-sign.webhook.v1".getBytes(StandardCharsets.UTF_8),
					id.getBytes(StandardCharsets.US_ASCII), timestamp.getBytes(StandardCharsets.US_ASCII), body }) {
				output.writeInt(frame.length);
				output.write(frame);
			}
			var mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec("test-only-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return "v1=" + java.util.HexFormat.of().formatHex(mac.doFinal(bytes.toByteArray()));
		} catch (java.io.IOException | java.security.GeneralSecurityException error) {
			throw new AssertionError(error);
		}
	}

	@Test
	void retainsVerifiedExactBytesAndFirstHeaders() throws Exception {
		byte[] raw = body("00000000-0000-0000-0000-000000009401");
		assertThat(mvc.perform(request("00000000-0000-0000-0000-000000009401", raw)).andReturn().getResponse().getStatus()).isEqualTo(204);
		var event = repository.findById("00000000-0000-0000-0000-000000009401").orElseThrow();
		assertThat(event.getRawBody()).isEqualTo(raw);
		assertThat(event.getSignature()).matches("v1=[0-9a-f]{64}");
		assertThat(event.getSignatureTimestamp()).isNotBlank();
		assertThat(event.getVerificationStatus()).isEqualTo("VERIFIED");
		assertThat(event.getReceivedAt()).isNotNull();
		String nextTimestamp = java.time.Instant.now().plusSeconds(1).toString();
		assertThat(mvc.perform(requestAt("00000000-0000-0000-0000-000000009401", raw, nextTimestamp))
				.andReturn().getResponse().getStatus()).isEqualTo(204);
		assertThat(repository.findById(event.getEventId()).orElseThrow().getSignature()).isEqualTo(event.getSignature());
		assertThat(repository.count()).isEqualTo(1);
		byte[] changed = new String(raw, StandardCharsets.UTF_8).replace("SUCCEEDED", "FAILED").getBytes(StandardCharsets.UTF_8);
		assertThat(mvc.perform(request("00000000-0000-0000-0000-000000009401", changed))
				.andReturn().getResponse().getStatus()).isEqualTo(409);
		assertThat(repository.findById("00000000-0000-0000-0000-000000009401").orElseThrow().getRawBody()).isEqualTo(raw);
	}

	@Test
	void concurrentRetriesCommitOneEventAndAllReceiveSuccess() throws Exception {
		var start = new CountDownLatch(1);
		Callable<Integer> send = () -> {
			start.await();
			return mvc.perform(request("00000000-0000-0000-0000-000000009403", body("00000000-0000-0000-0000-000000009403"))).andReturn().getResponse().getStatus();
		};
		try (var pool = Executors.newFixedThreadPool(6)) {
			var tasks = List.of(pool.submit(send), pool.submit(send), pool.submit(send),
					pool.submit(send), pool.submit(send), pool.submit(send));
			start.countDown();
			for (var task : tasks) assertThat(task.get()).isEqualTo(204);
		}
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void rejectsMissingHeadersMalformedIncompleteAndMismatchedPayloads() throws Exception {
		assertThat(mvc.perform(post("/api/webhooks/document-sign").content(body("00000000-0000-0000-0000-000000009404")))
				.andReturn().getResponse().getStatus()).isEqualTo(400);
		for (String invalid : List.of("{", "{}", "null", "[]",
				new String(body("00000000-0000-0000-0000-000000009404"), StandardCharsets.UTF_8).replace("true", "\"true\""),
				new String(body("00000000-0000-0000-0000-000000009404"), StandardCharsets.UTF_8) + "{}",
				new String(body("00000000-0000-0000-0000-000000009404"), StandardCharsets.UTF_8).replace("\"jobId\":\"00000000-0000-0000-0000-000000009402\"", "\"jobId\":null"))) {
			assertThat(mvc.perform(request("00000000-0000-0000-0000-000000009404", invalid.getBytes(StandardCharsets.UTF_8)))
					.andReturn().getResponse().getStatus()).isEqualTo(400);
		}
		assertThat(mvc.perform(request("different", body("00000000-0000-0000-0000-000000009404"))).andReturn().getResponse().getStatus()).isEqualTo(400);
		assertThat(mvc.perform(request("x".repeat(256), body("00000000-0000-0000-0000-000000009404"))).andReturn().getResponse().getStatus()).isEqualTo(400);
		assertThat(repository.count()).isZero();
	}

	@Test
	void limitsBodyEvenWithoutContentLength() throws Exception {
		assertThat(mvc.perform(request("large", new byte[DocumentSignWebhookController.MAX_BODY_BYTES + 1])
				.with(req -> { req.removeHeader("Content-Length"); return req; }))
				.andReturn().getResponse().getStatus()).isEqualTo(413);
		assertThat(repository.count()).isZero();
	}

	@Test
	void rejectsBadSignatureTamperingAndUnauthenticatedDuplicate() throws Exception {
		String id = "00000000-0000-0000-0000-000000009401";
		byte[] raw = body(id);
		var valid = request(id, raw);
		assertThat(mvc.perform(valid).andReturn().getResponse().getStatus()).isEqualTo(204);
		assertThat(mvc.perform(request(id, raw).with(req -> {
			req.removeHeader("X-Document-Sign-Signature");
			req.addHeader("X-Document-Sign-Signature", "v1=" + "0".repeat(64));
			return req;
		})).andReturn().getResponse().getStatus()).isEqualTo(401);
		// JSON 含义未变但原始空白变化时，原签名必须失效。
		assertThat(mvc.perform(request(id, raw).content(new String(raw, StandardCharsets.UTF_8) + " "))
				.andReturn().getResponse().getStatus()).isEqualTo(401);
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void rejectsStaleFutureAndMalformedDeliveryTimestamp() throws Exception {
		String id = "00000000-0000-0000-0000-000000009401";
		for (String timestamp : List.of(java.time.Instant.now().minusSeconds(360).toString(),
				java.time.Instant.now().plusSeconds(360).toString(), "not-an-instant")) {
			assertThat(mvc.perform(requestAt(id, body(id), timestamp)).andReturn().getResponse().getStatus()).isEqualTo(401);
		}
		assertThat(repository.count()).isZero();
	}

	@Test
	void rejectsInvalidUuidTerminalStateAndEventTimestamp() throws Exception {
		String id = "00000000-0000-0000-0000-000000009401";
		String original = new String(body(id), StandardCharsets.UTF_8);
		for (String invalid : List.of(original.replace("SUCCEEDED", "RUNNING"),
				original.replace("00000000-0000-0000-0000-000000009402", "bad-job-id"),
				original.replace("2026-10-22T04:00:00Z", "yesterday"))) {
			assertThat(mvc.perform(request(id, invalid.getBytes(StandardCharsets.UTF_8)))
					.andReturn().getResponse().getStatus()).isEqualTo(400);
		}
		assertThat(mvc.perform(request("1-1-1-1-1", body("1-1-1-1-1")))
				.andReturn().getResponse().getStatus()).isEqualTo(400);
		assertThat(repository.count()).isZero();
	}
}
