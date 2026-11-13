package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.testconfig.AuditedEchoController;
import de.tum.cit.aet.hephaestus.testconfig.AuditedRequestCheck;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/** The check fails a 2xx {@code @Audited} request that wrote no row of its declared type, and only that. */
class AuditedRequestCheckIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConfigAuditEventRepository events;

    private void post(String id, String mode) {
        webTestClient
                .post()
                .uri("/audited-request-check/{id}/{mode}", id, mode)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
    }

    @Test
    void shouldFailTheTestWhenAnAuditedRequestWritesNoRow() {
        post(UUID.randomUUID().toString(), "silent");

        assertThatThrownBy(AuditedRequestCheck::assertNoViolations)
                .hasMessageContaining("AuditedEchoController.silent")
                .hasMessageContaining("wrote 0 CONFIG_AUDIT rows of type 'CATALOG_GROUP'");
    }

    @Test
    void shouldFailTheTestWhenAnAuditedRequestWritesAnotherType() {
        post(UUID.randomUUID().toString(), "other-type");

        assertThatThrownBy(AuditedRequestCheck::assertNoViolations)
                .hasMessageContaining("AuditedEchoController.otherType")
                .hasMessageContaining("CATALOG_PRACTICE");
    }

    @Test
    void shouldPassWhenAnAuditedRequestWritesItsRow() {
        String id = UUID.randomUUID().toString();
        post(id, "recorded");

        assertThatCode(AuditedRequestCheck::assertNoViolations).doesNotThrowAnyException();
        assertThat(events.findAll())
                .anyMatch(row -> row.getEntityType() == ConfigAuditEntityType.CATALOG_GROUP
                        && row.getEntityId().equals(id));
    }

    @Test
    void shouldPassWhenAnUnchangedUpdateIsSuppressed() {
        post(UUID.randomUUID().toString(), "unchanged");

        assertThatCode(AuditedRequestCheck::assertNoViolations).doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptARequestThatTheTestDeclaresUnchangedOnlyWhenItWroteNoRow() {
        post(UUID.randomUUID().toString(), "silent");
        AuditedRequestCheck.acceptUnchanged(AuditedEchoController.class, "silent");
        assertThatCode(AuditedRequestCheck::assertNoViolations).doesNotThrowAnyException();

        post(UUID.randomUUID().toString(), "other-type");
        AuditedRequestCheck.acceptUnchanged(AuditedEchoController.class, "otherType");
        assertThatThrownBy(AuditedRequestCheck::assertNoViolations)
                .hasMessageContaining("AuditedEchoController.otherType");
    }
}
