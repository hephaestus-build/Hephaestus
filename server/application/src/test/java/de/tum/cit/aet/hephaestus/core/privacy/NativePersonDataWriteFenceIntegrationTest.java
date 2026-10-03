package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class NativePersonDataWriteFenceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PersonDataWriteFence fence;

    @Autowired
    private PersonSuppressionService suppression;

    @Autowired
    private PersonDataRequestRepository requests;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldRejectAnOldSnapshotForAdmissionAndFinalErasureValidation() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var identities = List.of(new PersonIdentity(1L, "native-subject", null));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> fence.holdForErasure(identities)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ_COMMITTED");
        assertThatThrownBy(() -> tx.executeWithoutResult(
                        status -> assertThat(fence.holdForWrite(identities)).isTrue()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ_COMMITTED");
    }

    @Test
    void shouldAdmitOnlyExistingUnsuppressedUsersFromABatchWithDuplicateAndMissingKeys() {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://batch-fence.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var other = users.saveAndFlush(TestUserFactory.createUser(84L, "other", provider));
        jdbc.update(
                "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                UUID.randomUUID(),
                provider.getId(),
                "42",
                "");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(fence.holdForUserWrites(List.of(other.getId(), target.getId(), other.getId(), -1L)))
                    .containsExactly(other.getId());
            assertThat(fence.holdForUserWrites(List.of())).isEmpty();
        });
    }

    @Test
    void shouldSuppressAProviderAliasRegisteredAfterErasureWithoutMatchingAnotherInstanceOrPerson() {
        databaseTestUtils.cleanDatabase();
        var original = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://late-alias.example.test"));
        jdbc.update(
                "INSERT INTO person_suppression(id,provider_id,subject,team_key) VALUES (?,?,?,?)",
                UUID.randomUUID(),
                original.getId(),
                "42",
                "");
        var alias = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "HTTPS://LATE-ALIAS.EXAMPLE.TEST:443/"));
        var otherInstance = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://late-alias.example.test:8443"));
        var otherType = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://late-alias.example.test"));
        var target = users.saveAndFlush(TestUserFactory.createUser(42L, "same-display", alias));
        var other = users.saveAndFlush(TestUserFactory.createUser(84L, "same-display", alias));
        assertThat(suppression.isSuppressed(Objects.requireNonNull(alias.getId()), "42", null))
                .isTrue();
        assertThat(suppression.isUserSuppressed(Objects.requireNonNull(target.getId())))
                .isTrue();
        assertThat(suppression.isUserSuppressed(Objects.requireNonNull(other.getId())))
                .isFalse();
        assertThat(suppression.isSuppressed(Objects.requireNonNull(otherInstance.getId()), "42", null))
                .isFalse();
        assertThat(suppression.isSuppressed(Objects.requireNonNull(otherType.getId()), "42", null))
                .isFalse();
        var seed = new SchemaRowSeeder(jdbc);
        long workspaceId = Objects.requireNonNull(workspaces
                .saveAndFlush(WorkspaceTestFixtures.activeWorkspace("late-alias-source"))
                .getId());
        seed.insert(
                "repository",
                Map.of(
                        "id",
                        812202L,
                        "provider_id",
                        alias.getId(),
                        "native_id",
                        812202L,
                        "name",
                        "source",
                        "name_with_owner",
                        "team/source"));
        seed.insert(
                "repository_to_monitor",
                Map.of("id", 812203L, "native_id", 812202L, "workspace_id", workspaceId, "generated_paths", "[]"));
        seed.insert(
                "connection",
                Map.of(
                        "id",
                        812204L,
                        "workspace_id",
                        workspaceId,
                        "kind",
                        "GITLAB",
                        "config",
                        "{\"serverUrl\":\"" + alias.getServerUrl() + "\"}"));
        seed.insert(
                "issue",
                Map.of(
                        "id",
                        812205L,
                        "provider_id",
                        alias.getId(),
                        "native_id",
                        812205L,
                        "repository_id",
                        812202L,
                        "author_id",
                        target.getId(),
                        "issue_type",
                        "ISSUE",
                        "number",
                        12L,
                        "state",
                        "OPEN"));
        assertThat(suppression.isArtifactSuppressed(workspaceId, "scm.issue", 812205L))
                .isTrue();
        assertThat(suppression.isArtifactSuppressed(-1L, "scm.issue", 812205L)).isFalse();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(fence.holdForUserWrites(List.of(target.getId(), other.getId())))
                    .containsExactly(other.getId());
            assertThat(fence.holdForUserWrite(target.getId())).isFalse();
        });
    }

    @Test
    void shouldFinishAnAdmittedWriteBeforeErasureChecksItsRows() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://fence.example.test"));
        var user = users.saveAndFlush(TestUserFactory.createUser(42L, "target", provider));
        var identities = List.of(new PersonIdentity(Objects.requireNonNull(provider.getId()), "42", null));
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var eraserPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertThat(fence.holdForWrite(identities)).isTrue();
                admitted.countDown();
                waitFor(release);
                jdbc.update(
                        "INSERT INTO user_preferences(user_id,participate_in_research,ai_review_enabled) VALUES (?,false,false)",
                        user.getId());
            }));
            assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
            var eraser = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                eraserPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                fence.holdForErasure(identities);
                return jdbc.queryForObject(
                        "SELECT count(*) FROM user_preferences WHERE user_id=?", Long.class, user.getId());
            }));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> waitingForLock(eraserPid.get()));
                assertThat(eraser.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
            assertThat(eraser.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldRefuseAWaitingWriterAfterTheErasureControlCommitsWithoutMatchingAnotherSlackTeam() throws Exception {
        databaseTestUtils.cleanDatabase();
        var provider = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        var alias = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.SLACK, "HTTPS://SLACK.COM:443/"));
        var identity = new PersonIdentity(Objects.requireNonNull(provider.getId()), "UFENCE", "T1");
        var aliasIdentity = new PersonIdentity(Objects.requireNonNull(alias.getId()), "UFENCE", "T1");
        var identities = List.of(identity);
        var request = requests.saveAndFlush(new PersonDataRequest());
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var eraser = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                fence.holdForErasure(identities);
                locked.countDown();
                waitFor(release);
                suppression.suppress(new PersonScope(null, identities, List.of()), request.getId());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                writerPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                return fence.holdForWrite(List.of(aliasIdentity));
            }));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> waitingForLock(writerPid.get()));
                assertThat(writer.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            eraser.get(10, TimeUnit.SECONDS);
            assertThat(writer.get(10, TimeUnit.SECONDS)).isFalse();
            new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> assertThat(fence.holdForWrite(
                                    List.of(new PersonIdentity(aliasIdentity.providerId(), identity.subject(), "T2"))))
                            .isTrue());

        } finally {
            release.countDown();
        }
    }

    private boolean waitingForLock(int pid) {
        return pid != 0
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event='advisory')",
                        Boolean.class,
                        pid));
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
