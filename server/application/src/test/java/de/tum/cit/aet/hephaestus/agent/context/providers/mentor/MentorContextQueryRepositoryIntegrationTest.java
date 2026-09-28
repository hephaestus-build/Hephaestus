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
import java.util.Locale;
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

        /** One provider's side of the tests: a monitored repository, the reader asked to review and the author. */
        private record Side(Repository repository, User reader, User author) {}

        private final AtomicLong nativeIds = new AtomicLong(880_000);
        private Side gitLab;
        private Side gitHub;

        @BeforeEach
        void seedRepositories() {
            gitLab = side(user.getProvider(), "https://gitlab.com", user);
            IdentityProvider gitHubProvider = gitProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                    .orElseGet(() -> gitProviderRepository.save(
                            new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
            gitHub = side(gitHubProvider, "https://github.com", person(gitHubProvider, "https://github.com", "reader"));
        }

        @Test
        void shouldCountARequestPendingUntilGitLabSaysTheReviewerGaveAVerdict() {
            PullRequest reRequested = asking(gitLab, ReviewState.UNREVIEWED);
            review(gitLab, reRequested, PullRequestReview.State.APPROVED, false);
            PullRequest started = asking(gitLab, ReviewState.REVIEW_STARTED);
            PullRequest withdrawn = asking(gitLab, ReviewState.UNAPPROVED);
            PullRequest approved = asking(gitLab, ReviewState.APPROVED);
            PullRequest sentBack = asking(gitLab, ReviewState.REQUESTED_CHANGES);
            PullRequest reviewed = asking(gitLab, ReviewState.REVIEWED);

            assertPending(gitLab, Set.of(reRequested, started, withdrawn, reviewed), Set.of(approved, sentBack));
        }

        @Test
        void shouldCountARequestPendingWhenGitLabStatedNothingAndTheReviewerGaveNoStandingVerdict() {
            PullRequest untouched = asking(gitLab, null);
            PullRequest commented = asking(gitLab, null);
            review(gitLab, commented, PullRequestReview.State.COMMENTED, false);
            PullRequest dismissed = asking(gitLab, null);
            review(gitLab, dismissed, PullRequestReview.State.APPROVED, true);
            PullRequest approved = asking(gitLab, null);
            review(gitLab, approved, PullRequestReview.State.APPROVED, false);
            PullRequest sentBack = asking(gitLab, null);
            review(gitLab, sentBack, PullRequestReview.State.CHANGES_REQUESTED, false);

            assertPending(gitLab, Set.of(untouched, commented, dismissed), Set.of(approved, sentBack));
        }

        @Test
        void shouldCountAGitHubRequestPendingWhenTheReviewerApprovedBeforeBeingAskedAgain() {
            PullRequest reRequested = asking(gitHub, null);
            review(gitHub, reRequested, PullRequestReview.State.APPROVED, false);

            assertPending(gitHub, Set.of(reRequested), Set.of());
        }

        @Test
        void shouldLeaveOutADraftWhenItAsksForAReview() {
            PullRequest ready = asking(gitLab, ReviewState.UNREVIEWED);
            PullRequest drafted = asking(gitLab, ReviewState.UNREVIEWED);
            drafted.setDraft(true);
            pullRequestRepository.save(drafted);

            assertPending(gitLab, Set.of(ready), Set.of(drafted));
        }

        private void assertPending(Side side, Set<PullRequest> pending, Set<PullRequest> notPending) {
            Long readerId = side.reader().getId();
            Set<Long> listed = queryRepository.findPendingReviewRequestPrs(workspace.getId(), readerId).stream()
                    .map(PullRequest::getId)
                    .collect(Collectors.toSet());
            assertThat(listed)
                    .containsAll(pending.stream().map(PullRequest::getId).toList());
            assertThat(notPending).extracting(PullRequest::getId).noneMatch(listed::contains);
            Instant now = Instant.now();
            assertThat(queryRepository
                            .fetchUserCounts(
                                    workspace.getId(),
                                    readerId,
                                    now.minus(Duration.ofDays(14)),
                                    now.minus(Duration.ofDays(7)),
                                    now)
                            .pendingReviewRequests())
                    .as("the count agrees with the list")
                    .isEqualTo(listed.size());
        }

        private Side side(IdentityProvider provider, String serverUrl, User reader) {
            Repository repository = new Repository();
            repository.setNativeId(nativeIds.incrementAndGet());
            repository.setProvider(provider);
            repository.setName("widgets");
            repository.setNameWithOwner(
                    "mentor-context-org/widgets-" + provider.getType().name().toLowerCase(Locale.ROOT));
            repository.setHtmlUrl(serverUrl + "/" + repository.getNameWithOwner());
            repository.setDefaultBranch("main");
            repository = repositoryRepository.save(repository);
            RepositoryToMonitor monitor = new RepositoryToMonitor();
            monitor.setWorkspace(workspace);
            monitor.setNameWithOwner(repository.getNameWithOwner());
            repositoryToMonitorRepository.save(monitor);
            return new Side(repository, reader, person(provider, serverUrl, "author"));
        }

        private User person(IdentityProvider provider, String serverUrl, String login) {
            User person = new User();
            person.setNativeId(nativeIds.incrementAndGet());
            person.setLogin(login);
            person.setName(login);
            person.setAvatarUrl("https://example.com/" + login + ".png");
            person.setHtmlUrl(serverUrl + "/" + login);
            person.setType(User.Type.USER);
            person.setProvider(provider);
            return userRepository.save(person);
        }

        /** An open pull request by the side's author that lists its reader as reviewer in {@code state}. */
        private PullRequest asking(Side side, @Nullable ReviewState state) {
            Repository repository = side.repository();
            long nativeId = nativeIds.incrementAndGet();
            PullRequest work = new PullRequest();
            work.setNativeId(nativeId);
            work.setProvider(repository.getProvider());
            work.setNumber((int) (nativeId % 100_000));
            work.setTitle("Pull request " + nativeId);
            work.setState(Issue.State.OPEN);
            String path =
                    repository.getProvider().getType() == IdentityProviderType.GITLAB ? "/-/merge_requests/" : "/pull/";
            work.setHtmlUrl(repository.getHtmlUrl() + path + nativeId);
            work.setRepository(repository);
            work.setAuthor(side.author());
            work.setCreatedAt(Instant.now());
            PullRequest stored = pullRequestRepository.save(work);
            Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
            reviewers.put(side.reader(), state);
            stored.replaceRequestedReviewers(reviewers, Instant.now());
            return pullRequestRepository.save(stored);
        }

        private void review(Side side, PullRequest pullRequest, PullRequestReview.State state, boolean dismissed) {
            PullRequestReview review = new PullRequestReview();
            review.setNativeId(nativeIds.incrementAndGet());
            review.setProvider(pullRequest.getProvider());
            review.setState(state);
            review.setDismissed(dismissed);
            review.setPullRequest(pullRequest);
            review.setAuthor(side.reader());
            review.setSubmittedAt(Instant.now().minus(Duration.ofDays(1)));
            review.setHtmlUrl(pullRequest.getHtmlUrl() + "#review");
            reviewRepository.save(review);
        }
    }
}
