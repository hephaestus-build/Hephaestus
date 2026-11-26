package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class PracticeIdentityIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeReviewDigest digest;

    @Autowired
    CatalogOriginPresenter presenter;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void shouldGiveAWorkspacePracticeALocalKeyThatItsEditsKeep() {
        var owner = persistUser("identity-owner");
        var workspace = createWorkspace("identity-local", "Identity", "identity-org", AccountType.ORG, owner);
        var authored = DefinitionTestFixtures.definition();
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(), "local-identity", new ItemKind("scm.pull_request"), authored, null, 0, null));
        var first = practice.getCurrentVersion();
        assertThat(first.getIdentityKey()).isEqualTo("local/" + workspace.getId() + "/" + practice.getId());
        assertThat(first.getComparabilityClass()).isEqualTo(digest.of(authored));
        assertThat(jdbc.queryForObject(
                        "SELECT comparability_class = review_digest FROM practice_version WHERE id = ? AND workspace_id = ?",
                        Boolean.class,
                        first.getId(),
                        workspace.getId()))
                .as("the class is the digest that the database stored")
                .isTrue();
        assertThat(presenter.present(workspace.getId(), practice).portable()).isFalse();

        var edited = DefinitionTestFixtures.withCriteria(authored, "Each change names the problem it solves.");
        var second = versions.append(practice, edited, null);
        assertThat(second.getIdentityKey()).isEqualTo(first.getIdentityKey());
        assertThat(second.getComparabilityClass())
                .isEqualTo(digest.of(edited))
                .isNotEqualTo(first.getComparabilityClass());
    }

    @Test
    void shouldSetAVersionIdentityOnceFromNullAndRefuseAnyLaterChange() {
        var owner = persistUser("identity-guard");
        var workspace = createWorkspace("identity-guard", "Identity", "identity-guard-org", AccountType.ORG, owner);
        var authored = DefinitionTestFixtures.definition();
        var practice = versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(), "guarded-identity", new ItemKind("scm.pull_request"), authored, null, 0, null));
        // A version that the upgrade has not filled yet has no identity.
        long id = Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO practice_version(workspace_id, practice_id, number, definition)
                SELECT workspace_id, practice_id, 2, definition FROM practice_version WHERE id = ? AND workspace_id = ?
                RETURNING id
                """, Long.class, practice.getCurrentVersion().getId(), workspace.getId()));
        String key = Objects.requireNonNull(practice.getCurrentVersion().getIdentityKey());
        String comparabilityClass =
                Objects.requireNonNull(practice.getCurrentVersion().getComparabilityClass());

        jdbc.update(
                "UPDATE practice_version SET identity_key = ?, comparability_class = ? WHERE id = ?",
                key,
                comparabilityClass,
                id);
        assertThat(jdbc.queryForObject("SELECT identity_key FROM practice_version WHERE id = ?", String.class, id))
                .isEqualTo(key);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE practice_version SET comparability_class = ? WHERE id = ?", "v6:" + "0".repeat(64), id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("practice versions are immutable");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE practice_version SET identity_key = NULL, comparability_class = NULL WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("practice versions are immutable");
        long current = Objects.requireNonNull(practice.getCurrentVersion().getId());
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE practice_version SET identity_key = ? WHERE id = ?", "local/1/2", current))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("practice versions are immutable");
    }
}
