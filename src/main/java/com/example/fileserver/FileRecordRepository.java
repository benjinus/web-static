package com.example.fileserver;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRecordRepository extends JpaRepository<FileRecord, Long> {

	/** All files currently visible in the list, newest upload first. */
	List<FileRecord> findByDeletedFalseOrderByUploadedAtDescIdDesc();

	/** The live record owning a stored file name (deleted history rows are ignored). */
	Optional<FileRecord> findFirstByStoredNameAndDeletedFalse(String storedName);

	/** Any record, live or archived, for a stored file name. */
	Optional<FileRecord> findFirstByStoredNameOrderByUploadedAtDesc(String storedName);

	List<FileRecord> findByDeletedFalse();
}
