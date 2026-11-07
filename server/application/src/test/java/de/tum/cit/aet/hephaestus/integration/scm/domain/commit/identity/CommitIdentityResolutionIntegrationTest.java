package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.database.CommitIdentityResolutionSeedChange;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CommitIdentityResolutionIntegrationTest extends BaseIntegrationTest {

    private static final String EMAIL = "someone@example.com";

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CommitIdentityResolutionRepository resolutions;

    @Autowired
    private CommitIdentityChangeRepository changes;

    @Autowired
    private CommitIdentityResolutionService service;

    private final RepositoryScopeProvider scopes = mock(RepositoryScopeProvider.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private IdentityProvider github;
    private Function<Question, @Nullable Answer> provider = question -> null;
    private @Nullable CommitIdentityResolutionJob job;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        github = provider(IdentityProviderType.GITHUB, "https://github.com");
        when(scopes.getActiveScopesForRepository(anyLong(), anyLong())).thenReturn(List.of(1L));
    }

    @Test
    void shouldOverwriteAWrongLinkOnEveryRowOfTheEmailAndRecordEachChange() throws Exception {
        User wrong = user("wrong");
        User right = user("right");
        Repository repository = repository();
        long commit = commit(repository, wrong, wrong, EMAIL);
        long contributor = contributor(commit, wrong, EMAIL);
        seed();
        provider = question -> new Answer.Account(id(right), right.getNativeId());

        run(50);

        assertThat(authorOf(commit)).isEqualTo(right.getId());
        assertThat(committerOf(commit)).isEqualTo(right.getId());
        assertThat(contributorUser(contributor)).isEqualTo(right.getId());
        assertThat(states()).containsOnly("RESOLVED");
        assertThat(jdbc.queryForList(
                        "SELECT target FROM commit_identity_change WHERE old_user_id = ? AND new_user_id = ?"
                                + " AND outcome = 'resolved'",
                        String.class,
                        wrong.getId(),
                        right.getId()))
                .containsExactlyInAnyOrder(
                        "git_commit.author_id", "git_commit.committer_id", "commit_contributor.user_id");
    }

    @Test
    void shouldLinkAnUnlinkedRowAndRecordNoChangeForARowThatIsAlreadyRight() throws Exception {
        User right = user("right");
        Repository repository = repository();
        long linked = commit(repository, right, null, EMAIL);
        long unlinked = commit(repository, null, null, EMAIL);
        seed();
        provider = question -> new Answer.Account(id(right), right.getNativeId());

        run(50);

        assertThat(authorOf(linked)).isEqualTo(right.getId());
        assertThat(authorOf(unlinked)).isEqualTo(right.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM commit_identity_change WHERE target = 'git_commit.author_id'",
                        Long.class))
                .isEqualTo(1L);
    }

    @Test
    void shouldClearTheLinkWhenTheProviderSaysThatNoAccountOwnsTheEmail() throws Exception {
        User wrong = user("wrong");
        long commit = commit(repository(), wrong, null, EMAIL);
        seed();
        provider = question -> new Answer.NoAccount();

        run(50);

        assertThat(authorOf(commit)).isNull();
        assertThat(states()).containsOnly("NO_ACCOUNT");
        assertThat(jdbc.queryForObject(
                        "SELECT new_user_id FROM commit_identity_change WHERE old_user_id = ? AND outcome = 'no_account'",
                        Long.class,
                        wrong.getId()))
                .isNull();
    }

    @Test
    void shouldChangeOnlyTheRowsOfTheAnsweredEmail() throws Exception {
        User wrong = user("wrong");
        User right = user("right");
        Repository repository = repository();
        long asked = commit(repository, wrong, wrong, EMAIL);
        long other = commit(repository, wrong, wrong, "other@example.com");
        long otherContributor = contributor(asked, wrong, "other@example.com");
        seed();
        provider =
                question -> question.email().equals(EMAIL) ? new Answer.Account(id(right), right.getNativeId()) : null;

        run(50);

        assertThat(authorOf(asked)).isEqualTo(right.getId());
        assertThat(authorOf(other)).isEqualTo(wrong.getId());
        assertThat(committerOf(other)).isEqualTo(wrong.getId());
        assertThat(contributorUser(otherContributor)).isEqualTo(wrong.getId());
    }

    @Test
    void shouldChangeNoOtherRepository() throws Exception {
        User wrong = user("wrong");
        User right = user("right");
        Repository asked = repository();
        Repository other = repository();
        long commit = commit(asked, wrong, null, EMAIL);
        long untouched = commit(other, wrong, null, EMAIL);
        seed();
        jdbc.update("DELETE FROM commit_identity_resolution WHERE repository_id = ?", other.getId());
        provider = question -> new Answer.Account(id(right), right.getNativeId());

        run(50);

        assertThat(authorOf(commit)).isEqualTo(right.getId());
        assertThat(authorOf(untouched)).isEqualTo(wrong.getId());
    }

    @Test
    void shouldNotLinkAnAccountThatWasErased() throws Exception {
        User wrong = user("wrong");
        User erased = user("erased");
        long commit = commit(repository(), wrong, null, EMAIL);
        jdbc.update(
                "INSERT INTO identity_restriction (id, provider_id, provider_user_key, team_key, kind)"
                        + " VALUES (?, ?, ?, '', 'ERASED')",
                UUID.randomUUID(),
                github.getId(),
                Long.toString(erased.getNativeId()));
        seed();
        provider = question -> new Answer.Account(id(erased), erased.getNativeId());

        run(50);

        assertThat(authorOf(commit)).isEqualTo(wrong.getId());
        assertThat(states()).containsOnly("RESOLVED");
        assertThat(changes.count()).isZero();
        assertThat(meters.counter("commit.identity.resolutions", "outcome", "erased")
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldChangeNothingAndSpendNoAttemptWhenTheRateLimitStopsTheRun() throws Exception {
        User wrong = user("wrong");
        long commit = commit(repository(), wrong, null, EMAIL);
        seed();
        provider = question -> new Answer.Deferred();

        run(50);

        assertThat(authorOf(commit)).isEqualTo(wrong.getId());
        assertThat(states()).containsOnly("PENDING");
        assertThat(attempts()).containsOnly(0);
    }

    @Test
    void shouldChangeNothingWhenTheProviderGivesNoAnswerAndGiveUpAfterTheLastAttempt() throws Exception {
        User wrong = user("wrong");
        long commit = commit(repository(), wrong, null, EMAIL);
        seed();

        for (int attempt = 1; attempt < CommitIdentityResolutionJob.MAX_ATTEMPTS; attempt++) {
            run(50);
            assertThat(states()).containsOnly("PENDING");
        }
        run(50);

        assertThat(states()).containsOnly("UNANSWERED");
        assertThat(authorOf(commit)).isEqualTo(wrong.getId());
        assertThat(changes.count()).isZero();
    }

    @Test
    void shouldChangeNothingWhenNoConnectionMonitorsTheRepository() throws Exception {
        User wrong = user("wrong");
        long commit = commit(repository(), wrong, null, EMAIL);
        seed();
        when(scopes.getActiveScopesForRepository(anyLong(), anyLong())).thenReturn(List.of());
        provider = question -> new Answer.NoAccount();

        run(50);

        assertThat(authorOf(commit)).isEqualTo(wrong.getId());
        assertThat(attempts()).containsOnly(1);
    }

    @Test
    void shouldEndTheQuestionWhenErasureRemovedTheEmail() throws Exception {
        long commit = commit(repository(), null, null, EMAIL);
        seed();
        jdbc.update("UPDATE git_commit SET author_email = NULL, committer_email = NULL WHERE id = ?", commit);

        run(50);

        assertThat(states()).containsOnly("UNANSWERED");
    }

    @Test
    void shouldResumeWithTheNextEmailAfterEachRun() throws Exception {
        User right = user("right");
        Repository repository = repository();
        commit(repository, null, null, "a@example.com");
        commit(repository, null, null, "b@example.com");
        seed();
        provider = question -> new Answer.Account(id(right), right.getNativeId());

        run(1);
        assertThat(states()).containsExactlyInAnyOrder("RESOLVED", "PENDING");
        run(1);

        assertThat(states()).containsOnly("RESOLVED");
        assertThat(meters.get("commit.identity.pending").gauge().value()).isZero();
    }

    @Test
    void shouldDeleteARecordAfterItsRetentionWithoutTheCorrectionJob() throws Exception {
        User right = user("right");
        commit(repository(), null, null, EMAIL);
        seed();
        provider = question -> new Answer.Account(id(right), right.getNativeId());
        run(50);
        assertThat(changes.count()).isPositive();
        jdbc.update(
                "UPDATE commit_identity_change SET changed_at = ?",
                Timestamp.from(Instant.now()
                        .minus(CommitIdentityChangeRetentionJob.RETENTION)
                        .minus(Duration.ofHours(1))));

        new CommitIdentityChangeRetentionJob(changes, Clock.systemUTC()).deleteExpired();

        assertThat(changes.count()).isZero();
    }

    @Test
    void shouldNotBlockTheDeleteOfAQueuedContributorOrRepository() throws Exception {
        Repository repository = repository();
        long commit = commit(repository, null, null, EMAIL);
        long contributor = contributor(commit, null, "coauthor@example.com");
        seed();

        jdbc.update("DELETE FROM commit_contributor WHERE id = ?", contributor);
        assertThat(states()).hasSize(1);
        jdbc.update("DELETE FROM repository WHERE id = ?", repository.getId());

        assertThat(states()).isEmpty();
    }

    @Test
    void shouldSeedOneRowForEachEmailOnGitHubOnly() throws Exception {
        Repository repository = repository();
        long older = commit(repository, null, null, EMAIL);
        long newer = commit(repository, null, null, EMAIL);
        contributor(older, null, "coauthor@example.com");
        IdentityProvider gitlab = provider(IdentityProviderType.GITLAB, "https://gitlab.com");
        commit(repository(gitlab), null, null, "gitlab@example.com");

        seed();

        assertThat(jdbc.queryForList(
                        "SELECT commit_id FROM commit_identity_resolution WHERE source <> 'CONTRIBUTOR'", Long.class))
                .containsOnly(newer);
        assertThat(jdbc.queryForList(
                        "SELECT commit_id FROM commit_identity_resolution WHERE source = 'CONTRIBUTOR'", Long.class))
                .containsExactly(older);
    }

    /** The job runs on a scheduler thread, which holds no workspace and no bypass of a caller. */
    private void run(int batchSize) throws Exception {
        if (job == null) {
            job = new CommitIdentityResolutionJob(
                    resolutions,
                    service,
                    new CommitIdentityResolutionProperties(true, batchSize),
                    List.of(new TableProvider()),
                    scopes,
                    meters);
        }
        var running = job;
        Throwable[] failure = new Throwable[1];
        var thread = new Thread(running::run);
        thread.setUncaughtExceptionHandler((ignored, error) -> failure[0] = error);
        thread.start();
        thread.join();
        assertThat(failure[0]).isNull();
    }

    private final class TableProvider implements CommitIdentityProvider {
        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITHUB;
        }

        @Override
        public Map<Question, Answer> answer(
                String nameWithOwner, long providerId, long scopeId, Set<Question> questions) {
            Map<Question, Answer> answers = new HashMap<>();
            for (var question : questions) {
                var answer = provider.apply(question);
                if (answer != null) answers.put(question, answer);
            }
            return answers;
        }
    }

    private void seed() throws Exception {
        var change = new CommitIdentityResolutionSeedChange();
        try (var connection = dataSource.getConnection()) {
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            change.execute(database);
            // Liquibase turns autocommit off, and it commits a change set itself.
            connection.commit();
        }
    }

    private List<@Nullable String> states() {
        return jdbc.queryForList("SELECT state FROM commit_identity_resolution", String.class);
    }

    private List<@Nullable Integer> attempts() {
        return jdbc.queryForList("SELECT attempts FROM commit_identity_resolution", Integer.class);
    }

    private @Nullable Long authorOf(long commit) {
        return jdbc.queryForObject("SELECT author_id FROM git_commit WHERE id = ?", Long.class, commit);
    }

    private @Nullable Long committerOf(long commit) {
        return jdbc.queryForObject("SELECT committer_id FROM git_commit WHERE id = ?", Long.class, commit);
    }

    private @Nullable Long contributorUser(long contributor) {
        return jdbc.queryForObject("SELECT user_id FROM commit_contributor WHERE id = ?", Long.class, contributor);
    }

    private static long id(User user) {
        return Objects.requireNonNull(user.getId());
    }

    private IdentityProvider provider(IdentityProviderType type, String serverUrl) {
        return providers
                .findByTypeAndServerUrl(type, serverUrl)
                .orElseGet(() -> providers.save(new IdentityProvider(type, serverUrl)));
    }

    private static long nativeId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private User user(String login) {
        var user = new User();
        user.setNativeId(nativeId());
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://example.com/" + login + ".png");
        user.setHtmlUrl("https://example.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user.setProvider(github);
        return users.save(user);
    }

    private Repository repository() {
        return repository(github);
    }

    private Repository repository(IdentityProvider provider) {
        String name = "identity-" + UUID.randomUUID();
        var created = new Repository();
        created.setNativeId(nativeId());
        created.setName(name);
        created.setNameWithOwner("identity/" + name);
        created.setHtmlUrl("https://example.com/identity/" + name);
        created.setVisibility(Repository.Visibility.PRIVATE);
        created.setProvider(provider);
        return repositories.save(created);
    }

    private long commit(Repository repository, @Nullable User author, @Nullable User committer, String email) {
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
                author == null ? null : author.getId(),
                committer == null ? null : committer.getId(),
                email,
                email,
                null);
        return Objects.requireNonNull(commits.findByShaAndRepositoryId(sha, repository.getId())
                .orElseThrow()
                .getId());
    }

    private long contributor(long commit, @Nullable User user, String email) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO commit_contributor (commit_id, user_id, role, name, email, ordinal)"
                        + " VALUES (?, ?, 'CO_AUTHOR', 'A', ?, 1) RETURNING id",
                Long.class,
                commit,
                user == null ? null : user.getId(),
                email));
    }
}
