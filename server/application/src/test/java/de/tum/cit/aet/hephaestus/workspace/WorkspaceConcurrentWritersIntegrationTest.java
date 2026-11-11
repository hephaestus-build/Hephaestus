package de.tum.cit.aet.hephaestus.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each test holds a first writer's transaction open until PostgreSQL shows the second writer waiting on a lock,
 * then commits it, so the interleaving is the same on every run.
 */
@DisplayName("Concurrent writers over PostgreSQL")
class WorkspaceConcurrentWritersIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceSettingsService settings;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void shouldKeepThePurgeWhenASuspendRacesIt() throws Exception {
        String slug = "race-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = Objects.requireNonNull(workspaceRepository
                .saveAndFlush(WorkspaceTestFixtures.activeWorkspace(slug))
                .getId());

        Future<Throwable> suspend = whileFirstWriterHoldsItsTransaction(
                () -> lifecycle.purgeWorkspace(slug), () -> lifecycle.suspendWorkspace(slug));

        assertThat(suspend.get(30, SECONDS)).isInstanceOf(WorkspaceLifecycleViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspaceId))
                .isEqualTo("PURGED");
    }

    @Test
    void shouldKeepContributorNamesOffWhenSearchIndexingChangesConcurrently() throws Exception {
        var workspace = WorkspaceTestFixtures.activeWorkspace(
                "race-" + UUID.randomUUID().toString().substring(0, 8));
        workspace.setPublicActivityEnabled(true);
        workspace.setPublicActivityContributorsWithoutAccount(true);
        long workspaceId = Objects.requireNonNull(
                workspaceRepository.saveAndFlush(workspace).getId());

        Future<Throwable> second = whileFirstWriterHoldsItsTransaction(
                () -> settings.updatePublicActivity(workspaceId, new PublicActivityChoice(null, null, false)),
                () -> settings.updatePublicActivity(workspaceId, new PublicActivityChoice(null, true, null)));

        assertThat(second.get(30, SECONDS)).isNull();
        assertThat(jdbc.queryForMap(
                        "SELECT public_activity_enabled, public_activity_search_engines,"
                                + " public_activity_contributors_without_account FROM workspace WHERE id = ?",
                        workspaceId))
                .containsEntry("public_activity_enabled", true)
                .containsEntry("public_activity_search_engines", true)
                .containsEntry("public_activity_contributors_without_account", false);
    }

    @Test
    void shouldReturnTheSameOrganizationWhenTwoWritersCreateItTogether() throws Exception {
        long providerId = Objects.requireNonNull(ensureGitHubProvider().getId());
        long nativeId = 7_000_000_000L + (UUID.randomUUID().getMostSignificantBits() & 0xFFFFFF);
        AtomicLong firstId = new AtomicLong();

        Future<Throwable> second = whileFirstWriterHoldsItsTransaction(
                () -> firstId.set(Objects.requireNonNull(organizations
                        .upsertIdentity(nativeId, "first-login", providerId)
                        .getId())),
                () -> organizations.upsertIdentity(nativeId, "second-login", providerId));

        assertThat(second.get(30, SECONDS)).isNull();
        assertThat(jdbc.queryForList(
                        "SELECT id FROM organization WHERE native_id = ? AND provider_id = ?",
                        Long.class,
                        nativeId,
                        providerId))
                .containsExactly(firstId.get());
        assertThat(jdbc.queryForObject("SELECT login FROM organization WHERE id = ?", String.class, firstId.get()))
                .isEqualTo("second-login");
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void shouldKeepAPersonsRoleWhenAProviderGrantWaitsForTheManualChange(boolean fullRoster, boolean rejoining)
            throws Exception {
        var fixture = memberFixture();
        if (rejoining) workspaceMembershipService.applyProviderGrant(fixture.workspace(), fixture.userId(), null);

        Future<Throwable> provider = whileFirstWriterHoldsItsTransaction(
                () -> assignRole(fixture, Membership.WorkspaceRole.ADMIN),
                () -> grant(fixture, Membership.WorkspaceRole.ADMIN, fullRoster));

        assertThat(provider.get(30, SECONDS)).isNull();
        assertMembership(fixture, Membership.WorkspaceRole.ADMIN, Membership.RoleSource.PERSON);
        assertRoleChanges(fixture, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldApplyTheManualRoleAfterAConcurrentProviderPromotion(boolean fullRoster) throws Exception {
        var fixture = memberFixture();

        Future<Throwable> manual = whileFirstWriterHoldsItsTransaction(
                () -> grant(fixture, Membership.WorkspaceRole.ADMIN, fullRoster),
                () -> assignRole(fixture, Membership.WorkspaceRole.MEMBER));

        assertThat(manual.get(30, SECONDS)).isNull();
        assertMembership(fixture, Membership.WorkspaceRole.MEMBER, Membership.RoleSource.PERSON);
        assertRoleChanges(fixture, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRecordOneRoleChangeWhenTwoProviderGrantsPromoteTheSameMember(boolean fullRoster) throws Exception {
        var fixture = memberFixture();

        Future<Throwable> provider = whileFirstWriterHoldsItsTransaction(
                () -> grant(fixture, Membership.WorkspaceRole.ADMIN, fullRoster),
                () -> grant(fixture, Membership.WorkspaceRole.ADMIN, fullRoster));

        assertThat(provider.get(30, SECONDS)).isNull();
        assertMembership(fixture, Membership.WorkspaceRole.ADMIN, Membership.RoleSource.PROVIDER);
        assertRoleChanges(fixture, 1);
    }

    private record MemberFixture(Workspace workspace, long userId, long accountId) {}

    private MemberFixture memberFixture() {
        String slug = "roles-" + UUID.randomUUID().toString().substring(0, 8);
        var owner = persistUser(slug + "-owner");
        var workspace = createWorkspace(slug, slug, slug, AccountType.ORG, owner);
        var user = persistUser(slug + "-member");
        ensureMembership(workspace, user, Membership.WorkspaceRole.MEMBER);
        return new MemberFixture(workspace, Objects.requireNonNull(user.getId()), accountId(user));
    }

    private void grant(MemberFixture fixture, Membership.WorkspaceRole role, boolean fullRoster) {
        if (fullRoster)
            workspaceMembershipService.syncWorkspaceMembers(fixture.workspace(), Map.of(fixture.userId(), role));
        else workspaceMembershipService.applyProviderGrant(fixture.workspace(), fixture.userId(), role);
    }

    private void assignRole(MemberFixture fixture, Membership.WorkspaceRole role) {
        WorkspaceContextHolder.setContext(
                WorkspaceContext.fromWorkspace(fixture.workspace(), Set.of(Membership.WorkspaceRole.ADMIN), null));
        try {
            workspaceMembershipService.assignRole(fixture.workspace().getId(), fixture.accountId(), role);
        } finally {
            WorkspaceContextHolder.clearContext();
        }
    }

    private void assertMembership(MemberFixture fixture, Membership.WorkspaceRole role, Membership.RoleSource source) {
        var member =
                workspaceMembershipService.getMembership(fixture.workspace().getId(), fixture.accountId());
        assertThat(member.getRole()).isEqualTo(role);
        assertThat(member.getRoleSource()).isEqualTo(source);
        assertThat(member.getLeftAt()).isNull();
    }

    private void assertRoleChanges(MemberFixture fixture, int expected) {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM config_audit_event
                        WHERE workspace_id = ? AND entity_type = 'WORKSPACE_ROLE' AND entity_id = ?
                          AND action = 'UPDATED' AND changed_keys @> ARRAY['role']
                        """, Integer.class, fixture.workspace().getId(), Long.toString(fixture.accountId())))
                .isEqualTo(expected);
    }

    /** Runs {@code first} in a transaction that commits only after {@code second} waits on its lock; returns its failure. */
    private Future<Throwable> whileFirstWriterHoldsItsTransaction(Runnable first, Runnable second) throws Exception {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstBackend = new AtomicInteger();
        Future<?> firstWriter = threads.submit(() -> transactions.executeWithoutResult(status -> {
            first.run();
            firstBackend.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
            written.countDown();
            await(release);
        }));
        try {
            assertThat(written.await(30, SECONDS)).isTrue();
            Callable<Throwable> secondWriter = () -> {
                try {
                    second.run();
                    return null;
                } catch (RuntimeException failure) {
                    return failure;
                }
            };
            Future<Throwable> outcome = threads.submit(secondWriter);
            awaitASessionBlockedBy(firstBackend.get(), outcome);
            release.countDown();
            firstWriter.get(30, SECONDS);
            return outcome;
        } finally {
            release.countDown();
        }
    }

    private void awaitASessionBlockedBy(int backend, Future<Throwable> contender) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            assertThat(contender.isDone())
                    .as("the second writer must wait for the first transaction")
                    .isFalse();
            Integer blocked = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class,
                    backend);
            if (blocked != null && blocked > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("The second writer never waited on the first writer's lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, SECONDS)) throw new AssertionError("The test never released the first writer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
