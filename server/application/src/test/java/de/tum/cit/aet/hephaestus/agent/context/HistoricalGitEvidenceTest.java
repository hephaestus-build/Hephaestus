package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryLockManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.GitTestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Quotes are verified against the checkout the review saw, whose {@code .git} holds only what it could reach. */
class HistoricalGitEvidenceTest extends BaseUnitTest {
    private static final RepositoryKey KEY = new RepositoryKey(1L, 1L);

    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final HistoricalGitEvidence verifier = new HistoricalGitEvidence(files);
    private final AgentJob job = new AgentJob();

    @TempDir
    private Path temporary;

    private String first;
    private String head;
    private String unreachable;
    private GitRepositoryManager.GitTreeSnapshot snapshot;

    @BeforeEach
    void setUp() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        try (Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(source.toFile())
                .call()) {
            GitTestFixtures.disableSigning(git.getRepository());
            Files.writeString(source.resolve("one.java"), "other line\nsame quote\n");
            first = commit(git, "First");
            Files.writeString(source.resolve("two.java"), "same quote\n");
            head = commit(git, "Second");
            git.checkout().setCreateBranch(true).setName("later").call();
            Files.writeString(source.resolve("three.java"), "never captured\n");
            unreachable = commit(git, "Never fetched");
            git.checkout().setName("main").call();
            git.branchDelete().setBranchNames("later").setForce(true).call();
        }
        var manager = new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 8L << 30),
                new GitRepositoryLockManager(),
                new FabricLayout(temporary.resolve("fabric").toString()));
        manager.ensureRepository(KEY, source.toUri().toString(), null);
        snapshot = manager.readTreeSnapshot(KEY, head);
        when(files.repositoryForVerification(job, "head-digest", "refs-digest")).thenReturn(snapshot.stagingDir());
    }

    @Test
    void shouldVerifyQuotesAgainstTheCitedRevisionAndAnswerAbsenceForTheRest() {
        var match = new HistoricalGitEvidence.Citation(first, "one.java", "same quote", 2, 2);
        var wrongLine = new HistoricalGitEvidence.Citation(head, "two.java", "same quote", 2, 2);
        var missingPath = new HistoricalGitEvidence.Citation(head, "gone.java", "same quote", 1, 1);
        var notCaptured = new HistoricalGitEvidence.Citation(unreachable, "three.java", "never captured", 1, 1);
        var directory = new HistoricalGitEvidence.Citation(head, "", "same quote", 1, 1);
        var result = verifier.verifyAll(
                job,
                "head-digest",
                "refs-digest",
                head,
                List.of(match, wrongLine, missingPath, notCaptured, directory, match));
        assertThat(result).hasSize(5);
        assertThat(Objects.requireNonNull(result.get(match)).matches()).isTrue();
        assertThat(Objects.requireNonNull(result.get(match)).artifactSha256()).hasSize(64);
        assertThat(Objects.requireNonNull(result.get(wrongLine)).matches()).isFalse();
        assertThat(Objects.requireNonNull(result.get(wrongLine)).artifactSha256())
                .isNotNull();
        assertThat(result.get(missingPath)).isEqualTo(JobEvidenceFiles.QuoteMatch.absent());
        assertThat(result.get(notCaptured)).isEqualTo(JobEvidenceFiles.QuoteMatch.absent());
        assertThat(result.get(directory)).isEqualTo(JobEvidenceFiles.QuoteMatch.absent());
    }

    @Test
    void shouldRefuseACheckoutWhoseHeadIsNotThePinnedOne() {
        var citation = new HistoricalGitEvidence.Citation(first, "one.java", "same quote", 2, 2);
        assertThatThrownBy(() -> verifier.verifyAll(job, "head-digest", "refs-digest", first, List.of(citation)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pinned head");
    }

    @Test
    void shouldVerifyNothingWithoutCitations() {
        assertThat(verifier.verifyAll(job, "head-digest", "refs-digest", head, List.of()))
                .isEmpty();
    }

    private static String commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        return git.commit()
                .setSign(false)
                .setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
                .name();
    }
}
