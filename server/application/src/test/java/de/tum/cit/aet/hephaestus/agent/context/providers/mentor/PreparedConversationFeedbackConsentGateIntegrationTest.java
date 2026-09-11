package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalFeedbackPreparer;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.FeedbackChannelRouter;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.RoutingContext;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.settings.InstanceSettingsService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class PreparedConversationFeedbackConsentGateIntegrationTest extends AbstractSlackConsentGateIntegrationTest {

    @Autowired
    private PreparedConversationFeedbackContentSource contentSource;

    @Autowired
    private FeedbackChannelRouter router;

    @Autowired
    private ConversationalFeedbackPreparer preparer;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private AccountPreferencesQuery accountPreferencesQuery;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InstanceSettingsService instanceSettingsService;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    private Practice practice;
    private Repository monitoredRepository;
    private PullRequest pullRequest;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        var settings = instanceSettingsService.get();
        instanceSettingsService.updateSilentMode(
                false, null, null, EntityTagPrecondition.parse("\"" + settings.getVersion() + "\""));
        setUpWorkspaceAndRecipient("conv-consent-gate-test");
        Repository repository = new Repository();
        repository.setNativeId(4200L);
        repository.setProvider(recipient.getProvider());
        repository.setName("repo");
        repository.setNameWithOwner("owner/repo");
        repository.setHtmlUrl("https://github.com/owner/repo");
        repository.setVisibility(Repository.Visibility.PUBLIC);
        repository = repositoryRepository.save(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        repositoryToMonitorRepository.save(monitor);
        monitoredRepository = repository;
        pullRequest = persistPullRequest(4242L, 17);
        when(accountPreferencesQuery.practiceFeedbackDeliveryEnabled(recipient.getId()))
                .thenReturn(true);
        practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);
    }

    @Test
    @DisplayName("consent gate: only an ACTIVE-channel thread's derived fact surfaces; PAUSED/REVOKED are withheld")
    void onlyActiveChannelDerivedFactSurfaces() {
        long activeThreadId = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        long pausedThreadId = seedThread("C-paused", "200.0", ConsentState.PAUSED);
        long revokedThreadId = seedThread("C-revoked", "300.0", ConsentState.REVOKED);

        AgentJob job = conversationJob(activeThreadId);
        Observation activeObs = saveConversationObservation(job, "occ-active", activeThreadId);
        saveConversationObservation(job, "occ-paused", pausedThreadId);
        saveConversationObservation(job, "occ-revoked", revokedThreadId);
        preparer.prepare(
                job.getId(), workspace.getId(), List.of(activeObs), List.of(conversationUnit(List.of(activeObs))));

        assertThat(feedbackObservationRepository.findPreparedConversationFactsForRecipient(
                        workspace.getId(), recipient.getId(), PageRequest.of(0, 10)))
                .hasSize(1);

        JsonNode root = contribute();

        assertThat(root.get("_meta").get("trustLevel").asString()).isEqualTo("UNTRUSTED_EXTERNAL");

        JsonNode arr = root.get("preparedConversationFeedback");
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("observationId").asString())
                .isEqualTo(activeObs.getId().toString());
        assertThat(arr.get(0).get("artifactKind").asString()).isEqualTo("chat.conversation_thread");
        assertThat(arr.get(0).get("artifactId").asLong()).isEqualTo(activeThreadId);
        assertThat(root.get("totalPrepared").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Slack consent does not suppress an otherwise-authorized pull-request observation")
    void nonSlackArtifactFactAlwaysSurfaces() {
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 2));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);

        AgentJob job = pullRequestJob();
        Observation prObs = savePullRequestObservation(job, "occ-pr", pullRequest.getId());
        prepareFor(job);

        JsonNode root = contribute();
        JsonNode arr = root.get("preparedConversationFeedback");
        assertThat(arr).hasSize(1);
        assertThat(arr.get(0).get("observationId").asString())
                .isEqualTo(prObs.getId().toString());
        assertThat(arr.get(0).get("artifactKind").asString()).isEqualTo("scm.pull_request");

        pullRequest.setDeletedAt(Instant.now());
        pullRequestRepository.saveAndFlush(pullRequest);
        assertThat(contribute().get("preparedConversationFeedback")).isEmpty();
        assertThat(feedbackObservationRepository.findPreparedConversationFactsForRecipient(
                        workspace.getId(), recipient.getId(), PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void aPreparedFactIsWithheldWhenWorkspaceDeliveryPauses() {
        long threadId = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        AgentJob job = conversationJob(threadId);
        Observation observation = saveConversationObservation(job, "occ-paused", threadId);
        preparer.prepare(
                job.getId(), workspace.getId(), List.of(observation), List.of(conversationUnit(List.of(observation))));
        workspace.getReviewSettings().setDeliveryStatus(PracticeDeliveryStatus.PAUSED);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(contribute().get("preparedConversationFeedback")).isEmpty();
        assertThat(feedbackObservationRepository.findPreparedConversationFactsForRecipient(
                        workspace.getId(), recipient.getId(), PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void aPreparedFactIsWithheldAfterRepositorylessWorkLeavesCoverage() {
        long threadId = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        AgentJob job = conversationJob(threadId);
        saveConversationObservation(job, "occ-outside-coverage", threadId);
        prepareFor(job);
        assertThat(contribute().get("preparedConversationFeedback")).hasSize(1);
        workspace.getReviewSettings().setRepositoryCoverageMode(ReviewRepositoryMode.SELECTED);
        workspaceRepository.saveAndFlush(workspace);

        assertThat(contribute().get("preparedConversationFeedback")).isEmpty();
    }

    @Test
    void aPreparedFactIsWithheldWhenItsPracticeTurnsOff() {
        long threadId = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        AgentJob job = conversationJob(threadId);
        saveConversationObservation(job, "occ-practice-off", threadId);
        prepareFor(job);
        assertThat(contribute().get("preparedConversationFeedback")).hasSize(1);
        practice.setAutonomy(PracticeAutonomy.OFF);
        practiceRepository.saveAndFlush(practice);

        assertThat(contribute().get("preparedConversationFeedback")).isEmpty();
    }

    /**
     * The composer's notes have to reach the mentor's sandbox in all four parts, and they have to arrive as
     * {@code notes} rather than as anything a prompt could read as text to paste - the mentor reads the key
     * before it reads the values. The fallback is a missing key, not an empty object, so "nothing was
     * composed" cannot read as "the composer had nothing to say".
     */
    @Test
    @DisplayName("composed notes reach the mentor as notes, and an uncomposed unit carries none")
    void stagesTheComposedNotes() {
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 2));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);

        AgentJob job = pullRequestJob();
        savePullRequestObservation(job, "occ-composed", pullRequest.getId());
        List<Observation> admitted = router.admit(
                observationRepository.findByAgentJobId(
                        job.getId(), job.getWorkspace().getId()),
                workspace.getId(),
                RoutingContext.author());
        preparer.prepare(
                job.getId(),
                workspace.getId(),
                admitted,
                List.of(new ComposedFeedbackUnit(
                        FeedbackChannel.IN_CHAT,
                        practice.getSlug(),
                        List.of(admitted.getFirst().getId().toString()),
                        ComposedFeedbackUnit.Action.NEW,
                        null,
                        null,
                        "The test arrives after the review",
                        null,
                        null,
                        new ComposedFeedbackUnit.ConversationBrief(
                                "On !18, !20 and !22 the test landed a push after the review comment.",
                                "Writing the test last is what leaves the review to find the gap.",
                                "On !18, !20 and !22 the test arrived a push later.",
                                "They name a check they could run before pushing.",
                                null),
                        null)));

        JsonNode item = contribute().get("preparedConversationFeedback").get(0);
        assertThat(item.has("body")).isFalse();
        assertThat(item.get("topic").asString()).isEqualTo("The test arrives after the review");
        assertThat(item.get("evidence").get("citations").get(0).get("quote").asString())
                .isEqualTo("example");
        JsonNode notes = item.get("notes");
        assertThat(notes.get("situation").asString())
                .isEqualTo("On !18, !20 and !22 the test landed a push after the review comment.");
        assertThat(notes.get("capability").asString())
                .isEqualTo("Writing the test last is what leaves the review to find the gap.");
        assertThat(notes.get("evidenceSummary").asString())
                .isEqualTo("On !18, !20 and !22 the test arrived a push later.");
        assertThat(notes.get("inConversationSignal").asString())
                .isEqualTo("They name a check they could run before pushing.");

        // The same surface, prepared with nothing composed: no notes key at all, on that item alone.
        AgentJob uncomposed = pullRequestJob(persistPullRequest(4343L, 18));
        savePullRequestObservation(uncomposed, "occ-uncomposed", pullRequest.getId());
        preparer.prepare(uncomposed.getId(), workspace.getId(), List.of(), List.of());

        JsonNode both = contribute().get("preparedConversationFeedback");
        assertThat(both).hasSize(1);
        assertThat(both.get(0).has("notes")).isTrue();
    }

    private AgentJob conversationJob(long threadId) {
        var thread = slackThreadRepository.findById(threadId).orElseThrow();
        thread.setParticipantMemberIds(new long[] {recipient.getId()});
        slackThreadRepository.saveAndFlush(thread);
        var job = newJob();
        job.setArtifactKind(ArtifactKinds.CONVERSATION_THREAD);
        var metadata = objectMapper.createObjectNode();
        metadata.put("about_user_id", recipient.getId());
        metadata.put("slack_thread_id", threadId);
        metadata.put("slack_channel_id", thread.getSlackChannelId());
        metadata.put("slack_thread_ts", thread.getSlackThreadTs());
        job.setMetadata(metadata);
        return agentJobRepository.saveAndFlush(job);
    }

    private AgentJob pullRequestJob() {
        return pullRequestJob(pullRequest);
    }

    private AgentJob pullRequestJob(PullRequest target) {
        var job = newJob();
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        var metadata = objectMapper.createObjectNode();
        metadata.put("pull_request_id", target.getId());
        metadata.put("pr_number", target.getNumber());
        metadata.put("repository_id", monitoredRepository.getId());
        metadata.put("repository_full_name", monitoredRepository.getNameWithOwner());
        job.setMetadata(metadata);
        return agentJobRepository.saveAndFlush(job);
    }

    private PullRequest persistPullRequest(long nativeId, int number) {
        PullRequest created = new PullRequest();
        created.setNativeId(nativeId);
        created.setProvider(recipient.getProvider());
        created.setRepository(monitoredRepository);
        created.setAuthor(recipient);
        created.setNumber(number);
        created.setTitle("Test work");
        created.setState(Issue.State.OPEN);
        created.setBaseRefName("main");
        return pullRequestRepository.saveAndFlush(created);
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

    private JsonNode contribute() {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), recipient.getId(), UUID.randomUUID()), files);
        return objectMapper.readTree(files.get(PreparedConversationFeedbackContentSource.OUTPUT_KEY));
    }

    private void prepareFor(AgentJob job) {
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        List<Observation> admitted = router.admit(observations, workspace.getId(), RoutingContext.author());
        preparer.prepare(job.getId(), workspace.getId(), admitted, List.of(conversationUnit(admitted)));
    }

    private Observation saveConversationObservation(AgentJob job, String occurrenceKey, long threadId) {
        return saveObservation(job, occurrenceKey, "chat.conversation_thread", threadId);
    }

    private Observation savePullRequestObservation(AgentJob job, String occurrenceKey, long pullRequestId) {
        return saveObservation(job, occurrenceKey, "scm.pull_request", pullRequestId);
    }

    private Observation saveObservation(AgentJob job, String occurrenceKey, String artifactKind, long artifactId) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                occurrenceKey,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind,
                artifactId,
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                artifactKind.equals("chat.conversation_thread")
                        ? "{\"citations\":[{\"sourceKind\":\"slack.conversation.thread\",\"artifactPath\":\"inputs/context/thread.json\",\"path\":\"Slack thread\",\"startLine\":1,\"endLine\":1,\"quote\":\"example\",\"quoteRedacted\":false}]}"
                        : "{\"citations\":[{\"sourceKind\":\"scm.pull-request.core\",\"artifactPath\":\"inputs/context/pull-request.json\",\"path\":\"pull-request.json\",\"startLine\":1,\"endLine\":1,\"quote\":\"example\",\"quoteRedacted\":false}]}",
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationRepository.findById(id).orElseThrow();
    }
}
