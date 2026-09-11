package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class CommitDetailsCheckpointIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private CommitRepository commits;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Test
    void shouldDistinguishCapturedEmptyCommitsFromUnenrichedStubsWithinEachRepository() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Repository first = repository(provider, 71001L, "first");
        Repository second = repository(provider, 71002L, "second");
        String empty = "a".repeat(40);
        String stub = "b".repeat(40);
        upsert(first, empty);
        upsert(first, stub);
        upsert(second, empty);
        assertThat(commits.findGitDetailsCapturedShas(first.getId(), List.of(empty, stub)))
                .isEmpty();

        commits.markGitDetailsCaptured(first.getId(), empty, Instant.now());

        assertThat(commits.findGitDetailsCapturedShas(first.getId(), List.of(empty, stub)))
                .containsExactly(empty);
        assertThat(commits.findGitDetailsCapturedShas(second.getId(), List.of(empty)))
                .isEmpty();
        assertThat(commits.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull(empty, first.getId()))
                .isTrue();
        assertThat(commits.existsByShaAndRepositoryIdAndGitDetailsCapturedAtIsNotNull(stub, first.getId()))
                .isFalse();
    }

    private Repository repository(IdentityProvider provider, long nativeId, String name) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setName(name);
        repository.setNameWithOwner("checkpoint/" + name);
        repository.setHtmlUrl("https://github.com/checkpoint/" + name);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setProvider(provider);
        return repositories.save(repository);
    }

    private void upsert(Repository repository, String sha) {
        Instant now = Instant.now();
        commits.upsertCommit(
                sha,
                "Empty commit",
                null,
                "https://github.com/checkpoint/commit/" + sha,
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
                "author@example.com");
    }
}
