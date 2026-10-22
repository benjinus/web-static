package com.example.fileserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:webhook-disabled;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DocumentSignWebhookDisabledTests {
	@Autowired MockMvc mvc;
	@MockitoBean FileStorageService fileStorage;

	@Test
	void disabledByDefault() throws Exception {
		assertThat(mvc.perform(post("/api/webhooks/document-sign")).andReturn().getResponse().getStatus()).isEqualTo(404);
	}
}
