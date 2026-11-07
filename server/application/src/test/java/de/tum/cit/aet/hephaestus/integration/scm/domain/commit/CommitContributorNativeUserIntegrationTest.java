package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A provider's user id and a local user id share a number range, so the id resolves on the commit's provider. */
class CommitContributorNativeUserIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommitRepository commits;

    @Autowired
    private CommitContributorRepository contributors;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private IdentityProvider github;
    private IdentityProvider gitlab;
    private String email;

    @BeforeEach
    void providers() {
        // A provider id here equals a local id, so a row left by an earlier test could match it.
        databaseTestUtils.cleanDatabase();
        github = provider(IdentityProviderType.GITHUB, "https://github.com");
        gitlab = provider(IdentityProviderType.GITLAB, "https://gitlab.com");
        email = UUID.randomUUID() + "@example.com";
    }

    @Test
    void shouldLinkTheUserWhenItsLocalIdDiffersFromItsProviderId() {
        long nativeId = nativeId();
        User user = user(github, nativeId);
        assertThat(user.getId()).isNotEqualTo(nativeId);
        long commit = commit(repository(github));

        contributors.upsertContributorByNativeUserId(commit, nativeId, "AUTHOR", "A", email, 0);

        assertThat(linkedUser(commit)).isEqualTo(user.getId());
    }

    @Test
    void shouldLinkNobodyWhenTheProviderIdEqualsAnotherProvidersLocalId() {
        User otherProvider = user(gitlab, nativeId());
        long commit = commit(repository(github));

        contributors.upsertContributorByNativeUserId(commit, otherProvider.getId(), "AUTHOR", "A", email, 0);

        assertThat(linkedUser(commit)).isNull();
    }

    @Test
    void shouldLinkNobodyWhenTheUserIsOnlyKnownOnAnotherProvider() {
        long nativeId = nativeId();
        user(gitlab, nativeId);
        long commit = commit(repository(github));

        contributors.upsertContributorByNativeUserId(commit, nativeId, "AUTHOR", "A", email, 0);

        assertThat(linkedUser(commit)).isNull();
    }

    @Test
    void shouldStoreTheContributorUnlinkedWhenTheActorHasNoProviderId() {
        long commit = commit(repository(github));

        contributors.upsertContributorByNativeUserId(commit, null, "AUTHOR", "A", email, 0);

        assertThat(linkedUser(commit)).isNull();
    }

    @Test
    void shouldKeepTheLinkWhenTheContributorIsStoredAgainWithNoProviderId() {
        long nativeId = nativeId();
        User user = user(github, nativeId);
        long commit = commit(repository(github));
        contributors.upsertContributorByNativeUserId(commit, nativeId, "AUTHOR", "A", email, 0);

        contributors.upsertContributorByNativeUserId(commit, null, "AUTHOR", "A", email, 0);

        assertThat(linkedUser(commit)).isEqualTo(user.getId());
    }

    private @Nullable Long linkedUser(long commit) {
        return jdbc.queryForObject(
                "SELECT user_id FROM commit_contributor WHERE commit_id = ? AND email = ?", Long.class, commit, email);
    }

    private IdentityProvider provider(IdentityProviderType type, String serverUrl) {
        return providers
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> providers.save(new IdentityProvider(type, serverUrl)));
    }

    private static long nativeId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private User user(IdentityProvider provider, long nativeId) {
        String login = "native-" + UUID.randomUUID();
        var user = new User();
        user.setNativeId(nativeId);
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://example.com/" + login + ".png");
        user.setHtmlUrl("https://example.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(provider);
        return users.save(user);
    }

    private Repository repository(IdentityProvider provider) {
        String name = "native-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(nativeId());
        created.setName(name);
        created.setNameWithOwner("native/" + name);
        created.setHtmlUrl("https://example.com/native/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        return repositories.save(created);
    }

    private long commit(Repository repository) {
        String sha = UUID.randomUUID().toString().replace("-", "") + "00000000";
        Instant now = Instant.now();
        commits.upsertCommit(
                sha,
                "Work",
                null,
                repository.getHtmlUrl() + "/commit/" + sha,
                now,
                now,
                0,
                0,
                0,
                now,
                repository.getId(),
                null,
                null,
                email,
                email,
                null,
                false);
        return Objects.requireNonNull(commits.findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId());
    }
}
