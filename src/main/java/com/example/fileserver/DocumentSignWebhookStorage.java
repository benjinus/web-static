package com.example.fileserver;

import java.util.Arrays;

import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 事务提交成功后才确认接收，唯一主键负责跨线程、跨进程的事件去重。 */
@Service
public class DocumentSignWebhookStorage {

	private final DocumentSignWebhookEventRepository repository;
	private final EntityManager entityManager;
	private final TransactionTemplate transaction;

	public DocumentSignWebhookStorage(DocumentSignWebhookEventRepository repository,
			EntityManager entityManager, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.entityManager = entityManager;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void capture(DocumentSignWebhookEvent event) {
		var existing = repository.findById(event.getEventId());
		if (existing.isPresent()) {
			checkSameBody(existing.get(), event);
			return;
		}
		try {
			transaction.executeWithoutResult(status -> {
				// persist 禁止 merge 覆盖原始凭据；flush 使唯一约束在本事务内生效。
				entityManager.persist(event);
				entityManager.flush();
			});
		} catch (DataIntegrityViolationException | org.hibernate.exception.ConstraintViolationException duplicate) {
			// 失败的插入事务已完整回滚，在新查询中读取并发获胜者。
			var winner = repository.findById(event.getEventId());
			if (winner.isEmpty()) {
				throw duplicate;
			}
			checkSameBody(winner.get(), event);
		}
	}

	private static void checkSameBody(DocumentSignWebhookEvent existing, DocumentSignWebhookEvent incoming) {
		if (!Arrays.equals(existing.getRawBody(), incoming.getRawBody())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "eventId already has a different body");
		}
	}
}
