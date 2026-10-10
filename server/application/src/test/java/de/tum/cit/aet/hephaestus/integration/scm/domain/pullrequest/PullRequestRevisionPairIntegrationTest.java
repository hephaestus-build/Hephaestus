package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class PullRequestRevisionPairIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private UserRepository users;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private record Fixture(long providerId, long authorId, long repositoryId) {}

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldInvalidateThePreviousPairWhenHeadChangesWithoutBase(IdentityProviderType providerType) {
        Fixture fixture = createFixture(providerType);

        upsert(fixture, "new-head", null);

        PullRequest pullRequest = read(fixture);
        assertThat(pullRequest.getHeadRefOid()).isEqualTo("new-head");
        assertThat(pullRequest.getBaseRefOid()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldPreserveTheKnownPairWhenHeadIsUnchangedOrOmitted(IdentityProviderType providerType) {
        Fixture fixture = createFixture(providerType);

        upsert(fixture, "old-head", null);
        assertThat(read(fixture).getBaseRefOid()).isEqualTo("old-base");

        upsert(fixture, null, null);
        PullRequest pullRequest = read(fixture);
        assertThat(pullRequest.getHeadRefOid()).isEqualTo("old-head");
        assertThat(pullRequest.getBaseRefOid()).isEqualTo("old-base");
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRestoreThePairWhenTheProviderSuppliesItsBase(IdentityProviderType providerType) {
        Fixture fixture = createFixture(providerType);
        upsert(fixture, "new-head", null);
        assertThat(read(fixture).getBaseRefOid()).isNull();

        upsert(fixture, "new-head", "new-base");

        PullRequest pullRequest = read(fixture);
        assertThat(pullRequest.getHeadRefOid()).isEqualTo("new-head");
        assertThat(pullRequest.getBaseRefOid()).isEqualTo("new-base");

        upsert(fixture, "later-head", "later-base");
        PullRequest updated = read(fixture);
        assertThat(updated.getHeadRefOid()).isEqualTo("later-head");
        assertThat(updated.getBaseRefOid()).isEqualTo("later-base");
    }

    // A diff base belongs to its target too: a retargeted pull request at the same head has another range.
    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldInvalidateThePreviousPairWhenTheTargetChangesWithoutBase(IdentityProviderType providerType) {
        Fixture fixture = createFixture(providerType);

        upsert(fixture, "old-head", null, "release");

        PullRequest pullRequest = read(fixture);
        assertThat(pullRequest.getHeadRefOid()).isEqualTo("old-head");
        assertThat(pullRequest.getBaseRefName()).isEqualTo("release");
        assertThat(pullRequest.getBaseRefOid()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldPreserveThePairWhenTheTargetIsOmittedOrUnchanged(IdentityProviderType providerType) {
        Fixture fixture = createFixture(providerType);

        upsert(fixture, "old-head", null, null);
        assertThat(read(fixture).getBaseRefOid()).isEqualTo("old-base");
        upsert(fixture, "old-head", null, "main");

        PullRequest pullRequest = read(fixture);
        assertThat(pullRequest.getBaseRefName()).isEqualTo("main");
        assertThat(pullRequest.getBaseRefOid()).isEqualTo("old-base");
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepTheNewPairWhenTheTargetChangesWithItsBase(IdentityProviderType providerType) {
        Fixture fixture = createFixture(providerType);

        upsert(fixture, "old-head", "release-base", "release");

        PullRequest pullRequest = read(fixture);
        assertThat(pullRequest.getBaseRefName()).isEqualTo("release");
        assertThat(pullRequest.getBaseRefOid()).isEqualTo("release-base");
    }

    private Fixture createFixture(IdentityProviderType providerType) {
        IdentityProvider provider = providers.save(new IdentityProvider(providerType, "https://scm.example"));
        var author = users.save(TestUserFactory.createUser(1001L, "developer", provider));
        Repository repository = new Repository();
        repository.setNativeId(2001L);
        repository.setProvider(provider);
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl("https://scm.example/owner/repo");
        repository.setDefaultBranch("main");
        repository = repositories.save(repository);
        Fixture fixture = new Fixture(
                Objects.requireNonNull(provider.getId()),
                Objects.requireNonNull(author.getId()),
                Objects.requireNonNull(repository.getId()));
        upsert(fixture, "old-head", "old-base");
        return fixture;
    }

    private void upsert(Fixture fixture, @Nullable String head, @Nullable String base) {
        upsert(fixture, head, base, "main");
    }

    private void upsert(Fixture fixture, @Nullable String head, @Nullable String base, @Nullable String target) {
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
                false,
                null,
                0,
                now,
                now,
                now,
                fixture.authorId(),
                fixture.repositoryId(),
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
                target,
                head,
                base,
                null,
                null);
    }

    private PullRequest read(Fixture fixture) {
        EntityManager manager = Objects.requireNonNull(entityManager);
        manager.flush();
        manager.clear();
        return pullRequests
                .findByRepositoryIdAndNumber(fixture.repositoryId(), 42)
                .orElseThrow();
    }
}
