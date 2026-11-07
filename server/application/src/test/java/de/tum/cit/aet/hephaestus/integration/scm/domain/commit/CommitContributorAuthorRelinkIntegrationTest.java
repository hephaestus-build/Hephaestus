package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.database.CommitContributorAuthorRelinkChange;
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

/** The relink links a first author only where the row itself proves who it is. */
class CommitContributorAuthorRelinkIntegrationTest extends BaseIntegrationTest {

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

    @BeforeEach
    void providers() {
        databaseTestUtils.cleanDatabase();
        github = provider(IdentityProviderType.GITHUB, "https://github.com");
        gitlab = provider(IdentityProviderType.GITLAB, "https://gitlab.com");
    }

    @Test
    void shouldLinkTheFirstAuthorWhenTheEmailIsStoredOnOneGitHubUser() throws Exception {
        User author = user(github, "octocat", "Mona Lisa", "WORK@example.com");
        long contributor = contributor(repository(github), author, "work@example.com", "AUTHOR", 0);

        var change = relink();

        assertThat(linkedUser(contributor)).isEqualTo(author.getId());
        assertThat(change.getConfirmationMessage()).startsWith("Linked 1 ");
    }

    @Test
    void shouldLinkTheFirstAuthorWhenANoreplyAddressHoldsTheAccountId() throws Exception {
        User author = user(github, "octocat", "Mona Lisa", null);
        Repository repository = repository(github);
        long current = contributor(
                repository, author, author.getNativeId() + "+OctoCat@USERS.noreply.GitHub.com", "AUTHOR", 0);
        long renamed =
                contributor(repository, author, author.getNativeId() + "+old@users.noreply.github.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(current)).isEqualTo(author.getId());
        assertThat(linkedUser(renamed)).isEqualTo(author.getId());
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenOnlyTheCommitsAuthorPointsAtTheUser() throws Exception {
        long contributor =
                contributor(repository(github), user(github, "bob", "Bob", null), "x@example.com", "AUTHOR", 0);

        var change = relink();

        assertThat(linkedUser(contributor)).isNull();
        assertThat(change.getConfirmationMessage()).startsWith("Linked 0 ");
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheNoreplyAddressHoldsAnotherAccountsId() throws Exception {
        User reusedLogin = user(github, "oldlogin", "New Owner", null);
        long contributor =
                contributor(repository(github), reusedLogin, "1+oldlogin@users.noreply.github.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheNoreplyAddressHoldsNoAccountId() throws Exception {
        User author = user(github, "octocat", "Mona Lisa", null);
        long contributor = contributor(repository(github), author, "octocat@users.noreply.github.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheAddressOnlyStartsLikeANoreplyAddress() throws Exception {
        User author = user(github, "octocat", "Mona Lisa", null);
        long contributor = contributor(
                repository(github),
                author,
                author.getNativeId() + "+octocat@users.noreply.github.com.example.com",
                "AUTHOR",
                0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTwoUsersOfTheProviderHaveTheEmail() throws Exception {
        user(github, "first", "First", "shared@example.com");
        User author = user(github, "second", "Second", "shared@example.com");
        long contributor = contributor(repository(github), author, "shared@example.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTwoUsersHaveTheEmailInDifferentCase() throws Exception {
        user(github, "first", "First", "Shared@example.com");
        User author = user(github, "second", "Second", "shared@EXAMPLE.com");
        long contributor = contributor(repository(github), author, "shared@example.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldLinkTheFirstAuthorWhenAUserOfAnotherProviderHasTheEmail() throws Exception {
        user(gitlab, "first", "First", "shared@example.com");
        User author = user(github, "second", "Second", "shared@example.com");
        long contributor = contributor(repository(github), author, "shared@example.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isEqualTo(author.getId());
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheEmailIsEmpty() throws Exception {
        User author = user(github, "a", "A", "");
        long contributor = contributor(repository(github), author, "", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheRepositoryIsOnGitLab() throws Exception {
        Repository repository = repository(gitlab);
        long stored = contributor(repository, user(gitlab, "a", "A", "a1@example.com"), "a1@example.com", "AUTHOR", 0);
        User third = user(gitlab, "third", "T", null);
        long noreply =
                contributor(repository, third, third.getNativeId() + "+third@users.noreply.github.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(stored)).isNull();
        assertThat(linkedUser(noreply)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenItIsNotTheCommitsFirstAuthor() throws Exception {
        Repository repository = repository(github);
        long coAuthor =
                contributor(repository, user(github, "a", "A", "a1@example.com"), "a1@example.com", "AUTHOR", 1);
        long committer =
                contributor(repository, user(github, "b", "B", "b1@example.com"), "b1@example.com", "COMMITTER", 0);

        relink();

        assertThat(linkedUser(coAuthor)).isNull();
        assertThat(linkedUser(committer)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheEmailDiffersFromTheCommitsAuthor() throws Exception {
        User author = user(github, "a", "A", "a1@example.com");
        long contributor = contributor(repository(github), author, "a1@example.com", "AUTHOR", 0);
        jdbc.update(
                "UPDATE git_commit SET author_email = 'someone.else@example.com'"
                        + " WHERE id = (SELECT commit_id FROM commit_contributor WHERE id = ?)",
                contributor);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepTheRowUnlinkedWhenTheCommitsAuthorIsOnAnotherProvider() throws Exception {
        User author = user(gitlab, "a", "A", "a1@example.com");
        long contributor = contributor(repository(github), author, "a1@example.com", "AUTHOR", 0);

        relink();

        assertThat(linkedUser(contributor)).isNull();
    }

    @Test
    void shouldKeepAnExistingLinkAndLinkNothingWhenItRunsAgain() throws Exception {
        User author = user(github, "a", "A", "a1@example.com");
        User other = user(github, "b", "B", null);
        long linked = contributor(repository(github), author, "a1@example.com", "AUTHOR", 0);
        jdbc.update("UPDATE commit_contributor SET user_id = ? WHERE id = ?", other.getId(), linked);

        var change = relink();

        assertThat(linkedUser(linked)).isEqualTo(other.getId());
        assertThat(change.getConfirmationMessage()).startsWith("Linked 0 ");
    }

    private CommitContributorAuthorRelinkChange relink() throws Exception {
        var change = new CommitContributorAuthorRelinkChange();
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

    private User user(IdentityProvider provider, String login, String name, @Nullable String email) {
        var user = new User();
        user.setNativeId(nativeId());
        user.setLogin(login);
        user.setName(name);
        if (email != null) user.setEmail(email);
        user.setAvatarUrl("https://example.com/" + login + ".png");
        user.setHtmlUrl("https://example.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(provider);
        return users.save(user);
    }

    private Repository repository(IdentityProvider provider) {
        String name = "relink-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(nativeId());
        created.setName(name);
        created.setNameWithOwner("relink/" + name);
        created.setHtmlUrl("https://example.com/relink/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        return repositories.save(created);
    }

    private long contributor(Repository repository, User author, String email, String role, int ordinal) {
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
                author.getId(),
                null,
                email,
                email,
                null);
        long commitId = Objects.requireNonNull(commits.findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId());
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO commit_contributor (commit_id, role, name, email, ordinal)"
                        + " VALUES (?, ?, 'A', ?, ?) RETURNING id",
                Long.class,
                commitId,
                role,
                email,
                ordinal));
    }
}
