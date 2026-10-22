package com.example.fileserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = {
		"webhook.document-sign.enabled=true",
		"webhook.document-sign.signing-secret=test-only-secret",
		"spring.datasource.url=jdbc:h2:mem:webhook-failure;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DocumentSignWebhookFailureTests {
	@Autowired MockMvc mvc;
	@MockitoBean DocumentSignWebhookStorage storage;
	@MockitoBean FileStorageService fileStorage;

	@Test
	void failedStorageRemainsRetryable() throws Exception {
		doThrow(new IllegalStateException("private DB failure")).when(storage).capture(any());
		var response = mvc.perform(DocumentSignWebhookTests.request("00000000-0000-0000-0000-000000009405", DocumentSignWebhookTests.body("00000000-0000-0000-0000-000000009405")))
				.andReturn().getResponse();
		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentAsString()).doesNotContain("private DB failure");
	}
}
