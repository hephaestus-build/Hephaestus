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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A commit's email is unverified, so linking it to a user stays inside the repository being synced. */
class CommitContributorLinkIntegrationTest extends BaseIntegrationTest {

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

    private IdentityProvider provider;
    private String email;

    @BeforeEach
    void provider() {
        provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));
        email = UUID.randomUUID() + "@example.com";
    }

    @Test
    void shouldLinkOnlyTheSyncedRepositoryWhenAnotherRepositoryHasTheSameEmail() {
        Repository synced = repository();
        Repository other = repository();
        long syncedContributor = contributor(synced);
        long otherContributor = contributor(other);
        long userId = Objects.requireNonNull(user().getId());

        int linked = contributors.backfillUserIdByEmail(email.toUpperCase(Locale.ROOT), synced.getId(), userId);

        assertThat(linked).isEqualTo(1);
        assertThat(linkedUser(syncedContributor)).isEqualTo(userId);
        assertThat(linkedUser(otherContributor)).isNull();
    }

    @Test
    void shouldKeepAnExistingLinkWhenTheRepositoryIsSyncedAgain() {
        Repository synced = repository();
        long contributor = contributor(synced);
        long first = Objects.requireNonNull(user().getId());
        long second = Objects.requireNonNull(user().getId());
        contributors.backfillUserIdByEmail(email, synced.getId(), first);

        int linked = contributors.backfillUserIdByEmail(email, synced.getId(), second);

        assertThat(linked).isZero();
        assertThat(linkedUser(contributor)).isEqualTo(first);
    }

    private @Nullable Long linkedUser(long contributor) {
        return jdbc.queryForObject("SELECT user_id FROM commit_contributor WHERE id = ?", Long.class, contributor);
    }

    private User user() {
        String login = "linked-" + UUID.randomUUID();
        var user = new User();
        user.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://gitlab.com/" + login + ".png");
        user.setHtmlUrl("https://gitlab.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(provider);
        return users.save(user);
    }

    private Repository repository() {
        String name = "link-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        created.setName(name);
        created.setNameWithOwner("link/" + name);
        created.setHtmlUrl("https://gitlab.com/link/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        return repositories.save(created);
    }

    private long contributor(Repository repository) {
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
                null);
        long commitId = Objects.requireNonNull(commits.findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId());
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO commit_contributor (commit_id, role, name, email, ordinal)"
                        + " VALUES (?, 'AUTHOR', 'A', ?, 0) RETURNING id",
                Long.class,
                commitId,
                email));
    }
}
