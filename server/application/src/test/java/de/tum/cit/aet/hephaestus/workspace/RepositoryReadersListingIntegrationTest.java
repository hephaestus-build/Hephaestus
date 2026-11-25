package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReaderListing;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryReadersProvider.ReadersRead;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A complete listing proves a private repository's readers for one monitor. A listing that overlapped another answer
 * does not apply, and GitHub's removal of a person, recorded through any write of the membership mirror, removes the
 * person's rows in the same transaction and ends every listing that began before it.
 */
class RepositoryReadersListingIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final Instant REQUESTED = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceSyncTargetProvider provider;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    /** Each test reads its own repository, so a narrowing trigger in one test never touches another's monitors. */
    private long repositoryNativeId;

    private long repositoryId;

    private long gitHub;

    @BeforeEach
    void ownRepository() {
        gitHub = Objects.requireNonNull(ensureGitHubProvider().getId());
        repositoryNativeId = ThreadLocalRandom.current().nextLong(100_000_000L, 1_000_000_000L);
        repositoryId = Objects.requireNonNull(
                jdbc.queryForObject("""
                INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,
                    html_url,pushed_at,visibility)
                VALUES (?,?,'repo',?,true,false,false,'https://example.com/repo',now(),'PRIVATE') RETURNING id
                """, Long.class, repositoryNativeId, gitHub, "course/" + repositoryNativeId));
    }

    private RepositoryToMonitor monitor(String slug) {
        long installation = ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
        Workspace workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installation, slug + "-" + repositoryNativeId),
                installation);
        return monitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(workspace, "course/" + repositoryNativeId, repositoryNativeId));
    }

    private ReadersRead read(RepositoryToMonitor monitor) {
        return provider.beginReadersProof(monitor.getId(), repositoryNativeId, gitHub, REQUESTED);
    }

    private String proof(RepositoryToMonitor monitor) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT visibility_class || ' ' || proof_state || ' ' || proof_reason FROM repository_to_monitor"
                        + " WHERE id = ?",
                String.class,
                monitor.getId()));
    }

    private List<Long> readers(RepositoryToMonitor monitor) {
        return jdbc
                .queryForList(
                        "SELECT reader_native_id FROM resource_reader WHERE repository_to_monitor_id = ? ORDER BY 1",
                        Long.class,
                        monitor.getId())
                .stream()
                .map(Objects::requireNonNull)
                .toList();
    }

    private void listed(RepositoryToMonitor monitor, Long... readers) {
        provider.recordRepositoryReaders(read(monitor), new ReaderListing.Listed(Set.of(readers)));
    }

    /** A GitHub user in the mirror, with GitHub's native id {@code nativeId}; returns the mirror row's id. */
    private long user(long nativeId) {
        return Objects.requireNonNull(
                jdbc.queryForObject("""
                INSERT INTO "user"(id,provider_id,native_id,login,type,avatar_url,html_url)
                VALUES (?,?,?,?,'USER','','') RETURNING id
                """, Long.class, mirrorId(), gitHub, nativeId, "reader-" + nativeId));
    }

    /** Far above any id a sequence hands out in a test run, so a mirror row never takes another test's next id. */
    private static long mirrorId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 9_000_000_000_000L);
    }

    private long readerNativeId() {
        return ThreadLocalRandom.current().nextLong(1_000_000L, 2_000_000_000L);
    }

    @Test
    void shouldProveThePrivateRepositorysReadersFromACompleteListing() {
        var monitor = monitor("listed");

        listed(monitor, 11L, 12L);

        assertThat(proof(monitor)).isEqualTo("RESTRICTED PROVEN READERS_LISTED");
        assertThat(readers(monitor)).containsExactly(11L, 12L);
        assertThat(monitors.findById(monitor.getId()).orElseThrow().getReaders().getReadersSyncedAt())
                .isEqualTo(REQUESTED);
    }

    @Test
    void shouldReplaceTheReadersWithTheNextCompleteListing() {
        var monitor = monitor("relisted");
        listed(monitor, 11L, 12L);

        listed(monitor, 12L, 13L);

        assertThat(readers(monitor)).containsExactly(12L, 13L);
    }

    /** Two listings overlap; the one that began first answers last and names a reader the later one did not. */
    @Test
    void shouldDiscardAListingThatOverlappedAnAppliedAnswer() {
        var monitor = monitor("overlap");
        ReadersRead slow = read(monitor);
        listed(monitor, 11L);

        provider.recordRepositoryReaders(slow, new ReaderListing.Listed(Set.of(11L, 12L)));

        assertThat(readers(monitor)).containsExactly(11L);
    }

    @Test
    void shouldStopTrustingTheReadersWhenAListingStopsButShowWhichProofWaits() {
        var monitor = monitor("stopped");
        listed(monitor, 11L);

        provider.recordRepositoryReaders(read(monitor), new ReaderListing.Stopped(ProofReason.RATE_LIMITED));

        assertThat(proof(monitor)).isEqualTo("RESTRICTED PENDING RATE_LIMITED");
        assertThat(provider.readersListingDue(monitor.getId(), Instant.now(), Duration.ofHours(1)))
                .as("a pending proof asks for a listing at once")
                .isTrue();
    }

    @Test
    void shouldDropTheReadersOfARepositoryWhoseReadersCannotBeListed() {
        var monitor = monitor("internal");
        listed(monitor, 11L);

        provider.recordRepositoryReaders(read(monitor), new ReaderListing.NotListable());

        assertThat(proof(monitor)).isEqualTo("UNPROVEN FAILED READERS_NOT_LISTABLE");
        assertThat(readers(monitor)).isEmpty();
    }

    /** A monitor holds readers only while a listing proves them: a public proof or a loss of access drops them. */
    @Test
    void shouldDropTheReadersWhenAnotherAnswerEndsTheListingsProof() {
        var turnedPublic = monitor("turned-public");
        var lostAccess = monitor("lost-access");
        listed(turnedPublic, 11L);
        listed(lostAccess, 11L);

        provider.recordRepositoryPublic(read(turnedPublic));
        provider.recordRepositoryUnavailable(
                Objects.requireNonNull(lostAccess.getWorkspace()).getId(), lostAccess.getId());

        assertThat(proof(turnedPublic)).isEqualTo("WORKSPACE PROVEN PUBLIC_ON_PROVIDER");
        assertThat(readers(turnedPublic)).isEmpty();
        assertThat(proof(lostAccess)).isEqualTo("UNPROVEN FAILED RESOURCE_UNAVAILABLE");
        assertThat(readers(lostAccess)).isEmpty();
    }

    @Test
    void shouldKeepTheListingWhenTheMetadataReadShowsTheRepositoryPrivateAgain() {
        var monitor = monitor("metadata-again");
        listed(monitor, 11L);

        provider.recordRepositoryNotPublic(read(monitor));

        assertThat(proof(monitor)).isEqualTo("RESTRICTED PROVEN READERS_LISTED");
        assertThat(readers(monitor)).containsExactly(11L);
        assertThat(provider.readersListingDue(monitor.getId(), REQUESTED, Duration.ofHours(1)))
                .as("a fresh listing is not due again")
                .isFalse();
        assertThat(provider.readersListingDue(
                        monitor.getId(), REQUESTED.plus(Duration.ofHours(2)), Duration.ofHours(1)))
                .as("an old listing is renewed")
                .isTrue();
    }

    /**
     * A listing of workspace A reads a page that names X, then X loses access, and workspace B's complete listing
     * removes X from the shared mirror. A's listing must not restore X, and B's proof must not keep X.
     */
    @Test
    void shouldNotLetAListingThatBeganBeforeARemovalRestoreTheRemovedReader() {
        var first = monitor("before-removal");
        var second = monitor("other-workspace");
        long removed = readerNativeId();
        long stays = readerNativeId();
        long removedUser = user(removed);
        listed(second, removed, stays);
        jdbc.update(
                "INSERT INTO repository_collaborator(repository_id,user_id,permission) VALUES (?,?,'READ')",
                repositoryId,
                removedUser);
        ReadersRead slow = read(first);

        jdbc.update(
                "DELETE FROM repository_collaborator WHERE repository_id = ? AND user_id = ?",
                repositoryId,
                removedUser);
        provider.recordRepositoryReaders(slow, new ReaderListing.Listed(Set.of(removed, stays)));

        assertThat(readers(first)).as("the slow listing does not apply").isEmpty();
        assertThat(proof(first)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(readers(second)).containsExactly(stays);
        assertThat(proof(second))
                .as("a removal narrows the reader set and keeps the proof of the others")
                .isEqualTo("RESTRICTED PROVEN READERS_LISTED");
    }

    @Test
    void shouldForgetTheTeamsMembersWhenTheTeamLosesTheRepository() {
        var monitor = monitor("team-access");
        long member = readerNativeId();
        long collaborator = readerNativeId();
        long memberUser = user(member);
        long team = team();
        jdbc.update("INSERT INTO team_membership(team_id,user_id) VALUES (?,?)", team, memberUser);
        jdbc.update(
                "INSERT INTO team_repository_permission(team_id,repository_id,permission) VALUES (?,?,'READ')",
                team,
                repositoryId);
        listed(monitor, member, collaborator);

        jdbc.update("DELETE FROM team_repository_permission WHERE team_id = ?", team);

        assertThat(readers(monitor)).containsExactly(collaborator);
    }

    @Test
    void shouldForgetAPersonWhoLeftATeamThatReadsTheRepository() {
        var monitor = monitor("team-member");
        long member = readerNativeId();
        long memberUser = user(member);
        long team = team();
        jdbc.update("INSERT INTO team_membership(team_id,user_id) VALUES (?,?)", team, memberUser);
        jdbc.update(
                "INSERT INTO team_repository_permission(team_id,repository_id,permission) VALUES (?,?,'READ')",
                team,
                repositoryId);
        listed(monitor, member);

        jdbc.update("DELETE FROM team_membership WHERE team_id = ? AND user_id = ?", team, memberUser);

        assertThat(readers(monitor)).isEmpty();
    }

    /** A team's deletion removes its memberships and its access in one cascade; its members' rows still go. */
    @Test
    void shouldForgetTheMembersOfADeletedTeam() {
        var monitor = monitor("team-deleted");
        long member = readerNativeId();
        long memberUser = user(member);
        long team = team();
        jdbc.update("INSERT INTO team_membership(team_id,user_id) VALUES (?,?)", team, memberUser);
        jdbc.update(
                "INSERT INTO team_repository_permission(team_id,repository_id,permission) VALUES (?,?,'READ')",
                team,
                repositoryId);
        listed(monitor, member);

        jdbc.update("DELETE FROM team WHERE id = ?", team);

        assertThat(readers(monitor)).isEmpty();
    }

    @Test
    void shouldForgetAPersonWhoLeftTheOrganizationThatOwnsTheRepository() {
        var monitor = monitor("org-member");
        long member = readerNativeId();
        long memberUser = user(member);
        long organization = Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO organization(id,native_id,login,provider_id)
                VALUES (?,?,?,?) RETURNING id
                """, Long.class, mirrorId(), readerNativeId(), "org-" + repositoryNativeId, gitHub));
        jdbc.update("UPDATE repository SET organization_id = ? WHERE id = ?", organization, repositoryId);
        jdbc.update(
                "INSERT INTO organization_membership(organization_id,user_id,role) VALUES (?,?,'MEMBER')",
                organization,
                memberUser);
        listed(monitor, member);

        jdbc.update(
                "DELETE FROM organization_membership WHERE organization_id = ? AND user_id = ?",
                organization,
                memberUser);

        assertThat(readers(monitor)).isEmpty();
    }

    @Test
    void shouldDropEveryMonitorsReadersWhenTheRepositoryIsDeleted() {
        var monitor = monitor("deleted");
        listed(monitor, 11L);

        jdbc.update("DELETE FROM repository WHERE id = ?", repositoryId);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(readers(monitor)).isEmpty();
    }

    @Test
    void shouldDeleteTheReadersWithTheirMonitor() {
        var monitor = monitor("unmonitored");
        listed(monitor, 11L);

        monitors.deleteById(monitor.getId());

        assertThat(readers(monitor)).isEmpty();
    }

    /**
     * Two real transactions: another replica holds the monitor's row lock while it applies a narrowing answer. The
     * listing waits for that lock, then sees the committed generation and does not apply.
     */
    @Test
    void shouldWaitForAnotherReplicasAnswerBeforeApplyingAListing() throws Exception {
        var monitor = monitor("contended");
        ReadersRead listingRead = read(monitor);
        var holderPid = new CompletableFuture<Integer>();
        var release = new CountDownLatch(1);
        var otherReplica = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject(
                    "SELECT id FROM repository_to_monitor WHERE id = ? FOR UPDATE", Long.class, monitor.getId());
            jdbc.update(
                    "UPDATE repository_to_monitor SET proof_generation = proof_generation + 1 WHERE id = ?",
                    monitor.getId());
            holderPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            awaitLatch(release);
        }));
        CompletableFuture<Void> listing;
        try {
            int holder = holderPid.get(30, TimeUnit.SECONDS);
            listing = CompletableFuture.runAsync(
                    () -> provider.recordRepositoryReaders(listingRead, new ReaderListing.Listed(Set.of(11L))));
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                    Long.class,
                                    holder))
                            .as("the listing waits for the other replica's row lock")
                            .isEqualTo(1L));
        } finally {
            release.countDown();
        }
        otherReplica.get(30, TimeUnit.SECONDS);
        listing.get(30, TimeUnit.SECONDS);

        assertThat(proof(monitor)).isEqualTo("UNPROVEN PENDING AWAITING_READERS");
        assertThat(readers(monitor)).isEmpty();
    }

    private long team() {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO team(id,native_id,provider_id,name,slug,organization)
                VALUES (?,?,?,'Readers',?,'course') RETURNING id
                """, Long.class, mirrorId(), readerNativeId(), gitHub, "readers-" + repositoryNativeId));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
