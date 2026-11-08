package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ScmSparseCountersIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    private record Fixture(long providerId, long repositoryId) {}

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldPreserveKnownCountersAndLockWhenPartialUpdatesOmitThem(IdentityProviderType providerType) {
        Fixture fixture = fixture(providerType);
        upsertPullRequest(fixture, true, 7, 3, 20, 9, 4);
        upsertIssue(fixture, true, 7);

        upsertPullRequest(fixture, null, null, null, null, null, null);
        upsertIssue(fixture, null, null);

        PullRequest pullRequest = readPullRequest(fixture);
        Issue issue = readIssue(fixture);
        assertThat(tuple(
                        pullRequest.getCommits(),
                        pullRequest.getAdditions(),
                        pullRequest.getDeletions(),
                        pullRequest.getChangedFiles(),
                        pullRequest.getCommentsCount(),
                        pullRequest.isLocked(),
                        issue.getCommentsCount(),
                        issue.isLocked()))
                .isEqualTo(tuple(3, 20, 9, 4, 7, true, 7, true));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldReplaceKnownCountersAndLockWhenProviderExplicitlyReportsZeroAndFalse(IdentityProviderType providerType) {
        Fixture fixture = fixture(providerType);
        upsertPullRequest(fixture, true, 7, 3, 20, 9, 4);
        upsertIssue(fixture, true, 7);

        upsertPullRequest(fixture, false, 0, 0, 0, 0, 0);
        upsertIssue(fixture, false, 0);

        assertDefaultPullRequestCountersAndLocks(fixture);
        assertThat(readIssue(fixture).getCommentsCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldPreserveOnlyTheOmittedFieldsWhenPartialUpdatesReportOtherValues(IdentityProviderType providerType) {
        Fixture fixture = fixture(providerType);
        upsertPullRequest(fixture, true, 7, 3, 20, 9, 4);
        upsertIssue(fixture, true, 7);

        upsertPullRequest(fixture, false, null, 0, null, 0, null);
        upsertIssue(fixture, null, 0);

        PullRequest pullRequest = readPullRequest(fixture);
        assertThat(pullRequest.getCommits()).isZero();
        assertThat(pullRequest.getAdditions()).isEqualTo(20);
        assertThat(pullRequest.getDeletions()).isZero();
        assertThat(pullRequest.getChangedFiles()).isEqualTo(4);
        assertThat(pullRequest.getCommentsCount()).isEqualTo(7);
        assertThat(pullRequest.isLocked()).isFalse();
        Issue issue = readIssue(fixture);
        assertThat(issue.getCommentsCount()).isZero();
        assertThat(issue.isLocked()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepAnOmittedIssueCountUnknownAndDefaultOtherCountersOnInsert(IdentityProviderType providerType) {
        Fixture fixture = fixture(providerType);

        upsertPullRequest(fixture, null, null, null, null, null, null);
        upsertIssue(fixture, null, null);

        assertDefaultPullRequestCountersAndLocks(fixture);
        assertThat(readIssue(fixture).getCommentsCount()).isNull();
    }

    private void assertDefaultPullRequestCountersAndLocks(Fixture fixture) {
        PullRequest pullRequest = readPullRequest(fixture);
        assertThat(pullRequest.getCommits()).isZero();
        assertThat(pullRequest.getAdditions()).isZero();
        assertThat(pullRequest.getDeletions()).isZero();
        assertThat(pullRequest.getChangedFiles()).isZero();
        assertThat(pullRequest.getCommentsCount()).isZero();
        assertThat(pullRequest.isLocked()).isFalse();
        assertThat(readIssue(fixture).isLocked()).isFalse();
    }

    private Fixture fixture(IdentityProviderType providerType) {
        IdentityProvider provider = providers.save(
                new IdentityProvider(providerType, "https://scm-" + UUID.randomUUID() + ".example.test"));
        Repository repository = new Repository();
        repository.setNativeId(2001L);
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl("https://scm.example/owner/repo");
        repository.setDefaultBranch("main");
        repository = repositories.save(repository);
        return new Fixture(Objects.requireNonNull(provider.getId()), Objects.requireNonNull(repository.getId()));
    }

    private void upsertPullRequest(
            Fixture fixture,
            @Nullable Boolean locked,
            @Nullable Integer comments,
            @Nullable Integer commits,
            @Nullable Integer additions,
            @Nullable Integer deletions,
            @Nullable Integer changedFiles) {
        Instant now = Instant.now();
        pullRequests.upsertCore(
                4001L,
                fixture.providerId(),
                42,
                "Pull request",
                "body",
                "OPEN",
                null,
                "https://scm.example/owner/repo/pull/42",
                locked,
                null,
                comments,
                now,
                now,
                now,
                null,
                fixture.repositoryId(),
                null,
                null,
                false,
                false,
                commits,
                additions,
                deletions,
                changedFiles,
                null,
                null,
                null,
                "feature",
                "main",
                null,
                null,
                null,
                null);
    }

    private void upsertIssue(Fixture fixture, @Nullable Boolean locked, @Nullable Integer comments) {
        Instant now = Instant.now();
        issues.upsertCore(
                4002L,
                fixture.providerId(),
                43,
                "Issue",
                "body",
                "OPEN",
                null,
                "https://scm.example/owner/repo/issues/43",
                locked,
                null,
                comments,
                now,
                now,
                now,
                null,
                fixture.repositoryId(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private PullRequest readPullRequest(Fixture fixture) {
        return pullRequests
                .findByRepositoryIdAndNumber(fixture.repositoryId(), 42)
                .orElseThrow();
    }

    private Issue readIssue(Fixture fixture) {
        return issues.findByRepositoryIdAndNumber(fixture.repositoryId(), 43).orElseThrow();
    }
}
