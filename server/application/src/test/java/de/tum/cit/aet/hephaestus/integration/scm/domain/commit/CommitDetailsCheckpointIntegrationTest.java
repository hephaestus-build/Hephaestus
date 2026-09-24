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
import org.jspecify.annotations.Nullable;
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
        upsert(first, empty, "Empty commit", null);
        upsert(first, stub, "Empty commit", null);
        upsert(second, empty, "Empty commit", null);
        assertThat(commits.findGitDetailsCapturedShas(first.getId(), List.of(empty, stub)))
                .isEmpty();

        upsert(first, empty, "Empty commit", Instant.now());

        assertThat(commits.findGitDetailsCapturedShas(first.getId(), List.of(empty, stub)))
                .containsExactly(empty);
        assertThat(commits.findGitDetailsCapturedShas(second.getId(), List.of(empty)))
                .isEmpty();
    }

    @Test
    void shouldKeepWhatNativeGitCapturedWhenALesserSourceUpsertsTheSameCommit() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Repository repository = repository(provider, 71003L, "guarded");
        String sha = "c".repeat(40);
        Instant captured = Instant.parse("2024-01-15T10:00:00Z");
        Instant authored = Instant.parse("2024-01-14T09:00:00Z");
        commits.upsertCommit(
                sha,
                "Native subject",
                null,
                "https://github.com/checkpoint/guarded/commit/" + sha,
                authored,
                authored,
                3,
                1,
                2,
                captured,
                repository.getId(),
                null,
                null,
                "author@example.com",
                "author@example.com",
                captured);

        upsert(repository, sha, "Webhook subject", null);

        Commit commit =
                commits.findByShaAndRepositoryId(sha, repository.getId()).orElseThrow();
        assertThat(commit.getMessage()).isEqualTo("Native subject");
        assertThat(commit.getAuthoredAt()).isEqualTo(authored);
        assertThat(commit.getCommittedAt()).isEqualTo(authored);
        assertThat(commit.getAdditions()).isEqualTo(3);
        assertThat(commit.getGitDetailsCapturedAt()).isEqualTo(captured);
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

    private void upsert(Repository repository, String sha, String message, @Nullable Instant capturedAt) {
        Instant now = Instant.now();
        commits.upsertCommit(
                sha,
                message,
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
                "author@example.com",
                capturedAt);
    }
}
