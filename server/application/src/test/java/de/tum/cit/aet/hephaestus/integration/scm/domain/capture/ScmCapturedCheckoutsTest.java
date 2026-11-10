package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.capture.CheckoutKey;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.CheckoutUnverifiedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitTransportFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A captured checkout built again on another server: from its own mirror, verified, or refused with a reason. */
@Tag("unit")
class ScmCapturedCheckoutsTest {
    private static final long WORKSPACE = 7L;
    private static final RepositoryKey KEY = new RepositoryKey(WORKSPACE, 11L);

    @TempDir
    Path temporary;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ReviewRepositoryPreparer preparer = mock(ReviewRepositoryPreparer.class);
    private GitRepositoryManager git;
    private Path upstream;
    private String head;
    private String tree;

    @BeforeEach
    void setUp() throws Exception {
        // Where the clone URL of `owner/repo` on the server `srv` points.
        upstream = Files.createDirectories(temporary.resolve("srv/owner/repo.git"));
        try (Git repository = Git.init()
                .setInitialBranch("main")
                .setDirectory(upstream.toFile())
                .call()) {
            GitTestFixtures.disableSigning(repository.getRepository());
            Files.writeString(upstream.resolve("App.java"), "class App {}\n");
            repository.add().addFilepattern(".").call();
            head = repository
                    .commit()
                    .setSign(false)
                    .setMessage("Initial commit")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()
                    .name();
            tree = repository
                    .getRepository()
                    .parseCommit(repository.getRepository().resolve(head))
                    .getTree()
                    .name();
        }
        git = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("fabric").toString()),
                GitTransportFixtures.guarded(),
                mock(RepositoryRefState.class));
        when(preparer.permittedRepositories(WORKSPACE)).thenReturn(List.of());
        when(preparer.activeTokenSource(WORKSPACE)).thenReturn(Optional.empty());
    }

    @Test
    void shouldCopyTheCheckoutFromThisServersMirrorAndThenFromItsCache() {
        git.ensureRepository(KEY, upstream.toUri().toString(), null);
        var checkouts = new ScmCapturedCheckouts(git, preparer, meters);
        var key = new CheckoutKey(KEY.repositoryId(), head, tree, null, null);

        checkouts.copy(WORKSPACE, key, temporary.resolve("first"), copy -> true);
        checkouts.copy(WORKSPACE, key, temporary.resolve("second"), copy -> true);

        assertThat(temporary.resolve("second/App.java")).hasContent("class App {}");
        assertThat(count("rebuilt")).isEqualTo(1);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    void shouldRefuseACopyThatTheCallersDigestCheckRejectsAndLeaveNoCopy() {
        git.ensureRepository(KEY, upstream.toUri().toString(), null);
        var checkouts = new ScmCapturedCheckouts(git, preparer, meters);
        Path target = temporary.resolve("copy");

        assertThatThrownBy(() -> checkouts.copy(
                        WORKSPACE, new CheckoutKey(KEY.repositoryId(), head, tree, null, null), target, copy -> false))
                .isInstanceOfSatisfying(
                        CheckoutUnverifiedException.class,
                        unverified ->
                                assertThat(unverified.reason()).isEqualTo(CheckoutUnverifiedException.Reason.MISMATCH));
        assertThat(target).doesNotExist();
        assertThat(count("mismatch")).isEqualTo(1);
    }

    @Test
    void shouldRefuseARecordedTreeThatTheCommitDoesNotHave() {
        git.ensureRepository(KEY, upstream.toUri().toString(), null);
        var checkouts = new ScmCapturedCheckouts(git, preparer, meters);

        assertThatThrownBy(() -> checkouts.copy(
                        WORKSPACE,
                        new CheckoutKey(KEY.repositoryId(), head, "c".repeat(40), null, null),
                        temporary.resolve("copy"),
                        copy -> true))
                .isInstanceOfSatisfying(
                        CheckoutUnverifiedException.class,
                        unverified ->
                                assertThat(unverified.reason()).isEqualTo(CheckoutUnverifiedException.Reason.MISMATCH));
    }

    @Test
    void shouldNotFetchForARepositoryThatTheWorkspaceNoLongerMonitors() {
        var checkouts = new ScmCapturedCheckouts(git, preparer, meters);

        assertThatThrownBy(() -> checkouts.copy(
                        WORKSPACE,
                        new CheckoutKey(KEY.repositoryId(), head, tree, null, null),
                        temporary.resolve("copy"),
                        copy -> true))
                .isInstanceOfSatisfying(
                        CheckoutUnverifiedException.class,
                        unverified -> assertThat(unverified.reason())
                                .isEqualTo(CheckoutUnverifiedException.Reason.UNAVAILABLE));
        assertThat(git.isRepositoryCloned(KEY)).isFalse();
        assertThat(count("unavailable")).isEqualTo(1);
    }

    @Test
    void shouldFetchTheMirrorAgainWhenAnEvictionTakesItBetweenTheCheckAndTheCopy() {
        git.ensureRepository(KEY, upstream.toUri().toString(), null);
        var evicting = spy(git);
        doThrow(new GitRepositoryManager.MirrorUnavailableException())
                .doCallRealMethod()
                .when(evicting)
                .copyCheckout(any(), any(), any(), any(), any());
        var repository = TestEntities.repository(KEY.repositoryId(), "owner/repo");
        when(preparer.permittedRepositories(WORKSPACE)).thenReturn(List.of(repository));
        var source = mock(ScmTokenSource.class);
        when(source.serverUrl(WORKSPACE))
                .thenReturn(Optional.of(temporary.resolve("srv").toUri().toString()));
        when(source.accessToken(WORKSPACE)).thenReturn(Optional.of(""));
        when(preparer.activeTokenSource(WORKSPACE)).thenReturn(Optional.of(source));
        var checkouts = new ScmCapturedCheckouts(evicting, preparer, meters);

        checkouts.copy(
                WORKSPACE,
                new CheckoutKey(KEY.repositoryId(), head, tree, null, null),
                temporary.resolve("copy"),
                copy -> true);

        assertThat(temporary.resolve("copy/App.java")).hasContent("class App {}");
        assertThat(count("fetch_failed"))
                .as("an eviction under disk pressure is no capture failure")
                .isZero();
    }

    private double count(String outcome) {
        var counter = meters.find(ScmMetrics.EVIDENCE_CHECKOUT_COPIES)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
