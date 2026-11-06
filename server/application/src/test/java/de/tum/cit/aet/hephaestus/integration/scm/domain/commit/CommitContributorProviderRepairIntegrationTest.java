package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.database.CommitContributorProviderRepairChange;
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
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The repair clears a link only where the account cannot be the contributor, never where the link may be right. */
class CommitContributorProviderRepairIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CommitRepository commits;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private IdentityProvider github;
    private IdentityProvider gitlab;
    private IdentityProvider otherGitlab;

    @BeforeEach
    void providers() {
        databaseTestUtils.cleanDatabase();
        github = provider(IdentityProviderType.GITHUB, "https://github.com");
        gitlab = provider(IdentityProviderType.GITLAB, "https://gitlab.com");
        otherGitlab = provider(IdentityProviderType.GITLAB, "HTTPS://GitLab.com:443");
    }

    @Test
    void shouldClearALinkWhenTheUserIsOnAnotherKindOfProvider() throws Exception {
        long gitlabUserOnGithub = contributor(repository(github), user(gitlab), "a@example.com");
        long githubUserOnGitlab = contributor(repository(gitlab), user(github), "b@example.com");

        var change = repair();

        assertThat(linkedUser(gitlabUserOnGithub)).isNull();
        assertThat(linkedUser(githubUserOnGitlab)).isNull();
        assertThat(change.getConfirmationMessage()).startsWith("Cleared 2 ");
    }

    @Test
    void shouldKeepALinkWhenTwoProviderRowsMayBeOneInstance() throws Exception {
        long sameKind = contributor(repository(gitlab), user(otherGitlab), "a@example.com");

        var change = repair();

        assertThat(linkedUser(sameKind)).isNotNull();
        assertThat(change.getConfirmationMessage()).startsWith("Cleared 0 ");
    }

    @Test
    void shouldKeepALinkOnTheSameProviderWhateverElseLooksUnusual() throws Exception {
        User gitlabUser = user(gitlab);
        User githubUser = user(github);
        Repository gitlabRepository = repository(gitlab);
        Repository githubRepository = repository(github);

        // A user with no other attribution in any workspace that monitors the repository.
        long outsideOnGitlab = contributor(gitlabRepository, gitlabUser, "a@example.com");
        long outsideOnGithub = contributor(githubRepository, githubUser, "b@example.com");
        // The commit names no author, or another author.
        long commitAuthorNull = contributor(gitlabRepository, gitlabUser, "c@example.com");
        long commitAuthorOther = contributor(gitlabRepository, gitlabUser, "d@example.com");
        jdbc.update(
                "UPDATE git_commit SET author_id = ? WHERE id = (SELECT commit_id FROM commit_contributor WHERE id = ?)",
                user(gitlab).getId(),
                commitAuthorOther);
        // One email on two users of one provider, and on one user of each of two providers.
        long sameProviderFirst = contributor(gitlabRepository, gitlabUser, "e@example.com");
        long sameProviderSecond = contributor(gitlabRepository, user(gitlab), "e@example.com");
        long twoProvidersFirst = contributor(gitlabRepository, gitlabUser, "f@example.com");
        long twoProvidersSecond = contributor(githubRepository, githubUser, "f@example.com");

        var change = repair();

        assertThat(linkedUser(outsideOnGitlab)).isNotNull();
        assertThat(linkedUser(outsideOnGithub)).isNotNull();
        assertThat(linkedUser(commitAuthorNull)).isNotNull();
        assertThat(linkedUser(commitAuthorOther)).isNotNull();
        assertThat(linkedUser(sameProviderFirst)).isNotNull();
        assertThat(linkedUser(sameProviderSecond)).isNotNull();
        assertThat(linkedUser(twoProvidersFirst)).isNotNull();
        assertThat(linkedUser(twoProvidersSecond)).isNotNull();
        assertThat(change.getConfirmationMessage()).startsWith("Cleared 0 ");
    }

    @Test
    void shouldLeaveAnUnlinkedContributorAndTheCommitWhenClearingALink() throws Exception {
        Repository repository = repository(github);
        long wrong = contributor(repository, user(gitlab), "a@example.com");
        User author = user(github);
        jdbc.update(
                "UPDATE git_commit SET author_id = ? WHERE id = (SELECT commit_id FROM commit_contributor WHERE id = ?)",
                author.getId(),
                wrong);

        repair();

        assertThat(jdbc.queryForObject(
                        "SELECT gc.author_id FROM git_commit gc JOIN commit_contributor cc ON cc.commit_id = gc.id"
                                + " WHERE cc.id = ?",
                        Long.class,
                        wrong))
                .isEqualTo(author.getId());
        assertThat(jdbc.queryForMap("SELECT email, name, role, ordinal FROM commit_contributor WHERE id = ?", wrong))
                .containsEntry("email", "a@example.com")
                .containsEntry("name", "A")
                .containsEntry("role", "AUTHOR")
                .containsEntry("ordinal", 0);
    }

    @Test
    void shouldClearNothingWhenItRunsAgain() throws Exception {
        contributor(repository(github), user(gitlab), "a@example.com");
        repair();

        var second = repair();

        assertThat(second.getConfirmationMessage()).startsWith("Cleared 0 ");
    }

    private CommitContributorProviderRepairChange repair() throws Exception {
        var change = new CommitContributorProviderRepairChange();
        try (var connection = dataSource.getConnection()) {
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            change.execute(database);
            // Liquibase turns autocommit off, and it commits a change set itself.
            connection.commit();
        }
        return change;
    }

    private @Nullable Long linkedUser(long contributor) {
        return jdbc.queryForObject("SELECT user_id FROM commit_contributor WHERE id = ?", Long.class, contributor);
    }

    private IdentityProvider provider(IdentityProviderType type, String serverUrl) {
        return providers
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> providers.save(new IdentityProvider(type, serverUrl)));
    }

    private static long nativeId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private User user(IdentityProvider provider) {
        String login = "repair-" + UUID.randomUUID();
        var user = new User();
        user.setNativeId(nativeId());
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
        String name = "repair-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(nativeId());
        created.setName(name);
        created.setNameWithOwner("repair/" + name);
        created.setHtmlUrl("https://example.com/repair/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        return repositories.save(created);
    }

    private long contributor(Repository repository, User user, String email) {
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
                "INSERT INTO commit_contributor (commit_id, user_id, role, name, email, ordinal)"
                        + " VALUES (?, ?, 'AUTHOR', 'A', ?, 0) RETURNING id",
                Long.class,
                commitId,
                user.getId(),
                email));
    }
}
