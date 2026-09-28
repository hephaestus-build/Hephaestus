package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures.admittedMentorConfig;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnPersistence;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer.ReviewState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code findFirstUserMessagePartsByThreadIds} is native SQL, so it isn't boot-validated like JPQL — a
 * jsonb type-mapping regression could silently empty the prior-conversation context. Runs against a real
 * Postgres container to pin the {@code DISTINCT ON} and jsonb-to-text projection contracts.
 */
class MentorContextQueryRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MentorContextQueryRepository queryRepository;

    @Autowired
    private MentorTurnPersistence persistence;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    private Workspace workspace;
    private User user;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = new Workspace();
        workspace.setWorkspaceSlug("mentor-context-q");
        workspace.setDisplayName("Mentor Context Query Workspace");
        workspace.setAccountLogin("mentor-context-org");
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaceRepository.save(workspace);

        IdentityProvider gitProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));

        user = new User();
        user.setNativeId(8_001L);
        user.setLogin("context-tester");
        user.setName("Context Tester");
        user.setAvatarUrl("https://example.com/a.png");
        user.setHtmlUrl("https://gitlab.com/context-tester");
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(gitProvider);
        user = userRepository.save(user);
    }

    private ChatThread seedThreadWithUserMessage(String firstPrompt) {
        ChatThread thread =
                persistence.ensureThread(workspace.getId(), UUID.randomUUID(), user, Set.of(user.getId()), firstPrompt);
        persistence.persistInFlight(thread, firstPrompt, UUID.randomUUID(), null, admittedMentorConfig());
        return thread;
    }

    @Test
    void returnsEarliestUserMessagePartsPerThread() {
        ChatThread t1 = seedThreadWithUserMessage("how do I structure my PR description?");
        ChatThread t2 = seedThreadWithUserMessage("what makes a good test?");

        List<Object[]> rows = queryRepository.findFirstUserMessagePartsByThreadIds(
                workspace.getId(), List.of(t1.getId(), t2.getId()));

        Map<UUID, String> byThread = new HashMap<>();
        for (Object[] row : rows) {
            byThread.put((UUID) row[0], row[1] == null ? null : row[1].toString());
        }

        assertThat(byThread).containsKeys(t1.getId(), t2.getId());
        // parts is projected as jsonb-cast-to-text; the user's prompt text is embedded in it.
        assertThat(byThread.get(t1.getId())).contains("how do I structure my PR description?");
        assertThat(byThread.get(t2.getId())).contains("what makes a good test?");
    }

    @Test
    void excludesThreadsFromOtherWorkspaces() {
        ChatThread mine = seedThreadWithUserMessage("my prompt");

        Workspace other = new Workspace();
        other.setWorkspaceSlug("other-context-ws");
        other.setDisplayName("Other");
        other.setAccountLogin("other-org");
        other.setAccountType(AccountType.ORG);
        other = workspaceRepository.save(other);
        ChatThread foreign = persistence.ensureThread(
                other.getId(), UUID.randomUUID(), user, Set.of(user.getId()), "foreign prompt");
        persistence.persistInFlight(foreign, "foreign prompt", UUID.randomUUID(), null, admittedMentorConfig());

        List<Object[]> rows = queryRepository.findFirstUserMessagePartsByThreadIds(
                workspace.getId(), List.of(mine.getId(), foreign.getId()));

        assertThat(rows).hasSize(1);
        assertThat((UUID) rows.get(0)[0]).isEqualTo(mine.getId());
    }

    /** Heph counts a review request pending until its reviewer gives a verdict; a comment is not one. */
    @Nested
    class PendingReviewRequests {

        private Repository repository;
        private User author;
        private final AtomicLong nativeIds = new AtomicLong(880_000);

        @BeforeEach
        void seedRepository() {
            repository = new Repository();
            repository.setNativeId(nativeIds.incrementAndGet());
            repository.setProvider(user.getProvider());
            repository.setName("widgets");
            repository.setNameWithOwner("mentor-context-org/widgets");
            repository.setHtmlUrl("https://gitlab.com/mentor-context-org/widgets");
            repository.setDefaultBranch("main");
            repository = repositoryRepository.save(repository);
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setWorkspace(workspace);
            monitor.setNameWithOwner(repository.getNameWithOwner());
            repositoryToMonitorRepository.save(monitor);
            author = new User();
            author.setNativeId(8_002L);
            author.setLogin("author");
            author.setName("Author");
            author.setAvatarUrl("https://example.com/b.png");
            author.setHtmlUrl("https://gitlab.com/author");
            author.setType(User.Type.USER);
            author.setProvider(user.getProvider());
            author = userRepository.save(author);
        }

        @Test
        void shouldCountARequestPendingUntilGitLabSaysTheReviewerGaveAVerdict() {
            PullRequest reRequested = asking(gitLab(), ReviewState.UNREVIEWED);
            review(reRequested, PullRequestReview.State.APPROVED, false);
            PullRequest started = asking(gitLab(), ReviewState.REVIEW_STARTED);
            PullRequest withdrawn = asking(gitLab(), ReviewState.UNAPPROVED);
            PullRequest approved = asking(gitLab(), ReviewState.APPROVED);
            PullRequest sentBack = asking(gitLab(), ReviewState.REQUESTED_CHANGES);
            PullRequest reviewed = asking(gitLab(), ReviewState.REVIEWED);

            assertPending(Set.of(reRequested, started, withdrawn, reviewed), Set.of(approved, sentBack));
        }

        @Test
        void shouldCountARequestPendingWhenGitLabStatedNothingAndTheReviewerGaveNoStandingVerdict() {
            PullRequest untouched = asking(gitLab(), null);
            PullRequest commented = asking(gitLab(), null);
            review(commented, PullRequestReview.State.COMMENTED, false);
            PullRequest dismissed = asking(gitLab(), null);
            review(dismissed, PullRequestReview.State.APPROVED, true);
            PullRequest approved = asking(gitLab(), null);
            review(approved, PullRequestReview.State.APPROVED, false);
            PullRequest sentBack = asking(gitLab(), null);
            review(sentBack, PullRequestReview.State.CHANGES_REQUESTED, false);

            assertPending(Set.of(untouched, commented, dismissed), Set.of(approved, sentBack));
        }

        @Test
        void shouldCountEveryGitHubRequestPendingWhenGitHubStillListsTheReviewer() {
            PullRequest reRequested = asking(gitHub(), null);
            review(reRequested, PullRequestReview.State.APPROVED, false);

            assertPending(Set.of(reRequested), Set.of());
        }

        @Test
        void shouldLeaveOutADraftWhenItAsksForAReview() {
            PullRequest ready = asking(gitHub(), null);
            PullRequest drafted = asking(gitLab(), ReviewState.UNREVIEWED);
            drafted.setDraft(true);
            pullRequestRepository.save(drafted);

            assertPending(Set.of(ready), Set.of(drafted));
        }

        private void assertPending(Set<PullRequest> pending, Set<PullRequest> notPending) {
            Set<Long> listed = queryRepository.findPendingReviewRequestPrs(workspace.getId(), user.getId()).stream()
                    .map(PullRequest::getId)
                    .collect(Collectors.toSet());
            assertThat(listed)
                    .containsAll(pending.stream().map(PullRequest::getId).toList());
            assertThat(notPending).extracting(PullRequest::getId).noneMatch(listed::contains);
            Instant now = Instant.now();
            assertThat(queryRepository
                            .fetchUserCounts(
                                    workspace.getId(),
                                    user.getId(),
                                    now.minus(Duration.ofDays(14)),
                                    now.minus(Duration.ofDays(7)),
                                    now)
                            .pendingReviewRequests())
                    .as("the count agrees with the list")
                    .isEqualTo(listed.size());
        }

        private IdentityProvider gitLab() {
            return user.getProvider();
        }

        private IdentityProvider gitHub() {
            return gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                    .orElseGet(() -> gitProviderRepository.save(
                            new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        }

        /** An open pull request by {@link #author} that lists the user as reviewer in {@code state}. */
        private PullRequest asking(IdentityProvider provider, @Nullable ReviewState state) {
            long nativeId = nativeIds.incrementAndGet();
            PullRequest work = new PullRequest();
            work.setNativeId(nativeId);
            work.setProvider(provider);
            work.setNumber((int) (nativeId % 100_000));
            work.setTitle("Pull request " + nativeId);
            work.setState(Issue.State.OPEN);
            work.setHtmlUrl(repository.getHtmlUrl() + "/-/merge_requests/" + nativeId);
            work.setRepository(repository);
            work.setAuthor(author);
            work.setCreatedAt(Instant.now());
            PullRequest stored = pullRequestRepository.save(work);
            Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
            reviewers.put(user, state);
            stored.replaceRequestedReviewers(reviewers, null);
            return pullRequestRepository.save(stored);
        }

        private void review(PullRequest pullRequest, PullRequestReview.State state, boolean dismissed) {
            PullRequestReview review = new PullRequestReview();
            review.setNativeId(nativeIds.incrementAndGet());
            review.setProvider(pullRequest.getProvider());
            review.setState(state);
            review.setDismissed(dismissed);
            review.setPullRequest(pullRequest);
            review.setAuthor(user);
            review.setSubmittedAt(Instant.now().minus(Duration.ofDays(1)));
            review.setHtmlUrl(pullRequest.getHtmlUrl() + "#review");
            reviewRepository.save(review);
        }
    }
}
