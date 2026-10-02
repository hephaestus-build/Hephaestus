package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.*;
import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.*;
import de.tum.cit.aet.hephaestus.testconfig.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonScmCopiesIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonDataService service;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Test
    void shouldEraseExactAuthoredCopiesAndKeepAnotherAuthorsWorkWhenOnlyItsCommitterIsErased() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://scm-copies.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", provider));
        var administrator = new Account("Administrator");
        administrator.setAppRole(Account.AppRole.APP_ADMIN);
        administrator = accounts.saveAndFlush(administrator);
        var rows = new SchemaRowSeeder(jdbc);
        rows.insert(
                "repository",
                Map.of("id", 995601L, "native_id", 995601L, "provider_id", Objects.requireNonNull(provider.getId())));
        for (int n = 0; n < 2; n++) {
            long commit = 995604L + n;
            rows.insert(
                    "milestone",
                    Map.of(
                            "id",
                            995602L + n,
                            "native_id",
                            995602L + n,
                            "provider_id",
                            Objects.requireNonNull(provider.getId()),
                            "repository_id",
                            995601L,
                            "creator_id",
                            n == 0 ? target.getId() : other.getId(),
                            "number",
                            n + 1,
                            "state",
                            "OPEN",
                            "title",
                            n == 0 ? "Target milestone" : "Other milestone",
                            "description",
                            n == 0 ? "Target milestone content" : "Other milestone content"));
            rows.insert(
                    "git_commit",
                    Map.of(
                            "id",
                            commit,
                            "repository_id",
                            995601L,
                            "sha",
                            (n == 0 ? "a" : "b").repeat(40),
                            "author_id",
                            n == 0 ? target.getId() : other.getId(),
                            "committer_id",
                            n == 0 ? other.getId() : target.getId(),
                            "message",
                            n == 0 ? "Target commit" : "Other commit"));
            rows.insert(
                    "commit_file_change",
                    Map.of(
                            "id",
                            995606L + n,
                            "commit_id",
                            commit,
                            "filename",
                            n == 0 ? "target-path" : "other-path",
                            "change_type",
                            "MODIFIED"));
        }
        rows.insert(
                "git_commit",
                Map.of(
                        "id",
                        995608L,
                        "repository_id",
                        995601L,
                        "sha",
                        "c".repeat(40),
                        "author_id",
                        other.getId(),
                        "message",
                        "Co-authored work",
                        "message_body",
                        "Target co-author copy"));
        rows.insert(
                "commit_contributor",
                Map.of(
                        "id",
                        995609L,
                        "commit_id",
                        995608L,
                        "user_id",
                        target.getId(),
                        "role",
                        "CO_AUTHOR",
                        "name",
                        "Target co-author",
                        "email",
                        "target@example.test",
                        "ordinal",
                        1));
        rows.insert(
                "commit_contributor",
                Map.of(
                        "id",
                        995610L,
                        "commit_id",
                        995608L,
                        "user_id",
                        other.getId(),
                        "role",
                        "AUTHOR",
                        "name",
                        "Other author",
                        "email",
                        "other@example.test",
                        "ordinal",
                        0));
        rows.insert(
                "commit_file_change",
                Map.of("id", 995611L, "commit_id", 995608L, "filename", "co-authored-path", "change_type", "MODIFIED"));
        long adminId = Objects.requireNonNull(administrator.getId());
        var preview = service.preview(
                adminId, null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null)));
        var export = service.export(preview.request().getId()).path("stores");
        assertThat(export.path("milestone").size()).isEqualTo(1);
        assertThat(export.path("milestone").get(0).path("description").asString())
                .isEqualTo("Target milestone content");
        assertThat(export.path("commit_file_change").size()).isEqualTo(2);
        assertThat(export.path("git_commit_authored_content").size()).isEqualTo(2);
        assertThat(export.path("git_commit_authored_content").toString())
                .contains("Target co-author copy")
                .doesNotContain("other@example.test", "Other author", "author_id");
        assertThat(export.path("commit_file_change").get(0).path("filename").asString())
                .isEqualTo("target-path");
        assertThat(export.toString()).doesNotContain("Other milestone content", "other-path", "Other commit");
        service.requestErasure(preview.request().getId(), adminId, true);
        service.run(preview.request().getId());
        assertThat(service.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(jdbc.queryForObject(
                        "SELECT creator_id IS NULL AND description IS NULL AND title='Erased work' FROM milestone WHERE id=995602",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT description FROM milestone WHERE id=995603", String.class))
                .isEqualTo("Other milestone content");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commit_file_change WHERE id=995606", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT filename FROM commit_file_change WHERE id=995607", String.class))
                .isEqualTo("other-path");
        assertThat(jdbc.queryForObject(
                        "SELECT committer_id IS NULL AND message='Other commit' FROM git_commit WHERE id=995605",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT author_id=? AND message='' AND message_body IS NULL FROM git_commit WHERE id=995608",
                        Boolean.class,
                        other.getId()))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commit_contributor WHERE id=995609", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM commit_contributor WHERE id=995610", String.class))
                .isEqualTo("Other author");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM commit_file_change WHERE id=995611", Long.class))
                .isZero();
    }
}
