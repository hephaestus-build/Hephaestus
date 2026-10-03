package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the reversibility the GitLab deletion sweep depends on: a tombstoned issue or merge request
 * that reappears upstream is revived by the ordinary upsert. The GitLab issue and merge-request
 * processors persist through the same shared {@code IssueRepository.upsertCore} /
 * {@code PullRequestRepository.upsertCore} that {@code GitLabDeletionSweepService} relies on, and both
 * clear {@code deleted_at} — so the next ordinary sync heals a sweep that tombstoned on bad data,
 * without operator intervention.
 */
@Tag("integration")
class GitLabDeletionSweepSelfHealIntegrationTest extends BaseIntegrationTest {

    private static final long PROVIDER_NATIVE = 1L;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private IdentityProviderRepository providerRepository;

    private Repository repository;
    private long providerId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        IdentityProvider provider = providerRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> providerRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));
        Long savedProviderId = provider.getId();
        assertNotNull(savedProviderId);
        providerId = savedProviderId;

        Organization org = new Organization();
        org.setNativeId(PROVIDER_NATIVE);
        org.setLogin("acme");
        org.setName("Acme");
        org.setAvatarUrl("");
        org.setHtmlUrl("https://gitlab.com/acme");
        org.setCreatedAt(Instant.now());
        org.setUpdatedAt(Instant.now());
        org.setProvider(provider);
        org = organizationRepository.save(org);

        repository = new Repository();
        repository.setNativeId(555L);
        repository.setName("widgets");
        repository.setNameWithOwner("acme/widgets");
        repository.setHtmlUrl("https://gitlab.com/acme/widgets");
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setOrganization(org);
        repository.setProvider(provider);
        repository = repositoryRepository.save(repository);
    }

    @Test
    void tombstonedIssueIsRevivedWhenTheUpstreamUpsertSeesItAgain() {
        int number = 5;
        upsertIssue(number);
        assertThat(issueRepository.findLiveIssueNumbersByRepositoryId(repository.getId()))
                .containsExactly(number);

        int tombstoned = issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(
                repository.getId(), List.of(number), Instant.now());
        assertThat(tombstoned).isEqualTo(1);
        assertThat(issueRepository.findLiveIssueNumbersByRepositoryId(repository.getId()))
                .isEmpty();

        // Upstream hands the issue back through the ordinary upsert path.
        upsertIssue(number);

        assertThat(issueRepository.findLiveIssueNumbersByRepositoryId(repository.getId()))
                .containsExactly(number);
    }

    @Test
    void tombstonedMergeRequestIsRevivedWhenTheUpstreamUpsertSeesItAgain() {
        int number = 7;
        upsertMergeRequest(number);
        assertThat(issueRepository.findLivePullRequestNumbersByRepositoryId(repository.getId()))
                .containsExactly(number);

        int tombstoned = issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(
                repository.getId(), List.of(number), Instant.now());
        assertThat(tombstoned).isEqualTo(1);
        assertThat(issueRepository.findLivePullRequestNumbersByRepositoryId(repository.getId()))
                .isEmpty();

        upsertMergeRequest(number);

        assertThat(issueRepository.findLivePullRequestNumbersByRepositoryId(repository.getId()))
                .containsExactly(number);
    }

    /**
     * GitLab keeps issue IIDs and merge-request IIDs in <em>separate</em> per-project namespaces, both
     * starting at 1, so an issue #5 and an MR !5 routinely coexist — and both live in the single
     * {@code issue} table under the discriminator. Tombstoning a deleted issue #5 must not reach across
     * the discriminator and hide the live merge request that shares the IID: a type-blind
     * {@code UPDATE issue SET deleted_at ... WHERE repository_id = ? AND number IN (?)} tombstones both,
     * and the MR then stays hidden until the next daily upsert revives it (~24h). The tombstone query
     * must scope on {@code TYPE(i) = Issue}.
     */
    @Test
    void tombstoningACollidingIssueIidDoesNotHideTheMergeRequestWithTheSameIid() {
        int iid = 5;
        upsertIssue(iid);
        upsertMergeRequest(iid);
        assertThat(issueRepository.findLiveIssueNumbersByRepositoryId(repository.getId()))
                .containsExactly(iid);
        assertThat(issueRepository.findLivePullRequestNumbersByRepositoryId(repository.getId()))
                .containsExactly(iid);

        // Issue #5 was deleted upstream; the issue sweep computed missing=[5] and tombstones the ISSUE.
        int tombstoned = issueRepository.tombstoneIssuesByRepositoryIdAndNumbers(
                repository.getId(), List.of(iid), Instant.now());

        assertThat(tombstoned).isEqualTo(1);
        assertThat(issueRepository.findLiveIssueNumbersByRepositoryId(repository.getId()))
                .isEmpty();
        // The merge request in the other namespace must remain live.
        assertThat(issueRepository.findLivePullRequestNumbersByRepositoryId(repository.getId()))
                .containsExactly(iid);
    }

    /** The symmetric direction: sweeping a deleted MR !5 must not hide a live issue #5. */
    @Test
    void tombstoningACollidingMergeRequestIidDoesNotHideTheIssueWithTheSameIid() {
        int iid = 5;
        upsertIssue(iid);
        upsertMergeRequest(iid);

        int tombstoned = issueRepository.tombstonePullRequestsByRepositoryIdAndNumbers(
                repository.getId(), List.of(iid), Instant.now());

        assertThat(tombstoned).isEqualTo(1);
        assertThat(issueRepository.findLivePullRequestNumbersByRepositoryId(repository.getId()))
                .isEmpty();
        assertThat(issueRepository.findLiveIssueNumbersByRepositoryId(repository.getId()))
                .containsExactly(iid);
    }

    /**
     * The GitLab sub-issue / dependency sync services key rows by IID. With issue #5 and MR !5 both
     * present, the type-scoped {@code findAllIssuesByRepositoryId} must return only the issue — never
     * the colliding merge request — so a caller building an IID map cannot mistake the merge request for
     * the issue of the same number. (A polymorphic {@code findAllByRepository_Id} returns both.)
     */
    @Test
    void findAllIssuesByRepositoryIdExcludesACollidingMergeRequest() {
        int iid = 5;
        upsertIssue(iid);
        upsertMergeRequest(iid);

        var issues = issueRepository.findAllIssuesByRepositoryId(repository.getId());

        assertThat(issues).extracting(Issue::getNumber).containsExactly(iid);
        assertThat(issues).noneMatch(Issue::isPullRequest);
    }

    private void upsertIssue(int number) {
        Instant now = Instant.now();
        issueRepository.upsertCore(
                1000L + number,
                providerId,
                number,
                "Issue " + number,
                "body",
                "OPEN",
                null,
                "https://gitlab.com/acme/widgets/-/issues/" + number,
                null,
                null,
                0,
                now,
                now,
                now,
                null,
                repository.getId(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private void upsertMergeRequest(int number) {
        Instant now = Instant.now();
        pullRequestRepository.upsertCore(
                2000L + number,
                providerId,
                number,
                "MR " + number,
                "body",
                "OPEN",
                null,
                "https://gitlab.com/acme/widgets/-/merge_requests/" + number,
                null,
                null,
                0,
                now,
                now,
                now,
                null,
                repository.getId(),
                null,
                null,
                false,
                false,
                0,
                0,
                0,
                0,
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
}
