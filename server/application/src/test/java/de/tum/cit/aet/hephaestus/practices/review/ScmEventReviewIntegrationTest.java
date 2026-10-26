package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

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
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
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
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
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
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicationContext;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackSendTransactions;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
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
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
    ReviewRepository reviews;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReviewService service;

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
    FeedbackSendRepository sends;

    @Autowired
    PendingItemEventReaper reaper;

    @Autowired
    ItemEventLedgerProperties ledger;

    @Autowired
    ItemEventService itemEvents;

    @Autowired
    ReviewSnapshotQuery snapshots;

    @Autowired
    Clock clock;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        when(budget.canRequest(anyLong(), eq(ReviewKind.ITEM), isNull())).thenReturn(true);
        when(models.models(anyLong(), anyList()))
                .thenReturn(List.of(
                        new BoundModelSlot("chat", ModelKind.CHAT, null),
                        new BoundModelSlot("decision", ModelKind.DECISION, null),
                        new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                        new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                        new BoundModelSlot(
                                "review",
                                ModelKind.CHAT,
                                new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of()))));
    }

    @AfterEach
    void restoreInstanceDeliveryBrake() {
        jdbc.update("UPDATE instance_settings SET silent_mode_engaged=true WHERE id=1");
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
        assertThat(sends.findById(oldPiece).orElseThrow().getState()).isEqualTo(FeedbackSendState.PENDING);

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

        String oldOwner = "owner-" + oldPiece;
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
        String newerOwner = "owner-" + newerPiece;
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
                        .currentWorkVersion(item.workspaceId(), item.itemId());
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

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepTheSubmittedReviewCurrentWhenOnlyTheAuthorPushes(IdentityProviderType type) {
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
        assertThat(result.getEndedAt()).isNull();
        assertThat(resultRows.forDevelopersSince(
                        item.workspaceId(), List.of(scenario.account().getId()), Instant.EPOCH, true))
                .extracting(row -> row.getId())
                .contains(result.getId());
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
                        "2.0.0",
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
                                admitted.getId(), UUID.randomUUID(), 1, Set.of(core), "2.0.0", 3, null, Set.of(core))))
                .isInstanceOfSatisfying(
                        ItemCaptureException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ItemCaptureCode.VERSION_UNAVAILABLE));
        // Another occasion ID names no occasion of this item, so nothing is pinned, neither old nor current work.
        assertOccasionUnavailable(prepared, prepared.version(), UUID.randomUUID());
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
                "2.0.0",
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
                admitted.getId(), UUID.randomUUID(), 1, Set.of(threads), "2.0.0", 3, null, Set.of(threads));
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
        assertThat(record).isEqualTo(retainedRecord);
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
                                "2.0.0",
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
        assertThat(occasions)
                .extracting(ItemEvent::getName)
                .containsExactlyInAnyOrder(
                        ScmItemEvents.PULL_REQUEST_READY.value(), ScmItemEvents.PULL_REQUEST_SYNCHRONIZED.value());
        assertThat(admitted).hasSize(2);

        // The provider delivers both again after the push: the ready payload still names the first head.
        deliver(prepared, "ready_for_review", ready);
        deliver(prepared, "synchronize", synchronize);

        assertThat(itemEvents(prepared))
                .extracting(ItemEvent::getId)
                .containsExactlyInAnyOrderElementsOf(
                        occasions.stream().map(ItemEvent::getId).toList());
        assertThat(itemReviews(prepared)).extracting(Review::getId).containsExactlyInAnyOrderElementsOf(admitted);
        assertThat(occasions)
                .filteredOn(occasion -> occasion.getName().equals(ScmItemEvents.PULL_REQUEST_READY.value()))
                .singleElement()
                .extracting(ItemEvent::getItemVersion)
                .isEqualTo(prepared.version().value());
    }

    private GitHubPullRequestDTO pullRequestPayload(Prepared prepared, String head, Instant updatedAt) {
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
                         "draft": false, "merged": false, "commits": 1,
                         "user": {"id": %d, "login": "%s", "type": "User"},
                         "head": {"ref": "topic", "sha": "%s"}, "base": {"ref": "main", "sha": "%s"}}
                        """.formatted(
                                        pull.getNativeId(),
                                        pull.getNumber(),
                                        pull.getTitle(),
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

    private void deliver(Prepared prepared, String action, GitHubPullRequestDTO payload) {
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
            else pullRequestProcessor.processSynchronize(payload, context);
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
                UUID.randomUUID(), UUID.randomUUID(), 1, Set.of(core), "2.0.0", 3, null, Set.of(core), occasion);
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
        var monitor = WorkspaceTestFixtures.repositoryMonitor(other.workspace(), repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
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
                .contains(current.getId());
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
                noteId)));
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
                "INSERT INTO membership(workspace_id,account_id,role,joined_at) VALUES (?,?,'OWNER',now())",
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
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
        versions.create(
                new PracticeVersionService.NewPractice(workspace, "event-test", kind, definition, null, 0, account));
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
                "INSERT INTO membership(workspace_id,account_id,role,joined_at) VALUES (?,?,'OWNER',now())",
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
                workspace, "event-test", ScmItemEvents.PULL_REQUEST, definition, null, 0, authorAccount));
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
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, repository.getNameWithOwner());
        monitor.setNativeId(repository.getNativeId());
        monitors.saveAndFlush(monitor);
        var author = users.saveAndFlush(TestUserFactory.createUser(
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), slug + "-author", provider));
        return new Fixture(workspace, provider, repository, author);
    }

    private record Fixture(Workspace workspace, IdentityProvider provider, Repository repository, User author) {}
}
