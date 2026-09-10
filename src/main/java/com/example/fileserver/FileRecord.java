package com.example.fileserver;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Metadata for one uploaded file.
 *
 * <p>The binary itself lives in the {@code upload/} directory; this record keeps the
 * original client file name and the path the bytes were stored at, so a download can
 * always be offered under the name the user uploaded.
 */
@Entity
@Table(name = "file_record", indexes = {
		@Index(name = "idx_file_record_deleted", columnList = "deleted"),
		@Index(name = "idx_file_record_stored_name", columnList = "stored_name")
})
public class FileRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Original client file name, kept verbatim (path separators stripped). */
	@Column(name = "original_name", nullable = false, length = 512)
	private String originalName;

	/** File name inside {@code upload/}, e.g. {@code d41d8cd98f00b204e9800998ecf8427e.pdf}. */
	@Column(name = "stored_name", nullable = false, length = 255)
	private String storedName;

	/** Storage location relative to the working directory, e.g. {@code upload/xxx.pdf}. */
	@Column(name = "stored_path", nullable = false, length = 1024)
	private String storedPath;

	@Column(name = "content_type", length = 255)
	private String contentType;

	@Column(name = "md5", length = 32)
	private String md5;

	@Column(name = "size_bytes", nullable = false)
	private long size;

	@Column(name = "uploaded_at", nullable = false)
	private Instant uploadedAt;

	/** Soft-delete flag: the row is kept as history after the bytes are archived. */
	@Column(name = "deleted", nullable = false)
	private boolean deleted;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** Date-partitioned archive location, e.g. {@code upload/deleted/2026-09-10/xxx.pdf}. */
	@Column(name = "deleted_path", length = 1024)
	private String deletedPath;

	protected FileRecord() {
		// for JPA
	}

	public FileRecord(String storedName, String storedPath, String originalName, String contentType,
			String md5, long size, Instant uploadedAt) {
		this.storedName = storedName;
		this.storedPath = storedPath;
		this.originalName = originalName;
		this.contentType = contentType;
		this.md5 = md5;
		this.size = size;
		this.uploadedAt = uploadedAt;
		this.deleted = false;
	}

	public Long getId() {
		return id;
	}

	public String getOriginalName() {
		return originalName;
	}

	public void setOriginalName(String originalName) {
		this.originalName = originalName;
	}

	public String getStoredName() {
		return storedName;
	}

	public void setStoredName(String storedName) {
		this.storedName = storedName;
	}

	public String getStoredPath() {
		return storedPath;
	}

	public void setStoredPath(String storedPath) {
		this.storedPath = storedPath;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getMd5() {
		return md5;
	}

	public void setMd5(String md5) {
		this.md5 = md5;
	}

	public long getSize() {
		return size;
	}

	public void setSize(long size) {
		this.size = size;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public void setUploadedAt(Instant uploadedAt) {
		this.uploadedAt = uploadedAt;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public void setDeleted(boolean deleted) {
		this.deleted = deleted;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public void setDeletedAt(Instant deletedAt) {
		this.deletedAt = deletedAt;
	}

	public String getDeletedPath() {
		return deletedPath;
	}

	public void setDeletedPath(String deletedPath) {
		this.deletedPath = deletedPath;
	}
}
