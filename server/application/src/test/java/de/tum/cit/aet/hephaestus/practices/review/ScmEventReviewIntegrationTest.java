package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemSourceObserved;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.IntegrationCoreConnectionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEvent;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventLedgerProperties;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventRepository;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.PendingItemEventReaper;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.QuietWindowCloser;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.RoleHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ReviewThreadContentSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.DataSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmWorkCapture;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRunsRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestChecksRecord;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.GitHubPullRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabHeadPipeline;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadinessReader;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendTransactions;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewItemState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequestStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.SqlStatementCounter;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RecordApplicationEvents
class ScmEventReviewIntegrationTest extends AbstractResultAdmissionIntegrationTest {
    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    IntegrationCoreConnectionPersonDataCatalog itemEventCatalog;

    @Autowired
    IdentityProviderRepository providers;

    @Autowired
    ConnectionRepository connections;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    RepositoryToMonitorRepository monitors;

    @Autowired
    RepositoryRepository repositories;

    @Autowired
    UserRepository users;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    HeadCheckRunsRecorder checkRuns;

    @Autowired
    PullRequestReviewRepository providerReviews;

    @Autowired
    AccountRepository accounts;

    @Autowired
    IdentityRepository identities;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    ItemIndexService writer;

    @Autowired
    ScmItemService source;

    @Autowired
    ScmWorkCapture workCapture;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    ItemEventRepository events;

    @Autowired
    ReviewAssignmentQuery assignmentQuery;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReviewService service;

    @Autowired
    ReviewRequestService requests;

    @Autowired
    WebTestClient http;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ReviewStart starts;

    @Autowired
    GitLabMergeRequestProcessor mergeRequests;

    @Autowired
    GitHubPullRequestProcessor pullRequestProcessor;

    @Autowired
    IssueRepository issueRows;

    @Autowired
    ReviewIntegrationData resultData;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultStore resultStore;

    @Autowired
    ResultRepository resultRows;

    @Autowired
    ItemRepository itemRows;

    @Autowired
    PracticeRepository practices;

    @Autowired
    FeedbackStoreService feedbackStore;

    @Autowired
    FeedbackPublicationContext publication;

    @Autowired
    FeedbackSendTransactions delivery;

    @Autowired
    FeedbackRepository feedbackRows;

    @Autowired
    FeedbackCopyRepository copyRows;

    @Autowired
    FeedbackSendQueue sendQueue;

    @Autowired
    PendingItemEventReaper reaper;

    @Autowired
    ItemEventLedgerProperties ledger;

    @Autowired
    ItemEventService itemEvents;

    @Autowired
    ReviewItemRepository memberships;

    @Autowired
    ReviewSnapshotQuery snapshots;

