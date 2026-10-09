package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;

class ConversationalFeedbackDeliveryLoopIntegrationTest extends BaseIntegrationTest {

    private static final String SHOWN = "Your description names the decision but not why it beat the alternative.";

    @Autowired
    private ConversationalDeliveryReconciler reconciler;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnCompletion completion;

    @Autowired
    private MentorEntryRepository entries;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackPlacementRepository feedbackPlacementRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private FeedbackChannelRouter router;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private ApplicationContext beans;

    private Workspace workspace;
    private ConversationalFeedbackFixtures fixtures;
    private User recipient;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conv-delivery-test"));
        fixtures = new ConversationalFeedbackFixtures(beans, workspace, "Test Practice");
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        recipient = userRepository.save(TestUserFactory.createUser(100L, "recipient", provider));
    }

    @Test
    void shouldPrepareOneConversationBriefPerJobWhenTwoJobsObserveTheDeveloper() {
        AgentJob job1 = fixtures.job();
        AgentJob job2 = fixtures.job();
        saveObservation(job1, "occ-1");
        saveObservation(job2, "occ-2");

        fixtures.prepare(job1);
        fixtures.prepare(job2);

        List<Feedback> prepared = feedbackRepository.findRecentPreparedConversationForRecipient(
                workspace.getId(), recipient.getId(), PageRequest.of(0, 10));
        assertThat(prepared).hasSize(2);
        assertThat(prepared).allSatisfy(f -> {
            assertThat(f.getChannel()).isEqualTo(FeedbackChannel.IN_CHAT);
            assertThat(f.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
            assertThat(f.getBody()).contains("\"kind\":\"conversation-brief\"");
        });
    }

    @Test
    void shouldDeliverTheFeedbackOnceOnTheMessageThatShowedItWhenAnAppAnswerShowedIt() {
        AgentJob job = fixtures.job();
        Observation a = saveObservation(job, "occ-a");
        Observation b = saveObservation(job, "occ-b");
        fixtures.prepare(job);
        MentorTurn turn = runningTurn(MentorTurn.Surface.APP);

        end(turn, MentorTurn.Status.DONE, answer(link("call-a", a.getId()), link("call-b", b.getId())), null);

        UUID answerEntry = turns.answer(workspace.getId(), turn.getId()).answerEntryId();
        assertThat(fixtures.conversationUnits()).singleElement().satisfies(f -> {
            assertThat(f.getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
            assertThat(f.getBody()).contains("\"kind\":\"conversation-brief\"");
        });
        assertThat(placements()).singleElement().satisfies(placement -> {
            assertThat(placement.getMentorEntryId())
                    .isEqualTo(linkingMessage(turn))
                    .isNotEqualTo(answerEntry);
        });
    }

    @Test
    void shouldLeaveTheFeedbackPreparedWhenTheAppAnswerThatShowedItFails() {
        AgentJob job = fixtures.job();
        Observation observation = saveObservation(job, "occ-failed");
        fixtures.prepare(job);
        MentorTurn turn = runningTurn(MentorTurn.Surface.APP);

        end(turn, MentorTurn.Status.FAILED, answer(link("call-failed", observation.getId())), "The model failed");

        assertThat(turns.answer(workspace.getId(), turn.getId()).answerEntryId())
                .as("a failed turn stores no answer")
                .isNull();
        assertThat(preparedCount()).isOne();
        assertThat(placements()).isEmpty();
    }

    @Test
    void shouldDeliverOnlyTheFeedbackTheAnswerShowedWhenItAlsoNamedAnother() {
        AgentJob named = fixtures.job();
        Observation onlyNamed = saveObservation(named, "occ-only-named");
        fixtures.prepare(named);
        AgentJob shownJob = fixtures.job();
        Observation shown = saveObservation(shownJob, "occ-shown");
        fixtures.prepare(shownJob);
        MentorTurn turn = runningTurn(MentorTurn.Surface.SLACK);

        end(
                turn,
                MentorTurn.Status.DONE,
                answer(refusedLink("call-named", onlyNamed.getId()), link("call-shown", shown.getId())),
                null);
        completion.recordDelivery(workspace.getId(), turn.getId(), List.of(onlyNamed.getId(), shown.getId()));

        assertThat(fixtures.conversationUnits())
                .extracting(f -> f.getAgentJobId(), f -> f.getDeliveryState())
                .containsExactlyInAnyOrder(
                        tuple(named.getId(), FeedbackDeliveryState.PREPARED),
                        tuple(shownJob.getId(), FeedbackDeliveryState.DELIVERED));
        assertThat(placements())
                .singleElement()
                .satisfies(placement -> assertThat(placement.getMentorEntryId()).isEqualTo(linkingMessage(turn)));
    }

    @Test
    void shouldSuppressThePreparedUnitWhenSilentModeStoppedTheSlackAnswer() {
        AgentJob job = fixtures.job();
        Observation observation = saveObservation(job, "occ-silent");
        fixtures.prepare(job);
        MentorTurn turn = runningTurn(MentorTurn.Surface.SLACK);
        end(turn, MentorTurn.Status.DONE, answer(link("call-silent", observation.getId())), null);

        completion.recordSilenced(workspace.getId(), turn.getId());

        assertThat(reconciler.suppressForSilentMode(workspace.getId(), recipient.getId(), List.of(observation.getId())))
                .isZero();
        assertThat(fixtures.conversationUnits()).singleElement().satisfies(feedback -> {
            assertThat(feedback.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
            assertThat(feedback.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.INSTANCE_SILENCED);
        });
        assertThat(preparedCount()).isZero();
        assertThat(placements()).isEmpty();
    }

    /**
     * The decision composition reads before it writes notes is the router's own, keyed by the persisted rows: a problem
     * cited on the diff belongs on the line, and only the unanchored one is prepared for the conversation.
     */
    @Test
    void shouldRouteThePersistedProblemsBeforeCompositionAsPreparationDoes() {
        AgentJob job = fixtures.job();
        Observation onTheDiff = fixtures.observation(job, recipient, "occ-diff", "scm.pull-request.diff");
        Observation described = fixtures.observation(job, recipient, "occ-described", "scm.pull-request.core");
        List<Observation> persisted = observationRepository.findByAgentJobId(job.getId(), workspace.getId());

        assertThat(router.decide(persisted, workspace.getId(), RoutingContext.author()))
                .containsOnly(
                        Map.entry(onTheDiff.getId(), ConversationRoutingDecision.HAS_INLINE_ANCHOR),
                        Map.entry(described.getId(), ConversationRoutingDecision.ADMIT));
        assertThat(router.decide(persisted, workspace.getId(), RoutingContext.reviewer()))
                .containsOnlyKeys(onTheDiff.getId(), described.getId())
                .doesNotContainValue(ConversationRoutingDecision.ADMIT);

        fixtures.prepare(job);

        assertThat(fixtures.conversationUnits())
                .filteredOn(unit -> unit.getAgentJobId().equals(job.getId()))
                .singleElement()
                .satisfies(unit -> assertThat(unit.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED));
        assertThat(router.admit(persisted, workspace.getId(), RoutingContext.author()))
                .extracting(Observation::getId)
                .containsExactly(described.getId());
    }

    /** Heph's message that called {@code link_observation}; the answer's closing message follows it. */
    private UUID linkingMessage(MentorTurn turn) {
        return entries.findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
                        workspace.getId(), turn.getId(), MentorEntry.Kind.HEPH)
                .getFirst()
                .getId();
    }

    /** The placements of this workspace's conversation feedback. */
    private List<FeedbackPlacement> placements() {
        return feedbackPlacementRepository.findByFeedbackIdInAndPlacementType(
                fixtures.conversationUnits().stream().map(Feedback::getId).toList(), PlacementType.CONVERSATION_TURN);
    }

    private long preparedCount() {
        return fixtures.conversationUnits().stream()
                .filter(f -> f.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .count();
    }

    private Observation saveObservation(AgentJob job, String occurrenceKey) {
        return fixtures.observation(job, recipient, occurrenceKey);
    }

    private static final String WORKER = "worker-delivery";

    /** A {@code link_observation} call of Heph's, and whether the server admitted it. */
    private record Link(String callId, UUID observationId, boolean shown) {}

    private static Link link(String callId, UUID observationId) {
        return new Link(callId, observationId, true);
    }

    private static Link refusedLink(String callId, UUID observationId) {
        return new Link(callId, observationId, false);
    }

    private MentorTurn runningTurn(MentorTurn.Surface surface) {
        var conversation =
                conversations.findOrCreateMain(workspace.getId(), recipient.getId(), Set.of(recipient.getId()));
        MentorTurn asked = turns.submit(
                        workspace.getId(),
                        conversation.getId(),
                        new MentorTurnService.Question(
                                surface,
                                "How did my review go?",
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn();
        MentorTurn claimed = turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
        assertThat(claimed.getId()).isEqualTo(asked.getId());
        return claimed;
    }

    /** The Pi entries of an answer that links each observation, then answers. */
    private static List<JsonNode> answer(Link... links) {
        Map<String, UUID> calls = new LinkedHashMap<>();
        for (Link link : links) {
            calls.put(link.callId(), link.observationId());
        }
        List<JsonNode> entries = new ArrayList<>();
        entries.add(MentorLedgerFixtures.linkObservations(MentorLedgerFixtures.piId(), calls, SHOWN));
        for (Link link : links) {
            entries.add(MentorLedgerFixtures.toolResult(
                    MentorLedgerFixtures.piId(),
                    link.callId(),
                    "link_observation",
                    link.shown() ? "Shown to the developer" : "Not admitted",
                    !link.shown()));
        }
        entries.add(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "That is what I saw."));
        return entries;
    }

    /** The host ends the turn with the entries it appended. */
    private void end(MentorTurn turn, MentorTurn.Status outcome, List<JsonNode> entries, @Nullable String error) {
        completion.end(
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                new MentorTurnCompletion.Ending(outcome, entries, error, null));
    }
}
