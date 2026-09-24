package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The general discussion the reviewer-craft practices read, against a real schema.
 *
 * <p>{@code GeneralReviewCommentContentSourceTest} mocks the repository, so it can only prove the
 * in-memory pass against rows it was handed. This covers what only Postgres can prove: the query itself
 * excludes Hephaestus-authored comments, the {@code LEFT JOIN FETCH} initialises the lazy author before
 * the session closes, and the {@code issue.id} filter does not leak a neighbouring artifact's thread.
 */
class GeneralReviewCommentContentSourceIntegrationTest extends BaseIntegrationTest {

    private static final String FILE_KEY = "inputs/context/general_comments.json";

    @Autowired
    private GeneralReviewCommentContentSource source;

    @Autowired
    private IssueCommentRepository issueCommentRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IdentityProvider provider;
    private Repository repository;
    private long nativeIdSeq = 7_000L;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));

        repository = new Repository();
        repository.setNativeId(nextNativeId());
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("acme/repo");
        repository.setHtmlUrl("https://github.com/acme/repo");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository = repositoryRepository.save(repository);
    }

    @Test
    void theQueryDropsHephaestusOwnCommentsAndEmptyBodies() {
        PullRequest pr = persistPullRequest(11);
        User human = persistUser("reviewer-a");
        persistComment(pr, human, "<!-- hephaestus:practice-review:abc --> 2 gaps to fix", at("09:00"));
        persistComment(pr, human, "   ", at("09:30"));
        persistComment(pr, human, "split persistence out so each unit is testable", at("10:00"));

        List<IssueComment> rows = issueCommentRepository.findRecentHumanByIssueIdWithAuthor(
                pr.getId(), GeneralReviewCommentContentSource.HEPHAESTUS_MARKER, PageRequest.of(0, 50));

        assertThat(rows)
                .extracting(IssueComment::getBody)
                .containsExactly("split persistence out so each unit is testable");
    }

    @Test
    void stagesTheDiscussionOldestFirstWithEachAuthorReadableAfterTheSessionCloses() {
        PullRequest pr = persistPullRequest(12);
        User reviewer = persistUser("reviewer-a");
        User author = persistUser("contributor-b");
        // Newest persisted first, so the ordering in the output can only come from the query and the sort.
        persistComment(pr, author, "addressed the feedback", at("11:00"));
        persistComment(pr, reviewer, "this branch is always taken", at("10:00"));

        JsonNode staged = stage(pr);

        assertThat(staged.get("comments")).hasSize(2);
        assertThat(staged.get("comments").get(0).get("body").asString()).isEqualTo("this branch is always taken");
        assertThat(staged.get("comments").get(0).get("author").asString()).isEqualTo("reviewer-a");
        assertThat(staged.get("comments").get(1).get("author").asString()).isEqualTo("contributor-b");
    }

    @Test
    void doesNotStageAnotherPullRequestsDiscussion() {
        PullRequest target = persistPullRequest(13);
        PullRequest neighbour = persistPullRequest(14);
        User human = persistUser("reviewer-a");
        persistComment(target, human, "on the pull request under review", at("10:00"));
        persistComment(neighbour, human, "on a different pull request", at("10:00"));

        JsonNode staged = stage(target);

        assertThat(staged.get("comments")).hasSize(1);
        assertThat(staged.get("comments").get(0).get("body").asString()).isEqualTo("on the pull request under review");
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldExcludeTombstonedDiscussionAndIncludeItAfterResurrection(IdentityProviderType type) {
        String serverUrl = type == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.com";
        provider = gitProviderRepository
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> gitProviderRepository.save(new IdentityProvider(type, serverUrl)));
        repository.setProvider(provider);
        repositoryRepository.saveAndFlush(repository);
        PullRequest pr = persistPullRequest(15);
        persistComment(pr, persistUser("reviewer"), "Retained discussion", at("10:00"));
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", pr.getId());
        AgentJob job = new AgentJob();
        job.setMetadata(metadata);
        var request = new ContextRequest.PracticeReviewRequest(job);
        pr.setDeletedAt(Instant.now());
        pullRequestRepository.saveAndFlush(pr);

        var captured = source.capture(request, source.sourceKinds());
        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides().values())
                .containsExactly(new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        pr.setDeletedAt(null);
        pullRequestRepository.saveAndFlush(pr);
        assertThat(stage(pr).path("comments").get(0).path("body").asString()).isEqualTo("Retained discussion");
    }

    private JsonNode stage(PullRequest pullRequest) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", pullRequest.getId());
        AgentJob job = new AgentJob();
        job.setMetadata(metadata);
        Map<String, byte[]> files = new HashMap<>();

        source.contribute(new ContextRequest.PracticeReviewRequest(job), files);

        assertThat(files).containsKey(FILE_KEY);
        return objectMapper.readTree(files.get(FILE_KEY));
    }

    private static Instant at(String hourMinute) {
        return Instant.parse("2026-06-01T" + hourMinute + ":00Z");
    }

    private long nextNativeId() {
        return nativeIdSeq++;
    }

    private PullRequest persistPullRequest(int number) {
        PullRequest pr = new PullRequest();
        pr.setNativeId(nextNativeId());
        pr.setProvider(provider);
        pr.setNumber(number);
        pr.setTitle("PR #" + number);
        pr.setState(PullRequest.State.OPEN);
        pr.setHtmlUrl("https://github.com/acme/repo/pull/" + number);
        pr.setRepository(repository);
        pr.setCreatedAt(Instant.now());
        pr.setUpdatedAt(Instant.now());
        return pullRequestRepository.save(pr);
    }

    private User persistUser(String login) {
        User user = new User();
        user.setNativeId(nextNativeId());
        user.setProvider(provider);
        user.setLogin(login);
        user.setAvatarUrl("https://github.com/" + login + ".png");
        user.setHtmlUrl("https://github.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private void persistComment(PullRequest pullRequest, User author, String body, Instant createdAt) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nextNativeId());
        comment.setProvider(provider);
        comment.setBody(body);
        comment.setHtmlUrl(
                "https://github.com/acme/repo/pull/" + pullRequest.getNumber() + "#c" + comment.getNativeId());
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setCreatedAt(createdAt);
        comment.setIssue(pullRequest);
        comment.setAuthor(author);
        issueCommentRepository.save(comment);
    }
}
