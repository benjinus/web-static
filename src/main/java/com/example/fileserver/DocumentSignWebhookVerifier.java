package com.example.fileserver;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 按接入协议验证四段带长度的 HMAC；验签始终使用未经规范化的头和原始 body。 */
@Component
@ConditionalOnProperty(name = "webhook.document-sign.enabled", havingValue = "true")
public class DocumentSignWebhookVerifier {

	private static final byte[] DOMAIN = "rhc-office.document-sign.webhook.v1".getBytes(StandardCharsets.UTF_8);
	private final byte[] secret;
	private final Duration window;

	public DocumentSignWebhookVerifier(
			@Value("${webhook.document-sign.signing-secret:}") String secret,
			@Value("${webhook.document-sign.timestamp-window:PT5M}") Duration window) {
		if (secret.isBlank()) {
			throw new IllegalArgumentException("webhook.document-sign.signing-secret must be configured");
		}
		if (window.isZero() || window.isNegative()) {
			throw new IllegalArgumentException("webhook.document-sign.timestamp-window must be positive");
		}
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.window = window;
	}

	public void verify(String eventId, String timestamp, String signature, byte[] body) {
		verify(eventId, timestamp, signature, body, Instant.now());
	}

	void verify(String eventId, String timestamp, String signature, byte[] body, Instant now) {
		if (!isAscii(eventId) || !isAscii(timestamp) || !signature.matches("v1=[0-9a-f]{64}")) {
			throw unauthorized();
		}
		Instant deliveredAt;
		try {
			deliveredAt = Instant.parse(timestamp);
		} catch (DateTimeParseException invalid) {
			throw unauthorized();
		}
		if (Duration.between(deliveredAt, now).abs().compareTo(window) > 0) {
			throw unauthorized();
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			for (byte[] frame : new byte[][] { DOMAIN, eventId.getBytes(StandardCharsets.US_ASCII),
					timestamp.getBytes(StandardCharsets.US_ASCII), body }) {
				mac.update(ByteBuffer.allocate(4).putInt(frame.length).array());
				mac.update(frame);
			}
			if (!MessageDigest.isEqual(mac.doFinal(), HexFormat.of().parseHex(signature.substring(3)))) {
				throw unauthorized();
			}
		} catch (GeneralSecurityException unavailable) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", unavailable);
		}
	}

	private static boolean isAscii(String value) {
		return value.chars().allMatch(character -> character < 128);
	}

	private static ResponseStatusException unauthorized() {
		return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid webhook authentication");
	}
}
