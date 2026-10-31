package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog.CopyUnavailableException;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RetainedFeedbackTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticePageSupportDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemSourceAccess;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationEligibility;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.testconfig.AdmittedReviewJobFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.PracticeModelTestFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FeedbackPrivateHistoryIntegrationTest extends BaseIntegrationTest {
    private static final Path FABRIC_ROOT = temporaryDirectory();
    private static final UUID WORKER_LEASE = UUID.fromString("3e9b7c41-58d2-4a6f-b0e1-7c2d9a4f6b18");
    private static final String ADMISSION_DIGEST = "a".repeat(64);
    private static final String ITEM_VERSION = "history-work-v1";
    private final Set<Long> unavailableItems = new HashSet<>();

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private PracticeModelTestFixtures fixtures;

    @Autowired
    private ResultAdmissionService admission;

    @Autowired
    private ReviewAdmissionData admissionData;

    @Autowired
    private ResultStore resultStore;

    @Autowired
    private ResultRepository results;

    @Autowired
    private ItemRepository items;

    @Autowired
    private FeedbackCopyRepository copies;

    @Autowired
    private FeedbackRepository feedbacks;

    @Autowired
    private FeedbackBasisRepository bases;

    @Autowired
    private LlmConnectionRepository connections;

    @Autowired
    private LlmModelRepository models;

    @Autowired
    private LlmModelResolver resolver;

    @Autowired
    private WorkspaceAgentBindingRepository bindings;

    @Autowired
    private JobEvidenceFiles files;

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private PersonDataCopyRecorder recorder;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private FeedbackDecisionRepository decisions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    // The native provider is outside this proof; authorization, history selection and copy custody stay real.
    @MockitoBean
    private ScmItemSourceAccess sourceAccess;

    @MockitoBean(name = "scmItemService", enforceOverride = true)
    private ScmItemService source;

    @MockitoSpyBean
    private FeedbackHistoryQueryService historyQuery;

    private ReviewIntegrationData.Scenario scenario;
    private IdentityProvider provider;

    @DynamicPropertySource
    static void evidenceStore(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.fabric.root", FABRIC_ROOT::toString);
    }

    @AfterAll
    static void removeEvidenceStore() throws IOException {
        FileUtils.deleteDirectory(FABRIC_ROOT.toFile());
    }

    @BeforeEach
    void setUpHistory() {
        databaseTestUtils.cleanDatabase();
        unavailableItems.clear();
        when(sourceAccess.kinds()).thenReturn(Set.of(new ItemKind("scm.pull_request")));
        when(sourceAccess.permits(any(), any()))
                .thenAnswer(call ->
                        !unavailableItems.contains(call.<ItemRef>getArgument(0).itemId()));
        when(source.supports(any()))
                .thenAnswer(call -> call.<ItemRef>getArgument(0).kind().equals(new ItemKind("scm.pull_request")));
        when(source.current(any()))
                .thenAnswer(call -> Optional.of(ItemFixtures.snapshot(call.getArgument(0), Map.of())
                        .version(new ItemVersion(ITEM_VERSION, null))
                        .build()));
        scenario = data.scenario();
        jdbc.update(
                "UPDATE practice SET feedback_mode='AUTOMATIC' WHERE workspace_id=? AND id=?",
                scenario.workspace().getId(),
                scenario.practice().getId());
        AdmittedReviewJobFixtures.snapshot(scenario.workspace(), connections, models, bindings, resolver, mapper);
        AdmittedReviewJobFixtures.mentorBinding(scenario.workspace(), bindings);
        provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://private-history.example"));
    }

    @Test
    void shouldReturnPrivateWitnessAndQueuedTargetOnlyAfterIndexingTheirActualCopies() {
        var delivered = historical(
                scenario,
                FeedbackDestination.CONVERSATION,
                FeedbackState.DELIVERED,
                "Actual earlier conversation words.",
                "101");
        var queued = historical(
                scenario, FeedbackDestination.CONVERSATION, FeedbackState.READY, "An earlier queued concern.", "102");
        var closedPage = historical(
                scenario,
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.DELIVERED,
                "A recent card on a closed page route.",
                "103");
        var sibling = data.scenario();
        var foreign = historical(
                sibling,
                FeedbackDestination.PRACTICE_PAGE,
                FeedbackState.DELIVERED,
                "Another workspace's private card.",
                "201");
        var otherDeveloper = new ReviewIntegrationData.Scenario(
                scenario.workspace(), data.member(scenario.workspace().getId()), scenario.practice(), scenario.item());
        var other = historical(
                otherDeveloper,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                "Another developer's queued concern.",
                "202");
        var onWork = historical(
                scenario,
                FeedbackDestination.ON_WORK,
                FeedbackState.DELIVERED,
                "Public words are not a private-delivery witness.",
                "203");
        try (var captured = admittedResult(scenario, Instant.now(), "999")) {
            var current = captured.job();
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            assertThat(copiedJobs(current)).isEmpty();
            var response = admission.admittedResponse(current);
            assertThat(response.admissionDigest()).isEqualTo(ADMISSION_DIGEST);
            assertThat(response.attempt()).isZero();
            assertThat(response.admitted()).hasSize(1);
            assertConversationOpen(response);
            assertPageClosed(response);
            var history = Objects.requireNonNull(response.privateHistory());
            assertThat(history.coverage().omittedWithoutCopyAuthority()).isZero();
            assertThat(history.delivered()).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(delivered.feedback().getId());
                assertThat(row.destination()).isEqualTo(FeedbackDestination.CONVERSATION);
                assertThat(row.bodyRole()).isEqualTo(PrivateFeedbackHistoryDTO.BodyRole.CONVERSATION_WORDS);
                assertThat(row.body()).isEqualTo("Actual earlier conversation words.");
                assertThat(row.recordedClaimCurrentness()).isEqualTo(Currentness.CURRENT);
                assertThat(row.basedOn()).singleElement().satisfies(basis -> {
                    assertThat(basis.resultId()).isEqualTo(delivered.result().getId());
                    assertThat(basis.practiceSlug())
                            .isEqualTo(scenario.practice().getSlug());
                    assertThat(basis.outcome()).isEqualTo(ResultOutcome.NOT_MET);
                });
            });
            assertThat(history.queued()).singleElement().satisfies(row -> {
                assertThat(row.feedbackId()).isEqualTo(queued.feedback().getId());
                assertThat(row.threadKey()).isEqualTo(queued.feedback().getId().toString());
                assertThat(row.destination()).isEqualTo(FeedbackDestination.CONVERSATION);
                assertThat(row.practiceSlug()).isEqualTo(scenario.practice().getSlug());
                assertThat(row.bodyRole()).isEqualTo(PrivateFeedbackHistoryDTO.BodyRole.MENTOR_NOTES);
                assertThat(row.body()).isEqualTo("An earlier queued concern.");
            });
            assertThat(copiedJobs(current))
                    .containsExactlyInAnyOrder(
                            delivered.job().getId().toString(),
                            queued.job().getId().toString());
            assertThat(copiedSubjects(current)).containsExactlyInAnyOrder("999", "101", "102");
            assertThat(copiedJobs(current))
                    .doesNotContain(
                            closedPage.job().getId().toString(),
                            foreign.job().getId().toString(),
                            other.job().getId().toString(),
                            onWork.job().getId().toString());
            assertThat(receipt(current).path("dependencies").asString()).isEqualTo("COMPLETE");
        }
    }

    @Test
    void shouldRefreshWithdrawalAndLostSourceAccessWithoutReturningTheirWords() {
        var delivered = historical(
                scenario,
                FeedbackDestination.CONVERSATION,
                FeedbackState.DELIVERED,
                "Words that will be withdrawn.",
                "301");
        var queued = historical(
                scenario,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                "Notes whose source access will be lost.",
                "302");
        try (var captured = admittedResult(scenario, Instant.now(), "999")) {
            var current = captured.job();
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            var firstResponse = admission.admittedResponse(current);
            assertConversationOpen(firstResponse);
            var first = Objects.requireNonNull(firstResponse.privateHistory());
            assertThat(first.delivered())
                    .singleElement()
                    .extracting(PrivateFeedbackHistoryDTO.Delivered::body)
                    .isEqualTo("Words that will be withdrawn.");
            assertThat(first.queued())
                    .singleElement()
                    .extracting(PrivateFeedbackHistoryDTO.Queued::feedbackId)
                    .isEqualTo(queued.feedback().getId());
            decisions.saveAndFlush(FeedbackDecision.builder()
                    .id(UUID.randomUUID())
                    .workspaceId(scenario.workspace().getId())
                    .feedbackId(delivered.feedback().getId())
                    .actorAccountId(scenario.account().getId())
                    .action(FeedbackDecisionAction.WITHDRAW)
                    .reason("The earlier wording is wrong.")
                    .decidedAt(Instant.now())
                    .build());

            var withdrawn =
                    Objects.requireNonNull(admission.admittedResponse(current).privateHistory());
            assertThat(withdrawn.delivered()).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(delivered.feedback().getId());
                assertThat(row.withdrawn()).isTrue();
                assertThat(row.body()).isNull();
                assertThat(row.bodyRole()).isNull();
            });
            unavailableItems.add(delivered.result().getItemId());
            unavailableItems.add(queued.result().getItemId());
            var refusedResponse = admission.admittedResponse(current);
            assertConversationOpen(refusedResponse);
            var refused = Objects.requireNonNull(refusedResponse.privateHistory());
            assertThat(refused.delivered()).isEmpty();
            assertThat(refused.queued()).isEmpty();
            assertThat(refused.coverage().omittedWithoutCopyAuthority()).isEqualTo(2);
            assertThat(copiedJobs(current))
                    .containsExactlyInAnyOrder(
                            delivered.job().getId().toString(),
                            queued.job().getId().toString());
            assertThat(receipt(current).path("dependencies").asString()).isEqualTo("COMPLETE");
        }
    }

    @Test
    void shouldRefuseTheHistoryResponseWhenTheCurrentAttemptsCopyWasRevoked() {
        historical(
                scenario,
                FeedbackDestination.CONVERSATION,
                FeedbackState.DELIVERED,
                "Authorized historical words.",
                "401");
        try (var captured = admittedResult(scenario, Instant.now(), "999")) {
            var current = captured.job();
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            var response = admission.admittedResponse(current);
            assertConversationOpen(response);
            assertThat(Objects.requireNonNull(response.privateHistory()).delivered())
                    .hasSize(1);
            jdbc.update(
                    "UPDATE person_evidence_copy SET state='ERASE_REQUESTED' WHERE workspace_id=? AND job_id=?",
                    current.getWorkspaceId(),
                    current.getId());
            String before = receipt(current).toString();
            assertThatThrownBy(() -> admission.admittedResponse(current)).isInstanceOf(CopyUnavailableException.class);
            assertThat(jdbc.queryForObject(
                            "SELECT state FROM person_evidence_copy WHERE workspace_id=? AND job_id=?",
                            String.class,
                            current.getWorkspaceId(),
                            current.getId()))
                    .isEqualTo("ERASE_REQUESTED");
            assertThat(receipt(current).toString()).isEqualTo(before);
        }
    }

    @Test
    void shouldRetainShownConversationWordsWhenTheRealResultWriterReplacesTheirClaim() {
        Result original;
        ReviewJob originalJob;
        try (var captured = admittedResult(scenario, Instant.now().minusSeconds(600), "501")) {
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            original = captured.result();
            originalJob = captured.job();
        }
        var shown = fixtures.feedback(
                original,
                FeedbackDestination.CONVERSATION,
                FeedbackState.DELIVERED,
                "Internal notes that were never shown.");
        postedConversation(shown, "The words actually delivered in conversation.");
        try (var captured = admittedResult(scenario, Instant.now(), "999")) {
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            var current = captured.job();
            var replacement = captured.result();
            var old = results.findByIdAndWorkspaceId(original.getId(), original.getWorkspaceId())
                    .orElseThrow();
            assertThat(old.getEndReason()).isEqualTo(ResultEndReason.REPLACED);
            assertThat(old.getEndedAt()).isNotNull();
            assertThat(replacement.getId()).isNotEqualTo(original.getId());
            assertThat(replacement.getEndedAt()).isNull();
            var response = admission.admittedResponse(current);
            assertConversationOpen(response);
            assertThat(response.admitted())
                    .singleElement()
                    .satisfies(row -> assertThat(row.resultId()).isEqualTo(replacement.getId()));
            var history = Objects.requireNonNull(response.privateHistory());
            assertThat(history.delivered()).singleElement().satisfies(row -> {
                assertThat(row.id()).isEqualTo(shown.getId());
                assertThat(row.recordedClaimCurrentness()).isEqualTo(Currentness.STALE);
                assertThat(row.bodyRole()).isEqualTo(PrivateFeedbackHistoryDTO.BodyRole.CONVERSATION_WORDS);
                assertThat(row.body())
                        .isEqualTo("The words actually delivered in conversation.")
                        .isNotEqualTo(shown.getBody());
                assertThat(row.basedOn())
                        .singleElement()
                        .satisfies(basis -> assertThat(basis.resultId()).isEqualTo(original.getId()));
            });
            assertThat(history.queued()).isEmpty();
            assertThat(copiedJobs(current)).containsExactly(originalJob.getId().toString());
            assertThat(copiedSubjects(current)).containsExactlyInAnyOrder("999", "501");
            assertThat(receipt(current).path("dependencies").asString()).isEqualTo("COMPLETE");
            assertThat(results.findByIdAndWorkspaceId(original.getId(), original.getWorkspaceId())
                            .orElseThrow()
                            .getEndReason())
                    .isEqualTo(ResultEndReason.REPLACED);
        }
    }

    @Test
    void shouldNotReadOrIndexPrivateHistoryWhenNoPrivateRouteIsPermitted() {
        historical(
                scenario,
                FeedbackDestination.CONVERSATION,
                FeedbackState.DELIVERED,
                "Historical words for a route that is now closed.",
                "601");
        historical(
                scenario,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                "Queued notes for a route that is now closed.",
                "602");
        try (var captured = admittedResult(scenario, Instant.now(), "999")) {
            var current = captured.job();
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            jdbc.update(
                    "UPDATE practice SET feedback_mode='OFF' WHERE workspace_id=? AND id=?",
                    scenario.workspace().getId(),
                    scenario.practice().getId());
            var before = receipt(current).toString();
            clearInvocations(historyQuery);
            var response = admission.admittedResponse(current);
            assertThat(Objects.requireNonNull(response.conversationEligibility())
                            .results())
                    .singleElement()
                    .satisfies(row -> assertThat(row.decision()).isEqualTo(ConversationDecision.FEEDBACK_OFF));
            var page = (PracticePageSupportDTO.Complete) Objects.requireNonNull(response.practicePageSupport());
            assertThat(page.practices())
                    .singleElement()
                    .satisfies(row -> assertThat(row.decision()).isEqualTo(PracticePageDecision.FEEDBACK_OFF));
            assertThat(response.privateHistory()).isNull();
            verifyNoInteractions(historyQuery);
            assertThat(copiedJobs(current)).isEmpty();
            assertThat(copiedSubjects(current)).containsExactly("999");
            assertThat(receipt(current).toString()).isEqualTo(before);
        }
    }

    @Test
    void shouldKeepConversationOpenWhenTheResultWasAlreadyConfirmedOnTheWork() {
        var privateWords = "Words Hephaestus said in an earlier conversation.";
        var privateWitness =
                historical(scenario, FeedbackDestination.CONVERSATION, FeedbackState.DELIVERED, privateWords, "701");
        try (var captured = admittedResult(scenario, Instant.now(), "999")) {
            var current = captured.job();
            var result = captured.result();
            long workspaceId = scenario.workspace().getId();
            long developerId = Objects.requireNonNull(scenario.account().getId());
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            assertThat(result.getOutcome()).isEqualTo(ResultOutcome.NOT_MET);
            assertThat(result.getDeveloperId()).isEqualTo(developerId);

            var publicWords = "The summary posted on the work about this result.";
            var onWork = fixtures.feedback(result, FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, publicWords);
            copies.saveAndFlush(RetainedFeedbackTestData.posted(
                    onWork,
                    FeedbackCopyKind.SUMMARY,
                    0,
                    "note-confirmed-on-work",
                    "https://private-history.example/merge_requests/1#note_1"));

            assertThat(feedbacks.findByIdAndWorkspaceId(onWork.getId(), workspaceId))
                    .as("the public feedback this result's developer received on the work")
                    .hasValueSatisfying(row -> {
                        assertThat(row.getDestination()).isEqualTo(FeedbackDestination.ON_WORK);
                        assertThat(row.getState()).isEqualTo(FeedbackState.DELIVERED);
                        assertThat(row.getDeveloperId()).isEqualTo(developerId);
                        assertThat(row.getBody()).isEqualTo(publicWords);
                        assertThat(row.getDeliveredAt()).isNotNull();
                    });
            assertThat(bases.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(workspaceId, onWork.getId()))
                    .as("the public feedback rests on this result")
                    .singleElement()
                    .satisfies(basis -> {
                        assertThat(basis.getResultId()).isEqualTo(result.getId());
                        assertThat(basis.getDeveloperId()).isEqualTo(developerId);
                    });
            assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspaceId, onWork.getId()))
                    .as("its summary was posted on the work")
                    .singleElement()
                    .satisfies(copy -> {
                        assertThat(copy.getKind()).isEqualTo(FeedbackCopyKind.SUMMARY);
                        assertThat(copy.getState()).isEqualTo(FeedbackCopyState.POSTED);
                        assertThat(copy.getPostedAt()).isNotNull();
                        assertThat(copy.getExternalRef()).isEqualTo("note-confirmed-on-work");
                        assertThat(copy.getBody())
                                .as("a summary copy keeps no words; they are the feedback's")
                                .isNull();
                    });

            var response = admission.admittedResponse(current);

            // A posted public summary is not a reason to close the private conversation route.
            assertConversationOpen(response);
            var history = Objects.requireNonNull(response.privateHistory());
            assertThat(history.delivered())
                    .as("the private witness is delivery history; the public words on the work are not")
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.id())
                                .isEqualTo(privateWitness.feedback().getId())
                                .isNotEqualTo(onWork.getId());
                        assertThat(row.destination()).isEqualTo(FeedbackDestination.CONVERSATION);
                        assertThat(row.body()).isEqualTo(privateWords).isNotEqualTo(publicWords);
                    });
            assertThat(history.queued())
                    .as("public feedback is never a queued private replacement target")
                    .extracting(PrivateFeedbackHistoryDTO.Queued::feedbackId)
                    .doesNotContain(onWork.getId());
        }
    }

    private Historical historical(
            ReviewIntegrationData.Scenario about,
            FeedbackDestination destination,
            FeedbackState state,
            String body,
            String subject) {
        try (var captured = admittedResult(anotherItem(about), Instant.now().minusSeconds(600), subject)) {
            assertThat(captured.inputs().filesOnDisk()).containsKey("context/history-fixture.txt");
            var result = captured.result();
            var feedback = fixtures.feedback(
                    result,
                    destination,
                    state,
                    destination == FeedbackDestination.CONVERSATION && state == FeedbackState.DELIVERED
                            ? "Internal preparation notes, not the shown words."
                            : body);
            if (destination == FeedbackDestination.CONVERSATION && state == FeedbackState.DELIVERED)
                postedConversation(feedback, body);
            assertThat(receipt(captured.job()).path("dependencies").asString()).isEqualTo("COMPLETE");
            return new Historical(result, feedback, captured.job());
        }
    }

    private CapturedResult admittedResult(ReviewIntegrationData.Scenario about, Instant orderedAt, String subject) {
        var pending = admissionData.pending(about, orderedAt, about.account().getId(), ITEM_VERSION);
        var job = ownedPending(pending);
        var inputs = capture(job, subject);
        try {
            var result = admitResult(pending);
            jdbc.update(
                    "UPDATE review_job SET admission_attempt=0,admission_digest=? WHERE workspace_id=? AND id=?",
                    ADMISSION_DIGEST,
                    job.getWorkspaceId(),
                    job.getId());
            return new CapturedResult(result, jobs.findById(job.getId()).orElseThrow(), inputs);
        } catch (RuntimeException | Error failure) {
            inputs.close();
            throw failure;
        }
    }

    private ReviewIntegrationData.Scenario anotherItem(ReviewIntegrationData.Scenario about) {
        var item = new Item();
        item.setWorkspaceId(about.workspace().getId());
        item.setKind(about.item().getKind());
        item.setSourceId(ThreadLocalRandom.current().nextLong(1000000000000L, Long.MAX_VALUE));
        item.setCreatedAt(Instant.now().minusSeconds(3600));
        return new ReviewIntegrationData.Scenario(
                about.workspace(), about.account(), about.practice(), items.saveAndFlush(item));
    }

    private ReviewJob ownedPending(ReviewAdmissionData.Pending pending) {
        jdbc.update(
                "UPDATE review_job SET lease_id=?, leased_until=now() + interval '10 minutes' WHERE workspace_id=? AND id=?",
                WORKER_LEASE,
                pending.review().getWorkspaceId(),
                pending.jobId());
        return jobs.findById(pending.jobId()).orElseThrow();
    }

    private Result admitResult(ReviewAdmissionData.Pending pending) {
        var receipt = resultStore.admit(
                pending.review().getWorkspaceId(),
                pending.review().getId(),
                pending.jobId(),
                0,
                List.of(admissionData.candidate(pending, ResultOutcome.NOT_MET)));
        assertThat(receipt.withheldItems()).isEmpty();
        assertThat(receipt.resultIds()).hasSize(1);
        return results.findByIdAndWorkspaceId(
                        receipt.resultIds().getFirst(), pending.review().getWorkspaceId())
                .orElseThrow();
    }

    /** A completed Hephaestus answer that showed {@code words} through {@code show_feedback}, and its posted copy. */
    private void postedConversation(Feedback feedback, String words) {
        var conversation = UUID.randomUUID();
        var turn = UUID.randomUUID();
        var answer = UUID.randomUUID();
        var callId = "call-" + feedback.getId();
        jdbc.update(
                "INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,now(),now())",
                conversation,
                feedback.getWorkspaceId(),
                feedback.getDeveloperId());
        jdbc.update(
                "INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,created_at,"
                        + "finished_at) VALUES (?,?,?,?,'DONE','APP',1,now(),now())",
                turn,
                feedback.getWorkspaceId(),
                conversation,
                turn.toString());
        var call = Map.of(
                "type",
                "toolCall",
                "id",
                callId,
                "name",
                "show_feedback",
                "arguments",
                Map.of("feedbackId", feedback.getId().toString(), "text", words));
        jdbc.update(
                "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,turn_id,kind,surface,text,payload,"
                        + "created_at) VALUES (?,?,?,1,?,'HEPH','APP','',?::jsonb,now())",
                answer,
                feedback.getWorkspaceId(),
                conversation,
                turn,
                mapper.writeValueAsString(Map.of("message", Map.of("role", "assistant", "content", List.of(call)))));
        jdbc.update(
                "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,turn_id,kind,surface,text,payload,"
                        + "created_at) VALUES (?,?,?,2,?,'TOOL_RESULT','APP','',?::jsonb,now())",
                UUID.randomUUID(),
                feedback.getWorkspaceId(),
                conversation,
                turn,
                mapper.writeValueAsString(Map.of(
                        "message",
                        Map.of(
                                "role",
                                "toolResult",
                                "toolCallId",
                                callId,
                                "toolName",
                                "show_feedback",
                                "isError",
                                false))));
        var id = FeedbackIds.copy(feedback.getId(), FeedbackCopyKind.CHAT_TURN, 0);
        var at = Objects.requireNonNull(feedback.getDeliveredAt());
        copies.saveAndFlush(FeedbackCopy.builder()
                .id(id)
                .workspaceId(feedback.getWorkspaceId())
                .feedbackId(feedback.getId())
                .kind(FeedbackCopyKind.CHAT_TURN)
                .position(0)
                .state(FeedbackCopyState.POSTED)
                .body(words)
                .writeKey(id.toString())
                .writeStartedAt(at)
                .postedAt(at)
                .mentorTurnId(turn)
                .mentorEntryId(answer)
                .createdAt(at)
                .build());
    }

    private static void assertConversationOpen(AdmissionResponseDTO response) {
        var conversation = Objects.requireNonNull(response.conversationEligibility());
        assertThat(conversation.state()).isEqualTo(ConversationEligibility.State.COMPLETE);
        assertThat(conversation.results()).singleElement().satisfies(row -> {
            assertThat(row.decision())
                    .as("Conversation refusal: %s", row.stopReason())
                    .isEqualTo(ConversationDecision.ADMIT);
            assertThat(row.stopReason()).isNull();
        });
    }

    private static void assertPageClosed(AdmissionResponseDTO response) {
        assertThat(response.practicePageSupport()).isInstanceOf(PracticePageSupportDTO.Complete.class);
        var page = (PracticePageSupportDTO.Complete) Objects.requireNonNull(response.practicePageSupport());
        assertThat(page.practices())
                .singleElement()
                .satisfies(row -> assertThat(row.decision()).isNotEqualTo(PracticePageDecision.ADMIT));
    }

    private PreparedJobInputs capture(ReviewJob job, String subject) {
        files.beginPersonCapture(job);
        recorder.recordIdentity(new PersonCopyIdentity("GITLAB", provider.getServerUrl(), subject, null));
        return files.prepare(
                job,
                new PreparedEvidence(
                        Map.of(
                                "context/history-fixture.txt",
                                "Retained evidence for the history-custody test.".getBytes(StandardCharsets.UTF_8)),
                        null),
                null);
    }

    private JsonNode receipt(ReviewJob job) {
        return mapper.readTree(Objects.requireNonNull(jdbc.queryForObject(
                "SELECT payload::text FROM person_evidence_copy WHERE workspace_id=? AND job_id=?",
                String.class,
                job.getWorkspaceId(),
                job.getId())));
    }

    private List<String> copiedJobs(ReviewJob job) {
        var copied = new ArrayList<String>();
        receipt(job).path("copiedJobs").forEach(value -> copied.add(value.asString()));
        return copied;
    }

    private List<String> copiedSubjects(ReviewJob job) {
        var copied = new ArrayList<String>();
        receipt(job)
                .path("identities")
                .forEach(value -> copied.add(value.path("subject").asString()));
        return copied;
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("private-feedback-history-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private record CapturedResult(Result result, ReviewJob job, PreparedJobInputs inputs) implements AutoCloseable {
        @Override
        public void close() {
            inputs.close();
        }
    }

    private record Historical(Result result, Feedback feedback, ReviewJob job) {}
}
