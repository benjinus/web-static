package com.example.fileserver;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 测试投递凭据：保留原始字节及首个请求的签名头，供签名协议明确后核对。 */
@Entity
@Table(name = "document_sign_webhook_event")
public class DocumentSignWebhookEvent {

	@Id
	@Column(name = "event_id", length = 255, nullable = false)
	private String eventId;

	@Lob
	@Column(name = "raw_body", nullable = false)
	private byte[] rawBody;

	@Column(name = "signature_timestamp", length = 255, nullable = false)
	private String signatureTimestamp;

	@Column(name = "signature", length = 4096, nullable = false)
	private String signature;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "verification_status", length = 32, nullable = false)
	private String verificationStatus;

	protected DocumentSignWebhookEvent() {
	}

	public DocumentSignWebhookEvent(String eventId, byte[] rawBody, String timestamp, String signature) {
		this.eventId = eventId;
		this.rawBody = rawBody.clone();
		this.signatureTimestamp = timestamp;
		this.signature = signature;
		this.receivedAt = Instant.now();
		this.verificationStatus = "VERIFIED";
	}

	public String getEventId() { return eventId; }
	public byte[] getRawBody() { return rawBody.clone(); }
	public String getSignatureTimestamp() { return signatureTimestamp; }
	public String getSignature() { return signature; }
	public Instant getReceivedAt() { return receivedAt; }
	public String getVerificationStatus() { return verificationStatus; }
}
