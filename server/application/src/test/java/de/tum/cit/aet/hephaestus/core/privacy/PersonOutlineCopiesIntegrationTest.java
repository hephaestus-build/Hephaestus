package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.*;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.testconfig.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonOutlineCopiesIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonDataService service;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Test
    void exactEditorAndCollaboratorKeysCoverBodyCopiesWithoutExportingOtherProfiles() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OUTLINE, "https://outline-copy.example.test"));
        var admin = new Account("Administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        String target = "00000000-0000-4000-8000-000000000042";
        String other = "00000000-0000-4000-8000-000000000084";
        var rows = new SchemaRowSeeder(jdbc);
        rows.insert(
                "workspace",
                Map.of(
                        "id",
                        995701L,
                        "slug",
                        "outline-copies",
                        "display_name",
                        "Outline copies",
                        "status",
                        "ACTIVE",
                        "account_type",
                        "ORG"));
        rows.insert(
                "connection",
                Map.of(
                        "id",
                        995702L,
                        "workspace_id",
                        995701L,
                        "kind",
                        "OUTLINE",
                        "state",
                        "ACTIVE",
                        "config",
                        "{\"type\":\"OUTLINE\",\"serverUrl\":\"https://outline-copy.example.test\",\"enabledStreams\":[]}"));
        for (int n = 0; n < 3; n++) {
            var fields = new HashMap<String, Object>();
            fields.put("id", 995703L + n);
            fields.put("workspace_id", 995701L);
            fields.put("connection_id", 995702L);
            fields.put("document_id", UUID.randomUUID().toString());
            fields.put("collection_id", UUID.randomUUID().toString());
            fields.put("created_by_subject", other);
            fields.put("created_by_name", "Other profile canary");
            fields.put("updated_by_subject", n == 0 ? target : other);
            fields.put("updated_by_name", n == 0 ? "Target editor" : "Other editor canary");
            fields.put(
                    "collaborator_subjects", n == 1 ? "[\"" + target + "\",\"" + other + "\"]" : "[\"" + other + "\"]");
            fields.put("title", n < 2 ? "Shared target work" : "Unrelated work");
            fields.put("body_markdown", n < 2 ? "Target copied body" : "Unrelated body canary");
            rows.insert("outline_document", fields);
        }
        var preview = service.preview(
                adminId, null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), target, null)));
        var export = service.export(preview.request().getId()).path("stores");
        assertThat(export.path("outline_document_content").size()).isEqualTo(2);
        assertThat(export.path("outline_document_editor").size()).isEqualTo(1);
        assertThat(export.path("outline_document_collaborator").size()).isEqualTo(1);
        assertThat(export.toString())
                .contains("Target copied body")
                .doesNotContain("Other profile canary", "Other editor canary", "Unrelated body canary");
        service.requestErasure(preview.request().getId(), adminId, true);
        service.run(preview.request().getId());
        assertThat(service.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM outline_document WHERE id IN (995703,995704) AND body_markdown IS NULL AND title IS NULL AND created_by_subject=?",
                        Long.class,
                        other))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        "SELECT updated_by_subject IS NULL FROM outline_document WHERE id=995703", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT collaborator_subjects::text FROM outline_document WHERE id=995704", String.class))
                .isEqualTo("[\"" + other + "\"]");
        assertThat(jdbc.queryForObject("SELECT body_markdown FROM outline_document WHERE id=995705", String.class))
                .isEqualTo("Unrelated body canary");
    }
}
