package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource.WEBHOOK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.database.CommitIdentityResolutionSeedChange;
import de.tum.cit.aet.hephaestus.core.database.GitLabCommitIdentityResolutionSeedChange;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections.ActiveScm;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetailsPersister;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Batch;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.CredentialGeneration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Pause;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Question;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.commit.GitLabCommitIdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.sql.DataSource;
import liquibase.database.core.PostgresDatabase;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

class CommitIdentityResolutionIntegrationTest extends BaseIntegrationTest {

    private static final String EMAIL = "someone@example.com";

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private CommitRepository commits;

    @Autowired
    private CommitContributorRepository contributors;

    @Autowired
    private CommitDetailsPersister capture;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CommitIdentityResolutionRepository resolutions;

    @Autowired
    private ProviderCredentialPauseRepository pauses;

    @Autowired
    private CommitIdentityChangeRepository changes;

    @Autowired
    private CommitIdentityResolutionService service;

    private final RepositoryScopeProvider scopes = mock(RepositoryScopeProvider.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ActiveScm> askedCredentials = new ArrayList<>();

    private IdentityProvider github;
    private Function<Question, @Nullable Answer> provider = question -> null;
    private @Nullable Instant batchDeadline;
    private @Nullable CommitIdentityResolutionJob job;

    /** The workspace that monitors each repository unless a test names another. */
    private long scope;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ActiveScmConnections activeScm;

    @Autowired
    private ConnectionService connections;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        github = provider(IdentityProviderType.GITHUB, "https://github.com");
        scope = workspace();
        when(scopes.getActiveScopesForRepository(anyLong(), anyLong())).thenReturn(List.of(scope));
    }

    private long workspace() {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("identity-" + UUID.randomUUID());
        workspace.setDisplayName("Commit identity test");
        workspace.setAccountLogin("identity");
        workspace.setAccountType(AccountType.ORG);
        Workspace saved = workspaces.saveAndFlush(workspace);
        provisionPat(saved, "test-connection", "test-token");
        return Objects.requireNonNull(saved.getId());
    }

