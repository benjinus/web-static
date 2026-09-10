package com.example.fileserver;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link FileRecord} 的数据访问接口。
 *
 * <p>方法名由 Spring Data 按约定解析成 SQL，无需手写实现。库里的记录既有在库的也有已删除的，
 * 因此带 {@code DeletedFalse} 的方法才是「面向用户」的查询。
 */
public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

	/** 列表页数据：所有在库文件，按上传时间倒序（同一时刻则按 id 倒序，保证顺序稳定）。 */
	List<FileRecord> findByDeletedFalseOrderByUploadedAtDescIdDesc();

	/**
	 * 按存储名找「在库」记录。
	 * 上传时用它判断内容是否重复，删除/下载时用它确认文件当前是否有效。
	 */
	Optional<FileRecord> findFirstByStoredNameAndDeletedFalse(String storedName);

	/**
	 * 按存储名找任意一条记录（含已删除的历史）。
	 * 用于在库记录缺失时兜底取原始文件名，让已删除文件仍能以原名下载。
	 */
	Optional<FileRecord> findFirstByStoredNameOrderByUploadedAtDesc(String storedName);

	/** 所有在库记录；启动对账时用来核对每条记录在磁盘上是否还有对应文件。 */
	List<FileRecord> findByDeletedFalse();
}
