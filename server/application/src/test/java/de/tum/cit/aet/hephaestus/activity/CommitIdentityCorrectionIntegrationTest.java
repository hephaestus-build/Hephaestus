package de.tum.cit.aet.hephaestus.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.config.SpringAsyncConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityProvider.Answer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolution;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionProperties;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity.CommitIdentityResolutionService;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

class CommitIdentityCorrectionIntegrationTest extends BaseIntegrationTest {
    private static final String EMAIL = "author@example.com";
    private static final Instant AT = Instant.parse("2025-01-03T10:11:12Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private CommitIdentityResolutionService service;

    @Autowired
    private CommitIdentityResolutionRepository resolutions;

    @Autowired
    private ApplicationEventMulticaster multicaster;

    private long providerId;
    private long repositoryId;
    private long wrong;
    private long correct;
    private long commit;
    private long resolution;
    private UUID event;

    @BeforeEach
    void fixture() {
        databaseTestUtils.cleanDatabase();
        String unique = UUID.randomUUID().toString();
        providerId = Objects.requireNonNull(providers
                .saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://" + unique + ".example.com"))
                .getId());
        wrong = user(1, "wrong");
        correct = user(2, "correct");
        repositoryId = Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,html_url,visibility)
            VALUES (1,?,'repo',?,false,false,false,'https://example.com/repo','PUBLIC') RETURNING id
            """, Long.class, providerId, "repair/" + unique));
        commit = commit(EMAIL, wrong);
        resolution = Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO commit_identity_resolution(repository_id,commit_id,source,state,attempts)
            VALUES (?,?,'AUTHOR','PENDING',0) RETURNING id
            """, Long.class, repositoryId, commit));
        event = event(workspace(), commit, wrong, "COMMIT_CREATED", "commit");
    }

    @Test
    void shouldCorrectActivityAcrossWorkspacesWhenTheProviderNamesAnAccount() {
        UUID secondWorkspace = event(workspace(), commit, wrong, "COMMIT_CREATED", "commit");
        UUID unrelatedEmail = event(workspace(), commit("other@example.com", wrong), wrong, "COMMIT_CREATED", "commit");
        UUID otherType = event(workspace(), commit, wrong, "ISSUE_CREATED", "issue");

        apply(new Answer.Account(correct, 2));

        assertThat(actor(event)).isEqualTo(correct);
        assertThat(actor(secondWorkspace)).isEqualTo(correct);
        assertThat(actor(unrelatedEmail)).isEqualTo(wrong);
        assertThat(actor(otherType)).isEqualTo(wrong);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM activity_event WHERE id=?", Timestamp.class, event))
                .isEqualTo(Timestamp.from(AT));
        assertThat(jdbc.queryForObject("SELECT target_id FROM activity_event WHERE id=?", Long.class, event))
                .isEqualTo(commit);
    }

    @Test
    void shouldClearTheOldActorWhenTheProviderSaysNoAccount() {
        apply(new Answer.NoAccount());

        assertThat(actor(event)).isNull();
        assertThat(jdbc.queryForObject("SELECT author_id FROM git_commit WHERE id=?", Long.class, commit))
                .isNull();
    }

    @Test
    void shouldRepairActivityWhenTheCommitAlreadyHasTheProviderAccount() {
        jdbc.update("UPDATE git_commit SET author_id=? WHERE id=?", correct, commit);

        var result = apply(new Answer.Account(correct, 2));

        assertThat(result.changedLinks()).isZero();
        assertThat(actor(event)).isEqualTo(correct);
    }

    @Test
    void shouldRollBackSourceQueueAndActivityWhenDerivedCorrectionFails() {
        ApplicationListener<ApplicationEvent> failure = candidate -> {
            if (candidate instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof ScmDomainEvent.CommitIdentityCorrected) {
                throw new IllegalStateException("Derived correction failed");
            }
        };
        multicaster.addApplicationListener(failure);
        try {
            assertThatThrownBy(() -> apply(new Answer.NoAccount())).isInstanceOf(IllegalStateException.class);
        } finally {
            multicaster.removeApplicationListener(failure);
        }

        assertThat(actor(event)).isEqualTo(wrong);
        assertThat(jdbc.queryForObject("SELECT author_id FROM git_commit WHERE id=?", Long.class, commit))
                .isEqualTo(wrong);
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM commit_identity_resolution WHERE id=?", String.class, resolution))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM commit_identity_change WHERE row_id=? AND target='git_commit.author_id'",
                        Long.class,
                        commit))
                .isZero();
    }

    @Test
    void shouldCorrectWithoutWorkspaceContextWhenRunOnTheRealMonitoringExecutor() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("default");
            context.register(SpringAsyncConfig.class);
            context.refresh();
            var executor = context.getBean("monitoringExecutor", AsyncTaskExecutor.class);

            executor.submit(() -> job().run()).get(30, TimeUnit.SECONDS);

            assertThat(actor(event)).isEqualTo(correct);
        }
    }

    @Test
    void shouldFillAnActivityActorWhenItWasUnresolved() {
        jdbc.update("UPDATE activity_event SET actor_id=NULL WHERE id=?", event);
        apply(new Answer.Account(correct, 2));
        assertThat(actor(event)).isEqualTo(correct);
    }

    @ParameterizedTest
    @EnumSource(CommitIdentityResolution.Source.class)
    void shouldFindTheSameEmailWhenTheQueueUsesAnySource(CommitIdentityResolution.Source source) {
        jdbc.update("UPDATE git_commit SET committer_email=? WHERE id=?", EMAIL, commit);
        Long contributor = jdbc.queryForObject("""
            INSERT INTO commit_contributor(commit_id,email,role,ordinal)
            VALUES (?,?,'CO_AUTHOR',1) RETURNING id
            """, Long.class, commit, EMAIL);
        jdbc.update(
                "UPDATE commit_identity_resolution SET source=?,contributor_id=? WHERE id=?",
                source.name(),
                source == CommitIdentityResolution.Source.CONTRIBUTOR ? contributor : null,
                resolution);

        apply(new Answer.Account(correct, 2));

        assertThat(actor(event)).isEqualTo(correct);
    }

    @Test
    void shouldLeaveAnotherRepositoryUnchangedWhenItsEmailMatches() {
        long originalRepository = repositoryId;
        repositoryId = Objects.requireNonNull(jdbc.queryForObject("""
            INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,html_url,visibility)
            VALUES (2,?,'other','repair/other',false,false,false,'https://example.com/other','PUBLIC') RETURNING id
            """, Long.class, providerId));
        long otherCommit = commit(EMAIL, wrong);
        UUID otherEvent = event(workspace(), otherCommit, wrong, "COMMIT_CREATED", "commit");
        repositoryId = originalRepository;

        apply(new Answer.Account(correct, 2));

        assertThat(actor(event)).isEqualTo(correct);
        assertThat(actor(otherEvent)).isEqualTo(wrong);
    }

    @Test
    void shouldRetryAndContinueWhenOneActivityCorrectionFails() {
        ApplicationListener<ApplicationEvent> failure = candidate -> {
            if (candidate instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof ScmDomainEvent.CommitIdentityCorrected corrected
                    && corrected.resolutionId() == resolution) {
                throw new IllegalStateException("Derived correction failed");
            }
        };
        long second = commit("second@example.com", wrong);
        jdbc.update(
                "INSERT INTO commit_identity_resolution(repository_id,commit_id,source,state,attempts)"
                        + " VALUES (?,?,'AUTHOR','PENDING',0)",
                repositoryId,
                second);
        UUID secondEvent = event(workspace(), second, wrong, "COMMIT_CREATED", "commit");
        multicaster.addApplicationListener(failure);
        try {
            var job = job();
            job.run();
            job.run();
        } finally {
            multicaster.removeApplicationListener(failure);
        }

        assertThat(actor(event)).isEqualTo(wrong);
        assertThat(actor(secondEvent)).isEqualTo(correct);
        assertThat(jdbc.queryForObject(
                        "SELECT attempts FROM commit_identity_resolution WHERE id=?", Integer.class, resolution))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM commit_identity_resolution WHERE id=?", String.class, resolution))
                .isEqualTo("PENDING");
    }

    private CommitIdentityResolutionJob job() {
        var scopes = mock(RepositoryScopeProvider.class);
        when(scopes.getActiveScopesForRepository(anyLong(), anyLong())).thenReturn(List.of(1L));
        CommitIdentityProvider provider = new CommitIdentityProvider() {
            @Override
            public IntegrationKind kind() {
                return IntegrationKind.GITHUB;
            }

            @Override
            public Map<Question, Answer> answer(String name, long provider, long scope, Set<Question> questions) {
                Map<Question, Answer> answers = new HashMap<>();
                questions.forEach(question -> answers.put(question, new Answer.Account(correct, 2)));
                return answers;
            }
        };
        return new CommitIdentityResolutionJob(
                resolutions,
                service,
                new CommitIdentityResolutionProperties(true, 1),
                List.of(provider),
                scopes,
                new SimpleMeterRegistry());
    }

    private CommitIdentityResolutionService.Result apply(Answer answer) {
        return service.apply(resolution, repositoryId, providerId, EMAIL, answer);
    }

    private @Nullable Long actor(UUID id) {
        return jdbc.queryForObject("SELECT actor_id FROM activity_event WHERE id=?", Long.class, id);
    }

    private long user(long nativeId, String login) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO \"user\"(native_id,provider_id,login,type) VALUES (?,?,?,'USER') RETURNING id",
                Long.class,
                nativeId,
                providerId,
                login));
    }

    private long workspace() {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("correction-" + UUID.randomUUID());
        workspace.setDisplayName("Correction test");
        workspace.setAccountLogin("repair");
        workspace.setAccountType(AccountType.ORG);
        return workspaces.saveAndFlush(workspace).getId();
    }

    private long commit(String email, long actor) {
        return Objects.requireNonNull(jdbc.queryForObject(
                """
            INSERT INTO git_commit(sha,message,authored_at,committed_at,created_at,updated_at,
                additions,deletions,changed_files,repository_id,author_id,author_email)
            VALUES (?,'Work',?,?,?,?,0,0,0,?,?,?) RETURNING id
            """,
                Long.class,
                UUID.randomUUID().toString().replace("-", "") + "00000000",
                Timestamp.from(AT),
                Timestamp.from(AT),
                Timestamp.from(AT),
                Timestamp.from(AT),
                repositoryId,
                actor,
                email));
    }

    private UUID event(long workspace, long target, @Nullable Long actor, String type, String targetType) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
            INSERT INTO activity_event(id,event_key,event_type,occurred_at,actor_id,workspace_id,
                repository_id,target_type,target_id,ingested_at)
            VALUES (?,?,?,?,?,?,?,?,?,now())
            """,
                id,
                "correction." + id,
                type,
                Timestamp.from(AT),
                actor,
                workspace,
                repositoryId,
                targetType,
                target);
        return id;
    }
}
