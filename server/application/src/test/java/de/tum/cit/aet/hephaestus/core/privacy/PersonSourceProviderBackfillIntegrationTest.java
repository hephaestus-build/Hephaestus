package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.*;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonSourceProviderBackfillIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private OutlineDocumentRepository documents;

    @Test
    void shouldRegisterLegacySourcesAndInheritExactNativeControlsIdempotently() throws Exception {
        databaseTestUtils.cleanDatabase();
        String host = UUID.randomUUID() + ".example.test";
        var original = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.OUTLINE, "https://" + host));
        UUID controlId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                controlId,
                original.getId(),
                "native-42",
                "");
        String legacyUrl = "HTTPS://" + host.toUpperCase(java.util.Locale.ROOT) + ":443/";
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("source-backfill"));
        connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "outline",
                new ConnectionConfig.OutlineConfig(legacyUrl, null, null, Set.of())));
        connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T1",
                new ConnectionConfig.SlackConfig("T1", "not-a-key", null, Set.of())));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            var change = new PersonSourceProviderBackfillChange();
            change.execute(database);
            change.execute(database);
            connection.commit();
        }
        long aliasId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.OUTLINE, legacyUrl)
                .orElseThrow()
                .getId());
        assertThat(providers.findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com"))
                .isPresent();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM person_suppression WHERE provider_id=? AND subject='native-42' AND team_key=''",
                        Long.class,
                        aliasId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT id FROM person_suppression WHERE provider_id=?", UUID.class, original.getId()))
                .isEqualTo(controlId);
    }

    @Test
    void shouldRejectAnUnboundLegacyOutlineMirrorWithoutDeletingVisibleContent() throws Exception {
        databaseTestUtils.cleanDatabase();
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("unbound-source"));
        var source = connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "unbound",
                new ConnectionConfig.OutlineConfig(null, null, null, Set.of())));
        var doc = new OutlineDocument();
        doc.setWorkspaceId(Objects.requireNonNull(workspace.getId()));
        doc.setConnectionId(Objects.requireNonNull(source.getId()));
        doc.setDocumentId(UUID.randomUUID().toString());
        doc.setCollectionId(UUID.randomUUID().toString());
        doc.setCreatedBySubject("native-42");
        doc.setBodyMarkdown("Visible content must stay");
        doc = documents.saveAndFlush(doc);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            assertThatThrownBy(() -> new PersonSourceProviderBackfillChange().execute(database))
                    .isInstanceOf(CustomChangeException.class)
                    .hasRootCauseMessage("Outline connection " + source.getId()
                            + " mirrors documents but has no exact provider instance");
            connection.rollback();
        }
        assertThat(documents.findById(doc.getId()).orElseThrow().getBodyMarkdown())
                .isEqualTo("Visible content must stay");
    }
}
