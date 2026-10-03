package de.tum.cit.aet.hephaestus.integration.scm.domain.common;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ProxyAwareEntityEqualityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private CommitRepository commitRepository;

    private IdentityProvider provider;
    private Repository repository;

    @BeforeEach
    void setUp() {
        provider = new IdentityProvider(
                IdentityProviderType.GITHUB, "https://proxy-equality-" + UUID.randomUUID() + ".example");
        entityManager.persist(provider);
        repository = new Repository();
        repository.setNativeId(randomNativeId());
        repository.setProvider(provider);
        repository.setName("proxy-equality");
        repository.setNameWithOwner("owner/proxy-equality-" + UUID.randomUUID());
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        entityManager.persist(repository);
        entityManager.flush();
    }

    @Test
    void shouldEqualTheLoadedIssueWhenComparedWithAnUninitializedReference() {
        long id = persist(new Issue(), 1).getId();

        Issue loaded = loadDetached(Issue.class, id);
        Issue reference = entityManager.getReference(Issue.class, id);

        assertSameRow(loaded, reference);
    }

    @Test
    void shouldEqualTheLoadedPullRequestWhenReferencedAsAnIssue() {
        long id = persist(new PullRequest(), 2).getId();

        PullRequest loaded = loadDetached(PullRequest.class, id);
        Issue reference = entityManager.getReference(Issue.class, id);

        assertSameRow(loaded, reference);
    }

    @Test
    void shouldEqualTheLoadedCommitWhenComparedWithAnUninitializedReference() {
        String sha = "d".repeat(40);
        Instant now = Instant.now();
        commitRepository.upsertCommit(
                sha,
                "Proxy equality",
                null,
                "https://github.com/owner/proxy-equality/commit/" + sha,
                now,
                now,
                0,
                0,
                0,
                now,
                repository.getId(),
                null,
                null,
                "author@example.com",
                "author@example.com",
                null);
        long id = commitRepository
                .findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId();

        Commit loaded = loadDetached(Commit.class, id);
        Commit reference = entityManager.getReference(Commit.class, id);

        assertSameRow(loaded, reference);
    }

    @Test
    void shouldEqualTheLoadedIdentityProviderWhenComparedWithAnUninitializedReference() {
        long id = Objects.requireNonNull(provider.getId());

        IdentityProvider loaded = loadDetached(IdentityProvider.class, id);
        IdentityProvider reference = entityManager.getReference(IdentityProvider.class, id);

        assertSameRow(loaded, reference);
    }

    /** Loads the row in its own persistence context, so a later reference to it is a fresh proxy. */
    private <T> T loadDetached(Class<T> type, long id) {
        entityManager.flush();
        entityManager.clear();
        T loaded = entityManager.find(type, id);
        entityManager.clear();
        return loaded;
    }

    private static void assertSameRow(Object loaded, Object reference) {
        assertThat(reference).isInstanceOf(HibernateProxy.class);
        assertThat(Hibernate.isInitialized(reference)).isFalse();

        assertThat(loaded.equals(reference)).isTrue();
        assertThat(Hibernate.isInitialized(reference)).isFalse();
        assertThat(reference.equals(loaded)).isTrue();
        assertThat(reference.hashCode()).isEqualTo(loaded.hashCode());
    }

    private <T extends Issue> T persist(T issue, int number) {
        issue.setNativeId(randomNativeId());
        issue.setProvider(provider);
        issue.setRepository(repository);
        issue.setNumber(number);
        issue.setTitle("Proxy equality");
        issue.setState(Issue.State.OPEN);
        issue.setCreatedAt(Instant.now());
        issue.setUpdatedAt(Instant.now());
        entityManager.persist(issue);
        return issue;
    }

    private static long randomNativeId() {
        return UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    }
}
