package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.tenancy.TenancyBypass;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Commit sync and author resolution write these shared mirror rows with no bypass open. No request fails when one of
 * them is refused: the only signal is the tenancy violation metric and its log line.
 */
class CommitMirrorWritesIntegrationTest extends BaseIntegrationTest {

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

    private Repository repository;
    private String email;

    @BeforeEach
    void repository() {
        IdentityProvider provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        String name = "commits-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        created.setName(name);
        created.setNameWithOwner("commits/" + name);
        created.setHtmlUrl("https://github.com/commits/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        repository = repositories.save(created);
        email = name + "@example.com";
        assertThat(TenancyBypass.isActive()).isFalse();
    }

    @Test
    void shouldStoreTheAuthorWhenTheEmailResolvesWithNoBypassOpen() {
        long commitId = commit(sha());
        long userId = user();

        assertThat(commits.bulkUpdateAuthorIdByEmail(email, repository.getId(), userId))
                .isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT author_id FROM git_commit WHERE id = ?", Long.class, commitId))
                .isEqualTo(userId);
    }

    @Test
    void shouldStoreTheCommitterWhenTheEmailResolvesWithNoBypassOpen() {
        long commitId = commit(sha());
        long userId = user();

        assertThat(commits.bulkUpdateCommitterIdByEmail(email, repository.getId(), userId))
                .isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT committer_id FROM git_commit WHERE id = ?", Long.class, commitId))
                .isEqualTo(userId);
    }

    @Test
    void shouldStoreParentMetadataWhenNoBypassIsOpen() {
        String sha = sha();
        long commitId = commit(sha);

        assertThat(commits.updateParentMetadataBySha(repository.getId(), sha, 2, "a,b"))
                .isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT parent_count FROM git_commit WHERE id = ?", Integer.class, commitId))
                .isEqualTo(2);
    }

    @Test
    void shouldLinkContributorsWhenTheirEmailResolvesWithNoBypassOpen() {
        long commitId = commit(sha());
        long userId = user();
        jdbc.update(
                "INSERT INTO commit_contributor (commit_id, role, name, email, ordinal) VALUES (?, 'AUTHOR', 'A', ?, 0)",
                commitId,
                email);

        assertThat(contributors.backfillUserIdByEmail(email, repository.getId(), userId))
                .isEqualTo(1);

        assertThat(jdbc.queryForObject(
                        "SELECT user_id FROM commit_contributor WHERE commit_id = ?", Long.class, commitId))
                .isEqualTo(userId);
    }

    @Test
    void shouldRefreshTheMirrorRowFromItsSnapshotWhenNoBypassIsOpen() {
        String renamed = repository.getNameWithOwner() + "-renamed";

        boolean recorded = repositories.upsertFromSnapshot(
                repository.getNativeId(),
                Objects.requireNonNull(repository.getProvider().getId()),
                renamed,
                repository.getName() + "-renamed",
                true,
                null);

        assertThat(recorded).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT name_with_owner FROM repository WHERE id = ?", String.class, repository.getId()))
                .isEqualTo(renamed);
    }

    private long user() {
        String login = "commits-" + UUID.randomUUID();
        var user = new User();
        user.setNativeId(ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE));
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://github.com/" + login + ".png");
        user.setHtmlUrl("https://github.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(repository.getProvider());
        return Objects.requireNonNull(users.save(user).getId());
    }

    private static String sha() {
        return UUID.randomUUID().toString().replace("-", "") + "00000000";
    }

    private long commit(String sha) {
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
