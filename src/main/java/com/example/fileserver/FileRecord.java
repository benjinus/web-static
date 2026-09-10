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
 * 一个已上传文件的元数据（对应数据库表 {@code file_record}）。
 *
 * <p>文件二进制本身存在 {@code upload/} 目录里，这张表只负责记住「这个文件是谁、放在哪」：
 * 原始文件名用于前端展示和下载时还原，存储路径用于定位磁盘上的实际文件。
 *
 * <p>删除采用软删除：{@link #deleted} 置为 {@code true} 并记录 {@link #deletedAt}/{@link #deletedPath}，
 * 记录本身保留下来作为历史，因此在库记录都满足 {@code deleted = false}。
 */
@Entity
@Table(name = "file_record", indexes = {
		// 列表查询恒定带 deleted 条件，建索引避免全表扫描
		@Index(name = "idx_file_record_deleted", columnList = "deleted"),
		// 按存储名查记录（去重判断、下载、启动对账）都很频繁
		@Index(name = "idx_file_record_stored_name", columnList = "stored_name")
})
public class FileRecord {

	/** 主键，由数据库自增生成。 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 用户上传时的原始文件名，原样保存（已去掉路径部分），下载时用它还原文件名。 */
	@Column(name = "original_name", nullable = false, length = 512)
	private String originalName;

	/** 在 {@code upload/} 下的文件名，形如 {@code d41d8cd98f00b204e9800998ecf8427e.pdf}。 */
	@Column(name = "stored_name", nullable = false, length = 255)
	private String storedName;

	/** 相对工作目录的存储路径，形如 {@code upload/xxx.pdf}，便于人肉排查和迁移。 */
	@Column(name = "stored_path", nullable = false, length = 1024)
	private String storedPath;

	/** MIME 类型，主要供下载时设置响应头，探测不到时可能是 application/octet-stream。 */
	@Column(name = "content_type", length = 255)
	private String contentType;

	/** 内容 MD5（也是存储名的来源），用于识别重复上传。 */
	@Column(name = "md5", length = 32)
	private String md5;

	/** 文件字节数。列名加 _bytes 后缀，避免和 SQL 关键字/函数名混淆。 */
	@Column(name = "size_bytes", nullable = false)
	private long size;

	/** 上传时间，列表按它倒序排列。 */
	@Column(name = "uploaded_at", nullable = false)
	private Instant uploadedAt;

	/** 软删除标记：true 表示文件已被归档到 deleted/ 目录，不再出现在列表中。 */
	@Column(name = "deleted", nullable = false)
	private boolean deleted;

	/** 删除时间，未删除时为 null。 */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** 归档后的位置，形如 {@code upload/deleted/2026-09-10/xxx.pdf}；文件已不在磁盘上时为 null。 */
	@Column(name = "deleted_path", length = 1024)
	private String deletedPath;

	/** 供 JPA 反射创建实例使用，业务代码请用下面的带参构造。 */
	protected FileRecord() {
		// JPA 需要一个无参构造
	}

	/** 新建一条「在库」记录；删除相关字段默认未设置。 */
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
