package de.tum.cit.aet.hephaestus.integration.scm.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ScmReviewTargetQueryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewTargetQuery reviewTargets;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private record Fixture(long authorId, long repositoryId, long issueId, long pullRequestId) {}

    private Fixture createFixture(IdentityProviderType providerType) {
        IdentityProvider provider =
                identityProviderRepository.save(new IdentityProvider(providerType, "https://scm.example"));
        User author = userRepository.save(TestUserFactory.createUser(1001L, "developer", provider));

        Repository repository = new Repository();
        repository.setNativeId(2001L);
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl("https://scm.example/owner/repo");
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);

        long authorId = author.getId();
        long repositoryId = repository.getId();
        Instant now = Instant.now();
        issueRepository.upsertCore(
                3001L,
                persistedId(provider),
                42,
                "Issue",
                "body",
                "OPEN",
                null,
                "https://scm.example/owner/repo/issues/42",
                false,
                null,
                0,
                now,
                now,
                now,
                author.getId(),
                repository.getId(),
                null,
                null,
                null,
                null,
                null,
                null);
        pullRequestRepository.upsertCore(
                4001L,
                persistedId(provider),
                42,
                "Pull request",
                "body",
                "OPEN",
                null,
                "https://scm.example/owner/repo/pull/42",
                false,
                null,
                0,
                now,
                now,
                now,
                author.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                2,
                1,
                1,
                null,
                null,
                null,
                "feature",
                "main",
                "head",
                "base",
                null,
                null);

        long issueId = issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), 42)
                .orElseThrow()
                .getId();
        long pullRequestId = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), 42)
                .orElseThrow()
                .getId();
        flushAndClear();
        return new Fixture(authorId, repositoryId, issueId, pullRequestId);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldReturnIssueIdentityWithoutPullRequests(IdentityProviderType providerType) {
        var fixture = createFixture(providerType);
        var issue = reviewTargets.findIssue(fixture.issueId()).orElseThrow();

        assertThat(issue.authorId()).isEqualTo(fixture.authorId());
        assertThat(issue.repositoryId()).isEqualTo(fixture.repositoryId());
        assertThat(issue.repositoryFullName()).isEqualTo("owner/repo");
        assertThat(issue.number()).isEqualTo(42);
        assertThat(issue.deleted()).isFalse();
        assertThat(reviewTargets.findIssue(fixture.pullRequestId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldReturnPullRequestIdentityWithoutIssues(IdentityProviderType providerType) {
        var fixture = createFixture(providerType);
        var pullRequest = reviewTargets.findPullRequest(fixture.pullRequestId()).orElseThrow();

        assertThat(pullRequest.authorId()).isEqualTo(fixture.authorId());
        assertThat(pullRequest.repositoryId()).isEqualTo(fixture.repositoryId());
        assertThat(pullRequest.repositoryFullName()).isEqualTo("owner/repo");
        assertThat(pullRequest.number()).isEqualTo(42);
        assertThat(pullRequest.deleted()).isFalse();
        assertThat(reviewTargets.findPullRequest(fixture.issueId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldPreserveMissingAuthorsAndDeletionForAdmissionPolicy(IdentityProviderType providerType) {
        var fixture = createFixture(providerType);
        var issue = issueRepository.findById(fixture.issueId()).orElseThrow();
        issue.setAuthor(null);
        issue.setDeletedAt(Instant.now());
        flushAndClear();

        var target = reviewTargets.findIssue(fixture.issueId()).orElseThrow();
        assertThat(target.authorId()).isNull();
        assertThat(target.deleted()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldMatchOnlyThePersistedReviewArtifactAndReviewer(IdentityProviderType providerType) {
        var fixture = createFixture(providerType);
        var pullRequest =
                pullRequestRepository.findById(fixture.pullRequestId()).orElseThrow();
        var reviewer = userRepository.save(TestUserFactory.createUser(5001L, "reviewer", pullRequest.getProvider()));
        var review = new PullRequestReview();
        review.setNativeId(6001L);
        review.setProvider(pullRequest.getProvider());
        review.setPullRequest(pullRequest);
        review.setAuthor(reviewer);
        review.setBody("Reviewed");
        review.setState(PullRequestReview.State.COMMENTED);
        review.setHtmlUrl("https://scm.example/owner/repo/pull/42#review-6001");
        review.setSubmittedAt(Instant.now());
        long reviewId = reviewRepository.save(review).getId();
        long reviewerId = reviewer.getId();
        flushAndClear();

        assertThat(reviewTargets.reviewMatchesTarget(reviewId, fixture.pullRequestId(), reviewerId))
                .isTrue();
        assertThat(reviewTargets.reviewMatchesTarget(reviewId, fixture.issueId(), reviewerId))
                .isFalse();
        assertThat(reviewTargets.reviewMatchesTarget(reviewId, fixture.pullRequestId(), fixture.authorId()))
                .isFalse();

        var persisted = reviewRepository.findById(reviewId).orElseThrow();
        persisted.setAuthor(null);
        flushAndClear();
        assertThat(reviewTargets.reviewMatchesTarget(reviewId, fixture.pullRequestId(), reviewerId))
                .isFalse();

        persisted = reviewRepository.findById(reviewId).orElseThrow();
        persisted.setAuthor(userRepository.getReferenceById(reviewerId));
        persisted.setPullRequest(null);
        flushAndClear();
        assertThat(reviewTargets.reviewMatchesTarget(reviewId, fixture.pullRequestId(), reviewerId))
                .isFalse();

        reviewRepository.deleteById(reviewId);
        flushAndClear();
        assertThat(reviewTargets.reviewMatchesTarget(reviewId, fixture.pullRequestId(), reviewerId))
                .isFalse();
    }

    private void flushAndClear() {
        assertNotNull(entityManager);
        entityManager.flush();
        entityManager.clear();
    }

    private static long persistedId(IdentityProvider provider) {
        Long id = provider.getId();
        assertNotNull(id);
        return id;
    }
}