    @Autowired
    Clock clock;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        when(models.models(anyLong(), anyList()))
                .thenReturn(List.of(new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of()))));
    }

    @AfterEach
    void restoreInstanceDeliveryBrake() {
        jdbc.update("UPDATE instance_settings SET silent_mode_engaged=true WHERE id=1");
    }

    @Test
    void shouldReadRetainedRoleFactsWithTheApplicationMapper() {
        var human = mapper.readValue("""
                {"role":"AUTHOR","identity":{"providerId":1,"subject":"42","teamId":null},"human":true}
                """, RoleHolder.class);
        var unknown = mapper.readValue("""
                {"role":"AUTHOR","identity":{"providerId":1,"subject":"43","teamId":null},"human":false}
                """, RoleHolder.class);

        assertThat(human.human()).isTrue();
        assertThat(human.bot()).isFalse();
        assertThat(unknown.human()).isFalse();
        assertThat(unknown.bot()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "GITHUB,false,BOT,BOT_AUTHOR", "GITLAB,false,BOT,BOT_AUTHOR",
        "GITHUB,true,BOT,BOT_REVIEWER", "GITLAB,true,BOT,BOT_REVIEWER",
        "GITHUB,false,ORGANIZATION,ROLE_UNAVAILABLE", "GITLAB,false,ORGANIZATION,ROLE_UNAVAILABLE",
        "GITHUB,true,ORGANIZATION,ROLE_UNAVAILABLE", "GITLAB,true,ORGANIZATION,ROLE_UNAVAILABLE"
    })
    void shouldNameAKnownBotAuthorWithoutCallingOtherIdentitiesBots(
            IdentityProviderType provider, boolean submittedReview, User.Type type, String reason) {
        var prepared = prepare(provider, submittedReview);
        var identity = source.pin(prepared.item(), prepared.version())
                .roles()
                .getFirst()
                .identity();
        var author = users.findByNativeIdAndProviderId(Long.parseLong(identity.subject()), identity.providerId())
                .orElseThrow();
        author.setType(type);
        users.saveAndFlush(author);
        long workspace = prepared.item().workspaceId();
        var id = service.request(ReviewRequest.builder()
                .workspaceId(workspace)
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(prepared.version())
                .origin(ReviewOrigin.REQUEST)
                .requestedById(memberAccount(workspace))
                .practiceIds(List.of())
                .build());

        assertThat(reviews.findById(id).orElseThrow()).satisfies(review -> {
            assertThat(review.getStatus()).isEqualTo(ReviewStatus.PARTIAL);
            assertThat(review.getStopReason()).isEqualTo(ReviewStopReason.READINESS_REFUSED);
        });
        assertThat(jdbc.queryForObject("SELECT reason FROM review_practice WHERE review_id=?", String.class, id))
                .isEqualTo(reason);
        assertThat(jdbc.queryForObject(
                        "SELECT state||':'||reason FROM review_item WHERE review_id=?", String.class, id))
                .isEqualTo("WITHHELD:" + reason);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_job WHERE review_id=?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM result WHERE workspace_id=?", Long.class, workspace))
                .isZero();
        when(runtime.usage(workspace, id))
                .thenReturn(new ReviewUsageSnapshot(BigDecimal.ZERO, true, 0, 0, 0, List.of()));
        http.get()
                .uri(
                        "/workspaces/{slug}/practices/reviews/{id}",
                        workspaces.findById(workspace).orElseThrow().getWorkspaceSlug(),
                        id)
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + memberAccount(workspace)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.practices[0].reason")
                .isEqualTo(reason);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepALateOldCaptureAsHistoryWithoutMakingItCurrent(IdentityProviderType type) {
        var prepared = prepare(type, false);
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        var scenario = new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(author)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
        var pending = admissions.pending(
                scenario,
                Instant.now().minusSeconds(30),
                scenario.account().getId(),
                prepared.version().value());
        running(pending);
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                item.workspaceId(),
                null,
                DataSource.WEBHOOK,
                "edited",
                UUID.randomUUID().toString(),
                type,
                author.getId());
        transactions.executeWithoutResult(ignored -> {
            var changed = pulls.findById(pull.getId()).orElseThrow();
            changed.setBody("The work changed after this capture.");
            pulls.saveAndFlush(changed);
            publisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(changed), Set.of("body"), context));
        });
        var canonical = source.pin(item, null).version();
        assertThat(canonical).isNotEqualTo(prepared.version());
        var incumbent = resultData.result(
                scenario, ResultOutcome.MET, Instant.now().minusSeconds(60), Instant.now(), canonical.value());

        var receipt = resultStore.admit(
                item.workspaceId(),
                pending.review().getId(),
                pending.jobId(),
                1,
                List.of(admissions.candidate(pending, ResultOutcome.MET)));

        assertThat(receipt.resultIds()).hasSize(1);
        var historical = resultRows.findById(receipt.resultIds().getFirst()).orElseThrow();
        assertThat(historical.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(historical.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(resultRows.findById(incumbent.getId()).orElseThrow().getEndedAt())
                .isNull();
        assertThat(resultRows.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .extracting(row -> row.getId())
                .doesNotContain(historical.getId());
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldCommitNormalClaimOrderingWhenTheCurrentSourceIsNotReady(IdentityProviderType type) {
        var prepared = prepare(type, false);
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        var scenario = new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(author)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
        var older = admissions.pending(
                scenario,
                Instant.now().minusSeconds(60),
                scenario.account().getId(),
                prepared.version().value());
        var newer = admissions.pending(
                scenario,
                Instant.now(),
                scenario.account().getId(),
                prepared.version().value());
        running(newer);
        var incumbent = resultStore
                .admit(
                        item.workspaceId(),
                        newer.review().getId(),
                        newer.jobId(),
                        1,
                        List.of(admissions.candidate(newer, ResultOutcome.MET)))
                .resultIds()
                .getFirst();
        jdbc.update("UPDATE issue SET base_ref_oid=NULL WHERE id=?", pull.getId());
        assertThatThrownBy(() -> source.current(item)).isInstanceOf(ItemCaptureException.class);
        running(older);

        var historical = resultStore
                .admit(
                        item.workspaceId(),
                        older.review().getId(),
                        older.jobId(),
                        1,
                        List.of(admissions.candidate(older, ResultOutcome.MET)))
                .resultIds()
                .getFirst();

        assertThat(resultRows.findById(historical).orElseThrow().getEndReason()).isEqualTo(ResultEndReason.OUTRANKED);
        assertThat(resultRows.findById(incumbent).orElseThrow().getEndedAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRefuseAPreparedOldCapturePieceAndFenceTheNewerOneAfterATitleOnlyEdit(IdentityProviderType type) {
        jdbc.update("""
            INSERT INTO instance_settings(id,silent_mode_engaged,version) VALUES(1,false,0)
            ON CONFLICT(id) DO UPDATE SET silent_mode_engaged=false
            """);
        var prepared = prepare(type, false);
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        var practice = practices
                .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                .orElseThrow();
        practice.setFeedbackMode(FeedbackMode.AUTOMATIC);
        practices.saveAndFlush(practice);
        var scenario = new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(author)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
        long developer = Objects.requireNonNull(scenario.account().getId());
        var old = admissions.pending(
                scenario,
                Instant.now().minusSeconds(120),
                developer,
                prepared.version().value());
        // Captured at the old title too, but admitted only after the newer review.
        var late = admissions.pending(
                scenario,
                Instant.now().minusSeconds(90),
                developer,
                prepared.version().value());
        running(old);
        var oldResult = admit(old);
        assertThat(resultRows.findById(oldResult).orElseThrow().getEndedAt()).isNull();
        var oldPiece = onWork(
                old,
                oldResult,
                publication.issue(
                        item.workspaceId(), item.itemId(), old.review().getId(), List.of(developer)));
        var oldFeedback = feedbackRows.findById(oldPiece).orElseThrow();
        assertThat(oldFeedback.getState())
                .as("Prepared public piece: %s", oldFeedback.getStopReason())
                .isEqualTo(FeedbackState.READY);
        assertThat(sendQueue.current(item.workspaceId(), oldPiece).orElseThrow().getState())
                .isEqualTo(FeedbackSendState.PENDING);

        transactions.executeWithoutResult(ignored -> {
            var changed = pulls.findById(pull.getId()).orElseThrow();
            changed.setTitle("A retitled change");
            pulls.saveAndFlush(changed);
            publisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(changed),
                    Set.of("title"),
                    new EventContext(
                            UUID.randomUUID(),
                            Instant.now(),
                            item.workspaceId(),
                            null,
                            DataSource.WEBHOOK,
                            "edited",
                            UUID.randomUUID().toString(),
                            type,
                            author.getId())));
        });
        var canonical = source.pin(item, null).version();
        assertThat(canonical.value()).isNotEqualTo(prepared.version().value());
        assertThat(canonical.baseVersion()).isEqualTo(prepared.version().baseVersion());
        assertThat(ScmVersion.reviewedHead(canonical)).isEqualTo(ScmVersion.reviewedHead(prepared.version()));
        var newer = admissions.pending(scenario, Instant.now().minusSeconds(60), developer, canonical.value());
        running(newer);
        var newerResult = admit(newer);
        assertThat(resultRows.findById(oldResult).orElseThrow().getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        running(late);
        var lateRow = resultRows.findById(admit(late)).orElseThrow();
        assertThat(lateRow.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(lateRow.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(resultRows.findById(newerResult).orElseThrow().getEndedAt()).isNull();

        var oldOwner = ActionTestSupport.claimLane(jdbc, mapper, "feedback:" + oldPiece)
                .orElseThrow();
        assertThat(delivery.claim(item.workspaceId(), oldPiece, oldOwner)).isNotNull();
        assertThat(delivery.fence(
                        item.workspaceId(),
                        oldPiece,
                        oldOwner,
                        List.of(FeedbackIds.copy(oldPiece, FeedbackCopyKind.SUMMARY, 0)),
                        List.of()))
                .isFalse();
        var refused = feedbackRows.findById(oldPiece).orElseThrow();
        assertThat(refused.getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(refused.getStopReason()).isEqualTo(FeedbackStopReason.REVIEWED_VERSION_CHANGED);
        assertThat(copyRows.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(item.workspaceId(), oldPiece))
                .isNotEmpty()
                .allSatisfy(copy -> {
                    assertThat(copy.getState()).isNotEqualTo(FeedbackCopyState.WRITE_STARTED);
                    assertThat(copy.getWriteStartedAt()).isNull();
                });

        // The newer composer reads history only after the old piece was refused.
        var newerPiece = onWork(
                newer,
                newerResult,
                publication.issue(
                        item.workspaceId(), item.itemId(), newer.review().getId(), List.of(developer)));
        assertThat(feedbackRows.findById(newerPiece).orElseThrow().getState()).isEqualTo(FeedbackState.READY);
        var newerOwner = ActionTestSupport.claimLane(jdbc, mapper, "feedback:" + newerPiece)
                .orElseThrow();
        var summary = FeedbackIds.copy(newerPiece, FeedbackCopyKind.SUMMARY, 0);
        assertThat(delivery.claim(item.workspaceId(), newerPiece, newerOwner)).isNotNull();
        assertThat(delivery.fence(item.workspaceId(), newerPiece, newerOwner, List.of(summary), List.of()))
                .isTrue();
        assertThat(copyRows.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(item.workspaceId(), newerPiece))
                .singleElement()
                .satisfies(copy -> {
                    assertThat(copy.getId()).isEqualTo(summary);
                    assertThat(copy.getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
                });
    }

    private UUID admit(ReviewAdmissionData.Pending pending) {
        return resultStore
                .admit(
                        pending.review().getWorkspaceId(),
                        pending.review().getId(),
                        pending.jobId(),
                        1,
                        List.of(admissions.candidate(pending, ResultOutcome.NOT_MET)))
                .resultIds()
                .getFirst();
    }

    /** One public summary prepared through the real store, retaining the publication context its composer read. */
    private UUID onWork(ReviewAdmissionData.Pending pending, UUID resultId, String context) {
        long workspaceId = pending.review().getWorkspaceId();
        UUID reviewId = pending.review().getId();
        long developer = Objects.requireNonNull(pending.developerId());
        UUID id = FeedbackIds.onWork(workspaceId, reviewId, developer);
        UUID copy = FeedbackIds.copy(id, FeedbackCopyKind.SUMMARY, 0);
        feedbackStore.prepareAll(
                workspaceId,
                reviewId,
                List.of(new FeedbackDraft(
                        id,
                        workspaceId,
                        reviewId,
                        pending.review().getItemId(),
                        developer,
                        FeedbackDestination.ON_WORK,
                        FeedbackState.READY,
                        null,
                        "Check the changed branch.",
                        List.of(new FeedbackBasisInput(resultId, null, 0)),
                        List.of(new FeedbackCopyInput(
                                copy,
                                FeedbackCopyKind.SUMMARY,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                copy.toString(),
                                FeedbackIds.writeMarker(copy))),
                        Map.of(),
                        new FeedbackCompositionTrace(
                                List.of(new FeedbackCompositionTrace.CopySupport(
                                        FeedbackCopyKind.SUMMARY,
                                        0,
                                        List.of(new FeedbackCompositionTrace.Support(resultId, null)))),
                                List.of(),
                                null,
                                null,
                                context),
                        null)));
        return id;
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,false", "GITHUB,true", "GITLAB,false", "GITLAB,true"})
    void shouldSerializeAdmissionAndNativeSourceChangeOnTheExistingItemLock(
            IdentityProviderType type, boolean admissionFirst) throws Exception {
        var prepared = prepare(type, false);
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        var scenario = new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(author)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
        var pending = admissions.pending(
                scenario,
                Instant.now().minusSeconds(30),
                scenario.account().getId(),
                prepared.version().value());
        running(pending);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        var admissionStarted = new CountDownLatch(1);
        var executor =
                Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        try {
            if (admissionFirst) {
                doAnswer(call -> {
                            Object canonical = call.callRealMethod();
                            held.countDown();
                            awaitLatch(release);
                            return canonical;
                        })
                        .when(productItems)
                        .fingerprints(eq(item), any(), any());
            }
            Future<?> sourceWrite;
            Future<AdmissionReceipt> admitted;
            if (admissionFirst) {
                admitted = executor.submit(() -> resultStore.admit(
                        item.workspaceId(),
                        pending.review().getId(),
                        pending.jobId(),
                        1,
                        List.of(admissions.candidate(pending, ResultOutcome.MET))));
                awaitLatch(held);
                sourceWrite = executor.submit(() -> changeNativeBody(prepared, type, false, held, release, changed));
                awaitLatch(changed);
            } else {
                sourceWrite = executor.submit(() -> changeNativeBody(prepared, type, true, held, release, changed));
                awaitLatch(held);
                admitted = executor.submit(() -> {
                    admissionStarted.countDown();
                    return resultStore.admit(
                            item.workspaceId(),
                            pending.review().getId(),
                            pending.jobId(),
                            1,
                            List.of(admissions.candidate(pending, ResultOutcome.MET)));
                });
                awaitLatch(admissionStarted);
            }
            release.countDown();
            var receipt = admitted.get(20, TimeUnit.SECONDS);
            sourceWrite.get(20, TimeUnit.SECONDS);

            assertThat(source.pin(item, null).version()).isNotEqualTo(prepared.version());
            var historical = resultRows.findById(receipt.resultIds().getFirst()).orElseThrow();
            assertThat(historical.getItemVersion()).isEqualTo(prepared.version().value());
            assertThat(historical.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
            assertThat(resultRows.forDevelopersSince(
                            item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                    .extracting(row -> row.getId())
                    .doesNotContain(historical.getId());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    // GitLab captures no check runs yet: a trigger job reports success when it starts its child pipeline.
    @Test
    void shouldRecordChecksCompletedOncePerSettledRunSetOnlyWhenTheWholeListOfRunsEnded() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        long repositoryId = Objects.requireNonNull(pull.getRepository()).getId();
        var context = Objects.requireNonNull(transactions.execute(status -> ProcessingContext.forWebhook(
                item.workspaceId(), repositories.findById(repositoryId).orElseThrow(), "completed")));
        String head = Objects.requireNonNull(pull.getHeadRefOid());
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var running = new HeadCheckRuns(
                head,
                List.of(checkRun("build", "in_progress", null), checkRun("test", "success", readAt)),
                true,
                readAt,
                null);
        // Every listed run ended, but the provider still waited for one it had not listed yet.
        var partial = new HeadCheckRuns(
                head, List.of(checkRun("build", "success", readAt)), false, readAt.plusSeconds(30), null);
        var red = List.of(checkRun("build", "failure", readAt.plusSeconds(50)), checkRun("test", "success", readAt));
        var green = List.of(checkRun("build", "success", readAt.plusSeconds(170)), checkRun("test", "success", readAt));
        var redAgain =
                List.of(checkRun("build", "failure", readAt.plusSeconds(290)), checkRun("test", "success", readAt));

        checkRuns.observe(repositoryId, running, context);
        checkRuns.observe(repositoryId, partial, context);
        assertThat(checksCompleted(item)).isEmpty();

        checkRuns.observe(repositoryId, settled(head, red, readAt.plusSeconds(60)), context);
        checkRuns.observe(repositoryId, settled(head, red, readAt.plusSeconds(120)), context);
        assertThat(checksCompleted(item)).containsExactly(prepared.version().value());

        // A re-run that settles with other results is a new occasion at the same version. A later read of the same
        // runs,
        // a lagging read that still shows the earlier runs, and a read that lists them in another order are not.
        checkRuns.observe(repositoryId, settled(head, green, readAt.plusSeconds(180)), context);
        checkRuns.observe(repositoryId, settled(head, red, readAt.plusSeconds(200)), context);
        checkRuns.observe(repositoryId, settled(head, green.reversed(), readAt.plusSeconds(240)), context);
        assertThat(checksCompleted(item))
                .containsExactly(prepared.version().value(), prepared.version().value());

        // A third run that settles as the first did is a new occasion too: red, green, red.
        checkRuns.observe(repositoryId, settled(head, redAgain, readAt.plusSeconds(300)), context);
        assertThat(checksCompleted(item))
                .containsExactly(
                        prepared.version().value(),
                        prepared.version().value(),
                        prepared.version().value());
        assertThat(pulls.findById(pull.getId()).orElseThrow().getHeadCheckRuns())
                .isEqualTo(settled(head, redAgain, readAt.plusSeconds(300)));
    }

    private List<@Nullable String> checksCompleted(ItemRef item) {
        return jdbc.queryForList(
                "SELECT item_version FROM item_event WHERE workspace_id=? AND item_id=? AND name=?",
                String.class,
                item.workspaceId(),
                item.itemId(),
                ScmItemEvents.PULL_REQUEST_CHECKS_COMPLETED.value());
    }

    private static HeadCheckRuns.Run checkRun(String name, String state, @Nullable Instant finishedAt) {
        return new HeadCheckRuns.Run(name, "ci", state, finishedAt != null, null, null, finishedAt);
    }

    private static HeadCheckRuns settled(String head, List<HeadCheckRuns.Run> runs, Instant readAt) {
        return new HeadCheckRuns(head, runs, true, readAt, null);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldEndTheSubmittedReviewWhenItsReadableParentContextChanges(IdentityProviderType type) {
        var prepared = prepare(type, true);
        var item = prepared.item();
        var scenario = reviewScenario(prepared);
        var nativeReview = providerReviews
                .findById(Objects.requireNonNull(item.sourceId()))
                .orElseThrow();
        long pullId = Objects.requireNonNull(nativeReview.getPullRequest()).getId();
        var submittedCommit = nativeReview.getCommitId();
        var pending = admissions.pending(
                scenario,
                Instant.now().minusSeconds(30),
                scenario.account().getId(),
                prepared.version().value());
        running(pending);
        recordSources(
                item,
                pending.review().getId(),
                scenario.practice().getId(),
                Set.of(new SourceKind("scm.pull-request.core")));
        transactions.executeWithoutResult(ignored -> {
            var changed = pulls.findById(pullId).orElseThrow();
            changed.setHeadRefOid("c".repeat(40));
            pulls.saveAndFlush(changed);
            var context = new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    item.workspaceId(),
                    null,
                    DataSource.WEBHOOK,
                    "synchronize",
                    UUID.randomUUID().toString(),
                    type,
                    Objects.requireNonNull(changed.getAuthor()).getId());
            publisher.publishEvent(
                    new ScmDomainEvent.PullRequestSynchronized(ScmEventPayload.PullRequestData.from(changed), context));
        });
        assertThat(source.pin(item, null).version()).isNotEqualTo(prepared.version());
        var unchanged = providerReviews.findById(nativeReview.getId()).orElseThrow();
        assertThat(unchanged.getCommitId()).isEqualTo(submittedCommit);
        assertThat(unchanged.getBody()).isEqualTo(nativeReview.getBody());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM item_event WHERE workspace_id=? AND item_id=?",
                        Long.class,
                        item.workspaceId(),
                        item.itemId()))
                .isZero();

        var receipt = resultStore.admit(
                item.workspaceId(),
                pending.review().getId(),
                pending.jobId(),
                1,
                List.of(admissions.candidate(pending, ResultOutcome.MET)));

        var result = resultRows.findById(receipt.resultIds().getFirst()).orElseThrow();
        assertThat(result.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(result.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(resultRows.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .extracting(row -> row.getId())
                .doesNotContain(result.getId());
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldRetireThePriorClaimWhenTheSubmittedReviewItselfChanges(IdentityProviderType type) {
        var prepared = prepare(type, true);
        var item = prepared.item();
        var scenario = reviewScenario(prepared);
        var at = Instant.now().minusSeconds(30);
        var current = resultData.result(
                scenario, ResultOutcome.MET, at, at, prepared.version().value());
        recordSources(
                item,
                current.getReviewId(),
                current.getPracticeId(),
                Set.of(new SourceKind("scm.pull-request.core"), ReviewThreadContentSource.KIND));
        transactions.executeWithoutResult(ignored -> {
            var changed = providerReviews
                    .findById(Objects.requireNonNull(item.sourceId()))
                    .orElseThrow();
            changed.setBody("A revised submitted review.");
            providerReviews.saveAndFlush(changed);
            var context = new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    item.workspaceId(),
                    null,
                    DataSource.WEBHOOK,
                    "submitted",
                    UUID.randomUUID().toString(),
                    type,
                    Objects.requireNonNull(changed.getAuthor()).getId());
            publisher.publishEvent(new ScmDomainEvent.ReviewSubmitted(
                    ScmEventPayload.ReviewData.from(changed).orElseThrow(), context));
        });
        assertThat(source.pin(item, null).version()).isNotEqualTo(prepared.version());
        assertThat(resultRows.findById(current.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM item_event WHERE workspace_id=? AND item_id=?",
                        Long.class,
                        item.workspaceId(),
                        item.itemId()))
                .isEqualTo(1L);
    }

    private void recordSources(ItemRef item, UUID reviewId, long practiceId, Set<SourceKind> kinds) {
        var captured = productItems.fingerprints(item, kinds, null);
        assertThat(captured.keySet()).containsAll(kinds);
        var facts = new JsonMapper().createObjectNode();
        var fingerprints = facts.putObject("sourceFingerprints");
        captured.forEach((kind, value) -> fingerprints.put(kind.value(), value));
        assertThat(jdbc.update(
                        "UPDATE review_practice SET capture_decision=?::jsonb WHERE workspace_id=? AND review_id=? AND practice_id=?",
                        facts.toString(),
                        item.workspaceId(),
                        reviewId,
                        practiceId))
                .isEqualTo(1);
    }

    private ReviewIntegrationData.Scenario reviewScenario(Prepared prepared) {
        var item = prepared.item();
        var review = providerReviews
                .findById(Objects.requireNonNull(item.sourceId()))
                .orElseThrow();
        var reviewer = users.findById(Objects.requireNonNull(review.getAuthor()).getId())
                .orElseThrow();
        return new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(reviewer)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
    }

    private void changeNativeBody(
            Prepared prepared,
            IdentityProviderType type,
            boolean holdSource,
            CountDownLatch held,
            CountDownLatch release,
            CountDownLatch changed) {
        transactions.executeWithoutResult(ignored -> {
            var item = prepared.item();
            if (holdSource) writer.lock(item.workspaceId(), item.itemId());
            var work = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
            work.setBody("A concurrent native material edit.");
            pulls.saveAndFlush(work);
            var context = new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    item.workspaceId(),
                    null,
                    DataSource.WEBHOOK,
                    "edited",
                    UUID.randomUUID().toString(),
                    type,
                    Objects.requireNonNull(work.getAuthor()).getId());
            publisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(work), Set.of("body"), context));
            changed.countDown();
            if (holdSource) {
                held.countDown();
                awaitLatch(release);
            }
        });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent fixture timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent fixture interrupted", interrupted);
        }
    }

    private void running(ReviewAdmissionData.Pending pending) {
        admissions.running(pending, 1);
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
        when(runtime.activeJobIds(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Set.of(pending.jobId()));
        when(runtime.unsettledJobIds(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Set.of());
        when(runtime.runtime(pending.review().getWorkspaceId(), pending.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        1,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "late-capture")))));
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,false", "GITHUB,true", "GITLAB,false", "GITLAB,true"})
    void shouldRestoreTheCanonicalVersionThroughTheRecordedEventListener(IdentityProviderType type, boolean review) {
        var prepared = prepare(type, review);
        publish(prepared);
        var event = event(prepared);
        assertThat(event.getState()).isEqualTo(ItemEventState.STARTED);
        var admitted = reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), event.getId())
                .orElseThrow();
        assertThat(admitted.getOrigin()).isEqualTo(ReviewOrigin.EVENT);
        assertThat(admitted.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(admitted.getBaseVersion()).isEqualTo(prepared.version().baseVersion());
        assertThat(admitted.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        publish(prepared);
        assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                        prepared.item().workspaceId(), prepared.item().itemId()))
                .extracting(row -> row.getId())
                .containsExactly(admitted.getId());
        assertThat(source.pin(prepared.item(), prepared.version()).version()).isEqualTo(prepared.version());
        assertThatThrownBy(() -> source.pin(
                        prepared.item(), new ItemVersion(prepared.version().value(), "c".repeat(40))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        long pullId = review
                ? Objects.requireNonNull(providerReviews
                                .findById(Objects.requireNonNull(prepared.item().sourceId()))
                                .orElseThrow()
                                .getPullRequest())
                        .getId()
                : Objects.requireNonNull(prepared.item().sourceId());
        var changed = pulls.findById(pullId).orElseThrow();
        changed.setBody("A changed description");
        pulls.saveAndFlush(changed);
        assertThatThrownBy(() -> source.pin(
                        prepared.item(), new ItemVersion(prepared.version().value(), null)))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,false", "GITHUB,true", "GITLAB,false", "GITLAB,true"})
    void shouldSelectANotDraftPracticeOnlyForReadyWork(IdentityProviderType type, boolean draft) {
        var prepared = prepare(type, false, Map.of("draftStatus", Set.of("NOT_DRAFT")), draft);
        publish(prepared);
        var event = event(prepared);
        var admitted = reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), event.getId());
        if (draft) {
            assertThat(event.getState()).isEqualTo(ItemEventState.WITHHELD);
            assertThat(event.getReason()).isEqualTo(ReviewRequestReason.NO_ACTIVE_PRACTICE);
            assertThat(admitted).isEmpty();
        } else {
            assertThat(event.getState()).isEqualTo(ItemEventState.STARTED);
            assertThat(admitted).isPresent();
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldAdmitTheOriginalOpenedOccasionWhenReadyWorkMovesBeforeAdmission(IdentityProviderType type) {
        var prepared = prepare(type, false, Map.of("draftStatus", Set.of("NOT_DRAFT")), false);
        long workspaceId = prepared.item().workspaceId();
        long author = Objects.requireNonNull(
                jdbc.queryForObject("SELECT account_id FROM membership WHERE workspace_id=?", Long.class, workspaceId));
        // The budget is out when the ready work is reported, so its occasion waits to be admitted.
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(false);
        publish(prepared);
        var occasion = event(prepared);
        assertThat(occasion.getName()).isEqualTo(ScmItemEvents.PULL_REQUEST_OPENED.value());
        assertThat(occasion.getState()).isEqualTo(ItemEventState.WAITING);
        assertThat(occasion.getReason()).isEqualTo(ReviewRequestReason.BUDGET_EXHAUSTED);
        assertThat(reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId()))
                .isEmpty();

        // Before admission, the author retitles, rewrites and pushes the work and turns it back into a draft.
        transactions.executeWithoutResult(ignored -> {
            var moved = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            moved.setTitle("A retitled change");
            moved.setBody("A rewritten description");
            moved.setDraft(true);
            moved.setHeadRefOid("c".repeat(40));
            moved.setBaseRefOid("d".repeat(40));
            var data = ScmEventPayload.PullRequestData.from(pulls.saveAndFlush(moved));
            publisher.publishEvent(
                    new ScmDomainEvent.PullRequestUpdated(data, Set.of("title", "body", "draft"), context(prepared)));
            publisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(data, context(prepared)));
        });
        // An ordinary pin still reads only the current work.
        assertThatThrownBy(() -> source.pin(prepared.item(), prepared.version()))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));

        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        var request = ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(new ItemVersion(Objects.requireNonNull(occasion.getItemVersion()), null))
                .origin(ReviewOrigin.EVENT)
                .eventId(occasion.getId())
                .practiceIds(List.of())
                .build();

        assertThatCode(() -> service.request(request))
                .as("the occasion is admitted at the version, state and roles it was recorded with")
                .doesNotThrowAnyException();
        var admitted = reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId())
                .orElseThrow();
        assertThat(admitted.getOrigin()).isEqualTo(ReviewOrigin.EVENT);
        assertThat(admitted.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        assertThat(admitted.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(admitted.getBaseVersion()).isEqualTo("a".repeat(40));
        assertThat(admitted.getOrderedAt()).isEqualTo(occasion.getOccurredAt());
        var member = jdbc.queryForMap(
                "SELECT state, detail->>'developerId' AS developer FROM review_item"
                        + " WHERE workspace_id=? AND review_id=?",
                workspaceId,
                admitted.getId());
        assertThat(member.get("state")).isEqualTo("LISTED");
        assertThat(member.get("developer")).isEqualTo(Long.toString(author));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldCaptureTheRetainedOccasionWhenTheWorkMovesAfterAdmission(IdentityProviderType type) throws Exception {
        var prepared = prepare(type, false);
        publish(prepared);
        var occasion = event(prepared);
        var admitted = reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), occasion.getId())
                .orElseThrow();
        moveWork(prepared);

        var pinned = source.pinOccasion(
                prepared.item(),
                new ItemVersion(Objects.requireNonNull(occasion.getItemVersion()), null),
                occasion.getId());
        assertThat(pinned.version()).isEqualTo(prepared.version());
        var core = PullRequestContentSource.CORE;
        var captured = source.capture(
                pinned,
                new CaptureRequest(
                        admitted.getId(),
                        UUID.randomUUID(),
                        1,
                        Set.of(core),
                        "3.0.0",
                        3,
                        null,
                        Set.of(core),
                        occasion.getId()));

        var record = new JsonMapper()
                .readTree(Objects.requireNonNull(captured.files().get("context/metadata.json")));
        assertThat(record.path("title").asString()).isEqualTo("A bounded change");
        assertThat(record.path("body").asString()).isEqualTo("Describe this change");
        assertThat(record.path("commit_sha").asString()).isEqualTo("b".repeat(40));
        assertThat(record.path("is_draft").asBoolean()).isFalse();
        assertThat(record.path("basis").path("other_fields").asString()).isEqualTo("OCCASION");
        assertThat(record.path("basis").has("observed_at")).isTrue();
        assertThat(record.path("basis").has("captured_at")).isFalse();
        assertThat(new String(
                        Objects.requireNonNull(captured.files().get(PullRequestContentSource.DESCRIPTION_FILE)),
                        StandardCharsets.UTF_8))
                .isEqualTo("Describe this change");
        assertThat(source.record(pinned, captured.files()).path("commit_sha").asString())
                .isEqualTo("b".repeat(40));
        // The same request without its occasion still captures only the current work, which no longer matches.
        assertThatThrownBy(() -> source.capture(
                        pinned,
                        new CaptureRequest(
                                admitted.getId(), UUID.randomUUID(), 1, Set.of(core), "3.0.0", 3, null, Set.of(core))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        // Another occasion ID names no occasion of this item, so nothing is pinned, neither old nor current work.
        assertOccasionUnavailable(prepared, prepared.version(), UUID.randomUUID());
    }

    /**
     * An automatic review reads its occasion. At each practice start, the server reads the same sources again under that
     * occasion. A repository workflow that assigns the author right after the pull request opens changes its roles but
     * not its version, so every source that the occasion retained must keep its fingerprint, or no practice can start.
     */
    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepTheOccasionRecordFingerprintsAfterTheAuthorIsAssigned(IdentityProviderType type) {
        var prepared = prepare(type, false);
        publish(prepared);
        var occasion = event(prepared);
        Set<SourceKind> kinds =
                Set.of(PullRequestContentSource.CORE, PullRequestChecksRecord.KIND, ReviewThreadContentSource.KIND);
        var captured = productItems.fingerprints(prepared.item(), kinds, occasion.getId());
        assertThat(captured).containsOnlyKeys(kinds);

        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            pull.getAssignees().add(Objects.requireNonNull(pull.getAuthor()));
            pulls.saveAndFlush(pull);
        });
        assertThat(source.pin(prepared.item(), null).version()).isEqualTo(prepared.version());

        assertThat(productItems.fingerprints(prepared.item(), kinds, occasion.getId()))
                .isEqualTo(captured);
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldCaptureTheOriginalSubmittedDecisionWhenTheReviewIsEditedAfterItsOccasion(IdentityProviderType type)
            throws Exception {
        var prepared = prepare(type, true);
        publish(prepared);
        var occasion = event(prepared);
        long workspaceId = prepared.item().workspaceId();
        var admitted = reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId())
                .orElseThrow();
        long reviewId = Objects.requireNonNull(prepared.item().sourceId());
        var reviewer = users.findById(Objects.requireNonNull(
                                providerReviews.findById(reviewId).orElseThrow().getAuthor())
                        .getId())
                .orElseThrow();
        // After the occasion, the reviewer edits the submitted review's words and changes its decision.
        transactions.executeWithoutResult(ignored -> {
            var edited = providerReviews.findById(reviewId).orElseThrow();
            edited.setBody("An edited later decision.");
            edited.setState(PullRequestReview.State.APPROVED);
            providerReviews.saveAndFlush(edited);
        });
        assertThat(source.pin(prepared.item(), null).state()).containsEntry("reviewState", "APPROVED");

        var pinned = source.pinOccasion(prepared.item(), prepared.version(), occasion.getId());
        assertThat(pinned.state()).containsEntry("reviewState", "COMMENTED");
        var threads = ReviewThreadContentSource.KIND;
        var request = new CaptureRequest(
                admitted.getId(),
                UUID.randomUUID(),
                1,
                Set.of(threads),
                "3.0.0",
                3,
                null,
                Set.of(threads),
                occasion.getId());
        var captured = source.capture(pinned, request);

        var staged = new JsonMapper()
                .readTree(Objects.requireNonNull(captured.files().get("context/review_threads.json")));
        assertThat(staged.path("reviewRecipient").path("author").asString()).isEqualTo(reviewer.getLogin());
        assertThat(staged.path("reviewRecipient").path("authorId").asLong()).isEqualTo(reviewer.getNativeId());
        var decisions = staged.path("reviewDecisions");
        assertThat(decisions.size()).isEqualTo(1);
        var decision = decisions.get(0);
        assertThat(decision.path("state").asString()).isEqualTo("COMMENTED");
        assertThat(decision.path("body").asString()).isEqualTo("Review this change");
        assertThat(decision.path("authorId").asLong()).isEqualTo(reviewer.getNativeId());
        // Dated when Hephaestus stored the occasion; the mirror's own dates stay as they were then.
        assertThat(decision.has("observedAt")).isTrue();
        assertThat(staged.toString()).doesNotContain("An edited later decision.");
        // The same request without its occasion reads the edited review, which no longer has the pinned version.
        var current = new CaptureRequest(
                admitted.getId(), UUID.randomUUID(), 1, Set.of(threads), "3.0.0", 3, null, Set.of(threads));
        assertThatThrownBy(() -> source.capture(pinned, current))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        // The original words still need current access to the work: once it is gone, nothing is captured.
        jdbc.update(
                "UPDATE issue SET deleted_at = now() WHERE id = ?",
                Objects.requireNonNull(
                                providerReviews.findById(reviewId).orElseThrow().getPullRequest())
                        .getId());
        assertThatThrownBy(() -> source.capture(pinned, request))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.ITEM_REMOVED));
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldReviewTheMergerOfADelayedMergeAndNeverDateALaterApprovalBeforeIt(IdentityProviderType type)
            throws Exception {
        var merged = mergedWork(type);
        var prepared = merged.prepared();
        long workspaceId = prepared.item().workspaceId();
        long pullId = Objects.requireNonNull(prepared.item().sourceId());
        long authorAccount = account(merged.author());
        long mergerAccount = account(merged.merger());
        assertThat(mergerAccount).isNotEqualTo(authorAccount);

        // The budget is out when the merge is reported, so its occasion waits to be admitted.
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(false);
        publish(prepared);
        var occasion = event(prepared);
        assertThat(occasion.getName()).isEqualTo(ScmItemEvents.PULL_REQUEST_MERGED.value());
        assertThat(occasion.getState()).isEqualTo(ItemEventState.WAITING);
        assertThat(occasion.getReason()).isEqualTo(ReviewRequestReason.BUDGET_EXHAUSTED);
        assertThat(occasion.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId()))
                .isEmpty();

        var retainedRecord =
                Objects.requireNonNull(occasion.getEvidence()).path("record").deepCopy();
        assertThat(retainedRecord.isObject()).isTrue();

        // Before admission the merged work is retitled and rewritten, and a later observation that a third person
        // reports stores another head and base. Neither the author nor this person merged it.
        var pusher = users.saveAndFlush(
                TestUserFactory.createUser(nativeId(), merged.author().getLogin() + "-pusher", merged.provider()));
        var later = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspaceId,
                null,
                DataSource.WEBHOOK,
                "synchronize",
                UUID.randomUUID().toString(),
                type,
                pusher.getId());
        transactions.executeWithoutResult(ignored -> {
            var moved = pulls.findById(pullId).orElseThrow();
            moved.setTitle("A retitled change");
            moved.setBody("A rewritten description");
            moved.setHeadRefOid("c".repeat(40));
            moved.setBaseRefOid("d".repeat(40));
            var data = ScmEventPayload.PullRequestData.from(pulls.saveAndFlush(moved));
            publisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(data, Set.of("title", "body"), later));
            publisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(data, later));
        });
        assertThat(source.pin(prepared.item(), null).version()).isNotEqualTo(prepared.version());
        assertThatThrownBy(() -> source.pin(prepared.item(), prepared.version()))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));

        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        var request = ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(new ItemVersion(Objects.requireNonNull(occasion.getItemVersion()), null))
                .origin(ReviewOrigin.EVENT)
                .eventId(occasion.getId())
                .practiceIds(List.of())
                .build();
        assertThatCode(() -> service.request(request))
                .as("the merge is admitted at the version, state and roles it was recorded with")
                .doesNotThrowAnyException();
        var admitted = reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId())
                .orElseThrow();
        assertThat(admitted.getOrigin()).isEqualTo(ReviewOrigin.EVENT);
        assertThat(admitted.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        assertThat(admitted.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(admitted.getBaseVersion()).isEqualTo("a".repeat(40));
        assertThat(admitted.getOrderedAt()).isEqualTo(occasion.getOccurredAt());
        var member = jdbc.queryForMap(
                "SELECT state, detail->>'developerId' AS developer FROM review_item"
                        + " WHERE workspace_id=? AND review_id=?",
                workspaceId,
                admitted.getId());
        assertThat(member.get("state")).isEqualTo("LISTED");
        assertThat(member.get("developer"))
                .as("the feedback is about the person who merged, not the author or a later pusher")
                .isEqualTo(Long.toString(mergerAccount));

        var pinned = source.pinOccasion(
                prepared.item(),
                new ItemVersion(Objects.requireNonNull(occasion.getItemVersion()), null),
                occasion.getId());
        assertThat(pinned.version()).isEqualTo(prepared.version());
        assertThat(pinned.state()).containsEntry("state", "MERGED");
        assertThat(pinned.roles())
                .filteredOn(role -> role.role() == AboutRole.MERGER)
                .extracting(role -> role.identity().subject())
                .containsExactly(Long.toString(merged.merger().getNativeId()));
        assertThat(pinned.roles())
                .filteredOn(role -> role.role() == AboutRole.AUTHOR)
                .extracting(role -> role.identity().subject())
                .containsExactly(Long.toString(merged.author().getNativeId()));

        var core = PullRequestContentSource.CORE;
        var threads = ReviewThreadContentSource.KIND;
        assertOriginalMergedRecord(merged, retainedRecord, captured(pinned, admitted, occasion, core));
        var approvedBeforeMerge = captured(pinned, admitted, occasion, threads);
        var mirror = new JsonMapper();
        var staged = mirror.readTree(Objects.requireNonNull(approvedBeforeMerge.get("context/review_threads.json")));
        assertThat(staged.path("decisionHistoryComplete").isBoolean()).isTrue();
        assertThat(staged.path("decisionHistoryComplete").asBoolean()).isFalse();
        var approval = decision(staged, merged.approval().getNativeId());
        assertThat(approval.path("state").asString()).isEqualTo("APPROVED");
        assertThat(approval.has("dismissed")).isFalse();
        assertThat(approval.path("authorId").asLong())
                .isEqualTo(merged.approver().getNativeId());
        assertThat(approval.path("commitId").asString()).isEqualTo("b".repeat(40));
        assertThat(Instant.parse(approval.path("submittedAt").asString()))
                .isEqualTo(merged.approvedAt())
                .isBefore(merged.mergedAt());
        // Read at capture from the mirror; a merge occasion retains no decision of its own.
        assertThat(approval.has("observedAt")).isFalse();

        // After the merge the approval is withdrawn as each provider stores it, and the same person approves again.
        transactions.executeWithoutResult(ignored -> {
            var withdrawn = providerReviews
                    .findById(Objects.requireNonNull(merged.approval().getId()))
                    .orElseThrow();
            // GitLab's unapproval replaces the state; a GitHub dismissal keeps the original APPROVED state.
            if (type == IdentityProviderType.GITLAB) withdrawn.setState(PullRequestReview.State.DISMISSED);
            withdrawn.setDismissed(true);
            providerReviews.saveAndFlush(withdrawn);
        });
        Instant reapprovedAt = merged.mergedAt().plusSeconds(600);
        var reapproval = approve(pullId, merged.approver(), reapprovedAt, "c".repeat(40));

        assertOriginalMergedRecord(merged, retainedRecord, captured(pinned, admitted, occasion, core));
        var restaged = mirror.readTree(Objects.requireNonNull(
                captured(pinned, admitted, occasion, threads).get("context/review_threads.json")));
        assertThat(restaged.path("decisionHistoryComplete").asBoolean())
                .as("the decisions read now are no history of what stood at the merge")
                .isFalse();
        var earlier = decision(restaged, merged.approval().getNativeId());
        assertThat(earlier.path("dismissed").asBoolean()).isTrue();
        assertThat(earlier.path("state").asString())
                .isEqualTo(type == IdentityProviderType.GITLAB ? "DISMISSED" : "APPROVED");
        assertThat(Instant.parse(earlier.path("submittedAt").asString())).isEqualTo(merged.approvedAt());
        var reapproved = decision(restaged, reapproval.getNativeId());
        assertThat(reapproved.path("state").asString()).isEqualTo("APPROVED");
        assertThat(reapproved.has("dismissed")).isFalse();
        assertThat(reapproved.path("commitId").asString()).isEqualTo("c".repeat(40));
        assertThat(Instant.parse(reapproved.path("submittedAt").asString()))
                .isEqualTo(reapprovedAt)
                .isAfter(merged.mergedAt());
        // No standing approval is dated before the merge: the withdrawn one is not restored, the later one not moved.
        var decisions = restaged.path("reviewDecisions");
        for (int i = 0; i < decisions.size(); i++) {
            var decision = decisions.get(i);
            if (decision.path("state").asString().equals("APPROVED")
                    && !decision.path("dismissed").asBoolean()) {
                assertThat(Instant.parse(decision.path("submittedAt").asString()))
                        .isAfter(merged.mergedAt());
            }
        }
        // Another occasion ID names no occasion of this item, so neither the merged nor the current work is pinned.
        assertOccasionUnavailable(prepared, prepared.version(), UUID.randomUUID());
    }

    /** The merged work as its merge occasion retained it, whatever the work says now. */
    private static void assertOriginalMergedRecord(
            MergedWork merged, JsonNode retainedRecord, Map<String, byte[]> files) throws Exception {
        var record = new JsonMapper().readTree(Objects.requireNonNull(files.get("context/metadata.json")));
        assertThat(record).isEqualTo(PullRequestChecksRecord.core((ObjectNode) retainedRecord));
        assertThat(record.path("title").asString()).isEqualTo("A bounded change");
        assertThat(record.path("body").asString()).isEqualTo("Describe this change");
        assertThat(record.path("commit_sha").asString()).isEqualTo("b".repeat(40));
        assertThat(record.path("state").asString()).isEqualTo("MERGED");
        assertThat(record.path("is_merged").asBoolean()).isTrue();
        assertThat(Instant.parse(record.path("merged_at").asString())).isEqualTo(merged.mergedAt());
        assertThat(record.path("merged_by").asString())
                .isEqualTo(merged.merger().getLogin());
        assertThat(record.path("author").asString()).isEqualTo(merged.author().getLogin());
        assertThat(record.path("basis").path("other_fields").asString()).isEqualTo("OCCASION");
        assertThat(record.path("basis").has("observed_at")).isTrue();
        assertThat(record.path("basis").has("captured_at")).isFalse();
        assertThat(record.toString()).doesNotContain("A retitled change", "A rewritten description");
        assertThat(new String(
                        Objects.requireNonNull(files.get(PullRequestContentSource.DESCRIPTION_FILE)),
                        StandardCharsets.UTF_8))
                .isEqualTo("Describe this change");
    }

    /** The files one source of the admitted event review captures for its occasion. */
    private Map<String, byte[]> captured(ItemSnapshot pinned, Review admitted, ItemEvent occasion, SourceKind kind) {
        return source.capture(
                        pinned,
                        new CaptureRequest(
                                admitted.getId(),
                                UUID.randomUUID(),
                                1,
                                Set.of(kind),
                                "3.0.0",
                                3,
                                null,
                                Set.of(kind),
                                occasion.getId()))
                .files();
    }

    private static JsonNode decision(JsonNode staged, long nativeId) {
        var decisions = staged.path("reviewDecisions");
        for (int i = 0; i < decisions.size(); i++) {
            if (decisions.get(i).path("nativeId").asLong() == nativeId) return decisions.get(i);
        }
        throw new AssertionError("No captured decision " + nativeId + " in " + staged);
    }

    private void assertOccasionUnavailable(Prepared prepared, ItemVersion version, UUID occasion) {
        assertThatThrownBy(() -> source.pinOccasion(prepared.item(), version, occasion))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.OCCASION_EVIDENCE_UNAVAILABLE));
    }

    private String unretained(UUID occasion) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT evidence->>'unretained' FROM item_event WHERE id=?", String.class, occasion));
    }

    @Test
    void shouldKeepTheFirstRecordedStateAndRolesWhenTheOccasionIsRedeliveredAfterTheyChange() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var original = source.pin(prepared.item(), null);
        publish(prepared);
        var occasion = event(prepared);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow();
        var assignee =
                users.saveAndFlush(TestUserFactory.createUser(nativeId(), "assignee-" + UUID.randomUUID(), provider));
        // Neither change is part of the version, so the redelivered occasion has the same one.
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            pull.setDraft(true);
            pull.getAssignees().add(users.findById(assignee.getId()).orElseThrow());
            pulls.saveAndFlush(pull);
        });
        var current = source.pin(prepared.item(), null);
        assertThat(current.version()).isEqualTo(prepared.version());
        assertThat(current.state()).isNotEqualTo(original.state());
        assertThat(current.roles()).extracting(role -> role.role()).contains(AboutRole.ASSIGNEE);
        publish(prepared);

        assertThat(event(prepared).getId()).isEqualTo(occasion.getId());
        var retained = source.pinOccasion(prepared.item(), prepared.version(), occasion.getId());
        assertThat(retained.state()).isEqualTo(original.state());
        assertThat(retained.roles()).isEqualTo(original.roles());

        // Past its expiry the record is gone, and the current work at the very same version never takes its place.
        jdbc.update(
                "UPDATE item_event SET evidence_expires_at = now() - interval '1 minute' WHERE id = ?",
                occasion.getId());
        assertOccasionUnavailable(prepared, prepared.version(), occasion.getId());
        assertThat(source.pin(prepared.item(), prepared.version()).state()).isEqualTo(current.state());
    }

    @Test
    void shouldStartNoSecondReviewWhenAReadyDeliveryIsReplayedAfterASynchronize() {
        var prepared = prepare(
                IdentityProviderType.GITHUB,
                false,
                Map.of(),
                false,
                List.of(ScmItemEvents.PULL_REQUEST_READY, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        var ready = pullRequestPayload(prepared, "b".repeat(40), Instant.now().plusSeconds(60));
        var synchronize =
                pullRequestPayload(prepared, "c".repeat(40), Instant.now().plusSeconds(120));
        deliver(prepared, "ready_for_review", ready);
        deliver(prepared, "synchronize", synchronize);
        var occasions = itemEvents(prepared);
        var admitted = itemReviews(prepared).stream().map(Review::getId).toList();
        // A push also records the edit of its head. The first occasion's review is still queued and measures the
        // practice, so the later occasion waits for it instead of starting a second review.
        assertThat(occasions)
                .extracting(ItemEvent::getName)
                .containsOnlyOnce(
                        ScmItemEvents.PULL_REQUEST_READY.value(), ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(admitted).hasSize(1);
        assertThat(occasions)
                .filteredOn(occasion -> occasion.getState() == ItemEventState.WAITING)
                .extracting(ItemEvent::getReason)
                .containsOnly(ReviewRequestReason.REVIEW_IN_PROGRESS);
        long jobsBefore = jobs(prepared);

        // The provider delivers both again after the push: the ready payload still names the first head.
        deliver(prepared, "ready_for_review", ready);
        deliver(prepared, "synchronize", synchronize);

        assertThat(itemEvents(prepared))
                .extracting(ItemEvent::getId)
                .containsExactlyInAnyOrderElementsOf(
                        occasions.stream().map(ItemEvent::getId).toList());
        assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactlyInAnyOrderElementsOf(admitted);
        assertThat(jobs(prepared)).isEqualTo(jobsBefore);
        assertThat(occasions)
                .filteredOn(occasion -> occasion.getName().equals(ScmItemEvents.PULL_REQUEST_READY.value()))
                .singleElement()
                .extracting(ItemEvent::getItemVersion)
                .isEqualTo(prepared.version().value());
    }

    @Test
    void shouldHoldALaterOccasionWhileTheEarlierReviewOnlyStagedThePractice() {
        var prepared = prepare(
                IdentityProviderType.GITHUB,
                false,
                Map.of(),
                false,
                List.of(ScmItemEvents.PULL_REQUEST_READY, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        deliver(
                prepared,
                "ready_for_review",
                pullRequestPayload(prepared, "b".repeat(40), Instant.now().plusSeconds(60)));
        var admitted = itemReviews(prepared).stream().map(Review::getId).toList();
        assertThat(admitted).hasSize(1);
        // The earlier review measured the practice and staged its result, but has not sealed it.
        jdbc.update("UPDATE review_item SET state='STAGED', staged_attempt=0 WHERE review_id=?", admitted.getFirst());
        long jobsBefore = jobs(prepared);

        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, "c".repeat(40), Instant.now().plusSeconds(120)));

        assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactlyElementsOf(admitted);
        assertThat(jobs(prepared)).isEqualTo(jobsBefore);
        assertThat(itemEvents(prepared))
                .filteredOn(occasion -> occasion.getState() == ItemEventState.WAITING)
                .extracting(ItemEvent::getReason)
                .containsOnly(ReviewRequestReason.REVIEW_IN_PROGRESS);
    }

    @Test
    void shouldReadAnEventReviewsSourcesWithNoTransactionWhileTheWorkspaceIsUnlocked() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var fetches = observeFetches(prepared.item());

        publish(prepared);

        assertThat(reviews.findByWorkspaceIdAndEventId(
                        prepared.item().workspaceId(), event(prepared).getId()))
                .isPresent();
        assertThat(fetches).isNotEmpty().containsOnly(Fetch.FREE);
    }

    @Test
    void shouldEndAStaleResultAfterReadingItsSourcesWithNoTransaction() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var item = prepared.item();
        var at = Instant.now().minusSeconds(30);
        var current = resultData.result(
                authorScenario(prepared),
                ResultOutcome.MET,
                at,
                at,
                prepared.version().value());
        recordSources(
                item, current.getReviewId(), current.getPracticeId(), Set.of(new SourceKind("scm.pull-request.core")));
        var fetches = observeFetches(item);

        transactions.executeWithoutResult(ignored -> {
            var changed =
                    pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
            changed.setBody("The work changed after this capture.");
            pulls.saveAndFlush(changed);
            publisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(changed), Set.of("body"), context(prepared)));
        });

        assertThat(resultRows.findById(current.getId()).orElseThrow().getEndReason())
                .isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(fetches).isNotEmpty().containsOnly(Fetch.FREE);
    }

    @Test
    void shouldWithholdAnOccasionWhenReviewsFromWorkActivityAreTurnedOffWhileItsReviewReadsItsSources()
            throws Exception {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        long workspaceId = prepared.item().workspaceId();
        var read = blockFirstFingerprintRead(prepared.item());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var published = executor.submit(() -> publish(prepared));
            awaitLatch(read.reading());
            var workspace = workspaces.findById(workspaceId).orElseThrow();
            workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
            workspaces.saveAndFlush(workspace);
            read.release().countDown();
            published.get(30, TimeUnit.SECONDS);

            assertThat(read.seen()).hasValue(Fetch.FREE);
            var occasion = event(prepared);
            assertThat(occasion.getState()).isEqualTo(ItemEventState.WITHHELD);
            assertThat(occasion.getReason()).isEqualTo(ReviewRequestReason.GATE_WITHHELD);
            assertThat(reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId()))
                    .isEmpty();
        } finally {
            read.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStartOneReviewWhenAnOccasionIsOfferedAgainWhileItsReviewReadsItsSources() throws Exception {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var item = prepared.item();
        var read = blockFirstFingerprintRead(item);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var published = executor.submit(() -> publish(prepared));
            awaitLatch(read.reading());
            var occasion = event(prepared);
            transactions.executeWithoutResult(ignored ->
                    publisher.publishEvent(new ItemEventRecorded(item.workspaceId(), occasion.getId(), item.itemId())));
            var started = reviews.findByWorkspaceIdAndEventId(item.workspaceId(), occasion.getId())
                    .orElseThrow();
            read.release().countDown();
            published.get(30, TimeUnit.SECONDS);

            assertThat(read.seen()).hasValue(Fetch.FREE);
            assertThat(events.findById(occasion.getId()).orElseThrow().getState())
                    .isEqualTo(ItemEventState.STARTED);
            assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactly(started.getId());
            // The overtaken offer finds the occasion decided and completes, so it is not sent again.
            assertThat(jdbc.queryForList(
                            "SELECT status FROM event_publication WHERE event_type=?"
                                    + " AND serialized_event::jsonb->>'eventId'=? AND completion_date IS NULL",
                            String.class,
                            ItemEventRecorded.class.getName(),
                            occasion.getId().toString()))
                    .isEmpty();
        } finally {
            read.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRefuseAMembersRequestThatAnotherRequestOvertookWhileItReadItsSources() throws Exception {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var item = prepared.item();
        long account = Objects.requireNonNull(authorScenario(prepared).account().getId());
        var read = blockFirstFingerprintRead(item);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> requests.request(item.workspaceId(), item.itemId(), account));
            awaitLatch(read.reading());
            var second = requests.request(item.workspaceId(), item.itemId(), account);
            read.release().countDown();
            var overtaken = first.get(30, TimeUnit.SECONDS);

            assertThat(read.seen()).hasValue(Fetch.FREE);
            assertThat(second.status()).isEqualTo(ReviewRequestStatus.SUBMITTED);
            assertThat(overtaken.status()).isEqualTo(ReviewRequestStatus.REFUSED);
            assertThat(overtaken.reason()).isEqualTo(ReviewRequestReason.CONCURRENT_DUPLICATE);
            assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactly(second.reviewId());
        } finally {
            read.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldQueueAPracticeWhoseCurrentResultEndsWhileTheEventReviewReadsItsSources() throws Exception {
        var prepared = prepare(
                IdentityProviderType.GITHUB,
                false,
                Map.of(),
                false,
                List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED),
                ReadNeed.OPTIONAL);
        var item = prepared.item();
        corePractice(prepared, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED);
        var pushed = Objects.requireNonNull(transactions.execute(ignored -> {
            var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
            pull.setHeadRefOid("c".repeat(40));
            return ScmEventPayload.PullRequestData.from(pulls.saveAndFlush(pull));
        }));
        var version = source.pin(item, null).version();
        // The practice that also reads the checks has a current result at the pushed version.
        var at = Instant.now().minusSeconds(30);
        var current = resultData.result(authorScenario(prepared), ResultOutcome.MET, at, at, version.value());
        recordSources(
                item, current.getReviewId(), current.getPracticeId(), Set.of(new SourceKind("scm.pull-request.core")));
        var read = blockFingerprintRead(item, kinds -> kinds.contains(PullRequestChecksRecord.KIND));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var published = executor.submit(() -> transactions.executeWithoutResult(ignored ->
                    publisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(pushed, context(prepared)))));
            awaitLatch(read.reading());
            jdbc.update(
                    "UPDATE result SET ended_at = now(), end_reason = 'WORK_CHANGED' WHERE id = ?", current.getId());
            read.release().countDown();
            published.get(30, TimeUnit.SECONDS);

            var occasion = events.findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                            item.workspaceId(),
                            item.itemId(),
                            ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value(),
                            version.value())
                    .orElseThrow();
            assertThat(occasion.getState()).isEqualTo(ItemEventState.STARTED);
            var review = reviews.findByWorkspaceIdAndEventId(item.workspaceId(), occasion.getId())
                    .orElseThrow();
            assertThat(memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                            item.workspaceId(), review.getId()))
                    .filteredOn(member -> member.getPracticeId() == current.getPracticeId())
                    .singleElement()
                    .extracting(ReviewItem::getState)
                    .isEqualTo(ReviewItemState.LISTED);
        } finally {
            read.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStartACommentRequestedReviewWhenTheChecksSettleWhileItReadsItsSources() throws Exception {
        var prepared = prepare(IdentityProviderType.GITHUB, false, Map.of(), false, List.of(), ReadNeed.REQUIRED);
        var item = prepared.item();
        corePractice(prepared, ScmItemEvents.PULL_REQUEST_OPENED);
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        long repositoryId = Objects.requireNonNull(pull.getRepository()).getId();
        var context = Objects.requireNonNull(transactions.execute(status -> ProcessingContext.forWebhook(
                item.workspaceId(), repositories.findById(repositoryId).orElseThrow(), "completed")));
        String head = Objects.requireNonNull(pull.getHeadRefOid());
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        checkRuns.observe(
                repositoryId,
                new HeadCheckRuns(head, List.of(checkRun("build", "in_progress", null)), true, readAt, null),
                context);
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        var commenter = new PersonIdentity(providerId(author), Long.toString(author.getNativeId()), null);
        var read = blockFirstFingerprintRead(item);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var requested = executor.submit(() ->
                    requests.requestByComment(item.workspaceId(), item.itemId(), commenter, UUID.randomUUID(), null));
            awaitLatch(read.reading());
            checkRuns.observe(
                    repositoryId,
                    settled(
                            head,
                            List.of(checkRun("build", "success", readAt.plusSeconds(60))),
                            readAt.plusSeconds(60)),
                    context);
            read.release().countDown();
            var outcome = requested.get(30, TimeUnit.SECONDS);

            assertThat(outcome.status()).isEqualTo(ReviewRequestStatus.SUBMITTED);
            long readsChecks = practices
                    .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                    .orElseThrow()
                    .getId();
            assertThat(memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(
                            item.workspaceId(), Objects.requireNonNull(outcome.reviewId())))
                    .filteredOn(member -> member.getPracticeId() == readsChecks)
                    .singleElement()
                    .extracting(ReviewItem::getState)
                    .isEqualTo(ReviewItemState.LISTED);
        } finally {
            read.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldStartAnOccasionOnceWhenItIsOfferedAgainAfterItsReviewWasRecordedButNotSettled() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var item = prepared.item();
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(false);
        publish(prepared);
        var occasion = event(prepared);
        assertThat(occasion.getState()).isEqualTo(ItemEventState.WAITING);
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        // The edge recorded its review, and then its settle step failed: the occasion still waits.
        var recorded = service.request(ReviewRequest.builder()
                .workspaceId(item.workspaceId())
                .itemId(item.itemId())
                .kind(ReviewKind.ITEM)
                .version(new ItemVersion(Objects.requireNonNull(occasion.getItemVersion()), null))
                .origin(ReviewOrigin.EVENT)
                .eventId(occasion.getId())
                .practiceIds(List.of())
                .occasions(Set.of(prepared.name()))
                .build());
        assertThat(events.findById(occasion.getId()).orElseThrow().getState()).isEqualTo(ItemEventState.WAITING);

        transactions.executeWithoutResult(ignored ->
                publisher.publishEvent(new ItemEventRecorded(item.workspaceId(), occasion.getId(), item.itemId())));

        assertThat(events.findById(occasion.getId()).orElseThrow().getState()).isEqualTo(ItemEventState.STARTED);
        assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactly(recorded);
    }

    /** A second practice of the prepared work: about nobody, selected on {@code on}, reading only the core record. */
    private void corePractice(Prepared prepared, ItemEventName on) {
        var original =
                DefinitionTestFixtures.definition(ScmItemEvents.PULL_REQUEST, "Name the change", "Name it.", null);
        var definition = new PracticeDefinition(
                original.name(),
                new ReviewOn(List.of(on), Map.of()),
                original.appliesWhen(),
                null,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
        versions.create(new PracticeVersionService.NewPractice(
                prepared.item().workspaceId(), "core-only", ScmItemEvents.PULL_REQUEST, definition, null, 1, null));
    }

    /** Whether a provider read ran in a transaction, and whether the workspace row was locked at it. */
    private record Fetch(boolean transaction, boolean workspaceLocked) {
        static final Fetch FREE = new Fetch(false, false);
    }

    /** Records each source read and occasion pin of the item, which can fetch from the provider. */
    private List<Fetch> observeFetches(ItemRef item) {
        var fetches = new CopyOnWriteArrayList<Fetch>();
        doAnswer(call -> {
                    fetches.add(fetch(item.workspaceId()));
                    return call.callRealMethod();
                })
                .when(productItems)
                .fingerprints(argThat(ref -> ref != null && ref.itemId() == item.itemId()), any(), any());
        doAnswer(call -> {
                    fetches.add(fetch(item.workspaceId()));
                    return call.callRealMethod();
                })
                .when(productItems)
                .pinOccasion(argThat(ref -> ref != null && ref.itemId() == item.itemId()), any(), any());
        return fetches;
    }

    private record BlockedRead(CountDownLatch reading, CountDownLatch release, AtomicReference<Fetch> seen) {}

    /** The first source read of the item records its fetch and waits for {@code release}; later reads run at once. */
    private BlockedRead blockFirstFingerprintRead(ItemRef item) {
        return blockFingerprintRead(item, kinds -> true);
    }

    /** The first source read of the item for matching sources records its fetch and waits for {@code release}. */
    private BlockedRead blockFingerprintRead(ItemRef item, Predicate<Set<SourceKind>> sources) {
        var read = new BlockedRead(new CountDownLatch(1), new CountDownLatch(1), new AtomicReference<>());
        var calls = new AtomicInteger();
        doAnswer(call -> {
                    Set<SourceKind> kinds = call.getArgument(1);
                    if (sources.test(kinds) && calls.getAndIncrement() == 0) {
                        read.seen().set(fetch(item.workspaceId()));
                        read.reading().countDown();
                        awaitLatch(read.release());
                    }
                    return call.callRealMethod();
                })
                .when(productItems)
                .fingerprints(argThat(ref -> ref != null && ref.itemId() == item.itemId()), any(), any());
        return read;
    }

    private Fetch fetch(long workspaceId) {
        var other = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        boolean locked;
        try {
            other.executeWithoutResult(ignored -> jdbc.queryForObject(
                    "SELECT id FROM workspace WHERE id = ? FOR UPDATE NOWAIT", Long.class, workspaceId));
            locked = false;
        } catch (PessimisticLockingFailureException held) {
            locked = true;
        }
        return new Fetch(TransactionSynchronizationManager.isActualTransactionActive(), locked);
    }

    /** The author of the prepared pull request, whose practice the fixture reviews. */
    private ReviewIntegrationData.Scenario authorScenario(Prepared prepared) {
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        return new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(author)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
    }

    @Test
    void shouldCarryWhatAReplacedQueuedReviewSelectedIntoTheReviewOfTheNewerHead() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        long everyPush = practices
                .findByWorkspaceIdAndSlug(prepared.item().workspaceId(), "event-test")
                .orElseThrow()
                .getId();
        long onlyWhenOpened = practiceOnlyWhenOpened(prepared);
        long workspace = prepared.item().workspaceId();
        publish(prepared);
        var stale = itemReviews(prepared).getFirst();
        assertThat(membersOf(workspace, stale.getId())).containsOnlyKeys(everyPush, onlyWhenOpened);

        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, "c".repeat(40), Instant.now().plusSeconds(60)));
        var pushed = occasion(prepared, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED);
        assertThat(pushed.getReason()).isEqualTo(ReviewRequestReason.REVIEW_IN_PROGRESS);

        assertThat(replaced(workspace, stale.getId())).isTrue();

        var replaced = reviews.findById(stale.getId()).orElseThrow();
        assertThat(replaced.getStatus()).isEqualTo(ReviewStatus.CANCELLED);
        assertThat(replaced.getStopReason()).isEqualTo(ReviewStopReason.REPLACED);
        assertThat(replaced.getCompletedAt()).isNotNull();
        assertThat(membersOf(workspace, stale.getId()).values()).allSatisfy(member -> {
            assertThat(member.getState()).isEqualTo(ReviewItemState.WITHHELD);
            assertThat(member.getReason()).isEqualTo("REPLACED");
        });
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT status||'/'||reason FROM review_practice WHERE review_id=?",
                        String.class,
                        stale.getId()))
                .containsExactly("WITHHELD/REPLACED");

        closeWindows(prepared);

        // The push alone selects one practice. The review of its head also runs what the replaced one still owed.
        var next = reviews
                .findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                        workspace, prepared.item().itemId())
                .stream()
                .filter(review -> !review.getId().equals(stale.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(next.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        assertThat(next.getItemVersion()).isEqualTo(pushed.getItemVersion()).isNotEqualTo(stale.getItemVersion());
        assertThat(membersOf(workspace, next.getId())).containsOnlyKeys(everyPush, onlyWhenOpened);
        assertThat(membersOf(workspace, next.getId()).values())
                .allSatisfy(member -> assertThat(member.getState()).isEqualTo(ReviewItemState.LISTED));

        // Only a replaced review carries its practices: the next review's own memberships now decide.
        jdbc.update(
                "UPDATE review SET status='CANCELLED', stop_reason='CANCELLED', completed_at=now() WHERE id=?",
                next.getId());
        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, "d".repeat(40), Instant.now().plusSeconds(120)));
        closeWindows(prepared);
        var after = reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                        workspace, prepared.item().itemId())
                .getFirst();
        assertThat(after.getId()).isNotIn(stale.getId(), next.getId());
        assertThat(membersOf(workspace, after.getId())).containsOnlyKeys(everyPush);
    }

    @Test
    void shouldMeasureAPracticeRefusedAtStartOnTheNextEdgeThatDoesNotSelectIt() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        long everyPush = practices
                .findByWorkspaceIdAndSlug(prepared.item().workspaceId(), "event-test")
                .orElseThrow()
                .getId();
        long onlyWhenOpened = practiceOnlyWhenOpened(prepared);
        long workspace = prepared.item().workspaceId();
        publish(prepared);
        var refused = itemReviews(prepared).getFirst();
        assertThat(membersOf(workspace, refused.getId())).containsOnlyKeys(everyPush, onlyWhenOpened);
        // Practice start could not verify the sources, as when the mirror sync failed, and the review ended.
        jdbc.update(
                "UPDATE review_item SET state='WITHHELD', reason='READINESS_REFUSED', attempted_at=now()"
                        + " WHERE review_id=? AND practice_id=?",
                refused.getId(),
                onlyWhenOpened);
        jdbc.update(
                "UPDATE review_item SET state='WITHHELD', reason='EXECUTION_INCOMPLETE', attempted_at=now()"
                        + " WHERE review_id=? AND practice_id=?",
                refused.getId(),
                everyPush);
        jdbc.update(
                "UPDATE review SET status='PARTIAL', stop_reason='READINESS_REFUSED', completed_at=now() WHERE id=?",
                refused.getId());

        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, "c".repeat(40), Instant.now().plusSeconds(60)));
        closeWindows(prepared);

        // The push alone selects one practice; the review of its head also measures the one refused at start.
        var next = reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                        workspace, prepared.item().itemId())
                .getFirst();
        assertThat(next.getId()).isNotEqualTo(refused.getId());
        assertThat(membersOf(workspace, next.getId())).containsOnlyKeys(everyPush, onlyWhenOpened);
    }

    @Test
    void shouldLeaveAQueuedReviewWhenNoNewerOccasionMovedTheWork() {
        var prepared = prepare(
                IdentityProviderType.GITHUB,
                false,
                Map.of(),
                false,
                List.of(ScmItemEvents.PULL_REQUEST_CHECKS_COMPLETED));
        long workspace = prepared.item().workspaceId();
        publish(prepared);
        var queued = itemReviews(prepared).getFirst();

        assertThat(replaced(workspace, queued.getId())).isFalse();

        // An occasion at the same version leaves the review's input as it was.
        settleChecks(prepared);
        assertThat(itemEvents.open(workspace, prepared.item().itemId()))
                .singleElement()
                .satisfies(open -> assertThat(open.itemVersion()).isEqualTo(queued.getItemVersion()));
        assertThat(replaced(workspace, queued.getId())).isFalse();
        assertThat(reviews.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.QUEUED);
    }

    @Test
    void shouldLeaveAPersonsRequestedReviewWhenTheWorkMoves() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        long workspace = prepared.item().workspaceId();
        var requested = service.request(ReviewRequest.builder()
                .workspaceId(workspace)
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(prepared.version())
                .origin(ReviewOrigin.REQUEST)
                .requestedById(memberAccount(workspace))
                .practiceIds(List.of())
                .build());
        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, "c".repeat(40), Instant.now().plusSeconds(60)));
        assertThat(occasion(prepared, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED).getReason())
                .isEqualTo(ReviewRequestReason.REVIEW_IN_PROGRESS);

        assertThat(replaced(workspace, requested)).isFalse();
        assertThat(reviews.findById(requested).orElseThrow().getStatus()).isEqualTo(ReviewStatus.QUEUED);
    }

    private boolean replaced(long workspace, UUID review) {
        return Boolean.TRUE.equals(transactions.execute(ignored -> starts.replaceByNewerOccasion(workspace, review)));
    }

    private long memberAccount(long workspace) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT account_id FROM membership WHERE workspace_id=? LIMIT 1", Long.class, workspace));
    }

    private Map<Long, ReviewItem> membersOf(long workspace, UUID review) {
        return memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspace, review).stream()
                .collect(Collectors.toMap(ReviewItem::getPracticeId, member -> member));
    }

    /** A practice that an opened pull request selects and a push does not. */
    private long practiceOnlyWhenOpened(Prepared prepared) {
        long workspace = prepared.item().workspaceId();
        var original = DefinitionTestFixtures.definition(
                ScmItemEvents.PULL_REQUEST, "Describe the work", "Explain the work.", null);
        var definition = new PracticeDefinition(
                original.name(),
                new ReviewOn(List.of(ScmItemEvents.PULL_REQUEST_OPENED), Map.of()),
                original.appliesWhen(),
                AboutRole.AUTHOR,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
        versions.create(new PracticeVersionService.NewPractice(
                workspaces.findById(workspace).orElseThrow().getId(),
                "only-when-opened",
                ScmItemEvents.PULL_REQUEST,
                definition,
                null,
                0,
                memberAccount(workspace)));
        return practices
                .findByWorkspaceIdAndSlug(workspace, "only-when-opened")
                .orElseThrow()
                .getId();
    }

    @Test
    void shouldReviewALateFirstReadyDeliveryOnceWhenAPushWasStoredBeforeIt() {
        var prepared = prepare(
                IdentityProviderType.GITHUB,
                false,
                Map.of(),
                false,
                List.of(ScmItemEvents.PULL_REQUEST_READY, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        var ready = pullRequestPayload(prepared, "b".repeat(40), Instant.now().plusSeconds(60));
        var synchronize =
                pullRequestPayload(prepared, "c".repeat(40), Instant.now().plusSeconds(120));

        // The ready delivery failed in a broker stall; the push after it was stored first.
        deliver(prepared, "synchronize", synchronize);
        deliver(prepared, "ready_for_review", ready);
        var occasions = itemEvents(prepared);
        var admitted = itemReviews(prepared).stream().map(Review::getId).toList();
        // A push also records the edit of its head. The first occasion's review is still queued and measures the
        // practice, so the later occasion waits for it instead of starting a second review.
        assertThat(occasions)
                .extracting(ItemEvent::getName)
                .containsOnlyOnce(
                        ScmItemEvents.PULL_REQUEST_READY.value(), ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(admitted).hasSize(1);
        assertThat(occasions)
                .filteredOn(occasion -> occasion.getState() == ItemEventState.WAITING)
                .extracting(ItemEvent::getReason)
                .containsOnly(ReviewRequestReason.REVIEW_IN_PROGRESS);
        long jobsBefore = jobs(prepared);

        deliver(prepared, "ready_for_review", ready);

        assertThat(itemEvents(prepared))
                .extracting(ItemEvent::getId)
                .containsExactlyInAnyOrderElementsOf(
                        occasions.stream().map(ItemEvent::getId).toList());
        assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactlyInAnyOrderElementsOf(admitted);
        assertThat(jobs(prepared)).isEqualTo(jobsBefore);
        // The late ready names the work as it is now, the push's head.
        assertThat(occasions)
                .filteredOn(occasion -> occasion.getName().equals(ScmItemEvents.PULL_REQUEST_READY.value()))
                .singleElement()
                .extracting(ItemEvent::getItemVersion)
                .isEqualTo(occasions.stream()
                        .filter(occasion -> occasion.getName().equals(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value()))
                        .findFirst()
                        .orElseThrow()
                        .getItemVersion());
    }

    @Test
    void shouldRecordAPushBackToAnEarlierHeadButNotItsReplay() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        String first = "b".repeat(40);
        String second = "c".repeat(40);
        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, first, Instant.now().plusSeconds(60)),
                null);
        deliver(
                prepared,
                "synchronize",
                pullRequestPayload(prepared, second, Instant.now().plusSeconds(120)),
                first);

        // The developer restores the first head after an edit; the work differs from the first push.
        var restored = pullRequestPayload(prepared, first, Instant.now().plusSeconds(180), "Restored title");
        deliver(prepared, "synchronize", restored, second);
        var pushes = synchronizedOccasions(prepared);
        deliver(prepared, "synchronize", restored, second);

        assertThat(pushes).hasSize(3);
        assertThat(pushes).extracting(ItemEvent::getItemVersion).doesNotHaveDuplicates();
        assertThat(synchronizedOccasions(prepared))
                .extracting(ItemEvent::getId)
                .containsExactlyInAnyOrderElementsOf(
                        pushes.stream().map(ItemEvent::getId).toList());
    }

    @Test
    void shouldApplyTwoPushesOfOneSecondInOrder() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        String start = storedHead(prepared);
        Instant second = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);

        deliver(prepared, "synchronize", pullRequestPayload(prepared, "b".repeat(40), second), start);
        deliver(prepared, "synchronize", pullRequestPayload(prepared, "c".repeat(40), second), "b".repeat(40));

        assertThat(storedHead(prepared)).isEqualTo("c".repeat(40));
        assertThat(synchronizedOccasions(prepared)).hasSize(2);
    }

    @Test
    void shouldKeepTheLaterHeadWhenTheEarlierPushOfOneSecondArrivesLast() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        String start = storedHead(prepared);
        Instant second = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);

        deliver(prepared, "synchronize", pullRequestPayload(prepared, "c".repeat(40), second), "b".repeat(40));
        deliver(prepared, "synchronize", pullRequestPayload(prepared, "b".repeat(40), second), start);

        assertThat(storedHead(prepared)).isEqualTo("c".repeat(40));
        assertThat(synchronizedOccasions(prepared)).hasSize(1);
    }

    private String storedHead(Prepared prepared) {
        var pull = Objects.requireNonNull(transactions.execute(
                ignored -> pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                        .orElseThrow()));
        return Objects.requireNonNull(pull.getHeadRefOid());
    }

    private List<ItemEvent> synchronizedOccasions(Prepared prepared) {
        return itemEvents(prepared).stream()
                .filter(occasion -> occasion.getName().equals(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value()))
                .toList();
    }

    private long jobs(Prepared prepared) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM review_job j JOIN review r ON r.id = j.review_id"
                        + " WHERE r.workspace_id = ? AND r.item_id = ?",
                Long.class,
                prepared.item().workspaceId(),
                prepared.item().itemId()));
    }

    private GitHubPullRequestDTO pullRequestPayload(Prepared prepared, String head, Instant updatedAt) {
        return pullRequestPayload(prepared, head, updatedAt, null);
    }

    private GitHubPullRequestDTO pullRequestPayload(
            Prepared prepared, String head, Instant updatedAt, @Nullable String title) {
        var pull = Objects.requireNonNull(transactions.execute(
                ignored -> pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                        .orElseThrow()));
        var author = Objects.requireNonNull(transactions.execute(ignored ->
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow()));
        return new JsonMapper()
                .readValue(
                        """
                        {"id": %d, "node_id": "PR_node", "number": %d, "title": "%s", "body": "%s",
                         "state": "open", "html_url": "%s", "created_at": "%s", "updated_at": "%s",
                         "draft": false, "merged": false, "locked": false, "commits": 1, "additions": 0,
                         "deletions": 0, "changed_files": 0, "comments": 0, "review_comments": 0,
                         "maintainerCanModify": false,
                         "user": {"id": %d, "login": "%s", "type": "User"},
                         "head": {"ref": "topic", "sha": "%s"}, "base": {"ref": "main", "sha": "%s"}}
                        """.formatted(
                                        pull.getNativeId(),
                                        pull.getNumber(),
                                        title != null ? title : pull.getTitle(),
                                        pull.getBody(),
                                        pull.getHtmlUrl(),
                                        pull.getCreatedAt(),
                                        updatedAt,
                                        author.getNativeId(),
                                        author.getLogin(),
                                        head,
                                        pull.getBaseRefOid()),
                        GitHubPullRequestDTO.class);
    }

    /**
     * An item row is locked with {@code FOR NO KEY UPDATE}. Admission inserts review rows whose foreign keys take
     * {@code FOR KEY SHARE} on the item, so a stronger lock on the item would block admission.
     */
    @Test
    void shouldLockTheItemRowWithoutBlockingAForeignKeyToIt() throws Exception {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            Future<?> locked = holder.submit(() -> transactions.executeWithoutResult(ignored -> {
                writer.lock(prepared.item().workspaceId(), prepared.item().itemId());
                held.countDown();
                awaitLatch(release);
            }));
            awaitLatch(held);

            Long shared = transactions.execute(ignored -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                return jdbc.queryForObject(
                        "SELECT id FROM item WHERE id = ? FOR KEY SHARE",
                        Long.class,
                        prepared.item().itemId());
            });

            assertThat(shared).isEqualTo(prepared.item().itemId());
            release.countDown();
            locked.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
    }

    /**
     * Pushes for several pull requests compete for admission in one workspace. Every occasion is decided, and each
     * pull request gets one measuring review. Its further occasions wait for that review.
     */
    @Test
    void shouldDecideEveryOccasionOfABurstOnSeveralItemsWithOneReviewEach() throws Exception {
        var first = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        var items = new ArrayList<>(List.of(first));
        for (int number = 4; number < 7; number++) items.add(anotherPullRequest(first, number));

        burst(items, 3);

        for (var item : items) {
            assertThat(synchronizations(item))
                    .hasSize(3)
                    .noneMatch(occasion -> occasion.getState() == ItemEventState.RECORDED);
            assertThat(measuringReviews(item)).hasSize(1);
        }
    }

    /**
     * A person asks for a review of the pull request while its pushes are decided. Admission serializes both on the
     * workspace row: every occasion is decided, and the pull request has one review that measures the practice.
     */
    @Test
    void shouldKeepOneMeasuringReviewWhenAPersonRequestsOneDuringABurst() throws Exception {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        long workspace = prepared.item().workspaceId();
        long requester = memberAccount(workspace);
        var executor =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            Future<?> manual = executor.submit(() -> {
                try {
                    service.request(ReviewRequest.builder()
                            .workspaceId(workspace)
                            .itemId(prepared.item().itemId())
                            .kind(ReviewKind.ITEM)
                            .version(prepared.version())
                            .origin(ReviewOrigin.REQUEST)
                            .requestedById(requester)
                            .practiceIds(List.of())
                            .build());
                } catch (ReviewRequestRejected refused) {
                    // A push admitted its review first, or moved the work past the requested version.
                }
                return null;
            });

            burst(List.of(prepared), 3);
            manual.get(20, TimeUnit.SECONDS);

            assertThat(synchronizations(prepared))
                    .hasSize(3)
                    .noneMatch(occasion -> occasion.getState() == ItemEventState.RECORDED);
            assertThat(measuringReviews(prepared)).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * A request pins the work with no lock. A push that lands before the request takes the workspace lock starts its own
     * review of the newer version. A member's request then meets its policy (a review of the item already measures); a
     * batch request finds its version replaced. Either records nothing.
     */
    @Test
    void shouldRefuseAMembersRequestWhoseItemAPushStartedReviewingWhileItReadTheWork() throws Exception {
        var prepared = preparedForReplacedVersion();
        long requester = memberAccount(prepared.item().workspaceId());

        assertThat(requestWhileAPushLands(
                        prepared, request(prepared).origin(ReviewOrigin.REQUEST).requestedById(requester)))
                .isInstanceOfSatisfying(
                        ReviewRequestRefused.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(ReviewRequestReason.CONCURRENT_DUPLICATE));
        assertThat(measuringReviews(prepared))
                .singleElement()
                .satisfies(review -> assertThat(review.getOrigin()).isEqualTo(ReviewOrigin.EVENT));
    }

    @Test
    void shouldRefuseABatchRequestWhoseVersionAPushReplacedWhileItReadTheWork() throws Exception {
        var prepared = preparedForReplacedVersion();

        assertThat(requestWhileAPushLands(
                        prepared,
                        request(prepared).origin(ReviewOrigin.BACKFILL).batchId(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        ReviewRequestRejected.class,
                        refusal ->
                                assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.VERSION_CHANGED));
        assertThat(measuringReviews(prepared))
                .singleElement()
                .satisfies(review -> assertThat(review.getOrigin()).isEqualTo(ReviewOrigin.EVENT));
    }

    private Prepared preparedForReplacedVersion() {
        return prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
    }

    private static ReviewRequest.ReviewRequestBuilder request(Prepared prepared) {
        return ReviewRequest.builder()
                .workspaceId(prepared.item().workspaceId())
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(prepared.version())
                .practiceIds(List.of());
    }

    /** Holds the request right after its pin, lands a push and its event review, then lets the request continue. */
    private Throwable requestWhileAPushLands(Prepared prepared, ReviewRequest.ReviewRequestBuilder request)
            throws Exception {
        var pinned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    Object snapshot = call.callRealMethod();
                    pinned.countDown();
                    awaitLatch(release);
                    return snapshot;
                })
                .when(productItems)
                .pin(eq(prepared.item()), eq(prepared.version()));
        var executor =
                Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            Future<UUID> requested = executor.submit(() -> service.request(request.build()));
            awaitLatch(pinned);
            deliver(
                    prepared,
                    "synchronize",
                    pullRequestPayload(prepared, "d".repeat(40), Instant.now().plusSeconds(60)));
            release.countDown();
            var failure = catchThrowable(() -> requested.get(20, TimeUnit.SECONDS));
            assertThat(failure).isInstanceOf(ExecutionException.class);
            return Objects.requireNonNull(failure.getCause());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private List<ItemEvent> synchronizations(Prepared prepared) {
        return itemEvents(prepared).stream()
                .filter(occasion -> occasion.getName().equals(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value()))
                .toList();
    }

    private List<Review> measuringReviews(Prepared prepared) {
        return itemReviews(prepared).stream()
                .filter(review ->
                        review.getStatus() == ReviewStatus.QUEUED || review.getStatus() == ReviewStatus.RUNNING)
                .toList();
    }

    /**
     * Delivers each item's versions in order, as the serial consumer does, while different items and a manual request
     * can compete for admission. Each worker holds two connections (delivery and synchronous listener), so four fit
     * the test pool.
     */
    private void burst(List<Prepared> items, int rounds) throws Exception {
        var pool = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
        try {
            var deliveries = new ArrayList<Future<?>>();
            var go = new CountDownLatch(1);
            for (int index = 0; index < items.size(); index++) {
                var item = items.get(index);
                int itemIndex = index;
                deliveries.add(pool.submit(() -> {
                    awaitLatch(go);
                    for (int round = 0; round < rounds; round++) {
                        var payload = pullRequestPayload(
                                item,
                                String.format("%040x", 0xabc000L + round * 100L + itemIndex),
                                Instant.now().plusSeconds(60L + round * 100L + itemIndex));
                        deliver(item, "synchronize", payload);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (var delivery : deliveries) delivery.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A second pull request in the workspace of {@code first}, on its repository. */
    private Prepared anotherPullRequest(Prepared first, int number) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            var original = pulls.findById(Objects.requireNonNull(first.item().sourceId()))
                    .orElseThrow();
            var repository = Objects.requireNonNull(original.getRepository());
            var pull = new PullRequest();
            pull.setNativeId(nativeId());
            pull.setProvider(original.getProvider());
            pull.setRepository(repository);
            pull.setNumber(number);
            pull.setTitle("A bounded change");
            pull.setBody("Describe this change");
            pull.setState(Issue.State.OPEN);
            pull.setDraft(false);
            pull.setAuthor(original.getAuthor());
            pull.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + number);
            pull.setCreatedAt(Instant.now());
            pull.setUpdatedAt(Instant.now());
            pull.setBaseRefName("main");
            pull.setHeadRefName("topic-" + number);
            pull.setBaseRefOid("a".repeat(40));
            pull.setHeadRefOid("b".repeat(40));
            pull = pulls.saveAndFlush(pull);
            var ref = writer.upsert(new ItemRegistration(
                    first.item().workspaceId(),
                    ScmItemEvents.PULL_REQUEST,
                    pull.getId(),
                    first.item().parentId(),
                    null));
            return new Prepared(ref, first.version(), first.name(), first.event());
        }));
    }

    @Autowired
    SqlStatementCounter statementCounter;

    /** Admission reads the review scope, the members and the item's people once, not once per selected practice. */
    @Test
    void shouldReadScopeAndRolesOnceWhenAdmittingAReviewOfManyPractices() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        long one = admissionStatements(prepared, 1);
        for (int number = 2; number <= 6; number++) addPractice(prepared, number);
        long six = admissionStatements(prepared, 6);

        // Each further practice adds only its own rows: the scope, the members and the item's people are read once.
        assertThat(six - one).isLessThanOrEqualTo(5L * 2);
    }

    /** The statements of one manual review request of the item, cancelled so that the item can be requested again. */
    private long admissionStatements(Prepared prepared, int practices) {
        long workspace = prepared.item().workspaceId();
        long requester = memberAccount(workspace);
        var measured = statementCounter.measure(() -> service.request(ReviewRequest.builder()
                .workspaceId(workspace)
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(prepared.version())
                .origin(ReviewOrigin.REQUEST)
                .requestedById(requester)
                .practiceIds(List.of())
                .build()));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_practice WHERE review_id = ?", Integer.class, measured.value()))
                .isEqualTo(practices);
        assertThat(service.cancel(workspace, measured.value())).isTrue();
        return measured.statements();
    }

    private void addPractice(Prepared prepared, int number) {
        var workspace = workspaces.findById(prepared.item().workspaceId()).orElseThrow();
        var definition = DefinitionTestFixtures.definition(
                ScmItemEvents.PULL_REQUEST, "Describe the work " + number, "Explain the work.", null);
        versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(), "event-test-" + number, ScmItemEvents.PULL_REQUEST, definition, null, number, null));
    }

    private void deliver(Prepared prepared, String action, GitHubPullRequestDTO payload) {
        deliver(prepared, action, payload, null);
    }

    private void deliver(Prepared prepared, String action, GitHubPullRequestDTO payload, @Nullable String before) {
        transactions.executeWithoutResult(ignored -> {
            var repository = repositories
                    .findById(Objects.requireNonNull(pulls.findById(Objects.requireNonNull(
                                            prepared.item().sourceId()))
                                    .orElseThrow()
                                    .getRepository())
                            .getId())
                    .orElseThrow();
            var context = ProcessingContext.forWebhook(prepared.item().workspaceId(), repository, action);
            if (action.equals("ready_for_review")) pullRequestProcessor.processReadyForReview(payload, context);
            else pullRequestProcessor.processSynchronize(payload, before, context);
        });
    }

    private List<ItemEvent> itemEvents(Prepared prepared) {
        return jdbc
                .queryForList(
                        "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?",
                        UUID.class,
                        prepared.item().workspaceId(),
                        prepared.item().itemId())
                .stream()
                .map(id -> events.findById(Objects.requireNonNull(id)).orElseThrow())
                .toList();
    }

    @Test
    void shouldStateThatAReferencedOccasionsEvidenceExpiredAndNeverPinTheCurrentWorkInItsPlace() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        publish(prepared);
        var occasion = event(prepared);
        assertThat(reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), occasion.getId()))
                .isPresent();
        assertThat(occasion.getEvidence()).isNotNull();
        assertThat(occasion.getEvidenceExpiresAt()).isAfter(Instant.now());
        moveWork(prepared);
        assertThat(source.pinOccasion(prepared.item(), prepared.version(), occasion.getId())
                        .version())
                .isEqualTo(prepared.version());

        jdbc.update(
                "UPDATE item_event SET evidence_expires_at = now() - interval '1 minute' WHERE id = ?",
                occasion.getId());
        var deadline = events.findById(occasion.getId()).orElseThrow().getEvidenceExpiresAt();
        // Past its expiry the evidence is no longer used, also before the reaper replaces it.
        assertOccasionUnavailable(prepared, prepared.version(), occasion.getId());
        reaper.sweep();

        var swept = events.findById(occasion.getId()).orElseThrow();
        assertThat(unretained(occasion.getId())).isEqualTo("EXPIRED");
        assertThat(Objects.requireNonNull(swept.getEvidence()).has("record")).isFalse();
        // The fixed expiry names no person and stays, so a later admission copies nothing for a new day.
        assertThat(swept.getEvidenceExpiresAt()).isNotNull().isEqualTo(deadline);
        assertThat(swept.getItemVersion()).isEqualTo(prepared.version().value());
        var admitted = reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), occasion.getId())
                .orElseThrow();
        assertThat(snapshots
                        .find(prepared.item().workspaceId(), admitted.getId())
                        .orElseThrow()
                        .evidenceExpiresAt())
                .isEqualTo(deadline);
        assertOccasionUnavailable(prepared, prepared.version(), occasion.getId());
        // A second sweep no longer selects the statement: it stays as it is, with the same expiry.
        reaper.sweep();
        assertThat(unretained(occasion.getId())).isEqualTo("EXPIRED");
        assertThat(events.findById(occasion.getId()).orElseThrow().getEvidenceExpiresAt())
                .isEqualTo(deadline);
    }

    @Test
    void shouldExpireRetainedEvidenceWithoutOfferingAnyOccasionAgainWhenTheAgentIsOff() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(false);
        publish(prepared);
        var occasion = event(prepared);
        assertThat(occasion.getState()).isEqualTo(ItemEventState.WAITING);
        // Both are due: the record's expiry and the occasion's next offer.
        jdbc.update(
                "UPDATE item_event SET evidence_expires_at = now() - interval '1 minute',"
                        + " retry_at = now() - interval '1 minute' WHERE id = ?",
                occasion.getId());
        var due = events.findById(occasion.getId()).orElseThrow();
        long offered = offers(occasion.getId());
        var agentOff = new PendingItemEventReaper(
                events,
                ledger,
                publisher,
                clock,
                new MockEnvironment().withProperty(RuntimeRole.AGENT_ENABLED_PROPERTY, "false"));

        transactions.executeWithoutResult(ignored -> agentOff.sweep());

        var swept = events.findById(occasion.getId()).orElseThrow();
        assertThat(unretained(occasion.getId())).isEqualTo("EXPIRED");
        assertThat(swept.getEvidenceExpiresAt()).isEqualTo(due.getEvidenceExpiresAt());
        assertThat(swept.getState()).isEqualTo(ItemEventState.WAITING);
        assertThat(swept.getLastAttemptedAt()).isEqualTo(due.getLastAttemptedAt());
        assertThat(swept.getRetryAt()).isEqualTo(due.getRetryAt());
        assertThat(offers(occasion.getId())).isEqualTo(offered);

        // Where the agent runs, the same sweep offers the due occasion again.
        var agentOn = new PendingItemEventReaper(
                events,
                ledger,
                publisher,
                clock,
                new MockEnvironment().withProperty(RuntimeRole.AGENT_ENABLED_PROPERTY, "true"));
        transactions.executeWithoutResult(ignored -> agentOn.sweep());
        assertThat(offers(occasion.getId())).isEqualTo(offered + 1);
    }

    @Test
    void shouldReadAnOccasionsEvidenceOnlyInItsOwnWorkspaceItemAndEvent() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var other = prepare(IdentityProviderType.GITHUB, false);
        publish(prepared);
        publish(other);
        var occasion = event(prepared);
        var item = prepared.item();
        assertThat(item.workspaceId()).isNotEqualTo(other.item().workspaceId());

        assertThat(itemEvents
                        .evidence(item.workspaceId(), item.itemId(), occasion.getId())
                        .record())
                .isNotNull();
        assertThat(itemEvents
                        .evidence(other.item().workspaceId(), item.itemId(), occasion.getId())
                        .unretained())
                .isEqualTo(ItemEventService.RetainedEvidence.UNKNOWN);
        assertThat(itemEvents
                        .evidence(item.workspaceId(), other.item().itemId(), occasion.getId())
                        .unretained())
                .isEqualTo(ItemEventService.RetainedEvidence.UNKNOWN);
        assertThat(itemEvents
                        .evidence(
                                item.workspaceId(), item.itemId(), event(other).getId())
                        .unretained())
                .isEqualTo(ItemEventService.RetainedEvidence.UNKNOWN);
        // The other workspace's own occasion of the same version is not this one.
        assertOccasionUnavailable(prepared, prepared.version(), event(other).getId());
    }

    @Test
    void shouldKeepTheFixedExpiryWhenTheItemLeavesTheSource() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        publish(prepared);
        var occasion = event(prepared);
        var deadline = Objects.requireNonNull(occasion.getEvidenceExpiresAt());

        transactions.executeWithoutResult(ignored -> itemEvents.forgetEvidence(
                prepared.item().workspaceId(), prepared.item().itemId()));

        assertThat(unretained(occasion.getId())).isEqualTo("REMOVED");
        assertThat(events.findById(occasion.getId()).orElseThrow().getEvidenceExpiresAt())
                .isEqualTo(deadline);
        assertOccasionUnavailable(prepared, prepared.version(), occasion.getId());
    }

    private long offers(UUID occasion) {
        return applicationEvents.stream(ItemEventRecorded.class)
                .filter(recorded -> recorded.eventId().equals(occasion))
                .count();
    }

    @Test
    void shouldRefuseAnOccasionRecordedBeforeEvidenceWasRetainedInsteadOfPinningTheCurrentWork() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        publish(prepared);
        var occasion = event(prepared);
        jdbc.update("UPDATE item_event SET evidence = NULL, evidence_expires_at = NULL WHERE id = ?", occasion.getId());
        // The current work still has exactly the occasion's version; a legacy occasion still reviews none of it.
        assertThat(source.pin(prepared.item(), prepared.version()).version()).isEqualTo(prepared.version());
        assertOccasionUnavailable(prepared, prepared.version(), occasion.getId());
    }

    @Test
    void shouldWithholdAWaitingOccasionWhoseEvidenceExpiredWhenItIsOfferedAgain() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        long workspaceId = prepared.item().workspaceId();
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(false);
        publish(prepared);
        var occasion = event(prepared);
        assertThat(occasion.getState()).isEqualTo(ItemEventState.WAITING);
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        jdbc.update(
                "UPDATE item_event SET evidence_expires_at = now() - interval '1 minute' WHERE id = ?",
                occasion.getId());

        transactions.executeWithoutResult(ignored -> publisher.publishEvent(new ItemEventRecorded(
                workspaceId, occasion.getId(), prepared.item().itemId())));

        var settled = events.findById(occasion.getId()).orElseThrow();
        assertThat(settled.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(settled.getReason()).isEqualTo(ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE);
        assertThat(reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId()))
                .isEmpty();
        // A manual request of the same work is not an occasion and still reviews the current work.
        long account = Objects.requireNonNull(
                jdbc.queryForObject("SELECT account_id FROM membership WHERE workspace_id=?", Long.class, workspaceId));
        var manual = service.request(ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(prepared.item().itemId())
                .kind(ReviewKind.ITEM)
                .version(prepared.version())
                .origin(ReviewOrigin.REQUEST)
                .requestedById(account)
                .requestKey(UUID.randomUUID())
                .requestDigest("c".repeat(64))
                .practiceIds(List.of())
                .build());
        assertThat(reviews.findById(manual).orElseThrow().getItemVersion())
                .isEqualTo(prepared.version().value());
    }

    @Test
    void shouldKeepAWaitingOccasionWaitingWhenItsRetainedRecordIsCorrupt() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        long workspaceId = prepared.item().workspaceId();
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(false);
        publish(prepared);
        var occasion = event(prepared);
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        jdbc.update("UPDATE item_event SET evidence = '{\"schema\":99}' WHERE id = ?", occasion.getId());

        // A corrupt record is a fault, not a missing original: it fails and is offered again, never withheld.
        assertThatThrownBy(() -> source.pinOccasion(prepared.item(), prepared.version(), occasion.getId()))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.CAPTURE_FAILED));
        transactions.executeWithoutResult(ignored -> publisher.publishEvent(new ItemEventRecorded(
                workspaceId, occasion.getId(), prepared.item().itemId())));
        // The AFTER_COMMIT listener records its fault for retry; it does not throw back to the publisher.
        assertThat(jdbc.queryForList(
                        "SELECT status FROM event_publication WHERE event_type=?"
                                + " AND serialized_event::jsonb->>'eventId'=? AND completion_date IS NULL",
                        String.class,
                        ItemEventRecorded.class.getName(),
                        occasion.getId().toString()))
                .containsExactly("FAILED");
        assertThat(events.findById(occasion.getId()).orElseThrow().getState()).isEqualTo(ItemEventState.WAITING);
        assertThat(reviews.findByWorkspaceIdAndEventId(workspaceId, occasion.getId()))
                .isEmpty();
    }

    @Test
    void shouldEraseAFormerAssigneesOccasionByTheNativeIdentityItKeeps() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow();
        var assignee =
                users.saveAndFlush(TestUserFactory.createUser(nativeId(), "erased-" + UUID.randomUUID(), provider));
        long pullId = Objects.requireNonNull(prepared.item().sourceId());
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(pullId).orElseThrow();
            pull.getAssignees().add(users.findById(assignee.getId()).orElseThrow());
            pulls.saveAndFlush(pull);
        });
        var withAssignee = source.pin(prepared.item(), null);
        var occasion = recordOccasion(prepared, withAssignee.version());
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(pullId).orElseThrow();
            pull.getAssignees().removeIf(user -> Objects.equals(user.getId(), assignee.getId()));
            pulls.saveAndFlush(pull);
        });
        var store = itemEventCatalog.contributors().stream()
                .filter(contributor -> contributor.store().equals("item_event_evidence"))
                .findFirst()
                .orElseThrow();
        // Only the native identity: the mirror no longer links this person to the work.
        var scope = new PersonScope(
                null,
                List.of(new PersonIdentity(providerId(assignee), Long.toString(assignee.getNativeId()), null)),
                List.of());

        var deadline = events.findById(occasion).orElseThrow().getEvidenceExpiresAt();
        var selection = store.select(scope);
        assertThat(selection.rows()).extracting(row -> row.columns().get("id")).contains(occasion.toString());
        store.erase(selection, scope);

        assertThat(unretained(occasion)).isEqualTo("ERASED");
        // The fixed expiry names no person, so erasure keeps it to bound the copies made before.
        assertThat(events.findById(occasion).orElseThrow().getEvidenceExpiresAt())
                .isNotNull()
                .isEqualTo(deadline);
        assertThat(store.select(scope).rows())
                .extracting(row -> row.columns().get("id"))
                .doesNotContain(occasion.toString());
        assertOccasionUnavailable(prepared, withAssignee.version(), occasion);
    }

    @Test
    void shouldStateTheSizeLimitInsteadOfTruncatingAnOversizedOccasion() {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        // Each "é" is two UTF-8 bytes, so the description alone exceeds the 1 MiB limit.
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            pull.setBody("é".repeat(600_000));
            pulls.saveAndFlush(pull);
        });
        var oversized = source.pin(prepared.item(), null);
        var occasion = recordOccasion(prepared, oversized.version());

        assertThat(unretained(occasion)).isEqualTo("SIZE_LIMIT");
        var settled = events.findById(occasion).orElseThrow();
        assertThat(settled.getEvidenceExpiresAt()).isNull();
        assertThat(settled.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(settled.getReason()).isEqualTo(ReviewRequestReason.OCCASION_EVIDENCE_UNAVAILABLE);
        assertOccasionUnavailable(prepared, oversized.version(), occasion);
    }

    @Test
    void shouldStateThatNothingWasRetainedForAnOccasionWhosePersonIsSuppressedWhenItIsRecorded() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var author = Objects.requireNonNull(transactions.execute(ignored -> users.findById(
                        Objects.requireNonNull(pulls.findById(Objects.requireNonNull(
                                                prepared.item().sourceId()))
                                        .orElseThrow()
                                        .getAuthor())
                                .getId())
                .orElseThrow()));
        UUID restriction = databaseTestUtils.erase(providerId(author), Long.toString(author.getNativeId()), "");
        ItemEvent occasion;
        try {
            publish(prepared);
            occasion = onlyEvent(prepared);
        } finally {
            jdbc.update("DELETE FROM identity_restriction WHERE id=?", restriction);
        }
        assertThat(unretained(occasion.getId())).isEqualTo("SUPPRESSED");
        assertThat(occasion.getEvidenceExpiresAt()).isNull();
        // Lifting the restriction later does not bring back words that were never kept.
        assertOccasionUnavailable(
                prepared, new ItemVersion(Objects.requireNonNull(occasion.getItemVersion()), null), occasion.getId());
    }

    @Test
    void shouldRefuseToCaptureAnOccasionWhoseFormerAssigneeWithdrewAfterLeavingTheWork() throws Exception {
        var prepared = prepare(IdentityProviderType.GITHUB, false);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseThrow();
        var assignee =
                users.saveAndFlush(TestUserFactory.createUser(nativeId(), "former-" + UUID.randomUUID(), provider));
        long pullId = Objects.requireNonNull(prepared.item().sourceId());
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(pullId).orElseThrow();
            pull.getAssignees().add(users.findById(assignee.getId()).orElseThrow());
            pulls.saveAndFlush(pull);
        });
        var withAssignee = source.pin(prepared.item(), null);
        var occasion = recordOccasion(prepared, withAssignee.version());
        assertThat(jdbc.queryForObject(
                        "SELECT evidence->'participants' @> CAST(? AS jsonb) FROM item_event WHERE id=?",
                        Boolean.class,
                        "[{\"userId\":" + assignee.getId() + "}]",
                        occasion))
                .isTrue();
        // The assignee leaves the work; only the occasion still names them.
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(pullId).orElseThrow();
            pull.getAssignees().removeIf(user -> Objects.equals(user.getId(), assignee.getId()));
            pulls.saveAndFlush(pull);
        });
        var pinned = source.pinOccasion(prepared.item(), withAssignee.version(), occasion);
        var core = PullRequestContentSource.CORE;
        var request = new CaptureRequest(
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(core), "3.0.0", 3, null, Set.of(core), occasion);
        UUID restriction = databaseTestUtils.erase(providerId(assignee), Long.toString(assignee.getNativeId()), "");
        try {
            assertThatThrownBy(() -> source.capture(pinned, request))
                    .isInstanceOfSatisfying(
                            ItemCaptureException.class,
                            failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.CONSENT_WITHHELD));
        } finally {
            jdbc.update("DELETE FROM identity_restriction WHERE id=?", restriction);
        }
        assertThat(source.capture(pinned, request).files()).containsKey("context/metadata.json");
    }

    @Test
    void shouldCompleteAPendingGitLabOccasionsEvidenceOnlyWithTheDiffBaseItsUnchangedFenceProves() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        assertThat(pending.getItemVersion()).isNull();
        var retained = Objects.requireNonNull(pending.getEvidence());
        assertThat(retained.path("itemVersion").isString()).isFalse();
        assertThat(retained.path("work").path("base").isString()).isFalse();
        // State and people are outside the fence: they change before the base arrives, and the occasion keeps its own.
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.test")
                .orElseThrow();
        var assignee =
                users.saveAndFlush(TestUserFactory.createUser(nativeId(), "later-" + UUID.randomUUID(), provider));
        transactions.executeWithoutResult(ignored -> {
            var moved = pulls.findById(pull.getId()).orElseThrow();
            moved.setDraft(true);
            moved.getAssignees().add(users.findById(assignee.getId()).orElseThrow());
            pulls.saveAndFlush(moved);
        });

        assertThat(readiness(pull, new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40))))
                .isTrue();

        var pinned = events.findById(pending.getId()).orElseThrow();
        assertThat(pinned.getItemVersion()).isEqualTo(prepared.version().value());
        var completed = Objects.requireNonNull(pinned.getEvidence());
        assertThat(completed.path("itemVersion").asString())
                .isEqualTo(prepared.version().value());
        assertThat(completed.path("work").path("base").asString()).isEqualTo("a".repeat(40));
        assertThat(completed.path("record")).isEqualTo(retained.path("record"));
        assertThat(completed.path("state")).isEqualTo(retained.path("state"));
        assertThat(completed.path("roles")).isEqualTo(retained.path("roles"));
        assertThat(completed.path("participants")).isEqualTo(retained.path("participants"));
        assertThat(pinned.getEvidenceExpiresAt()).isEqualTo(pending.getEvidenceExpiresAt());
        moveWork(prepared);
        var occasion = source.pinOccasion(prepared.item(), prepared.version(), pending.getId());
        assertThat(occasion.version()).isEqualTo(prepared.version());
        assertThat(occasion.roles()).extracting(role -> role.role()).doesNotContain(AboutRole.ASSIGNEE);
    }

    @Test
    void shouldWithholdAPendingGitLabOccasionUnchangedWhenItsFenceChangesBeforeTheBaseArrives() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        var retained = Objects.requireNonNull(pending.getEvidence());
        // The title is part of the fence, so the base GitLab pairs next belongs to different work.
        jdbc.update("UPDATE issue SET title = ? WHERE id = ?", "A retitled change", pull.getId());

        readiness(pull, new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40)));

        var withheld = events.findById(pending.getId()).orElseThrow();
        assertThat(withheld.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(withheld.getReason()).isEqualTo(ReviewRequestReason.VERSION_SUPERSEDED);
        assertThat(withheld.getItemVersion()).isNull();
        assertThat(withheld.getEvidence()).isEqualTo(retained);
        assertThat(withheld.getEvidenceExpiresAt()).isEqualTo(pending.getEvidenceExpiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ERASED", "EXPIRED", "REMOVED"})
    void shouldKeepAnEvidenceRemovalStatementWhenAStaleCompletionArrives(String reason) {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        var unresolved = itemEvents
                .unresolved(prepared.item().workspaceId(), prepared.item().itemId())
                .getFirst();
        var staleCompletion = workCapture.complete(
                itemEvents.pendingEvidence(prepared.item().workspaceId(), pending.getId()), prepared.version());
        assertThat(staleCompletion).contains("Describe this change");
        // Completion already read the words when erasure, expiry or removal commits its durable statement.
        jdbc.update(
                "UPDATE item_event SET evidence = jsonb_build_object('unretained', CAST(? AS text)) WHERE id=?",
                reason,
                pending.getId());

        assertThat(itemEvents.pin(
                        prepared.item().workspaceId(),
                        prepared.item().itemId(),
                        unresolved,
                        prepared.version().value(),
                        staleCompletion))
                .isTrue();

        var pinned = events.findById(pending.getId()).orElseThrow();
        assertThat(jdbc.queryForObject(
                        "SELECT evidence->>'unretained' FROM item_event WHERE id=?", String.class, pending.getId()))
                .isEqualTo(reason);
        assertThat(Objects.requireNonNull(pinned.getEvidence()).size()).isEqualTo(1);
        assertThat(pinned.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(pinned.getEvidenceExpiresAt()).isEqualTo(pending.getEvidenceExpiresAt());
        assertOccasionUnavailable(prepared, prepared.version(), pending.getId());
    }

    @Test
    void shouldKeepALegacyNullRecordWhenAStaleCompletionArrives() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        var unresolved = itemEvents
                .unresolved(prepared.item().workspaceId(), prepared.item().itemId())
                .getFirst();
        var staleCompletion = workCapture.complete(
                itemEvents.pendingEvidence(prepared.item().workspaceId(), pending.getId()), prepared.version());
        jdbc.update("UPDATE item_event SET evidence=NULL, evidence_expires_at=NULL WHERE id=?", pending.getId());

        assertThat(itemEvents.pin(
                        prepared.item().workspaceId(),
                        prepared.item().itemId(),
                        unresolved,
                        prepared.version().value(),
                        staleCompletion))
                .isTrue();

        var pinned = events.findById(pending.getId()).orElseThrow();
        assertThat(pinned.getEvidence()).isNull();
        assertThat(pinned.getEvidenceExpiresAt()).isNull();
        assertThat(pinned.getItemVersion()).isEqualTo(prepared.version().value());
        assertOccasionUnavailable(prepared, prepared.version(), pending.getId());
    }

    @Test
    void shouldKeepCorruptPendingEvidenceUnresolvedWithoutRollingBackTheMirrorOrOtherOccasions() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        var repository = repositories
                .findById(Objects.requireNonNull(pull.getRepository()).getId())
                .orElseThrow();
        var other = fixture(IdentityProviderType.GITLAB);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(
                other.workspace(), repository.getNameWithOwner(), repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var holder = writer.upsert(new ItemRegistration(
                other.workspace().getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null));
        var otherItem = writer.upsert(new ItemRegistration(
                other.workspace().getId(), ScmItemEvents.PULL_REQUEST, pull.getId(), holder.itemId(), null));
        publish(prepared);
        var pending = onlyEvent(prepared);
        var otherPending = itemEvents
                .unresolved(otherItem.workspaceId(), otherItem.itemId())
                .getFirst();
        // Corrupt only this workspace's retained record, not the shared mirror or the proved source fence.
        jdbc.update(
                "UPDATE item_event SET evidence = jsonb_set(evidence, '{work,body}', to_jsonb(CAST(? AS text))) "
                        + "WHERE workspace_id=? AND id=?",
                "Inconsistent retained words",
                prepared.item().workspaceId(),
                pending.getId());

        assertThat(readiness(pull, new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40))))
                .isTrue();

        assertThat(pulls.findById(pull.getId()).orElseThrow().getBaseRefOid()).isEqualTo("a".repeat(40));
        var unresolved = events.findById(pending.getId()).orElseThrow();
        assertThat(unresolved.getState()).isEqualTo(ItemEventState.RECORDED);
        assertThat(unresolved.getItemVersion()).isNull();
        var evidence = Objects.requireNonNull(unresolved.getEvidence());
        assertThat(evidence.path("work").path("body").asString()).isEqualTo("Inconsistent retained words");
        assertThat(evidence.has("unretained")).isFalse();
        assertThat(unresolved.getEvidenceExpiresAt()).isEqualTo(pending.getEvidenceExpiresAt());
        assertThat(reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), pending.getId()))
                .isEmpty();
        var completed = events.findById(otherPending.id()).orElseThrow();
        assertThat(completed.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(Objects.requireNonNull(completed.getEvidence())
                        .path("work")
                        .path("base")
                        .asString())
                .isEqualTo("a".repeat(40));
        assertThat(completed.getEvidenceExpiresAt()).isNotNull();
    }

    /** Records the occasion of {@code prepared} at {@code version}, as the provider event would. */
    private UUID recordOccasion(Prepared prepared, ItemVersion version) {
        publish(prepared);
        return events.findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                        prepared.item().workspaceId(),
                        prepared.item().itemId(),
                        prepared.name().value(),
                        version.value())
                .orElseThrow()
                .getId();
    }

    /** The author retitles, rewrites and pushes the work, and its diff base moves with the push. */
    private void moveWork(Prepared prepared) {
        transactions.executeWithoutResult(ignored -> {
            var moved = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            moved.setTitle("A retitled change");
            moved.setBody("A rewritten description");
            moved.setDraft(true);
            moved.setHeadRefOid("c".repeat(40));
            moved.setBaseRefOid("d".repeat(40));
            var data = ScmEventPayload.PullRequestData.from(pulls.saveAndFlush(moved));
            publisher.publishEvent(
                    new ScmDomainEvent.PullRequestUpdated(data, Set.of("title", "body", "draft"), context(prepared)));
            publisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(data, context(prepared)));
        });
    }

    @Test
    void shouldWithholdAutomaticEventsBeforePinningWhenActivityReviewsAreOff() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var workspace = workspaces.findById(prepared.item().workspaceId()).orElseThrow();
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
        workspaces.saveAndFlush(workspace);
        publish(prepared);
        var event = event(prepared);
        assertThat(event.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(event.getReason()).isEqualTo(ReviewRequestReason.GATE_WITHHELD);
        assertThat(event.getRetryAt()).isNull();
        assertThat(reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), event.getId()))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewOrigin.class,
            names = {"REQUEST", "BACKFILL", "SCHEDULE"})
    void shouldStopRequestedScansBeforeCaptureWhenRequestsAreOff(ReviewOrigin origin) {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var workspace = workspaces.findById(prepared.item().workspaceId()).orElseThrow();
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(false);
        workspaces.saveAndFlush(workspace);
        long account = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT account_id FROM membership WHERE workspace_id=?", Long.class, workspace.getId()));
        var request = ReviewRequest.builder()
                .workspaceId(workspace.getId())
                .itemId(Objects.requireNonNull(prepared.item().parentId()))
                .kind(ReviewKind.SCAN)
                .origin(origin)
                .requestedById(origin == ReviewOrigin.REQUEST ? account : null)
                .batchId(origin == ReviewOrigin.REQUEST ? null : UUID.randomUUID())
                .requestKey(UUID.randomUUID())
                .requestDigest("c".repeat(64))
                .practiceIds(List.of())
                .build();
        assertThatThrownBy(() -> service.request(request))
                .isInstanceOfSatisfying(
                        ReviewRequestRejected.class,
                        refusal -> assertThat(refusal.eventReason()).isEqualTo(ReviewRequestReason.GATE_WITHHELD));
    }

    @Test
    void shouldKeepAGitLabOccasionUntilReadinessRecordsTheDiffBaseItWasMissing() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        assertThat(pending.getItemVersion()).isNotEqualTo(prepared.version().value());
        assertThat(pending.getState()).isEqualTo(ItemEventState.RECORDED);
        assertThat(itemReviews(prepared)).isEmpty();

        assertThat(readiness(pull, new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40))))
                .isTrue();

        var resolved = events.findById(pending.getId()).orElseThrow();
        assertThat(resolved.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(resolved.getState()).isEqualTo(ItemEventState.STARTED);
        var admitted = reviews.findByWorkspaceIdAndEventId(prepared.item().workspaceId(), pending.getId())
                .orElseThrow();
        assertThat(admitted.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(admitted.getBaseVersion()).isEqualTo("a".repeat(40));
    }

    @Test
    void shouldRetireCurrentClaimsWhenReadinessPinsToAnExistingOccasion() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var item = prepared.item();
        long pullId = Objects.requireNonNull(item.sourceId());
        var pull = pulls.findById(pullId).orElseThrow();
        var author =
                users.findById(Objects.requireNonNull(pull.getAuthor()).getId()).orElseThrow();
        var scenario = new ReviewIntegrationData.Scenario(
                workspaces.findById(item.workspaceId()).orElseThrow(),
                accounts.findById(account(author)).orElseThrow(),
                practices
                        .findByWorkspaceIdAndSlug(item.workspaceId(), "event-test")
                        .orElseThrow(),
                itemRows.findByIdAndWorkspaceId(item.itemId(), item.workspaceId())
                        .orElseThrow());
        synchronizeHead(prepared, "b".repeat(40), false);
        var original = events.findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                        item.workspaceId(),
                        item.itemId(),
                        ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value(),
                        prepared.version().value())
                .orElseThrow();
        synchronizeHead(prepared, "c".repeat(40), false);
        var intervening = source.pin(item, null).version();
        assertThat(intervening).isNotEqualTo(prepared.version());
        var at = Instant.now();
        var current = resultData.result(scenario, ResultOutcome.MET, at, at, intervening.value());

        // GitLab stores a force-pushed head before its readiness read pairs that head's diff base.
        synchronizeHead(prepared, "b".repeat(40), true);
        var pendingIds = jdbc.queryForList(
                "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?" + " AND name=? AND item_version IS NULL",
                UUID.class,
                item.workspaceId(),
                item.itemId(),
                ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(pendingIds).hasSize(1);
        var pending = events.findById(pendingIds.getFirst()).orElseThrow();
        assertThat(pending.getState()).isEqualTo(ItemEventState.RECORDED);
        assertThat(resultRows.findById(current.getId()).orElseThrow().getEndedAt())
                .isNull();
        assertThat(resultRows.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .extracting(row -> row.getId())
                .doesNotContain(current.getId());
        var occasions = jdbc.queryForList(
                "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?",
                UUID.class,
                item.workspaceId(),
                item.itemId());
        var reviewIds = itemReviews(prepared).stream().map(Review::getId).toList();
        long recorded = applicationEvents.stream(ItemEventRecorded.class)
                .filter(event -> event.workspaceId() == item.workspaceId() && event.itemId() == item.itemId())
                .count();
        assertThat(applicationEvents.stream(ItemSourceObserved.class)
                        .filter(event -> event.workspaceId() == item.workspaceId() && event.itemId() == item.itemId()))
                .isEmpty();

        assertThat(readiness(
                        pulls.findById(pullId).orElseThrow(),
                        new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40))))
                .isTrue();

        var coalesced = events.findById(pending.getId()).orElseThrow();
        assertThat(coalesced.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(coalesced.getReason()).isEqualTo(ReviewRequestReason.COALESCED);
        assertThat(coalesced.getItemVersion()).isNull();
        assertThat(coalesced.getOccurredAt()).isEqualTo(pending.getOccurredAt());
        var retained = events.findById(original.getId()).orElseThrow();
        assertThat(retained.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(retained.getOccurredAt()).isEqualTo(original.getOccurredAt());
        assertThat(retained.getState()).isEqualTo(original.getState());
        assertThat(jdbc.queryForList(
                        "SELECT id FROM item_event WHERE workspace_id=? AND item_id=?",
                        UUID.class,
                        item.workspaceId(),
                        item.itemId()))
                .containsExactlyInAnyOrderElementsOf(occasions);
        assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactlyInAnyOrderElementsOf(reviewIds);
        assertThat(applicationEvents.stream(ItemEventRecorded.class)
                        .filter(event -> event.workspaceId() == item.workspaceId() && event.itemId() == item.itemId())
                        .count())
                .isEqualTo(recorded);
        assertThat(source.pin(item, null).version()).isEqualTo(prepared.version());
        var retired = resultRows.findById(current.getId()).orElseThrow();
        assertThat(retired.getItemVersion()).isEqualTo(intervening.value());
        assertThat(retired.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(resultRows.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .extracting(row -> row.getId())
                .doesNotContain(current.getId());
        assertThat(applicationEvents.stream(ItemSourceObserved.class)
                        .filter(event -> event.workspaceId() == item.workspaceId() && event.itemId() == item.itemId()))
                .hasSize(1);
    }

    private void synchronizeHead(Prepared prepared, String head, boolean withoutBase) {
        transactions.executeWithoutResult(ignored -> {
            long pullId = Objects.requireNonNull(prepared.item().sourceId());
            if (withoutBase) jdbc.update("UPDATE issue SET base_ref_oid=NULL WHERE id=?", pullId);
            var pull = pulls.findById(pullId).orElseThrow();
            pull.setHeadRefOid(head);
            pulls.saveAndFlush(pull);
            var context = new EventContext(
                    UUID.randomUUID(),
                    Instant.now(),
                    prepared.item().workspaceId(),
                    null,
                    DataSource.WEBHOOK,
                    "synchronize",
                    UUID.randomUUID().toString(),
                    IdentityProviderType.GITLAB,
                    Objects.requireNonNull(pull.getAuthor()).getId());
            publisher.publishEvent(
                    new ScmDomainEvent.PullRequestSynchronized(ScmEventPayload.PullRequestData.from(pull), context));
        });
    }

    @Test
    void shouldWithholdAPendingOccasionWhenTheHeadMovesBeforeItsDiffBaseIsKnown() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        transactions.executeWithoutResult(ignored -> {
            var moved = pulls.findById(pull.getId()).orElseThrow();
            moved.setHeadRefOid("c".repeat(40));
            moved.setBaseRefOid("a".repeat(40));
            publisher.publishEvent(new ScmDomainEvent.PullRequestSynchronized(
                    ScmEventPayload.PullRequestData.from(pulls.saveAndFlush(moved)), context(prepared)));
        });

        var withheld = events.findById(pending.getId()).orElseThrow();
        assertThat(withheld.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(withheld.getReason()).isEqualTo(ReviewRequestReason.VERSION_SUPERSEDED);
        assertThat(itemReviews(prepared)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "body"})
    void shouldWithholdAPendingOccasionWhenItsTextChangesBeforeItsDiffBaseIsKnown(String field) {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        transactions.executeWithoutResult(ignored -> {
            var edited = pulls.findById(pull.getId()).orElseThrow();
            if (field.equals("title")) edited.setTitle("A retitled change");
            else edited.setBody("A rewritten description");
            publisher.publishEvent(new ScmDomainEvent.PullRequestUpdated(
                    ScmEventPayload.PullRequestData.from(pulls.saveAndFlush(edited)),
                    Set.of(field),
                    context(prepared)));
        });

        assertThat(readiness(pull, new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40))))
                .isTrue();

        var withheld = events.findById(pending.getId()).orElseThrow();
        assertThat(withheld.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(withheld.getReason()).isEqualTo(ReviewRequestReason.VERSION_SUPERSEDED);
        assertThat(withheld.getItemVersion()).isNull();
        var edit = jdbc.queryForObject(
                "SELECT item_version FROM item_event WHERE workspace_id=? AND item_id=? AND name=?",
                String.class,
                prepared.item().workspaceId(),
                prepared.item().itemId(),
                ScmItemEvents.PULL_REQUEST_EDITED.value());
        assertThat(edit)
                .isEqualTo(source.pin(prepared.item(), null).version().value())
                .isNotEqualTo(prepared.version().value());
        assertThat(itemReviews(prepared)).isEmpty();
    }

    @Test
    void shouldWithholdAPendingOccasionWhenAClosedIssueChangesBeforeItsDiffBaseIsKnown() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        long pullId = Objects.requireNonNull(prepared.item().sourceId());
        long issueId = Objects.requireNonNull(transactions.execute(ignored -> {
            var pull = pulls.findById(pullId).orElseThrow();
            var issue = new Issue();
            issue.setNativeId(nativeId());
            issue.setProvider(pull.getProvider());
            issue.setRepository(pull.getRepository());
            issue.setNumber(4);
            issue.setTitle("The problem");
            issue.setBody("What is wrong");
            issue.setState(Issue.State.OPEN);
            issue.setAuthor(pull.getAuthor());
            issue.setHtmlUrl(pull.getHtmlUrl() + "/issues/4");
            issue.setCreatedAt(Instant.now());
            issue.setUpdatedAt(Instant.now());
            issue = issueRows.saveAndFlush(issue);
            pull.replaceClosingIssues(Set.of(issue));
            pulls.saveAndFlush(pull);
            return issue.getId();
        }));
        withoutBase(prepared);
        publish(prepared);
        var pending = onlyEvent(prepared);
        transactions.executeWithoutResult(ignored -> {
            var issue = issueRows.findById(issueId).orElseThrow();
            issue.setBody("What is wrong, restated");
            publisher.publishEvent(new ScmDomainEvent.IssueUpdated(
                    ScmEventPayload.IssueData.from(issueRows.saveAndFlush(issue)), Set.of("body"), context(prepared)));
        });

        var withheld = events.findById(pending.getId()).orElseThrow();
        assertThat(withheld.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(withheld.getReason()).isEqualTo(ReviewRequestReason.VERSION_SUPERSEDED);
        assertThat(itemReviews(prepared)).isEmpty();
    }

    @Test
    void shouldKeepOnePendingOccasionAndOneReviewWhenTheHookIsRedelivered() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var pull = withoutBase(prepared);
        for (int delivery = 0; delivery < 3; delivery++) publish(prepared);
        var pending = onlyEvent(prepared);
        assertThat(pending.getItemVersion()).isNull();

        assertThat(readiness(pull, new GitLabMergeRequestReadinessReader.DiffRefs("b".repeat(40), "a".repeat(40))))
                .isTrue();
        publish(prepared);

        var resolved = onlyEvent(prepared);
        assertThat(resolved.getId()).isEqualTo(pending.getId());
        assertThat(resolved.getItemVersion()).isEqualTo(prepared.version().value());
        assertThat(resolved.getState()).isEqualTo(ItemEventState.STARTED);
        assertThat(itemReviews(prepared)).singleElement().satisfies(review -> {
            assertThat(review.getOrigin()).isEqualTo(ReviewOrigin.EVENT);
            assertThat(review.getItemVersion()).isEqualTo(prepared.version().value());
        });
    }

    @Test
    void shouldStartOneRequestedReviewWhenTheSameCommandNoteArrivesThreeTimes() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var author = Objects.requireNonNull(transactions.execute(ignored -> users.findById(
                        Objects.requireNonNull(pulls.findById(Objects.requireNonNull(
                                                prepared.item().sourceId()))
                                        .orElseThrow()
                                        .getAuthor())
                                .getId())
                .orElseThrow()));
        long noteId = nativeId();
        for (int delivery = 0; delivery < 3; delivery++) command(prepared, author, noteId);

        assertThat(itemReviews(prepared)).singleElement().satisfies(review -> {
            assertThat(review.getOrigin()).isEqualTo(ReviewOrigin.REQUEST);
            assertThat(review.getRequestedById()).isEqualTo(account(author));
            assertThat(review.getItemVersion()).isEqualTo(prepared.version().value());
        });
    }

    @Test
    void shouldStartNoReviewWhenACommenterHasNoStandingOnTheWork() {
        var prepared = prepare(IdentityProviderType.GITLAB, false);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.test")
                .orElseThrow();
        var colleague =
                users.saveAndFlush(TestUserFactory.createUser(nativeId(), "colleague-" + UUID.randomUUID(), provider));
        TestUserFactory.ensureAccountForUser(accounts, identities, colleague);
        jdbc.update(
                "INSERT INTO membership(workspace_id,account_id,role,joined_at) VALUES (?,?,'MEMBER',now())",
                prepared.item().workspaceId(),
                account(colleague));

        command(prepared, colleague, nativeId());

        assertThat(itemReviews(prepared)).isEmpty();
    }

    private PullRequest withoutBase(Prepared prepared) {
        long id = Objects.requireNonNull(prepared.item().sourceId());
        // A merge request hook stores the head before GitLab has paired a diff base with it.
        jdbc.update("UPDATE issue SET base_ref_oid = NULL WHERE id = ?", id);
        return pulls.findById(id).orElseThrow();
    }

    private ItemEvent onlyEvent(Prepared prepared) {
        var ids = jdbc.queryForList(
                "SELECT id FROM item_event WHERE workspace_id=? AND item_id=? AND name=?",
                UUID.class,
                prepared.item().workspaceId(),
                prepared.item().itemId(),
                prepared.name().value());
        assertThat(ids).hasSize(1);
        return events.findById(ids.getFirst()).orElseThrow();
    }

    private List<Review> itemReviews(Prepared prepared) {
        return reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                prepared.item().workspaceId(), prepared.item().itemId());
    }

    private EventContext context(Prepared prepared) {
        return ((ScmDomainEvent.ContextualEvent) prepared.event()).context();
    }

    private boolean readiness(PullRequest pull, GitLabMergeRequestReadinessReader.DiffRefs diffRefs) {
        var repository = repositories
                .findById(Objects.requireNonNull(pull.getRepository()).getId())
                .orElseThrow();
        var facts = new GitLabMergeRequestReadinessReader.Facts(
                repository.getNativeId(),
                pull.getNativeId(),
                "opened",
                Instant.now(),
                Objects.requireNonNull(pull.getHeadRefOid()),
                true,
                "mergeable",
                null,
                GitLabHeadPipeline.NO_PIPELINE,
                List.of(),
                null,
                GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                null,
                diffRefs);
        return mergeRequests.applyReadiness(
                repository,
                pull.getNumber(),
                facts,
                Instant.now(),
                ProcessingContext.forWebhook(repository.getId(), repository, "update"));
    }

    private void command(Prepared prepared, User commenter, long noteId) {
        transactions.executeWithoutResult(ignored -> publisher.publishEvent(new BotCommandReceivedEvent(
                IntegrationKind.GITLAB,
                prepared.item().workspaceId(),
                prepared.item().itemId(),
                "/hephaestus review",
                commenter.getLogin(),
                providerId(commenter),
                commenter.getNativeId(),
                noteId,
                null)));
    }

    private long account(User user) {
        return Objects.requireNonNull(transactions.execute(ignored -> identities
                .findActiveByProviderSubject(providerId(user), Long.toString(user.getNativeId()), null)
                .orElseThrow()
                .getAccount()
                .getId()));
    }

    private long providerId(User user) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT provider_id FROM \"user\" WHERE id = ?", Long.class, user.getId()));
    }

    private ItemEvent event(Prepared prepared) {
        return events.findByWorkspaceIdAndItemIdAndNameAndItemVersion(
                        prepared.item().workspaceId(),
                        prepared.item().itemId(),
                        prepared.name().value(),
                        prepared.version().value())
                .orElseThrow();
    }

    private void publish(Prepared prepared) {
        transactions.executeWithoutResult(ignored -> publisher.publishEvent(prepared.event()));
    }

    private Prepared prepare(IdentityProviderType type, boolean reviewed) {
        return prepare(type, reviewed, Map.of(), false);
    }

    private Prepared prepare(
            IdentityProviderType type, boolean reviewed, Map<String, Set<String>> state, boolean draft) {
        return prepare(type, reviewed, state, draft, List.of());
    }

    private Prepared prepare(
            IdentityProviderType type,
            boolean reviewed,
            Map<String, Set<String>> state,
            boolean draft,
            List<ItemEventName> alsoOn) {
        return prepare(type, reviewed, state, draft, alsoOn, null);
    }

    /** @param checks how the practice reads the pull request's checks, or null when it does not read them */
    private Prepared prepare(
            IdentityProviderType type,
            boolean reviewed,
            Map<String, Set<String>> state,
            boolean draft,
            List<ItemEventName> alsoOn,
            @Nullable ReadNeed checks) {
        var fixture = fixture(type);
        var workspace = fixture.workspace();
        workspace.getFeatures().setPracticesEnabled(true);
        workspaces.saveAndFlush(workspace);
        TestUserFactory.ensureAccountForUser(accounts, identities, fixture.author());
        long account = Objects.requireNonNull(identities
                .findActiveByProviderSubject(
                        Objects.requireNonNull(fixture.provider().getId()),
                        Long.toString(fixture.author().getNativeId()),
                        null)
                .orElseThrow()
                .getAccount()
                .getId());
        jdbc.update(
                "INSERT INTO membership(workspace_id,account_id,role,role_source,joined_at) VALUES (?,?,'OWNER','PERSON',now())",
                workspace.getId(),
                account);
        var kind = reviewed ? ScmItemEvents.PULL_REQUEST_REVIEW : ScmItemEvents.PULL_REQUEST;
        var name = reviewed ? ScmItemEvents.PULL_REQUEST_REVIEW_SUBMITTED : ScmItemEvents.PULL_REQUEST_OPENED;
        var original = DefinitionTestFixtures.definition(kind, "Describe the work", "Explain the work.", null);
        var definition = new PracticeDefinition(
                original.name(),
                new ReviewOn(Stream.concat(Stream.of(name), alsoOn.stream()).toList(), state),
                original.appliesWhen(),
                reviewed ? AboutRole.REVIEWER : AboutRole.AUTHOR,
                checks == null
                        ? original.reads()
                        : new PracticeReads(
                                original.reads().contract(),
                                Stream.concat(
                                                original.reads().sources().stream(),
                                                Stream.of(new PracticeReadSource(PullRequestChecksRecord.KIND, checks)))
                                        .toList()),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
        versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(), "event-test", kind, definition, null, 0, account));
        var pull = new PullRequest();
        pull.setNativeId(nativeId());
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(3);
        pull.setTitle("A bounded change");
        pull.setBody("Describe this change");
        pull.setState(Issue.State.OPEN);
        pull.setDraft(draft);
        pull.setAuthor(fixture.author());
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/3");
        pull.setCreatedAt(Instant.now());
        pull.setUpdatedAt(Instant.now());
        pull.setBaseRefName("main");
        pull.setHeadRefName("topic");
        pull.setBaseRefOid("a".repeat(40));
        pull.setHeadRefOid("b".repeat(40));
        pull = pulls.saveAndFlush(pull);
        var holder = writer.upsert(new ItemRegistration(
                workspace.getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var ref = writer.upsert(new ItemRegistration(
                workspace.getId(), ScmItemEvents.PULL_REQUEST, pull.getId(), holder.itemId(), null));
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                null,
                DataSource.WEBHOOK,
                "created",
                UUID.randomUUID().toString(),
                type,
                fixture.author().getId());
        ScmDomainEvent.Event event;
        if (reviewed) {
            var review = new PullRequestReview();
            review.setProvider(fixture.provider());
            review.setNativeId(nativeId());
            review.setPullRequest(pull);
            review.setAuthor(fixture.author());
            review.setBody("Review this change");
            review.setState(PullRequestReview.State.COMMENTED);
            review.setCommitId(pull.getHeadRefOid());
            review.setSubmittedAt(Instant.now());
            review.setHtmlUrl(pull.getHtmlUrl() + "#review");
            review = providerReviews.saveAndFlush(review);
            ref = writer.upsert(new ItemRegistration(
                    workspace.getId(), ScmItemEvents.PULL_REQUEST_REVIEW, review.getId(), ref.itemId(), null));
            event = new ScmDomainEvent.ReviewSubmitted(
                    ScmEventPayload.ReviewData.from(review).orElseThrow(), context);
        } else {
            event = new ScmDomainEvent.PullRequestCreated(ScmEventPayload.PullRequestData.from(pull), context);
        }
        return new Prepared(ref, source.pin(ref, null).version(), name, event);
    }

    /**
     * A merged pull request reported by its merge. Its author, its merger and the person who approved it before the
     * merge are three people; the practice is about the merger and keeps the fixture definition's criteria.
     */
    private MergedWork mergedWork(IdentityProviderType type) {
        var fixture = fixture(type);
        var workspace = fixture.workspace();
        workspace.getFeatures().setPracticesEnabled(true);
        workspaces.saveAndFlush(workspace);
        var author = fixture.author();
        var merger = users.saveAndFlush(
                TestUserFactory.createUser(nativeId(), author.getLogin() + "-merger", fixture.provider()));
        var approver = users.saveAndFlush(
                TestUserFactory.createUser(nativeId(), author.getLogin() + "-approver", fixture.provider()));
        TestUserFactory.ensureAccountForUser(accounts, identities, author);
        TestUserFactory.ensureAccountForUser(accounts, identities, merger);
        long authorAccount = account(author);
        jdbc.update(
                "INSERT INTO membership(workspace_id,account_id,role,role_source,joined_at) VALUES (?,?,'OWNER','PERSON',now())",
                workspace.getId(),
                authorAccount);
        jdbc.update(
                "INSERT INTO membership(workspace_id,account_id,role,joined_at) VALUES (?,?,'MEMBER',now())",
                workspace.getId(),
                account(merger));
        var original = DefinitionTestFixtures.definition(
                ScmItemEvents.PULL_REQUEST, "Describe the work", "Explain the work.", null);
        var definition = new PracticeDefinition(
                original.name(),
                new ReviewOn(List.of(ScmItemEvents.PULL_REQUEST_MERGED), Map.of()),
                original.appliesWhen(),
                AboutRole.MERGER,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
        versions.create(new PracticeVersionService.NewPractice(
                workspace.getId(), "event-test", ScmItemEvents.PULL_REQUEST, definition, null, 0, authorAccount));
        // Whole seconds, so the stored and the captured times compare exactly.
        Instant mergedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(3600);
        var pull = new PullRequest();
        pull.setNativeId(nativeId());
        pull.setProvider(fixture.provider());
        pull.setRepository(fixture.repository());
        pull.setNumber(3);
        pull.setTitle("A bounded change");
        pull.setBody("Describe this change");
        pull.setState(Issue.State.MERGED);
        pull.setMerged(true);
        pull.setMergedAt(mergedAt);
        pull.setClosedAt(mergedAt);
        pull.setMergedBy(merger);
        pull.setAuthor(author);
        pull.setHtmlUrl(fixture.repository().getHtmlUrl() + "/pull/3");
        pull.setCreatedAt(mergedAt.minusSeconds(7200));
        pull.setUpdatedAt(mergedAt);
        pull.setBaseRefName("main");
        pull.setHeadRefName("topic");
        pull.setBaseRefOid("a".repeat(40));
        pull.setHeadRefOid("b".repeat(40));
        pull = pulls.saveAndFlush(pull);
        Instant approvedAt = mergedAt.minusSeconds(600);
        var approval = approve(Objects.requireNonNull(pull.getId()), approver, approvedAt, "b".repeat(40));
        var holder = writer.upsert(new ItemRegistration(
                workspace.getId(),
                ScmItemEvents.REPOSITORY,
                fixture.repository().getId(),
                null,
                null));
        var ref = writer.upsert(new ItemRegistration(
                workspace.getId(), ScmItemEvents.PULL_REQUEST, pull.getId(), holder.itemId(), null));
        var context = new EventContext(
                UUID.randomUUID(),
                Instant.now(),
                workspace.getId(),
                null,
                DataSource.WEBHOOK,
                "merge",
                UUID.randomUUID().toString(),
                type,
                merger.getId());
        var event = new ScmDomainEvent.PullRequestMerged(ScmEventPayload.PullRequestData.from(pull), context);
        var prepared = new Prepared(ref, source.pin(ref, null).version(), ScmItemEvents.PULL_REQUEST_MERGED, event);
        return new MergedWork(prepared, fixture.provider(), author, merger, approver, approval, approvedAt, mergedAt);
    }

    private record MergedWork(
            Prepared prepared,
            IdentityProvider provider,
            User author,
            User merger,
            User approver,
            PullRequestReview approval,
            Instant approvedAt,
            Instant mergedAt) {}

    /** An approval of the pull request {@code pullId} by {@code approver}, submitted on {@code commit}. */
    private PullRequestReview approve(long pullId, User approver, Instant submittedAt, String commit) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            var pull = pulls.findById(pullId).orElseThrow();
            var review = new PullRequestReview();
            review.setProvider(pull.getProvider());
            review.setNativeId(nativeId());
            review.setPullRequest(pull);
            review.setAuthor(users.findById(approver.getId()).orElseThrow());
            review.setState(PullRequestReview.State.APPROVED);
            review.setCommitId(commit);
            review.setSubmittedAt(submittedAt);
            review.setHtmlUrl(pull.getHtmlUrl() + "#approval-" + review.getNativeId());
            return providerReviews.saveAndFlush(review);
        }));
    }

    private static long nativeId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    @Test
    void shouldHoldANewerOccasionWhileAnEarlierReviewMeasuresItsPracticeAndRunItWhenThatReviewEnds() {
        var prepared = prepare(
                IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
        long workspace = prepared.item().workspaceId();
        publish(prepared);
        var first = reviews.findByWorkspaceIdAndEventId(
                        workspace, event(prepared).getId())
                .orElseThrow();

        deliver(prepared, "synchronize", pullRequestPayload(prepared, "c".repeat(40), Instant.now()), "b".repeat(40));
        var pushed = occasion(prepared, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED);
        assertThat(pushed.getState()).isEqualTo(ItemEventState.WAITING);
        assertThat(pushed.getReason()).isEqualTo(ReviewRequestReason.REVIEW_IN_PROGRESS);
        assertThat(reviews.findByWorkspaceIdAndEventId(workspace, pushed.getId()))
                .isEmpty();

        // While the earlier review runs, the closed window is offered again and still waits.
        closeWindows(prepared);
        assertThat(occasion(prepared, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED).getState())
                .isEqualTo(ItemEventState.WAITING);

        jdbc.update(
                "UPDATE review SET status='CANCELLED', stop_reason='CANCELLED', completed_at=now() WHERE id=?",
                first.getId());
        closeWindows(prepared);
        // The push also recorded the edit of its head: one review runs at the newest occasion of that window.
        assertThat(itemEvents(prepared))
                .filteredOn(event -> !event.getName().equals(ScmItemEvents.PULL_REQUEST_OPENED.value()))
                .extracting(ItemEvent::getState)
                .containsOnly(ItemEventState.STARTED, ItemEventState.WITHHELD)
                .containsOnlyOnce(ItemEventState.STARTED);
        assertThat(itemReviews(prepared))
                .hasSize(2)
                .filteredOn(review -> !review.getId().equals(first.getId()))
                .singleElement()
                .satisfies(review -> assertThat(review.getItemVersion()).isEqualTo(pushed.getItemVersion()));
    }

    @Test
    void shouldWithholdAContinuingOccasionWhenEverySelectedPracticeIsCurrentButMeasureAgainOnAMilestone() {
        var prepared = prepare(
                IdentityProviderType.GITHUB,
                false,
                Map.of(),
                false,
                List.of(ScmItemEvents.PULL_REQUEST_CHECKS_COMPLETED, ScmItemEvents.PULL_REQUEST_READY));
        long workspace = prepared.item().workspaceId();
        publish(prepared);
        var first = reviews.findByWorkspaceIdAndEventId(
                        workspace, event(prepared).getId())
                .orElseThrow();
        var practice = practices.findByWorkspaceId(workspace).getFirst();
        jdbc.update(
                """
                INSERT INTO result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,
                    outcome,evidence,coverage,recorded_at)
                VALUES(?,?,?,?,?,?,?,'MET','{}'::jsonb,'{}'::jsonb,now())
                """,
                UUID.randomUUID(),
                workspace,
                first.getId(),
                practice.getId(),
                practice.getCurrentVersion().getId(),
                prepared.item().itemId(),
                prepared.version().value());
        jdbc.update("UPDATE review SET status='COMPLETE', completed_at=now() WHERE id=?", first.getId());

        settleChecks(prepared);
        var checks = occasion(prepared, ScmItemEvents.PULL_REQUEST_CHECKS_COMPLETED);
        assertThat(checks.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(checks.getReason()).isEqualTo(ReviewRequestReason.ALREADY_CURRENT);

        // Ready is a milestone: it measures the practice again although its result is current.
        deliver(prepared, "ready_for_review", pullRequestPayload(prepared, "b".repeat(40), Instant.now()));
        var ready = occasion(prepared, ScmItemEvents.PULL_REQUEST_READY);
        assertThat(ready.getState()).isEqualTo(ItemEventState.STARTED);
    }

    @Test
    void shouldWithholdAPracticeThatNeedsTheChecksUntilTheyFinishAndRunItOnTheChecksEvent() {
        var prepared = prepare(IdentityProviderType.GITHUB, false, Map.of(), false, List.of(), ReadNeed.REQUIRED);
        long workspace = prepared.item().workspaceId();
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            pull.observeHeadChecks(Objects.requireNonNull(pull.getHeadRefOid()), CheckState.PENDING, true);
            pulls.saveAndFlush(pull);
        });
        publish(prepared);
        var opened = event(prepared);
        assertThat(opened.getState()).isEqualTo(ItemEventState.STARTED);
        var waiting =
                reviews.findByWorkspaceIdAndEventId(workspace, opened.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(ReviewStatus.PARTIAL);
        assertThat(memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspace, waiting.getId()))
                .singleElement()
                .satisfies(member -> {
                    assertThat(member.getState()).isEqualTo(ReviewItemState.WITHHELD);
                    assertThat(member.getReason()).isEqualTo("CHECKS_PENDING");
                });

        // No practice selects Checks finished: the practice that waits for the checks is selected by it.
        settleChecks(prepared);
        var checks = occasion(prepared, ScmItemEvents.PULL_REQUEST_CHECKS_COMPLETED);
        assertThat(checks.getState()).isEqualTo(ItemEventState.STARTED);
        var measured =
                reviews.findByWorkspaceIdAndEventId(workspace, checks.getId()).orElseThrow();
        assertThat(memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspace, measured.getId()))
                .singleElement()
                .satisfies(member -> assertThat(member.getState()).isEqualTo(ReviewItemState.LISTED));
    }

    @Test
    void shouldNeverWaitForTheChecksOfAPracticeThatReadsThemAsOptional() {
        var prepared = prepare(IdentityProviderType.GITHUB, false, Map.of(), false, List.of(), ReadNeed.OPTIONAL);
        long workspace = prepared.item().workspaceId();
        transactions.executeWithoutResult(ignored -> {
            var pull = pulls.findById(Objects.requireNonNull(prepared.item().sourceId()))
                    .orElseThrow();
            pull.observeHeadChecks(Objects.requireNonNull(pull.getHeadRefOid()), CheckState.PENDING, true);
            pulls.saveAndFlush(pull);
        });
        publish(prepared);
        var review = reviews.findByWorkspaceIdAndEventId(
                        workspace, event(prepared).getId())
                .orElseThrow();
        assertThat(memberships.findByWorkspaceIdAndReviewIdOrderByPracticeIdAscItemIdAsc(workspace, review.getId()))
                .singleElement()
                .satisfies(member -> assertThat(member.getState()).isEqualTo(ReviewItemState.LISTED));
    }

    @Nested
    @TestPropertySource(properties = "hephaestus.item-event-ledger.quiet-window=2m")
    class QuietWindow {
        @Test
        void shouldStartOneReviewAtTheNewestOccasionWhenTheWindowCloses() {
            var prepared = prepare(
                    IdentityProviderType.GITHUB,
                    false,
                    Map.of(),
                    false,
                    List.of(ScmItemEvents.PULL_REQUEST_SYNCHRONIZED));
            long workspace = prepared.item().workspaceId();
            publish(prepared);
            var opened = event(prepared);
            assertThat(opened.getState()).isEqualTo(ItemEventState.WAITING);
            assertThat(opened.getReason()).isEqualTo(ReviewRequestReason.QUIET_WINDOW);
            assertThat(opened.getRetryAt()).isAfter(Instant.now().plusSeconds(60));

            deliver(
                    prepared,
                    "synchronize",
                    pullRequestPayload(prepared, "c".repeat(40), Instant.now()),
                    "b".repeat(40));
            var pushed = occasion(prepared, ScmItemEvents.PULL_REQUEST_SYNCHRONIZED);
            assertThat(pushed.getState()).isEqualTo(ItemEventState.WAITING);
            assertThat(pushed.getReason()).isEqualTo(ReviewRequestReason.QUIET_WINDOW);
            assertThat(itemReviews(prepared)).isEmpty();

            // A window that has not closed is not offered.
            closeWindows(prepared, false);
            assertThat(itemReviews(prepared)).isEmpty();

            jdbc.update(
                    "UPDATE item_event SET decided_at = decided_at - interval '3 minutes' WHERE workspace_id=? AND item_id=?",
                    workspace,
                    prepared.item().itemId());
            closeWindows(prepared);
            // The push also recorded the edit of its head: one review runs at the newest occasion, at the pushed head.
            assertThat(itemReviews(prepared))
                    .singleElement()
                    .satisfies(review -> assertThat(review.getItemVersion()).isEqualTo(pushed.getItemVersion()));
            assertThat(itemEvents(prepared))
                    .extracting(ItemEvent::getState)
                    .containsOnly(ItemEventState.STARTED, ItemEventState.WITHHELD)
                    .containsOnlyOnce(ItemEventState.STARTED);
            var coalesced = events.findById(opened.getId()).orElseThrow();
            assertThat(coalesced.getState()).isEqualTo(ItemEventState.WITHHELD);
            assertThat(coalesced.getReason()).isEqualTo(ReviewRequestReason.COALESCED);
        }

        @Test
        void shouldDecideAMergeAtOnceWithoutClosingTheWindow() {
            var prepared = prepare(
                    IdentityProviderType.GITHUB, false, Map.of(), false, List.of(ScmItemEvents.PULL_REQUEST_MERGED));
            publish(prepared);
            assertThat(event(prepared).getState()).isEqualTo(ItemEventState.WAITING);
            var merged = Objects.requireNonNull(transactions.execute(ignored -> itemEvents.record(new ItemEventInput(
                    prepared.item().workspaceId(),
                    prepared.item().itemId(),
                    ScmItemEvents.PULL_REQUEST_MERGED,
                    prepared.version().value(),
                    Instant.now(),
                    FoundBy.WEBHOOK,
                    null,
                    null))));
            assertThat(events.findById(merged).orElseThrow().getState())
                    .isNotIn(ItemEventState.RECORDED, ItemEventState.WAITING);
            assertThat(event(prepared).getState()).isEqualTo(ItemEventState.WAITING);
        }
    }

    private ItemEvent occasion(Prepared prepared, ItemEventName name) {
        return itemEvents(prepared).stream()
                .filter(event -> event.getName().equals(name.value()))
                .reduce((older, newer) -> newer)
                .orElseThrow();
    }

    /** Settles the head's checks, which records the checks event of the pull request. */
    private void settleChecks(Prepared prepared) {
        var item = prepared.item();
        var pull = pulls.findById(Objects.requireNonNull(item.sourceId())).orElseThrow();
        long repositoryId = Objects.requireNonNull(pull.getRepository()).getId();
        var context = Objects.requireNonNull(transactions.execute(status -> ProcessingContext.forWebhook(
                item.workspaceId(), repositories.findById(repositoryId).orElseThrow(), "completed")));
        String head = Objects.requireNonNull(pull.getHeadRefOid());
        Instant readAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        checkRuns.observe(repositoryId, settled(head, List.of(checkRun("build", "success", readAt)), readAt), context);
    }

    private void closeWindows(Prepared prepared) {
        closeWindows(prepared, true);
    }

    /** Runs the quiet window closer once, with every window of the item due when {@code due}. */
    private void closeWindows(Prepared prepared, boolean due) {
        if (due)
            jdbc.update(
                    "UPDATE item_event SET retry_at = now() - interval '1 second' WHERE workspace_id=? AND item_id=? AND state='WAITING'",
                    prepared.item().workspaceId(),
                    prepared.item().itemId());
        var closer = new QuietWindowCloser(events, ledger, publisher, clock);
        transactions.executeWithoutResult(ignored -> closer.close());
    }

    private record Prepared(ItemRef item, ItemVersion version, ItemEventName name, ScmDomainEvent.Event event) {}

    private Fixture fixture(IdentityProviderType type) {
        String slug = "scm-source-" + UUID.randomUUID();
        String url = type == IdentityProviderType.GITHUB ? "https://github.com" : "https://gitlab.example.test";
        var provider = providers
                .findByTypeAndServerUrl(type, url)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(type, url)));
        Workspace workspace = type == IdentityProviderType.GITHUB
                ? WorkspaceTestFixtures.persistInstallationWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.installationWorkspace(
                                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), slug),
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                : WorkspaceTestFixtures.persistGitLabWorkspace(
                        workspaces,
                        connections,
                        WorkspaceTestFixtures.gitLabPatWorkspace(slug).withSlug(slug),
                        url);
        var repository = new Repository();
        repository.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        repository.setProvider(provider);
        repository.setName("source");
        repository.setNameWithOwner(slug + "/source");
        repository.setHtmlUrl(url + "/" + slug + "/source");
        repository.setDefaultBranch("main");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository.setPushedAt(Instant.now());
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository = repositories.saveAndFlush(repository);
        var monitor = WorkspaceTestFixtures.repositoryMonitor(
                workspace, repository.getNameWithOwner(), repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var author = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), slug + "-author", provider));
        return new Fixture(workspace, provider, repository, author);
    }

    private record Fixture(Workspace workspace, IdentityProvider provider, Repository repository, User author) {}
}
