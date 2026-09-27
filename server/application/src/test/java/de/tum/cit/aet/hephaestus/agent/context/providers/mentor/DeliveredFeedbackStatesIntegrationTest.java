package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationBriefBody;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.RecipientFeedbackRow;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackApprovalService;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.FeedbackRejectionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.approval.dto.DecideFeedbackProposalRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the mentor learns about the developer's feedback: only what could have reached them, each status tied to its
 * own unit, and nothing about proposals, withheld units or rows this conversation may not use.
 */
class DeliveredFeedbackStatesIntegrationTest extends AbstractSlackConsentGateIntegrationTest {

    private static final long FIRST_MR = 4009491064L;
    private static final long SECOND_MR = 4009506317L;
    private static final long ISSUE = 4009520006L;
    private static final long REVIEWER_ACCOUNT_ID = 7L;

    @Autowired
    private DeliveredFeedbackContentSource contentSource;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private FeedbackApprovalService feedbackApprovalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private AgentJob job;
    private Practice practice;
    private Instant base;
    private int nextPosition;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        setUpWorkspaceAndRecipient("feedback-states-test");
        practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug("issue-linking");
        practice.setName("Issue linking");
        practice.setCriteria("Link the issue the change resolves.");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);
        job = newJob();
        base = Instant.now().minus(1, ChronoUnit.DAYS);
        nextPosition = 0;
    }

    @Test
    void shouldShowNoFeedbackForTheSecondMergeRequestWhenItsProposalWasNotDelivered() {
        Feedback note = save(
                job,
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "delivered-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));
        Feedback proposal = save(
                job,
                SECOND_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.AWAITING_APPROVAL,
                "unapproved-proposal",
                observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR));

        byte[] pending = contribute();
        JsonNode root = objectMapper.readTree(pending);

        assertThat(root.get("deliveredFeedback"))
                .extracting(
                        e -> e.get("feedbackId").asString(), e -> e.get("body").asString())
                .containsExactly(tuple(note.getId().toString(), "delivered-note"));
        assertThat(root.get("feedbackStates"))
                .extracting(
                        e -> e.get("feedbackId").asString(),
                        e -> e.get("surface").asString(),
                        e -> e.get("status").asString())
                .containsExactly(tuple(note.getId().toString(), "IN_CONTEXT", "DELIVERED"));
        assertThat(new String(pending, StandardCharsets.UTF_8))
                .doesNotContain("unapproved-proposal", proposal.getId().toString());

        feedbackRepository.decideProposal(workspace.getId(), proposal.getId(), "DISCARDED");

        assertThat(withoutPreparedAt(contribute())).isEqualTo(withoutPreparedAt(pending));
    }

    @Test
    void shouldShowNothingAboutRejectedProposalsWhenNoFeedbackReachedTheDeveloper() {
        UUID strength = observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR);
        User otherRecipient =
                userRepository.save(TestUserFactory.createUser(102L, "other-recipient", recipient.getProvider()));
        Feedback othersNote = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(SECOND_MR)
                .recipientUserId(otherRecipient.getId())
                .aboutUserId(otherRecipient.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(nextPosition)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .source(FeedbackSource.AGENT)
                .body("someone-elses-note")
                .createdAt(base.plusSeconds(nextPosition++))
                .build());
        feedbackObservationRepository.insertIfAbsent(othersNote.getId(), strength, EvidenceRole.PRIMARY.name(), 0);
        JsonNode withoutProposals = withoutPreparedAt(contribute());

        List<Feedback> proposals = List.of(
                save(
                        job,
                        SECOND_MR,
                        FeedbackChannel.IN_CONTEXT,
                        FeedbackDeliveryState.AWAITING_APPROVAL,
                        "first-rejected-proposal",
                        strength),
                save(
                        job,
                        SECOND_MR,
                        FeedbackChannel.IN_CONTEXT,
                        FeedbackDeliveryState.AWAITING_APPROVAL,
                        "second-rejected-proposal",
                        strength));
        for (Feedback proposal : proposals) {
            feedbackApprovalService.decide(
                    workspace.getId(),
                    proposal.getId(),
                    REVIEWER_ACCOUNT_ID,
                    new DecideFeedbackProposalRequestDTO(
                            FeedbackApprovalDecision.REJECTED,
                            FeedbackRejectionReason.DUPLICATE,
                            "repeats-the-first-merge-request"));
        }
        assertThat(feedbackRepository.findAllById(
                        proposals.stream().map(Feedback::getId).toList()))
                .extracting(Feedback::getDeliveryState)
                .containsOnly(FeedbackDeliveryState.DISCARDED);

        byte[] bytes = contribute();
        JsonNode root = objectMapper.readTree(bytes);

        assertThat(root.get("feedbackStates")).isEmpty();
        assertThat(root.path("coverage").path("outsideScope"))
                .extracting(JsonNode::asString)
                .contains("PROPOSALS", "REVIEWER_DECISIONS");
        assertThat(withoutPreparedAt(bytes)).isEqualTo(withoutProposals);
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .doesNotContain(
                        proposals.get(0).getId().toString(),
                        proposals.get(1).getId().toString(),
                        "rejected-proposal",
                        "repeats-the-first-merge-request",
                        "DUPLICATE",
                        "DISCARDED",
                        othersNote.getId().toString(),
                        "someone-elses-note");
    }

    @Test
    void shouldShowNothingOfDeliveredFeedbackWhenThisConversationMayNotUseIt() {
        JsonNode withoutRecords = withoutPreparedAt(contribute());
        UUID withdrawn = observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR);
        Feedback note = save(
                job,
                SECOND_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "note-on-withdrawn-evidence",
                withdrawn);
        withdrawEvidence(withdrawn);
        UUID erased = observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR);
        Feedback erasedNote = save(
                job,
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "note-on-erased-evidence",
                erased);
        jdbcTemplate.update("DELETE FROM observation WHERE id = ?", erased);
        long revokedThread = seedThread("C-revoked", "300.0", ConsentState.REVOKED);
        Feedback topic = save(
                job,
                ArtifactKinds.CONVERSATION_THREAD,
                revokedThread,
                FeedbackChannel.IN_CHAT,
                FeedbackDeliveryState.DELIVERED,
                "topic-in-a-revoked-thread",
                observeStrength(ArtifactKinds.CONVERSATION_THREAD, revokedThread));
        assertThat(feedbackRepository.findAllById(List.of(note.getId(), erasedNote.getId(), topic.getId())))
                .extracting(Feedback::getDeliveryState)
                .containsOnly(FeedbackDeliveryState.DELIVERED);

        byte[] bytes = contribute();
        JsonNode root = objectMapper.readTree(bytes);

        assertThat(root.get("feedbackStates")).isEmpty();
        assertThat(root.path("coverage").path("outsideScope"))
                .extracting(JsonNode::asString)
                .contains("EVIDENCE_NOT_USABLE_IN_CONVERSATION", "CONVERSATION_CONSENT_NOT_ACTIVE");
        assertThat(withoutPreparedAt(bytes)).isEqualTo(withoutRecords);
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .doesNotContain(
                        note.getId().toString(),
                        erasedNote.getId().toString(),
                        topic.getId().toString(),
                        "note-on-withdrawn-evidence",
                        "note-on-erased-evidence",
                        "topic-in-a-revoked-thread");
    }

    @Test
    void shouldKeepDeliveredFeedbackDescribableWhenTheWorkIsRevisedAfterIt() {
        UUID linksIssue = observeStrength(ArtifactKinds.ISSUE, ISSUE);
        UUID describesIssue = observeStrength(ArtifactKinds.ISSUE, ISSUE);
        Feedback note = save(
                job,
                ArtifactKinds.ISSUE,
                ISSUE,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "issue-note",
                linksIssue);
        feedbackObservationRepository.insertIfAbsent(note.getId(), describesIssue, EvidenceRole.SUPPORTING.name(), 1);
        JsonNode before = objectMapper.readTree(contribute());
        assertThat(before.path("feedbackStates")
                        .path(0)
                        .path("evidenceCurrentness")
                        .asString())
                .isEqualTo("CURRENT");

        jdbcTemplate.update(
                "UPDATE observation SET superseded_at = now() WHERE id IN (?, ?)", linksIssue, describesIssue);
        JsonNode after = objectMapper.readTree(contribute());

        assertThat(after.get("deliveredFeedback")).isEqualTo(before.get("deliveredFeedback"));
        assertThat(after.get("feedbackStates"))
                .extracting(
                        e -> e.get("feedbackId").asString(),
                        e -> e.get("artifactKind").asString(),
                        e -> e.get("status").asString(),
                        e -> e.get("evidenceCurrentness").asString())
                .containsExactly(tuple(note.getId().toString(), "scm.issue", "DELIVERED", "STALE"));
    }

    @Test
    void shouldReportARecordedFailureWithoutItsText() {
        Feedback failed = save(
                job,
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.FAILED,
                "failed-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));
        Feedback partlyFailed = save(
                job,
                SECOND_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.PARTIALLY_FAILED,
                "partly-failed-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR));
        save(
                job,
                SECOND_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.SUPPRESSED,
                "withheld-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR));
        Feedback card = save(
                job,
                SECOND_MR,
                FeedbackChannel.IN_APP,
                FeedbackDeliveryState.PREPARED,
                "unread-card",
                observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR));

        byte[] bytes = contribute();
        JsonNode root = objectMapper.readTree(bytes);

        assertThat(root.get("deliveredFeedback")).isEmpty();
        assertThat(root.get("feedbackStates"))
                .extracting(
                        e -> e.get("feedbackId").asString(),
                        e -> e.get("status").asString())
                .containsExactlyInAnyOrder(
                        tuple(failed.getId().toString(), "DELIVERY_FAILED"),
                        tuple(partlyFailed.getId().toString(), "PARTIALLY_FAILED"),
                        tuple(card.getId().toString(), "PREPARED"));
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .doesNotContain("failed-note", "withheld-note", "unread-card", "SUPPRESSED");
    }

    @Test
    void shouldTieEachStatusToItsOwnUnitWhenOneMergeRequestIsReviewedRepeatedly() {
        Feedback first = save(
                newJob(),
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "first-summary",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));
        Feedback second = save(
                newJob(),
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "second-summary",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));
        feedbackRepository.supersedeDelivered(workspace.getId(), first.getId());
        Feedback third = save(
                newJob(),
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.FAILED,
                "third-summary",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));

        JsonNode root = objectMapper.readTree(contribute());

        assertThat(root.get("deliveredFeedback"))
                .extracting(
                        e -> e.get("feedbackId").asString(), e -> e.get("body").asString())
                .containsExactly(tuple(second.getId().toString(), "second-summary"));
        assertThat(root.get("feedbackStates"))
                .extracting(
                        e -> e.get("feedbackId").asString(),
                        e -> e.get("status").asString())
                .containsExactly(
                        tuple(third.getId().toString(), "DELIVERY_FAILED"),
                        tuple(second.getId().toString(), "DELIVERED"));
    }

    @Test
    void shouldIgnoreAnotherTenantsFeedbackWhenItSharesTheArtifactId() {
        Feedback own = save(
                job,
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "own-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));
        Workspace otherWorkspace =
                workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("feedback-states-other"));
        User otherRecipient =
                userRepository.save(TestUserFactory.createUser(101L, "other-recipient", recipient.getProvider()));
        saveUnbound(otherWorkspace.getId(), recipient.getId());
        saveUnbound(workspace.getId(), otherRecipient.getId());

        assertThat(feedbackRepository.findRecentReceivableForRecipient(
                        workspace.getId(), recipient.getId(), base.minusSeconds(1), null, null, PageRequest.of(0, 10)))
                .extracting(RecipientFeedbackRow::getId)
                .containsExactly(own.getId());
    }

    @Test
    void shouldWithholdStatusWhenItsEvidenceOrConsentNoLongerAuthorizesIt() {
        UUID withdrawn = observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR);
        Feedback failed =
                save(job, FIRST_MR, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.FAILED, "failed-note", withdrawn);
        saveUnbound(workspace.getId(), recipient.getId());
        long activeThread = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        long revokedThread = seedThread("C-revoked", "200.0", ConsentState.REVOKED);
        Feedback active = save(
                job,
                ArtifactKinds.CONVERSATION_THREAD,
                activeThread,
                FeedbackChannel.IN_CHAT,
                FeedbackDeliveryState.DELIVERED,
                "active-topic",
                observeStrength(ArtifactKinds.CONVERSATION_THREAD, activeThread));
        save(
                job,
                ArtifactKinds.CONVERSATION_THREAD,
                revokedThread,
                FeedbackChannel.IN_CHAT,
                FeedbackDeliveryState.DELIVERED,
                "revoked-topic",
                observeStrength(ArtifactKinds.CONVERSATION_THREAD, revokedThread));

        assertThat(objectMapper.readTree(contribute()).get("feedbackStates"))
                .extracting(e -> e.get("feedbackId").asString())
                .containsExactlyInAnyOrder(
                        failed.getId().toString(), active.getId().toString());
        withdrawEvidence(withdrawn);

        byte[] bytes = contribute();
        JsonNode root = objectMapper.readTree(bytes);

        assertThat(root.get("feedbackStates"))
                .extracting(e -> e.get("feedbackId").asString())
                .containsExactly(active.getId().toString());
        assertThat(root.get("deliveredFeedback")).isEmpty();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).doesNotContain("active-topic", "revoked-topic");
    }

    @Test
    void shouldReportADeliveredConversationTopicWhenItHasNoWordsToQuote() {
        long thread = seedThread("C-active", "100.0", ConsentState.ACTIVE);
        Feedback brief = save(
                job,
                ArtifactKinds.CONVERSATION_THREAD,
                thread,
                FeedbackChannel.IN_CHAT,
                FeedbackDeliveryState.DELIVERED,
                ConversationBriefBody.render("Handoffs", "situation", "capability", "evidence", "signal", null),
                observeStrength(ArtifactKinds.CONVERSATION_THREAD, thread));

        JsonNode root = objectMapper.readTree(contribute());

        assertThat(root.get("deliveredFeedback")).isEmpty();
        assertThat(root.get("feedbackStates"))
                .extracting(
                        e -> e.get("feedbackId").asString(),
                        e -> e.get("surface").asString(),
                        e -> e.get("status").asString())
                .containsExactly(tuple(brief.getId().toString(), "IN_CHAT", "DELIVERED"));
        assertThat(root.path("_meta").path("trustLevel").asString()).isEqualTo("UNTRUSTED_EXTERNAL");
    }

    @Test
    void shouldNeitherRepeatNorSkipFeedbackWhenItChangesBetweenPages() {
        List<Feedback> saved = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            saved.add(save(
                    job,
                    FIRST_MR + i,
                    FeedbackChannel.IN_CONTEXT,
                    FeedbackDeliveryState.DELIVERED,
                    "note-" + i,
                    observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR + i)));
        }
        RecipientFeedbackRow last = feedbackRepository
                .findRecentReceivableForRecipient(
                        workspace.getId(), recipient.getId(), base.minusSeconds(1), null, null, PageRequest.of(0, 30))
                .getLast();

        save(
                job,
                SECOND_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "newer-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, SECOND_MR));
        assertThat(nextPage(last)).containsExactly(saved.getFirst().getId());

        feedbackRepository.supersedeDelivered(workspace.getId(), saved.get(30).getId());
        feedbackRepository.supersedeDelivered(workspace.getId(), saved.get(29).getId());
        assertThat(nextPage(last)).containsExactly(saved.getFirst().getId());
    }

    @Test
    void shouldShowOlderVisibleFeedbackWhenNewerRowsAreHidden() {
        assertThat(objectMapper.readTree(contribute()).get("feedbackStates")).isEmpty();

        Feedback oldest = save(
                job,
                FIRST_MR,
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                "oldest-note",
                observeStrength(ArtifactKinds.PULL_REQUEST, FIRST_MR));
        for (int i = 0; i < 31; i++) {
            saveUnbound(workspace.getId(), recipient.getId());
        }

        assertThat(objectMapper.readTree(contribute()).get("feedbackStates"))
                .extracting(e -> e.get("feedbackId").asString())
                .containsExactly(oldest.getId().toString());
    }

    /** The payload with the one field that differs between two reads of the same rows. */
    private JsonNode withoutPreparedAt(byte[] bytes) {
        JsonNode root = objectMapper.readTree(bytes);
        ((ObjectNode) root.get("coverage")).remove("preparedAt");
        return root;
    }

    private List<UUID> nextPage(RecipientFeedbackRow after) {
        return feedbackRepository
                .findRecentReceivableForRecipient(
                        workspace.getId(),
                        recipient.getId(),
                        base.minusSeconds(1),
                        after.getCreatedAt(),
                        after.getId(),
                        PageRequest.of(0, 30))
                .stream()
                .map(RecipientFeedbackRow::getId)
                .toList();
    }

    private byte[] contribute() {
        Map<String, byte[]> files = new HashMap<>();
        contentSource.contribute(
                new ContextRequest.MentorChatRequest(workspace.getId(), recipient.getId(), UUID.randomUUID()), files);
        return Objects.requireNonNull(files.get(DeliveredFeedbackContentSource.OUTPUT_KEY));
    }

    private UUID observeStrength(ArtifactKind artifactKind, long artifactId) {
        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "observation-" + observationId,
                job.getId(),
                workspace.getId(),
                practice.getId(),
                practice.getCurrentRevision().getId(),
                artifactKind.value(),
                artifactId,
                recipient.getId(),
                "Links the issue it closes",
                "ASSESSED",
                "PRESENT",
                "GOOD",
                null,
                evidence(artifactKind),
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationId;
    }

    private Feedback save(
            AgentJob reviewJob,
            long mergeRequestId,
            FeedbackChannel channel,
            FeedbackDeliveryState state,
            String body,
            UUID observationId) {
        return save(reviewJob, ArtifactKinds.PULL_REQUEST, mergeRequestId, channel, state, body, observationId);
    }

    /** Each saved row is newer than the one before it. */
    private Feedback save(
            AgentJob reviewJob,
            ArtifactKind artifactKind,
            long artifactId,
            FeedbackChannel channel,
            FeedbackDeliveryState state,
            String body,
            UUID observationId) {
        Instant createdAt = base.plusSeconds(nextPosition);
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(reviewJob.getId())
                .workspaceId(workspace.getId())
                .artifactKind(artifactKind)
                .artifactId(artifactId)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(channel)
                .position(nextPosition++)
                .deliveryState(state)
                .source(FeedbackSource.AGENT)
                .body(body)
                .createdAt(createdAt)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? createdAt : null)
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
        return feedback;
    }

    /** A recorded failure bound to no evidence, so no conversation may use it. */
    private void saveUnbound(Long workspaceId, Long recipientUserId) {
        feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspaceId)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(FIRST_MR)
                .recipientUserId(recipientUserId)
                .aboutUserId(recipientUserId)
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(nextPosition)
                .deliveryState(FeedbackDeliveryState.FAILED)
                .source(FeedbackSource.AGENT)
                .body("hidden")
                .createdAt(base.plusSeconds(nextPosition++))
                .build());
    }

    /** The evidence now cites a source no purpose may use, so no surface may show it. */
    private void withdrawEvidence(UUID observationId) {
        jdbcTemplate.update(
                "UPDATE observation SET evidence = CAST(? AS jsonb) WHERE id = ?",
                citing("unapproved.source"),
                observationId);
    }

    private static String evidence(ArtifactKind artifactKind) {
        return citing(
                ArtifactKinds.CONVERSATION_THREAD.equals(artifactKind)
                        ? "slack.conversation.thread"
                        : "scm.pull-request.core");
    }

    private static String citing(String sourceKind) {
        return """
        {"citations":[{"sourceKind":"%s","artifactPath":"inputs/context/source.json",\
        "path":"source.json","startLine":1,"endLine":1,"quote":"evidence",\
        "quoteRedacted":false}]}
        """.formatted(sourceKind);
    }
}
