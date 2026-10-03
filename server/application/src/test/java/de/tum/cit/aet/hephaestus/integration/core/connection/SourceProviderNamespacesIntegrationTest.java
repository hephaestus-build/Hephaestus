package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SourceProviderNamespacesIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private OutlineDocumentRepository documents;

    @Autowired
    private ExactPersonIdentityResolver resolver;

    @Autowired
    private PersonDataService persons;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PersonProcessingSuppression suppression;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PersonDataCopyFence copies;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    @SuppressWarnings("try")
    void shouldWaitForErasureBeforeRegisteringASourceAndReadCommittedControlsAfterTheWait() throws Exception {
        databaseTestUtils.cleanDatabase();
        String host = UUID.randomUUID() + ".example.test";
        var original = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.OUTLINE, "https://" + host));
        String aliasUrl = "HTTPS://" + host.toUpperCase(Locale.ROOT) + ":443/";
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("namespace-fence"));
        var source = connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "bound",
                new ConnectionConfig.OutlineConfig(aliasUrl, null, null, Set.of())));
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Connection> writer;
            try (var admission = copies.erase()) {
                writer = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    writerPid.set(
                            Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                    return connectionService.transition(
                            source,
                            new ConnectionService.TransitionRequest(
                                    IntegrationState.ACTIVE,
                                    "SOURCE_REGISTERED",
                                    "SYSTEM",
                                    "integration",
                                    UUID.randomUUID().toString(),
                                    "Source connected"));
                }));
                await().atMost(Duration.ofSeconds(10))
                        .until(() -> writerPid.get() != 0
                                && Boolean.TRUE.equals(jdbc.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event='advisory')",
                                        Boolean.class,
                                        writerPid.get())));
                assertThat(writer.isDone()).isFalse();
                jdbc.update(
                        "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                        UUID.randomUUID(),
                        original.getId(),
                        "native-42",
                        "");
            }
            assertThat(writer.get(10, TimeUnit.SECONDS).getState()).isEqualTo(IntegrationState.ACTIVE);
        }
        long aliasId = Objects.requireNonNull(providers
                .findByTypeAndServerUrl(IdentityProviderType.OUTLINE, aliasUrl)
                .orElseThrow()
                .getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM person_suppression WHERE provider_id=? AND subject='native-42' AND team_key=''",
                        Long.class,
                        aliasId))
                .isEqualTo(1L);
        assertThat(suppression.isSuppressed(aliasId, "native-42", null)).isTrue();
    }

    @Test
    void shouldKeepScmSourceOwnershipScopedWhileComparingEquivalentProviderOrigins() {
        databaseTestUtils.cleanDatabase();
        String host = UUID.randomUUID() + ".example.test";
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://" + host));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "not-an-identity", provider));
        var first = activate(
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(
                        "HTTPS://" + host.toUpperCase(Locale.ROOT) + ":443/",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        null));
        var second = activate(
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(
                        "https://" + host + ":8443",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        null));
        var seed = new SchemaRowSeeder(jdbc);
        seed.insert(
                "repository",
                Map.of(
                        "id",
                        812501L,
                        "provider_id",
                        provider.getId(),
                        "native_id",
                        812501L,
                        "name",
                        "source",
                        "name_with_owner",
                        "team/source"));
        for (var c : List.of(first, second))
            seed.insert(
                    "repository_to_monitor",
                    Map.of(
                            "id",
                            812510L + Objects.requireNonNull(c.getWorkspace().getId()),
                            "native_id",
                            812501L,
                            "workspace_id",
                            c.getWorkspace().getId(),
                            "generated_paths",
                            "[]"));
        seed.insert(
                "issue",
                Map.of(
                        "id",
                        812502L,
                        "provider_id",
                        provider.getId(),
                        "native_id",
                        812502L,
                        "repository_id",
                        812501L,
                        "author_id",
                        target.getId(),
                        "issue_type",
                        "ISSUE",
                        "number",
                        12L,
                        "state",
                        "OPEN"));
        jdbc.update(
                "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                UUID.randomUUID(),
                provider.getId(),
                "42",
                "");
        assertThat(suppression.isArtifactSuppressed(first.getWorkspace().getId(), "scm.issue", 812502L))
                .isTrue();
        assertThat(suppression.isArtifactSuppressed(second.getWorkspace().getId(), "scm.issue", 812502L))
                .isFalse();
    }

    @Test
    void shouldRegisterAndEraseAnOutlineOnlyPersonAndSuppressANewEquivalentConnectionBeforeSearchLimit() {
        databaseTestUtils.cleanDatabase();
        String host = UUID.randomUUID() + ".example.test";
        String sourceUrl = "HTTPS://" + host.toUpperCase(Locale.ROOT) + ":443/";
        assertThat(providers.findByTypeAndServerUrl(IdentityProviderType.OUTLINE, sourceUrl))
                .isEmpty();
        var first =
                activate(IntegrationKind.OUTLINE, new ConnectionConfig.OutlineConfig(sourceUrl, null, null, Set.of()));
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.OUTLINE, sourceUrl)
                .orElseThrow();
        var target = document(first, "native-42", "Source-private-canary");
        var exact = new PersonIdentity(Objects.requireNonNull(provider.getId()), "native-42", null);
        var scope = resolver.resolve(null, List.of(exact));
        assertThat(scope.accountId()).isNull();
        assertThat(scope.outlineDocumentIds()).containsExactly(target.getId());
        var admin = new Account("Administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        var preview = persons.preview(adminId, null, List.of(exact));
        assertThat(persons.export(preview.request().getId())
                        .path("stores")
                        .path("outline_document")
                        .size())
                .isEqualTo(1);
        persons.requestErasure(preview.request().getId(), adminId, true);
        persons.run(preview.request().getId());
        assertThat(persons.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(documents.findById(target.getId()).orElseThrow().getBodyMarkdown())
                .isNull();

        var second = activate(
                IntegrationKind.OUTLINE, new ConnectionConfig.OutlineConfig("https://" + host, null, null, Set.of()));
        var mirrored = document(second, "native-42", "needle needle needle");
        var other = document(second, "native-84", "needle");
        long workspaceId = Objects.requireNonNull(second.getWorkspace().getId());
        assertThat(suppression.isArtifactSuppressed(workspaceId, "docs.document", mirrored.getId()))
                .isTrue();
        assertThat(suppression.isArtifactSuppressed(workspaceId, "docs.document", other.getId()))
                .isFalse();
        assertThat(documents.searchByRelevance(workspaceId, "needle", 1))
                .extracting(OutlineDocument::getId)
                .containsExactly(other.getId());
        assertThat(documents.findById(mirrored.getId()).orElseThrow().getBodyMarkdown())
                .isEqualTo("needle needle needle");
    }

    @Test
    void shouldRegisterSlackWithoutAnyFederatedAccountAndKeepAnotherTeamSeparate() {
        databaseTestUtils.cleanDatabase();
        var first = activate(
                IntegrationKind.SLACK, new ConnectionConfig.SlackConfig("T1", "not-an-identity", null, Set.of()));
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.SLACK, "https://slack.com")
                .orElseThrow();
        var scope = resolver.resolve(
                null, List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "U42", "T1")));
        assertThat(scope.accountId()).isNull();
        assertThat(scope.identities()).containsExactly(new PersonIdentity(provider.getId(), "U42", "T1"));
        assertThat(first.getState()).isEqualTo(IntegrationState.ACTIVE);
        var admin = new Account("Administrator");
        admin.setAppRole(Account.AppRole.APP_ADMIN);
        long adminId = Objects.requireNonNull(accounts.saveAndFlush(admin).getId());
        var preview = persons.preview(adminId, null, scope.identities());
        persons.requestErasure(preview.request().getId(), adminId, true);
        persons.run(preview.request().getId());
        assertThat(persons.get(preview.request().getId()).request().getState())
                .isEqualTo(PersonDataRequest.State.COMPLETE);
        assertThat(suppression.isSuppressed(provider.getId(), "U42", "T1")).isTrue();
        assertThat(suppression.isSuppressed(provider.getId(), "U42", "T2")).isFalse();
    }

    private Connection activate(IntegrationKind kind, ConnectionConfig config) {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("source-" + UUID.randomUUID()));
        var connection = connections.saveAndFlush(
                new Connection(workspace, kind, UUID.randomUUID().toString(), config));
        return connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "SOURCE_REGISTERED",
                        "SYSTEM",
                        "integration",
                        UUID.randomUUID().toString(),
                        "Source connected"));
    }

    private OutlineDocument document(Connection connection, String nativeSubject, String body) {
        var doc = new OutlineDocument();
        doc.setWorkspaceId(Objects.requireNonNull(connection.getWorkspace().getId()));
        doc.setConnectionId(Objects.requireNonNull(connection.getId()));
        doc.setDocumentId(UUID.randomUUID().toString());
        doc.setCollectionId(UUID.randomUUID().toString());
        doc.setCreatedBySubject(nativeSubject);
        doc.setTitle("Source document");
        doc.setBodyMarkdown(body);
        doc.setOutlineCreatedAt(Instant.now());
        return documents.saveAndFlush(doc);
    }
}
