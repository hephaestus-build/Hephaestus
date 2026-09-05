package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The commit list the commit-message and commit-scope practices read, against a real schema.
 *
 * <p>{@code PullRequestCommitContentSourceTest} mocks the repository, so it can only prove the in-memory
 * pass against rows it was handed. This covers what only Postgres can prove: the finder walks
 * {@code commit_pull_request} rather than the commit's own repository, orders by authored time with the
 * SHA as the tiebreak, and does not leak a neighbouring pull request's commits.
 */
class PullRequestCommitContentSourceIntegrationTest extends BaseIntegrationTest {

    private static final String FILE_KEY = "inputs/context/commits.json";

    @Autowired
    private PullRequestCommitContentSource source;

    @Autowired
    private CommitRepository commitRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IdentityProvider provider;
    private Repository repository;
    private long nativeIdSeq = 8_000L;

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
    void theFinderOrdersByAuthoredTimeThenShaAndIgnoresUnlinkedCommitsOfTheSameRepository() {
        PullRequest pr = persistPullRequest(21);
        // Persisted newest first, so the order can only come from the query.
        persistCommit(pr, "b".repeat(40), "later", null, at("11:00"));
        persistCommit(pr, "f".repeat(40), "same second, higher sha", null, at("10:00"));
        persistCommit(pr, "a".repeat(40), "same second, lower sha", null, at("10:00"));
        persistCommit(null, "c".repeat(40), "on the branch, never linked", null, at("09:00"));

        List<Commit> rows = commitRepository.findByAssociatedPullRequestId(pr.getId(), PageRequest.of(0, 50));

        assertThat(rows).extracting(Commit::getSha).containsExactly("a".repeat(40), "f".repeat(40), "b".repeat(40));
    }

    @Test
    void stagesTheCommitsOldestFirstWithTheSubjectSplitFromTheBody() {
        PullRequest pr = persistPullRequest(22);
        persistCommit(pr, "b".repeat(40), "Add a timeout to the upload call", null, at("11:00"));
        persistCommit(
                pr,
                "a".repeat(40),
                "Extract the retry logic into a helper",
                "The upload and the download paths duplicated it.",
                at("10:00"));

        JsonNode staged = stage(pr);

        assertThat(staged.get("count").asInt()).isEqualTo(2);
        assertThat(staged.get("truncated").asBoolean()).isFalse();
        assertThat(staged.get("commits").get(0).get("subject").asString())
                .isEqualTo("Extract the retry logic into a helper");
        assertThat(staged.get("commits").get(0).get("body").asString())
                .isEqualTo("The upload and the download paths duplicated it.");
        assertThat(staged.get("commits").get(1).get("subject").asString())
                .isEqualTo("Add a timeout to the upload call");
        assertThat(staged.get("commits").get(1).has("body")).isFalse();
    }

    @Test
    void doesNotStageAnotherPullRequestsCommits() {
        PullRequest target = persistPullRequest(23);
        PullRequest neighbour = persistPullRequest(24);
        persistCommit(target, "a".repeat(40), "on the pull request under review", null, at("10:00"));
        persistCommit(neighbour, "b".repeat(40), "on a different pull request", null, at("10:00"));

        JsonNode staged = stage(target);

        assertThat(staged.get("count").asInt()).isEqualTo(1);
        assertThat(staged.get("commits").get(0).get("subject").asString())
                .isEqualTo("on the pull request under review");
    }

    @Test
    void shouldExcludeATombstonedPullRequestAndIncludeItAfterResurrection() {
        PullRequest pr = persistPullRequest(25);
        persistCommit(pr, "a".repeat(40), "Retained commit", null, at("10:00"));
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
        assertThat(stage(pr).path("commits").get(0).path("subject").asString()).isEqualTo("Retained commit");
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

    private void persistCommit(
            @Nullable PullRequest pullRequest, String sha, String message, @Nullable String body, Instant authoredAt) {
        Commit commit = new Commit();
        commit.setSha(sha);
        commit.setMessage(message);
        commit.setMessageBody(body);
        commit.setAuthoredAt(authoredAt);
        commit.setCommittedAt(authoredAt);
        commit.setRepository(repository);
        if (pullRequest != null) {
            commit.getAssociatedPullRequests().add(pullRequest);
        }
        commitRepository.save(commit);
    }
}
