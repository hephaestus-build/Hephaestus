package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedObservationFixtures;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChannel;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnPersistence;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ConversationalFeedbackDeliveryLoopIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String SHOWN = "Your description names the decision but not why it beat the alternative.";

    @Autowired
    private FeedbackChannelRouter router;

    @Autowired
    private ConversationalFeedbackPreparer preparer;

    @Autowired
    private ConversationalDeliveryReconciler reconciler;

    @Autowired
    private MentorTurnPersistence mentorTurnPersistence;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackPlacementRepository feedbackPlacementRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private ChatThreadRepository chatThreadRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    private Workspace workspace;
    private Practice practice;
    private User recipient;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        var settings = instanceSettingsService.get();
        instanceSettingsService.updateSilentMode(
                false, null, null, EntityTagPrecondition.parse("\"" + settings.getVersion() + "\""));
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("conv-delivery-test"));
        practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        recipient = userRepository.save(TestUserFactory.createUser(100L, "recipient", provider));
    }

    @Test
    void twoJobsPrepareStructuredConversationBriefs() {
        AgentJob job1 = newJob();
        AgentJob job2 = newJob();
        saveObservation(job1, "occ-1");
        saveObservation(job2, "occ-2");

        prepareFor(job1);
        prepareFor(job2);

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
    void shouldDeliverTheFeedbackOnceWhenACompletedReplyShowedIt() {
        AgentJob job = newJob();
        Observation a = saveObservation(job, "occ-a");
        Observation b = saveObservation(job, "occ-b");
        prepareFor(job);
        ChatMessage assistant = persistAssistantMessage(ChatMessage.Status.in_flight);
        TranslatorState state = new TranslatorState(assistant.getId());
        state.recordDataObservation(UIMessageChunk.DataObservation.of(a.getId(), SHOWN));
        state.recordDataObservation(UIMessageChunk.DataObservation.of(b.getId(), SHOWN));
        MentorTurnPersistence.TurnPersistenceCookie cookie = cookie(assistant);
        mentorTurnPersistence.complete(
                cookie, state, new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null));

        mentorTurnPersistence.recordDelivery(cookie, MentorChannel.DeliveryOutcome.DELIVERED);
        mentorTurnPersistence.recordDelivery(cookie, MentorChannel.DeliveryOutcome.DELIVERED);

        assertThat(conversationUnits()).singleElement().satisfies(f -> {
            assertThat(f.getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
            assertThat(f.getBody()).contains("\"kind\":\"conversation-brief\"");
        });
        assertThat(feedbackPlacementRepository.findAll()).singleElement().satisfies(placement -> {
            assertThat(placement.getPlacementType()).isEqualTo(PlacementType.CONVERSATION_TURN);
            assertThat(placement.getChatMessageId()).isEqualTo(assistant.getId());
        });
        assertThat(chatMessageRepository
                        .findById(assistant.getId())
                        .orElseThrow()
                        .getParts())
                .extracting(part -> part.path("data").path("text").asString())
                .containsExactly(SHOWN, SHOWN);
    }

    @Test
    void shouldLeaveFeedbackPreparedWhenTheReplyOnlyNamedItsObservation() {
        AgentJob job = newJob();
        Observation observation = saveObservation(job, "occ-named");
        prepareFor(job);
        ChatMessage assistant = persistAssistantMessage(
                ChatMessage.Status.completed,
                OM.createArrayNode()
                        .add(OM.createObjectNode().put("type", "text").put("text", "How did the review go?"))
                        .add(link(observation.getId(), null)));

        mentorTurnPersistence.recordDelivery(cookie(assistant), MentorChannel.DeliveryOutcome.DELIVERED);

        assertThat(preparedCount()).isEqualTo(1);
        assertThat(feedbackPlacementRepository.findAll()).isEmpty();
    }

    @Test
    void shouldDeliverOnlyTheFeedbackTheReplyShowedWhenItAlsoNamedAnother() {
        AgentJob named = newJob();
        Observation onlyNamed = saveObservation(named, "occ-only-named");
        prepareFor(named);
        AgentJob shownJob = newJob();
        Observation shown = saveObservation(shownJob, "occ-shown");
        prepareFor(shownJob);
        ChatMessage assistant = persistAssistantMessage(
                ChatMessage.Status.completed,
                OM.createArrayNode().add(link(onlyNamed.getId(), null)).add(link(shown.getId(), SHOWN)));

        mentorTurnPersistence.recordDelivery(cookie(assistant), MentorChannel.DeliveryOutcome.DELIVERED);

        assertThat(conversationUnits())
                .extracting(f -> f.getAgentJobId(), f -> f.getDeliveryState())
                .containsExactlyInAnyOrder(
                        tuple(named.getId(), FeedbackDeliveryState.PREPARED),
                        tuple(shownJob.getId(), FeedbackDeliveryState.DELIVERED));
        assertThat(feedbackPlacementRepository.findAll())
                .singleElement()
                .satisfies(placement -> assertThat(placement.getChatMessageId()).isEqualTo(assistant.getId()));
    }

    @Test
    void silentTransportOutcomeConsumesPreparedUnitProspectively() {
        AgentJob job = newJob();
        Observation observation = saveObservation(job, "occ-silent");
        prepareFor(job);
        ChatMessage assistant = persistAssistantMessage(ChatMessage.Status.in_flight);
        TranslatorState state = new TranslatorState(assistant.getId());
        state.recordDataObservation(UIMessageChunk.DataObservation.of(observation.getId(), SHOWN));
        MentorTurnPersistence.TurnPersistenceCookie cookie = cookie(assistant);

        mentorTurnPersistence.complete(
                cookie, state, new UIMessageChunk.Finish(UIMessageChunk.FinishReason.STOP, null));

        mentorTurnPersistence.recordDelivery(cookie, MentorChannel.DeliveryOutcome.INSTANCE_SILENCED);

        assertThat(reconciler.suppressForSilentMode(workspace.getId(), recipient.getId(), List.of(observation.getId())))
                .isZero();
        assertThat(chatMessageRepository
                        .findById(assistant.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(ChatMessage.Status.completed);
        assertThat(conversationUnits()).singleElement().satisfies(feedback -> {
            assertThat(feedback.getDeliveryState()).isEqualTo(FeedbackDeliveryState.SUPPRESSED);
            assertThat(feedback.getSuppressionReason()).isEqualTo(FeedbackSuppressionReason.INSTANCE_SILENCED);
        });
        assertThat(preparedCount()).isZero();
        assertThat(feedbackPlacementRepository.findAll()).isEmpty();
    }

    private void prepareFor(AgentJob job) {
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        List<Observation> admitted = router.admit(observations, workspace.getId(), RoutingContext.author());
        preparer.prepare(job.getId(), workspace.getId(), admitted, List.of(conversationUnit(admitted)));
    }

    private ComposedFeedbackUnit conversationUnit(List<Observation> observations) {
        return new ComposedFeedbackUnit(
                FeedbackChannel.IN_CHAT,
                practice.getSlug(),
                observations.stream().map(o -> o.getId().toString()).toList(),
                ComposedFeedbackUnit.Action.NEW,
                null,
                null,
                "Test practice",
                null,
                null,
                new ComposedFeedbackUnit.ConversationBrief(
                        "The practice recurred.",
                        "Recognize the decision point.",
                        "The observations show the same pattern.",
                        "They can explain the decision in their own words.",
                        null),
                null);
    }

    private List<Feedback> conversationUnits() {
        return feedbackRepository.findAll().stream()
                .filter(f -> f.getChannel() == FeedbackChannel.IN_CHAT)
                .toList();
    }

    private long preparedCount() {
        return conversationUnits().stream()
                .filter(f -> f.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .count();
    }

    private AgentJob newJob() {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OM.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OM.readTree("{\"manifest\":{\"contractVersion\":\"1.2.0\"}}"));
        return agentJobRepository.save(job);
    }

    private Observation saveObservation(AgentJob job, String occurrenceKey) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                occurrenceKey,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                "scm.pull_request",
                42L,
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                AdmittedObservationFixtures.evidence(
                                job.getId(),
                                "scm.pull-request.core",
                                "inputs/context/metadata.json",
                                "metadata.json",
                                "example")
                        .toString(),
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationRepository.findById(id).orElseThrow();
    }

    private static MentorTurnPersistence.TurnPersistenceCookie cookie(ChatMessage assistant) {
        return new MentorTurnPersistence.TurnPersistenceCookie(
                assistant.getThread().getId(),
                UUID.randomUUID(),
                assistant.getId(),
                Instant.now(),
                "test-model",
                org.mockito.Mockito.mock(LlmPriceSnapshot.class));
    }

    /** A stored link; one with no text is how replies stored links before they carried the feedback. */
    private static ObjectNode link(UUID observationId, @Nullable String text) {
        ObjectNode part = OM.createObjectNode()
                .put("type", "data-observation")
                .put("id", UUID.randomUUID().toString());
        ObjectNode data = part.putObject("data").put("observationId", observationId.toString());
        if (text != null) {
            data.put("text", text);
        }
        return part;
    }

    private ChatMessage persistAssistantMessage(ChatMessage.Status status) {
        return persistAssistantMessage(status, OM.createArrayNode());
    }

    private ChatMessage persistAssistantMessage(ChatMessage.Status status, ArrayNode parts) {
        ChatThread thread = new ChatThread();
        thread.setId(UUID.randomUUID());
        thread.setUser(recipient);
        thread.setWorkspace(workspace);
        thread.setTitle("t");
        chatThreadRepository.save(thread);
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID());
        message.setThread(thread);
        message.setRole(ChatMessage.Role.ASSISTANT);
        message.setStatus(status);
        message.setParts(parts);
        message.setMetadata(OM.createObjectNode());
        return chatMessageRepository.save(message);
    }
}
