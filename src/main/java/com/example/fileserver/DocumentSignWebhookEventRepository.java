package com.example.fileserver;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSignWebhookEventRepository extends JpaRepository<DocumentSignWebhookEvent, String> {
}
