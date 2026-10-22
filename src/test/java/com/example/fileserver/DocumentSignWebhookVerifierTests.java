package com.example.fileserver;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentSignWebhookVerifierTests {
	private static final String ID = "00000000-0000-0000-0000-000000009401";
	private static final String TIMESTAMP = "2026-10-22T04:00:00.000Z";
	private static final byte[] BODY = "{\"hello\":\"world\"}".getBytes(StandardCharsets.UTF_8);
	// 独立 Python hmac + struct.pack('>I') 计算，防止收发两端共享同一种错误。
	private static final String SIGNATURE = "v1=a92c7a3cc8bc1179cf16da2d6b6ce1487acf77e9ed7c74aefc4e5b0b5582d176";

	@Test
	void acceptsKnownFramedHmacAndWindowBoundaryWithoutNormalizingTimestamp() {
		var verifier = new DocumentSignWebhookVerifier("test-only-secret", Duration.ofMinutes(5));
		verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY, Instant.parse(TIMESTAMP).plusSeconds(300));
		verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY, Instant.parse(TIMESTAMP).minusSeconds(300));
		assertThat(DocumentSignWebhookTests.sign(ID, TIMESTAMP, BODY)).isEqualTo(SIGNATURE);
		assertUnauthorized(verifier, ID, "2026-10-22T04:00:00Z", SIGNATURE, BODY);
		assertUnauthorized(verifier, "00000000-0000-0000-0000-000000009402", TIMESTAMP, SIGNATURE, BODY);
	}

	@Test
	void requiresExactSignatureFormatAndFreshTimestamp() {
		var verifier = new DocumentSignWebhookVerifier("test-only-secret", Duration.ofMinutes(5));
		for (String signature : List.of(SIGNATURE.toUpperCase(), "v2=" + SIGNATURE.substring(3),
				SIGNATURE + "0", "v1=" + "0".repeat(64))) {
			assertUnauthorized(verifier, ID, TIMESTAMP, signature, BODY);
		}
		assertThatThrownBy(() -> verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY,
				Instant.parse(TIMESTAMP).plusSeconds(301)))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> verifier.verify(ID, TIMESTAMP, SIGNATURE, BODY,
				Instant.parse(TIMESTAMP).minusSeconds(301)))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	void enabledReceiverFailsStartupWithoutSecretOrPositiveWindow() {
		new ApplicationContextRunner().withUserConfiguration(DocumentSignWebhookVerifier.class)
				.withPropertyValues("webhook.document-sign.enabled=true")
				.run(context -> assertThat(context).hasFailed());
		assertThatThrownBy(() -> new DocumentSignWebhookVerifier("test", Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DocumentSignWebhookVerifier("test", Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static void assertUnauthorized(DocumentSignWebhookVerifier verifier, String id,
			String timestamp, String signature, byte[] body) {
		assertThatThrownBy(() -> verifier.verify(id, timestamp, signature, body, Instant.parse(TIMESTAMP)))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
	}
}