    private void provisionPat(Workspace workspace, String instanceKey, String token) {
        connections.provisionPatConnection(
                workspace,
                IntegrationKind.GITHUB,
                instanceKey,
                new ConnectionConfig.GitHubPatConfig("identity", "https://github.com", Set.of()),
                token,
                UUID.randomUUID().toString());
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
    void shouldKeepTheProviderAccountWhenCaptureHasADifferentStoredEmailMatch() throws Exception {
        User correct = user("correct");
        User stored = user("stored");
        Repository repository = repository();
        long commitId = commit(repository, null, null, EMAIL);
        long contributorId = contributor(commitId, null, EMAIL);
        seed();
        provider = question -> new Answer.Account(id(correct), correct.getNativeId());
        run(50);
        String sha = commits.findById(commitId).orElseThrow().getSha();
        var now = Instant.now();
        var details = new CommitDetails(
                sha,
                "Captured",
                null,
                "Author",
                EMAIL,
                now,
                "Committer",
                EMAIL,
                now,
                0,
                0,
                0,
                List.of(),
                List.of(),
                "Captured");
        var origin = new CommitDetailsPersister.Origin(
                1L,
                WEBHOOK,
                IdentityProviderType.GITHUB,
                value -> "https://example.com/commit/" + value,
                email -> stored.getId(),
                (commit, captured, author, committer) ->
                        contributors.upsertContributor(commit.getId(), author, "CO_AUTHOR", "A", EMAIL, 1));

        assertThat(capture.persist(details, repository, origin)).isEqualTo(CommitDetailsPersister.Outcome.CAPTURED);

        assertThat(authorOf(commitId)).isEqualTo(correct.getId());
        assertThat(committerOf(commitId)).isEqualTo(correct.getId());
        assertThat(contributorUser(contributorId)).isEqualTo(correct.getId());
        assertThat(jdbc.queryForObject("SELECT message FROM git_commit WHERE id = ?", String.class, commitId))
                .isEqualTo("Captured");
    }

    @Test
    void shouldReplaceAStoredEmailLinkWhenTheProviderNamesAnotherAccount() {
        User stored = user("stored");
        User correct = user("correct");
        Repository repository = repository();
        long commit = commit(repository, stored, stored, EMAIL);
        String sha = commits.findById(commit).orElseThrow().getSha();
        var now = Instant.now();

        commits.upsertCommit(
                sha,
                "Provider update",
                null,
                null,
                now,
                now,
                null,
                null,
                null,
                now,
                repository.getId(),
                correct.getId(),
                correct.getId(),
                EMAIL,
                EMAIL,
                null,
                false);

        assertThat(authorOf(commit)).isEqualTo(correct.getId());
        assertThat(committerOf(commit)).isEqualTo(correct.getId());
    }

    @Test
    void shouldReadNoAccountEmailsAndAnErasedEmailWithoutLosingTheNull() throws Exception {
        Repository repository = repository();
        long erased = commit(repository, null, null, "erased@example.com");
        commit(repository, null, null, EMAIL);
        seed();
        jdbc.update("UPDATE commit_identity_resolution SET state = 'NO_ACCOUNT'");
        jdbc.update("UPDATE git_commit SET author_email = NULL, committer_email = NULL WHERE id = ?", erased);

        assertThat(resolutions.findNoAccountEmails(Objects.requireNonNull(repository.getId())))
                .containsExactlyInAnyOrder(EMAIL, null);
    }

    @Test
    void shouldKeepAClearWhenStoredEmailEnrichmentHasAnOlderExclusionList() throws Exception {
        User stored = user("stored");
        Repository repository = repository();
        long commit = commit(repository, stored, stored, EMAIL);
        seed();
        assertThat(resolutions.findNoAccountEmails(Objects.requireNonNull(repository.getId())))
                .isEmpty();
        provider = question -> new Answer.NoAccount();
        run(50);

        assertThat(commits.bulkUpdateAuthorIdFromStoredEmail(EMAIL, repository.getId(), stored.getId()))
                .isZero();
        assertThat(commits.bulkUpdateCommitterIdFromStoredEmail(EMAIL, repository.getId(), stored.getId()))
                .isZero();
        assertThat(authorOf(commit)).isNull();
        assertThat(committerOf(commit)).isNull();
    }

    @Test
    void shouldSeedGitLabOnlyAndPreferAnAuthoredCommitOverANewerCommitter() throws Exception {
        IdentityProvider gitlab = provider(IdentityProviderType.GITLAB, "https://gitlab.example.test");
        Repository repository = repository(gitlab);
        long authored = commit(repository, null, null, EMAIL);
        long newer = commit(repository, null, null, "other@example.com");
        jdbc.update("UPDATE git_commit SET committer_email = ? WHERE id = ?", EMAIL, newer);
        commit(repository(), null, null, "github@example.com");
        var change = new GitLabCommitIdentityResolutionSeedChange();
        try (var connection = dataSource.getConnection()) {
            var database = new PostgresDatabase();
            database.setConnection(new JdbcConnection(connection));
            change.execute(database);
            connection.commit();
        }

        assertThat(jdbc.queryForList("SELECT repository_id FROM commit_identity_resolution", Long.class))
                .containsOnly(Objects.requireNonNull(repository.getId()));
        assertThat(jdbc.queryForList(
                        "SELECT commit_id FROM commit_identity_resolution WHERE commit_id = ?", Long.class, authored))
                .containsExactly(authored);
        assertThat(jdbc.queryForList(
                        "SELECT source FROM commit_identity_resolution WHERE commit_id = ?", String.class, authored))
                .containsExactly("AUTHOR");
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldClearAContradictedLinkWhenTheProviderNamesAnErasedAccount(boolean providerKnows) throws Exception {
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
        provider =
                question -> providerKnows ? new Answer.Erased() : new Answer.Account(id(erased), erased.getNativeId());

        run(50);

        assertThat(authorOf(commit)).isNull();
        assertThat(states()).containsOnly("RESOLVED");
        assertThat(changes.count()).isEqualTo(1);
        assertThat(meters.counter("commit.identity.resolutions", "outcome", "erased")
                        .count())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 36})
    void shouldChangeNothingAndSpendNoAttemptOfTheLastWhenTheRateLimitStopsTheRun(int hours) throws Exception {
        User wrong = user("wrong");
        long commit = commit(repository(), wrong, null, EMAIL);
        seed();
        // The email has one attempt left: a throttle must not take it.
        jdbc.update("UPDATE commit_identity_resolution SET attempts = ?", CommitIdentityResolutionJob.MAX_ATTEMPTS - 1);
        Instant reset = Instant.now().plus(Duration.ofHours(hours)).truncatedTo(ChronoUnit.SECONDS);
        List<String> asked = new ArrayList<>();
        provider = question -> {
            asked.add(question.email());
            return throttle(reset);
        };

        Instant earliestCap = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofHours(24));
        run(50);
        // Another server, which never saw the throttle, reads the deadline from the row.
        job = null;
        run(50);

        assertThat(authorOf(commit)).isEqualTo(wrong.getId());
        assertThat(states()).containsOnly("PENDING");
        assertThat(attempts()).containsOnly(CommitIdentityResolutionJob.MAX_ATTEMPTS - 1);
        assertThat(asked)
                .as("no server asks again before the provider's deadline")
                .containsExactly(EMAIL);
        assertThat(jdbc.queryForList("SELECT deferred_until FROM commit_identity_resolution", Timestamp.class))
                .singleElement()
                .satisfies(until -> {
                    Instant actual = until.toInstant();
                    if (hours == 3) assertThat(actual).isEqualTo(reset);
                    else assertThat(actual).isBetween(earliestCap, Instant.now().plus(Duration.ofHours(24)));
                    var credential = activeScm.withToken(scope).orElseThrow();
                    assertThat(pauses.pausedUntil(scope, credential.connectionId(), credential.credentialsGeneration()))
                            .contains(actual);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldResumeDeferredWorkWithANewCredentialInTheSameWorkspace(boolean replaceToken) throws Exception {
        User right = user("right");
        long waiting = commit(repository(), null, null, EMAIL);
        seed();
        var old = activeScm.withToken(scope).orElseThrow();
        Instant reset = Instant.now().plus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);
        provider = question -> throttle(reset);
        run(50);
        assertThat(attempts()).containsExactly(0);
        assertThat(pauses.pausedUntil(scope, old.connectionId(), old.credentialsGeneration()))
                .contains(reset);

        if (replaceToken) {
            assertThat(connections.rotateBearerToken(
                            scope, IntegrationKind.GITHUB, new BearerToken("new-test-token", null), null))
                    .isPresent();
        } else {
            var previous = connections.findActive(scope, IntegrationKind.GITHUB).orElseThrow();
            connections.transition(
                    previous,
                    new ConnectionService.TransitionRequest(
                            IntegrationState.SUSPENDED, "SUSPEND", "SYSTEM", null, null, null));
            provisionPat(workspaces.findById(scope).orElseThrow(), "other-connection", "new-test-token");
        }
        var replacement = activeScm.withToken(scope).orElseThrow();
        assertThat(replacement).isNotEqualTo(old);
        provider = question -> new Answer.Account(id(right), right.getNativeId());

        job = null;
        run(50);

        assertThat(authorOf(waiting)).isEqualTo(right.getId());
        assertThat(askedCredentials).containsExactly(old, replacement);
        assertThat(attempts()).containsExactly(1);
        assertThat(pauses.pausedUntil(scope, old.connectionId(), old.credentialsGeneration()))
                .contains(reset);
        assertThat(pauses.pausedUntil(scope, replacement.connectionId(), replacement.credentialsGeneration()))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void shouldKeepTheLastAttemptAvailableWhenGitLabRotatesATokenMidBatch(int rotateOnRequest) throws Exception {
        String server = "https://gitlab.com";
        var workspace = workspaces.findById(scope).orElseThrow();
        var original = connections.findActive(scope, IntegrationKind.GITHUB).orElseThrow();
        connections.transition(
                original,
                new ConnectionService.TransitionRequest(
                        IntegrationState.SUSPENDED, "SUSPEND", "SYSTEM", null, null, null));
        connections.provisionPatConnection(
                workspace,
                IntegrationKind.GITLAB,
                "test-gitlab",
                new ConnectionConfig.GitLabConfig(server, 1L, null, Set.of(), null),
                "old-test-token",
                UUID.randomUUID().toString());
        var repository = repository(provider(IdentityProviderType.GITLAB, server));
        long first = commit(repository, null, null, "first@example.com");
        long second = commit(repository, null, null, EMAIL);
        jdbc.update("""
                INSERT INTO commit_identity_resolution(repository_id, commit_id, source, state, attempts)
                VALUES (?, ?, 'AUTHOR', 'PENDING', 0), (?, ?, 'AUTHOR', 'PENDING', 4)
                """, repository.getId(), first, repository.getId(), second);

        var clientProvider = mock(GitLabGraphQlClientProvider.class);
        var gitLabProperties = mock(GitLabProperties.class);
        when(gitLabProperties.graphqlTimeout()).thenReturn(Duration.ofSeconds(2));
        var calls = new AtomicInteger();
        var revoked = new AtomicInteger();
        var old = activeScm.withToken(scope).orElseThrow();
        String sha = commits.findById(second).orElseThrow().getSha();
        var healthy = HttpGraphQlClient.create(WebClient.builder()
                .baseUrl(server)
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {"data":{"project":{"repository":{"commit":{"sha":"%s","authorEmail":"%s","author":null}}}}}
                                """.formatted(sha, EMAIL))
                        .build()))
                .build());
        when(clientProvider.forCredential(eq(scope), any(), any())).thenAnswer(invocation -> {
            ActiveScm used = invocation.getArgument(1);
            var client = mock(HttpGraphQlClient.class);
            var request = mock(GraphQlClient.RequestSpec.class, RETURNS_SELF);
            when(client.documentName("GetCommitIdentity")).thenReturn(request);
            when(request.execute()).thenReturn(Mono.defer(() -> {
                int call = calls.incrementAndGet();
                if (call == rotateOnRequest) {
                    assertThat(connections.rotateBearerToken(
                                    scope, IntegrationKind.GITLAB, new BearerToken("new-test-token", null), null))
                            .isPresent();
                }
                if (call > 1 && used.credentialsGeneration() == old.credentialsGeneration()) {
                    revoked.incrementAndGet();
                    return Mono.error(WebClientResponseException.create(
                            401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null));
                }
                return healthy.document("{ __typename }").execute();
            }));
            return client;
        });
        job = new CommitIdentityResolutionJob(
                resolutions,
                pauses,
                service,
                new CommitIdentityResolutionProperties(true, 50),
                List.of(new GitLabCommitIdentityProvider(
                        clientProvider,
                        gitLabProperties,
                        mock(GitLabUserService.class),
                        mock(PersonProcessingSuppression.class),
                        activeScm,
                        pauses)),
                scopes,
                activeScm,
                meters);

        run(50);

        assertThat(jdbc.queryForMap(
                        "SELECT state, attempts FROM commit_identity_resolution WHERE commit_id = ?", second))
                .containsEntry("state", rotateOnRequest == 1 ? "NO_ACCOUNT" : "PENDING")
                .containsEntry("attempts", rotateOnRequest == 1 ? 5 : 4);
        assertThat(revoked).hasValue(rotateOnRequest == 1 ? 0 : 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCorrectAHealthyRepositoryBehindMoreThanFiftyPausedRepositories(boolean answersThrottledBatch)
            throws Exception {
        User wrong = user("wrong");
        User right = user("right");
        String throttledEmail = "throttled@example.com";
        Repository throttled = repository();
        long waiting = commit(throttled, wrong, null, throttledEmail);
        for (int index = 0; index < 50; index++) {
            commit(repository(), wrong, null, throttledEmail);
        }
        Repository healthy = repository();
        when(scopes.getActiveScopesForRepository(eq(throttled.getNativeId()), anyLong()))
                .thenReturn(List.of(scope));
        when(scopes.getActiveScopesForRepository(eq(healthy.getNativeId()), anyLong()))
                .thenReturn(List.of(workspace()));
        long corrected = commit(healthy, wrong, null, EMAIL);
        seed();
        assertThat(resolutions.findPendingRepositories().getFirst().getRepositoryId())
                .as("the throttled repository stands first in the queue")
                .isEqualTo(throttled.getId());
        var credential = activeScm.withToken(scope).orElseThrow();
        pauses.pause(
                scope,
                credential.connectionId(),
                credential.credentialsGeneration(),
                Instant.now().minusSeconds(1));
        List<String> asked = new ArrayList<>();
        provider = question -> {
            asked.add(question.email());
            if (question.email().equals(throttledEmail)) {
                throttle(Instant.now());
                if (!answersThrottledBatch) return null;
            }
            return new Answer.Account(id(right), right.getNativeId());
        };

        run(50);

        assertThat(authorOf(corrected))
                .as("the repository behind the throttled one is corrected")
                .isEqualTo(right.getId());
        assertThat(authorOf(waiting)).isEqualTo(answersThrottledBatch ? right.getId() : wrong.getId());
        assertThat(asked)
                .as("the throttled repository is not asked again before its deadline")
                .containsExactly(throttledEmail, EMAIL);
        assertThat(jdbc.queryForObject(
                        "SELECT attempts FROM commit_identity_resolution WHERE repository_id = ?",
                        Integer.class,
                        throttled.getId()))
                .isEqualTo(answersThrottledBatch ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldReadAnAlreadyDeferredRepositoryThroughAnotherScope(boolean rotateAlternate) throws Exception {
        var project = repository();
        long queued = commit(project, null, null, EMAIL);
        seed();
        long alternate = workspace();
        when(scopes.getActiveScopesForRepository(
                        eq(project.getNativeId()), eq(project.getProvider().getId())))
                .thenReturn(List.of(scope, alternate));
        var first = activeScm.withToken(scope).orElseThrow();
        var second = activeScm.withToken(alternate).orElseThrow();
        Instant reset = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        pauses.pause(scope, first.connectionId(), first.credentialsGeneration(), reset);
        var ids = jdbc
                .queryForList("SELECT id FROM commit_identity_resolution WHERE commit_id = ?", Long.class, queued)
                .stream()
                .map(Objects::requireNonNull)
                .toList();
        resolutions.defer(ids, first.connectionId(), first.credentialsGeneration(), reset);
        if (rotateAlternate) {
            pauses.pause(alternate, second.connectionId(), second.credentialsGeneration(), reset.plusSeconds(60));
            assertThat(connections.rotateBearerToken(
                            alternate, IntegrationKind.GITHUB, new BearerToken("healthy-replacement", null), null))
                    .isPresent();
        }
        var right = user("right");
        provider = question -> new Answer.Account(id(right), right.getNativeId());

        run(50);

        assertThat(askedCredentials)
                .containsExactly(activeScm.withToken(alternate).orElseThrow());
        assertThat(authorOf(queued)).isEqualTo(right.getId());
        assertThat(pauses.pausedUntil(scope, first.connectionId(), first.credentialsGeneration()))
                .contains(reset);
    }

    @Test
    void shouldKeepPartialAnswersAndTryAnotherScopeWhenAReplacementIsPaused() throws Exception {
        var project = repository();
        String firstEmail = "partial@example.com";
        long first = commit(project, null, null, firstEmail);
        long second = commit(project, null, null, EMAIL);
        seed();
        long alternate = workspace();
        when(scopes.getActiveScopesForRepository(eq(project.getNativeId()), eq(github.getId())))
                .thenReturn(List.of(scope, alternate));
        var right = user("right");
        var account = new Answer.Account(id(right), right.getNativeId());
        var adapter = mock(CommitIdentityProvider.class);
        when(adapter.kind()).thenReturn(IntegrationKind.GITHUB);
        when(adapter.answer(any(), anyLong(), eq(scope), any(), any())).thenAnswer(call -> {
            assertThat(connections.rotateBearerToken(
                            scope, IntegrationKind.GITHUB, new BearerToken("paused-replacement", null), null))
                    .isPresent();
            var replacement = activeScm.withToken(scope).orElseThrow();
            var generation = new CredentialGeneration(replacement.connectionId(), replacement.credentialsGeneration());
            Instant reset = Instant.now().plus(Duration.ofHours(1));
            pauses.pause(scope, generation.connectionId(), generation.generation(), reset);
            Set<Question> questions = call.getArgument(4);
            var answered = questions.stream()
                    .filter(question -> question.email().equals(firstEmail))
                    .findFirst()
                    .orElseThrow();
            return new Batch(Map.of(answered, account), new Pause(reset, generation, true));
        });
        when(adapter.answer(any(), anyLong(), eq(alternate), any(), any())).thenAnswer(call -> {
            Set<Question> questions = call.getArgument(4);
            assertThat(questions).extracting(Question::email).containsExactly(EMAIL);
            return new Batch(Map.of(questions.iterator().next(), account), null);
        });
        job = new CommitIdentityResolutionJob(
                resolutions,
                pauses,
                service,
                new CommitIdentityResolutionProperties(true, 50),
                List.of(adapter),
                scopes,
                activeScm,
                meters);

        run(50);

        assertThat(authorOf(first)).isEqualTo(right.getId());
        assertThat(authorOf(second)).isEqualTo(right.getId());
    }

    @Test
    void shouldSetAsideTheOtherRepositoriesOfAThrottledScopeWithoutAskingTheProvider() throws Exception {
        User wrong = user("wrong");
        String throttledEmail = "throttled@example.com";
        Repository first = repository();
        commit(first, wrong, null, throttledEmail);
        seed();
        Instant reset = Instant.now().plus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);
        List<String> asked = new ArrayList<>();
        provider = question -> {
            asked.add(question.email());
            return throttle(reset);
        };
        run(50);
        // A repository of the same credential comes due later, on another server that never saw the throttle.
        Repository sibling = repository();
        commit(sibling, wrong, null, EMAIL);
        seed();
        job = null;

        run(50);

        assertThat(asked)
                .as("one request for the whole credential, across servers")
                .containsExactly(throttledEmail);
        assertThat(jdbc.queryForList(
                        "SELECT deferred_until FROM commit_identity_resolution WHERE repository_id = ?",
                        Timestamp.class,
                        sibling.getId()))
                .singleElement()
                .satisfies(until -> assertThat(until.toInstant()).isEqualTo(reset));
        assertThat(attempts()).containsOnly(0);
    }

    @Test
    void shouldPauseTheCredentialWhenAThrottledBatchAnsweredEveryEmail() throws Exception {
        User right = user("right");
        long answered = commit(repository(), null, null, EMAIL);
        seed();
        Instant reset = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        provider = question -> {
            throttle(reset);
            return new Answer.Account(id(right), right.getNativeId());
        };
        run(50);
        assertThat(authorOf(answered)).isEqualTo(right.getId());

        long waiting = commit(repository(), null, null, "waiting@example.com");
        seed();
        job = null;
        run(50);

        assertThat(askedCredentials).hasSize(1);
        assertThat(authorOf(waiting)).isNull();
        assertThat(jdbc.queryForList(
                        "SELECT state, attempts, deferred_until FROM commit_identity_resolution WHERE commit_id = ?",
                        waiting))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("state", "PENDING")
                        .containsEntry("attempts", 0)
                        .containsEntry("deferred_until", Timestamp.from(reset)));
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
                    pauses,
                    service,
                    new CommitIdentityResolutionProperties(true, batchSize),
                    List.of(new TableProvider()),
                    scopes,
                    activeScm,
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

    private @Nullable Answer throttle(Instant until) {
        batchDeadline = until;
        return null;
    }

    private final class TableProvider implements CommitIdentityProvider {
        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITHUB;
        }

        @Override
        public Batch answer(
                String nameWithOwner, long providerId, long scopeId, ActiveScm credential, Set<Question> questions) {
            askedCredentials.add(credential);
            batchDeadline = null;
            Map<Question, Answer> answers = new HashMap<>();
            for (var question : questions) {
                var answer = provider.apply(question);
                if (answer != null) answers.put(question, answer);
            }
            Instant until = batchDeadline;
            return new Batch(
                    answers,
                    until == null
                            ? null
                            : new Pause(
                                    until,
                                    new CredentialGeneration(
                                            credential.connectionId(), credential.credentialsGeneration())));
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
                null,
                false);
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
