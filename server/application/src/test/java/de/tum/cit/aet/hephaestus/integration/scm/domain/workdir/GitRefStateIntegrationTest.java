package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewLookupData;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class GitRefStateIntegrationTest extends BaseIntegrationTest {
    @Autowired
    RepositoryRefState refs;

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    IssueRepository issues;

    @Autowired
    RepositoryRepository repositories;

    @Autowired
    RepositoryToMonitorRepository monitors;

    @Autowired
    ProductItemQuery items;

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path root;

    private record Scope(ReviewIntegrationData.Scenario scenario, ItemRef item, Repository repository) {
        RepositoryKey key() {
            return new RepositoryKey(item.workspaceId(), repository.getId());
        }
    }

    private Scope scope(IdentityProviderType provider) {
        var scenario = data.scenario();
        var item = lookup.work(scenario.workspace(), provider, true);
        var repository = Objects.requireNonNull(issues.findById(Objects.requireNonNull(item.sourceId()))
                .orElseThrow()
                .getRepository());
        return new Scope(
                scenario,
                item,
                repositories.findByIdWithOrganization(repository.getId()).orElseThrow());
    }

    @Test
    void shouldKeepOnlyTheLatestCompleteReadAndPreserveItWhenAReaderGivesUp() {
        var scope = scope(IdentityProviderType.GITHUB);
        var first = refs.begin(scope.key().workspaceId(), scope.key().repositoryId());
        var second = refs.begin(scope.key().workspaceId(), scope.key().repositoryId());
        refs.record(scope.key().workspaceId(), scope.key().repositoryId(), first, "a".repeat(64));
        assertThat(refs.current(scope.key().workspaceId(), scope.key().repositoryId()))
                .isEmpty();
        refs.record(scope.key().workspaceId(), scope.key().repositoryId(), second, "b".repeat(64));
        assertThat(refs.current(scope.key().workspaceId(), scope.key().repositoryId()))
                .contains("b".repeat(64));
        var manager = manager("abandoned");
        assertThatThrownBy(
                        () -> manager.ensureRepository(scope.key(), root.toUri().toString(), null, () -> true))
                .isInstanceOf(CancellationException.class);
        assertThat(refs.current(scope.key().workspaceId(), scope.key().repositoryId()))
                .contains("b".repeat(64));
        refs.invalidate(
                scope.repository().getNativeId(),
                Objects.requireNonNull(scope.repository().getProvider().getId()));
        refs.record(scope.key().workspaceId(), scope.key().repositoryId(), second, "b".repeat(64));
        assertThat(refs.current(scope.key().workspaceId(), scope.key().repositoryId()))
                .isEmpty();
    }

    @Test
    void shouldInvalidateEveryMonitoringWorkspaceOnlyOnTheExactProvider() {
        var github = scope(IdentityProviderType.GITHUB);
        var other = scope(IdentityProviderType.GITHUB);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(
                other.scenario().workspace(),
                github.repository().getNameWithOwner(),
                github.repository().getNativeId()));
        var secondKey = new RepositoryKey(
                other.item().workspaceId(), github.repository().getId());
        var gitlab = scope(IdentityProviderType.GITLAB);
        var monitor = monitors.findByWorkspaceIdAndSourceRepositoryId(
                        gitlab.item().workspaceId(), gitlab.repository().getId())
                .getFirst();
        gitlab.repository().setNativeId(github.repository().getNativeId());
        repositories.saveAndFlush(gitlab.repository());
        monitor.setNativeId(github.repository().getNativeId());
        monitors.saveAndFlush(monitor);
        refs.record(
                github.key().workspaceId(),
                github.key().repositoryId(),
                refs.begin(github.key().workspaceId(), github.key().repositoryId()),
                "a".repeat(64));
        refs.record(
                secondKey.workspaceId(),
                secondKey.repositoryId(),
                refs.begin(secondKey.workspaceId(), secondKey.repositoryId()),
                "b".repeat(64));
        refs.record(
                gitlab.key().workspaceId(),
                gitlab.key().repositoryId(),
                refs.begin(gitlab.key().workspaceId(), gitlab.key().repositoryId()),
                "c".repeat(64));
        refs.invalidate(
                github.repository().getNativeId(),
                Objects.requireNonNull(github.repository().getProvider().getId()));
        assertThat(refs.current(github.key().workspaceId(), github.key().repositoryId()))
                .isEmpty();
        assertThat(refs.current(secondKey.workspaceId(), secondKey.repositoryId()))
                .isEmpty();
        assertThat(refs.current(gitlab.key().workspaceId(), gitlab.key().repositoryId()))
                .contains("c".repeat(64));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepAPracticeCurrentWhenAnotherBranchOrATagOfItsRepositoryMoves(IdentityProviderType provider)
            throws Exception {
        var scope = scope(provider);
        var upstreamPath = root.resolve("upstream");
        try (var upstream = Git.init()
                .setInitialBranch("main")
                .setDirectory(upstreamPath.toFile())
                .call()) {
            Files.writeString(upstreamPath.resolve("README.md"), "Repository evidence.\n");
            upstream.add().addFilepattern(".").call();
            String base = commit(upstream, "Initial source");
            upstream.checkout().setCreateBranch(true).setName("topic").call();
            Files.writeString(upstreamPath.resolve("change.txt"), "The reviewed change.\n");
            upstream.add().addFilepattern(".").call();
            String head = commit(upstream, "The reviewed change");
            var worker = manager("worker");
            worker.ensureRepository(scope.key(), upstreamPath.toUri().toString(), null);
            var kind = RepositoryTreeContentSource.KIND;
            jdbc.update(
                    "UPDATE issue SET head_ref_oid=?, base_ref_oid=? WHERE id=?",
                    head,
                    base,
                    scope.item().sourceId());
            try (var capture = worker.readTreeSnapshot(scope.key(), head, base)) {
                String captured = SourceFingerprint.git(kind, GitRepositoryManager.treeIdentity(head, base));
                var otherHost = manager("other-host");
                assertThat(otherHost.isRepositoryCloned(scope.key())).isFalse();
                assertThat(items.fingerprints(scope.item(), Set.of(kind), null))
                        .as("the server reads what the capture read, from the pull request's commits alone")
                        .containsEntry(kind, captured);

                // Another developer pushes to another branch, and a release tag moves, while the practice waits.
                upstream.checkout()
                        .setCreateBranch(true)
                        .setName("unrelated-work")
                        .call();
                Files.writeString(upstreamPath.resolve("unrelated.txt"), "Another developer's work.\n");
                upstream.add().addFilepattern(".").call();
                commit(upstream, "Unrelated work");
                upstream.tag()
                        .setName("v1")
                        .setMessage("A release")
                        .setTagger(new PersonIdent("Synthetic author", "author@example.test"))
                        .setSigned(false)
                        .call();
                otherHost.ensureRepository(scope.key(), upstreamPath.toUri().toString(), null);

                assertThat(items.fingerprints(scope.item(), Set.of(kind), null))
                        .as("nothing the practice can read changed")
                        .containsEntry(kind, captured);
                try (var checkout = Git.open(capture.stagingDir().toFile())) {
                    assertThat(checkout.getRepository().getRefDatabase().getRefs())
                            .extracting(Ref::getName)
                            .containsExactly(Constants.HEAD);
                }
            }
        }
    }

    private static String commit(Git git, String message) throws Exception {
        return git.commit()
                .setMessage(message)
                .setAuthor("Synthetic author", "author@example.test")
                .setCommitter("Synthetic author", "author@example.test")
                .setSign(false)
                .call()
                .name();
    }

    private GitRepositoryManager manager(String role) {
        return new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 1L << 28, 1L << 40),
                new GitRepositoryLockManager(),
                new FabricLayout(root.resolve(role).toString()),
                GitTransportFixtures.guarded(),
                refs,
                GitTransportFixtures.copyFence(),
                new SimpleMeterRegistry());
    }
}
