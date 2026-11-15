package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ContextLoader;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryCheckouts;
import de.tum.cit.aet.hephaestus.integration.scm.context.ConversationContextQueryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.context.ScmConversationWorkQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScmContextLoaderTest extends BaseUnitTest {

    private static final long WS = 3;
    private static final long ACCOUNT = 4;

    @TempDir
    Path temp;

    private final IdentityProvider github = new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com");
    private final ReviewRepositoryPreparer preparer = mock(ReviewRepositoryPreparer.class);
    private final ScmConversationWorkQuery work = mock(ScmConversationWorkQuery.class);
    private final ConversationContextQueryRepository authored = mock(ConversationContextQueryRepository.class);
    private final RepositoryCheckouts checkouts = mock(RepositoryCheckouts.class);
    private final WorkspaceTeamRepositorySettingsRepository settings =
            mock(WorkspaceTeamRepositorySettingsRepository.class);
    private final ScmContextLoader loader =
            new ScmContextLoader(checkouts, preparer, settings, mock(PullRequestRepository.class), work, authored);

    private Repository repository(long id, String name, String defaultBranch) {
        var repository = new Repository();
        repository.setId(id);
        repository.setNameWithOwner(name);
        repository.setDefaultBranch(defaultBranch);
        repository.setProvider(github);
        return repository;
    }

    private void workspace(List<Repository> repositories) {
        ScmTokenSource source = mock(ScmTokenSource.class);
        when(source.serverUrl(WS)).thenReturn(Optional.of("https://github.com"));
        when(preparer.activeTokenSource(WS)).thenReturn(Optional.of(source));
        when(preparer.permittedRepositories(WS)).thenReturn(repositories);
        when(settings.findHiddenRepositoryIdsByWorkspace(WS)).thenReturn(Set.of());
        when(checkouts.enabled()).thenReturn(true);
    }

    private static PullRequest pullRequest(Repository repository, int number, Issue.State state) {
        var pullRequest = new PullRequest();
        pullRequest.setRepository(repository);
        pullRequest.setNumber(number);
        pullRequest.setState(state);
        return pullRequest;
    }

    @Test
    void shouldNameTheRepositoriesOfTheDevelopersRecentWorkFirstWithTheirOpenReviewsWhenTheTurnStarts() {
        Repository api = repository(1, "acme/api", "main");
        Repository web = repository(2, "acme/web", "master");
        Repository zeta = repository(3, "acme/zeta", "main");
        workspace(List.of(zeta, api, web));
        when(work.actors(WS, ACCOUNT)).thenReturn(Optional.of(new ScmConversationWorkQuery.Actors(9, List.of(7L))));
        when(authored.findRecentAuthoredPullRequests(eq(WS), eq(9L), eq(List.of(7L)), any()))
                .thenReturn(List.of(
                        pullRequest(web, 12, Issue.State.OPEN),
                        pullRequest(api, 3, Issue.State.CLOSED),
                        pullRequest(web, 10, Issue.State.OPEN)));

        assertThat(loader.situation(WS, ACCOUNT))
                .isEqualTo("Repositories you can load now, their recent work first (INDEX.md lists all):"
                        + " repos/github.com/acme/web (default master; your open @pull/12 @pull/10);"
                        + " repos/github.com/acme/api (default main); repos/github.com/acme/zeta (default main).");
    }

    @Test
    void shouldStayWithinItsBoundWhenTheWorkspaceHasManyRepositories() {
        List<Repository> many = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            many.add(repository(index, "acme-with-a-long-organisation-name/repository-number-" + index, "main"));
        }
        workspace(many);
        when(work.actors(anyLong(), anyLong())).thenReturn(Optional.empty());

        String line = loader.situation(WS, ACCOUNT);

        assertThat(line).hasSizeLessThanOrEqualTo(ContextLoader.SITUATION_CHARS);
        assertThat(line.split("repos/", -1)).hasSizeBetween(2, ScmContextLoader.SITUATION_REPOSITORIES + 1);
    }

    @Test
    void shouldSummariseAReviewAgainstItsMergeBaseWhenALoadedReviewIsStaged() throws Exception {
        Path directory = Files.createDirectories(temp.resolve("repo"));
        try (Git git = Git.init()
                .setDirectory(directory.toFile())
                .setInitialBranch("main")
                .call()) {
            git.getRepository().getConfig().setBoolean("commit", null, "gpgsign", false);
            commit(git, directory, "README.md", "Base");
            git.checkout().setCreateBranch(true).setName("feature").call();
            for (int file = 1; file <= 45; file++) {
                commit(git, directory, "src/File" + file + ".java", "class File" + file + " {}");
            }
            git.checkout().setName("main").call();
            commit(git, directory, "NOTES.md", "Later on main");
            git.checkout().setName("feature").call();
        }

        Process summary = new ProcessBuilder("sh", "-c", ScmContextLoader.REVIEW_SUMMARY, "summary", "main")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(summary.waitFor(30, TimeUnit.SECONDS)).isTrue();
        String output = new String(summary.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(output)
                .startsWith("Merge base with main: ")
                .contains("Last commits of the head:\n")
                .contains(" Change src/File45.java\n")
                .contains("… 5 more files\n")
                .contains("45 files changed")
                .doesNotContain("NOTES.md");
        assertThat(output.lines().filter(line -> line.contains("Change ")).count())
                .isEqualTo(5);
    }

    private static void commit(Git git, Path directory, String path, String content) throws Exception {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(".").call();
        git.commit()
                .setSign(false)
                .setMessage("Change " + path)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call();
    }
}
