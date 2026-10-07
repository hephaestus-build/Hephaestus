package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository.PostedCommentUrl;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
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
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

class FeedbackObservationRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> IN_CONTEXT_ONLY = List.of(FeedbackChannel.IN_CONTEXT.name());

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private FeedbackPlacementRepository feedbackPlacementRepository;

    private Workspace workspace;
    private Practice practice;
    private AgentJob agentJob;
    private IdentityProvider provider;
    private User recipient;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();

        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("feedback-observation-test"));

        practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("test-practice");
        practice.setName("Test Practice");
        practice.setCriteria("Test description");
        PracticeTestEvidence.configure(practice, ScmSignals.PULL_REQUEST_OPENED);
        practice = practiceRepository.save(practice);

        agentJob = new AgentJob();
        agentJob.setWorkspace(workspace);
        agentJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        agentJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        agentJob = agentJobRepository.save(agentJob);

        provider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        recipient = userRepository.save(TestUserFactory.createUser(100L, "recipient", provider));
    }

    @Test
    @DisplayName(
            "insertIfAbsent is idempotent: a second insert on the same (feedback, observation) returns 0 and keeps the original role/ordinal")
    void insertIfAbsentIsIdempotent() {
        Feedback feedback = saveFeedback(0, FeedbackDeliveryState.DELIVERED, "Delivered advice body");
        Observation observation = saveObservation("obs-1");

        int first = feedbackObservationRepository.insertIfAbsent(feedback.getId(), observation.getId(), "PRIMARY", 0);
        int second =
                feedbackObservationRepository.insertIfAbsent(feedback.getId(), observation.getId(), "SUPPORTING", 7);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);

        assertThat(feedbackObservationRepository.findAll()).hasSize(1);
        FeedbackObservation row = feedbackObservationRepository.findAll().get(0);
        assertThat(row.getRole()).isEqualTo(EvidenceRole.PRIMARY);
        assertThat(row.getOrdinal()).isEqualTo(0);
        assertThat(row.getFeedback().getId()).isEqualTo(feedback.getId());
        assertThat(row.getObservation().getId()).isEqualTo(observation.getId());
    }

    @Test
    @DisplayName("findLatestFeedbackBodiesByObservationIds returns DELIVERED and FAILED bodies and excludes PREPARED, "
            + "SUPPRESSED, and null-body units")
    void findAdviceBodiesIncludesFailedExcludesPreparedSuppressed() {
        Observation delivered = saveObservation("obs-delivered");
        Observation failed = saveObservation("obs-failed");
        Observation prepared = saveObservation("obs-prepared");
        Observation suppressed = saveObservation("obs-suppressed");
        Observation nullBody = saveObservation("obs-null-body");

        bind(saveFeedback(0, FeedbackDeliveryState.DELIVERED, "The advice the student saw"), delivered);
        bind(saveFeedback(4000, FeedbackDeliveryState.FAILED, "The advice the direct post could not place"), failed);
        bind(saveFeedback(1, FeedbackDeliveryState.PREPARED, "Not yet delivered"), prepared);
        bind(saveFeedback(2, FeedbackDeliveryState.SUPPRESSED, "Withheld"), suppressed);
        bind(saveFeedback(3, FeedbackDeliveryState.DELIVERED, null), nullBody);

        List<ObservationFeedbackBody> bodies = feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                workspace.getId(),
                List.of(delivered.getId(), failed.getId(), prepared.getId(), suppressed.getId(), nullBody.getId()),
                IN_CONTEXT_ONLY);

        assertThat(bodies).hasSize(2);
        Map<UUID, String> byObservation = bodies.stream()
                .collect(Collectors.toMap(ObservationFeedbackBody::getObservationId, ObservationFeedbackBody::getBody));
        assertThat(byObservation)
                .containsEntry(delivered.getId(), "The advice the student saw")
                .containsEntry(failed.getId(), "The advice the direct post could not place")
                .doesNotContainKeys(prepared.getId(), suppressed.getId(), nullBody.getId());
    }

    @Test
    void findLatestFeedbackBodiesUsesIdAsDeterministicTieBreak() {
        Observation observation = saveObservation("obs-repeated");
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        UUID lowerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higherId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        bind(saveFeedback(lowerId, 10, FeedbackDeliveryState.DELIVERED, "Earlier identity", createdAt), observation);
        bind(saveFeedback(higherId, 11, FeedbackDeliveryState.DELIVERED, "Latest identity", createdAt), observation);

        List<ObservationFeedbackBody> bodies = feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                workspace.getId(), List.of(observation.getId()), IN_CONTEXT_ONLY);

        assertThat(bodies)
                .singleElement()
                .extracting(ObservationFeedbackBody::getBody)
                .isEqualTo("Latest identity");
    }

    @Test
    @DisplayName("a newer IN_APP unit bound to the same observation does not become its advice: the per-observation "
            + "surfaces keep showing what was said about that observation")
    void findLatestFeedbackBodiesAnswersOnlyForTheChannelsTheCallerNames() {
        Observation observation = saveObservation("obs-both-lanes");
        bind(
                saveFeedback(
                        null,
                        20,
                        FeedbackDeliveryState.DELIVERED,
                        "The note posted on the pull request",
                        Instant.parse("2026-01-01T00:00:00Z")),
                observation);
        // The in-app unit is newer, DELIVERED and non-null-bodied, so it wins every other clause of
        // the query — the channel predicate is the only thing keeping it off a per-observation surface.
        bind(
                saveFeedback(
                        null,
                        7000,
                        FeedbackDeliveryState.DELIVERED,
                        "### You keep shipping untested changes\n\nAcross three pull requests…\n\n**Try next:** …",
                        Instant.parse("2026-02-01T00:00:00Z"),
                        FeedbackChannel.IN_APP),
                observation);

        assertThat(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                        workspace.getId(), List.of(observation.getId()), IN_CONTEXT_ONLY))
                .singleElement()
                .extracting(ObservationFeedbackBody::getBody)
                .isEqualTo("The note posted on the pull request");

        assertThat(feedbackObservationRepository.findLatestFeedbackBodiesByObservationIds(
                        workspace.getId(), List.of(observation.getId()), List.of(FeedbackChannel.IN_APP.name())))
                .singleElement()
                .extracting(ObservationFeedbackBody::getBody)
                .asString()
                .startsWith("### You keep shipping untested changes");
    }

    @Test
    @DisplayName("findLatestFeedbackByObservationIds reads the text and the response handle off one piece of "
            + "feedback: the newest on the named lanes, and a FAILED one carries its words without a handle")
    void shouldReadTextAndHandleOffOnePieceOfFeedbackWhenFindingTheLatest() {
        Observation delivered = saveObservation("obs-latest-delivered");
        Observation failed = saveObservation("obs-latest-failed");

        Feedback older = saveFeedback(
                null,
                30,
                FeedbackDeliveryState.DELIVERED,
                "The note posted on the pull request",
                Instant.parse("2026-01-01T00:00:00Z"));
        bind(older, delivered);
        // Newer, delivered and bound to the same observation, but on the cross-artifact lane the caller did
        // not name: it must decide neither the text nor the handle.
        bind(
                saveFeedback(
                        null,
                        7001,
                        FeedbackDeliveryState.DELIVERED,
                        "### You keep shipping untested changes",
                        Instant.parse("2026-02-01T00:00:00Z"),
                        FeedbackChannel.IN_APP),
                delivered);
        bind(
                saveFeedback(
                        null,
                        31,
                        FeedbackDeliveryState.FAILED,
                        "The advice the direct post could not place",
                        Instant.parse("2026-01-01T00:00:00Z")),
                failed);

        Map<UUID, ObservationFeedback> latest = feedbackObservationRepository
                .findLatestFeedbackByObservationIds(
                        workspace.getId(),
                        recipient.getId(),
                        List.of(delivered.getId(), failed.getId()),
                        IN_CONTEXT_ONLY)
                .stream()
                .collect(Collectors.toMap(ObservationFeedback::getObservationId, feedback -> feedback));

        assertThat(latest.get(delivered.getId())).isNotNull().satisfies(feedback -> {
            assertThat(feedback.getBody()).isEqualTo("The note posted on the pull request");
            assertThat(feedback.getFeedbackId()).isEqualTo(older.getId());
        });
        assertThat(latest.get(failed.getId())).isNotNull().satisfies(feedback -> {
            assertThat(feedback.getBody()).isEqualTo("The advice the direct post could not place");
            assertThat(feedback.getFeedbackId()).isNull();
        });
    }

    @Test
    @DisplayName("findLatestFeedbackByObservationIds answers delivered feedback that landed only as line notes, "
            + "which a newer failed unit with nothing to show does not shadow, and never prepared or suppressed units")
    void shouldCarryTheHandleOfDeliveredFeedbackWhenItHasNoSummaryText() {
        Observation inlineOnly = saveObservation("obs-inline-only");
        Observation prepared = saveObservation("obs-latest-prepared");
        Observation suppressed = saveObservation("obs-latest-suppressed");

        Feedback landed =
                saveFeedback(null, 40, FeedbackDeliveryState.DELIVERED, null, Instant.parse("2026-01-01T00:00:00Z"));
        bind(landed, inlineOnly);
        bind(
                saveFeedback(null, 4040, FeedbackDeliveryState.FAILED, null, Instant.parse("2026-02-01T00:00:00Z")),
                inlineOnly);
        bind(saveFeedback(41, FeedbackDeliveryState.PREPARED, "Not yet delivered"), prepared);
        bind(saveFeedback(42, FeedbackDeliveryState.SUPPRESSED, "Withheld"), suppressed);

        Map<UUID, ObservationFeedback> latest = feedbackObservationRepository
                .findLatestFeedbackByObservationIds(
                        workspace.getId(),
                        recipient.getId(),
                        List.of(inlineOnly.getId(), prepared.getId(), suppressed.getId()),
                        IN_CONTEXT_ONLY)
                .stream()
                .collect(Collectors.toMap(ObservationFeedback::getObservationId, feedback -> feedback));

        assertThat(latest).doesNotContainKeys(prepared.getId(), suppressed.getId());
        assertThat(latest.get(inlineOnly.getId())).isNotNull().satisfies(feedback -> {
            assertThat(feedback.getBody()).isNull();
            assertThat(feedback.getFeedbackId()).isEqualTo(landed.getId());
        });
    }

    @Test
    @DisplayName("findDeliveredCommentUrls returns the stored links of delivered in-context feedback, summary first "
            + "and line notes in file order, including feedback that landed only as line notes")
    void shouldReturnStoredCommentLinksWhenFeedbackWasDeliveredOnTheWork() {
        Instant postedAt = Instant.parse("2026-01-01T00:00:00Z");
        Feedback withSummary = saveFeedback(50, FeedbackDeliveryState.DELIVERED, "Split the retry out.");
        placeInline(withSummary, "src/B.java", "note-b", "https://github.com/o/r/pull/7#discussion_r2", postedAt);
        placeSummary(withSummary, "comment-1", "https://github.com/o/r/pull/7#issuecomment-1", postedAt);
        placeInline(withSummary, "src/A.java", "note-a", "https://github.com/o/r/pull/7#discussion_r1", postedAt);
        // Recorded before permalinks were captured, or with a blank one: there is nothing to link to.
        placeInline(withSummary, "src/C.java", "note-c", null, postedAt);
        placeInline(withSummary, "src/D.java", "note-d", "  ", postedAt);
        Feedback inlineOnly = saveFeedback(51, FeedbackDeliveryState.DELIVERED, null);
        String lineNote = "https://gitlab.example.com/a/b/-/merge_requests/1#note_1";
        placeInline(inlineOnly, "src/Main.java", "note-1", lineNote, postedAt);

        Map<UUID, List<String>> urls = commentUrls(withSummary.getId(), inlineOnly.getId());

        assertThat(urls.get(withSummary.getId()))
                .containsExactly(
                        "https://github.com/o/r/pull/7#issuecomment-1",
                        "https://github.com/o/r/pull/7#discussion_r1",
                        "https://github.com/o/r/pull/7#discussion_r2");
        assertThat(urls.get(inlineOnly.getId())).containsExactly(lineNote);
    }

    @Test
    @DisplayName("findDeliveredCommentUrls links only delivered in-context feedback to this developer in this "
            + "workspace, and not a comment that newer feedback took over")
    void shouldNotLinkCommentsWhenFeedbackIsNotThisDevelopersDeliveredFeedbackOnTheWork() {
        Instant postedAt = Instant.parse("2026-01-01T00:00:00Z");
        Feedback prepared = saveFeedback(60, FeedbackDeliveryState.PREPARED, "Not yet delivered");
        placeInline(prepared, "src/A.java", "note-prepared", "https://github.com/o/r/pull/7#discussion_r10", postedAt);
        Feedback failed = saveFeedback(61, FeedbackDeliveryState.FAILED, "Could not be placed");
        placeInline(failed, "src/A.java", "note-failed", "https://github.com/o/r/pull/7#discussion_r11", postedAt);
        Feedback inChat = saveFeedback(
                null, 62, FeedbackDeliveryState.DELIVERED, "Said in conversation", postedAt, FeedbackChannel.IN_CHAT);
        placeInline(inChat, "src/A.java", "note-chat", "https://github.com/o/r/pull/7#discussion_r12", postedAt);

        User someoneElse = userRepository.save(TestUserFactory.createUser(101L, "someone-else", provider));
        Feedback toSomeoneElse = feedbackRepository.save(Feedback.builder()
                .agentJobId(agentJob.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .recipientUserId(someoneElse.getId())
                .aboutUserId(someoneElse.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(63)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body("For another developer")
                .source(FeedbackSource.AGENT)
                .createdAt(postedAt)
                .deliveredAt(postedAt)
                .build());
        placeInline(
                toSomeoneElse, "src/A.java", "note-other", "https://github.com/o/r/pull/7#discussion_r13", postedAt);

        Workspace otherWorkspace =
                workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("feedback-observation-other"));
        AgentJob otherJob = new AgentJob();
        otherJob.setWorkspace(otherWorkspace);
        otherJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        otherJob.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        otherJob = agentJobRepository.save(otherJob);
        Feedback inOtherWorkspace = feedbackRepository.save(Feedback.builder()
                .agentJobId(otherJob.getId())
                .workspaceId(otherWorkspace.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body("In another workspace")
                .source(FeedbackSource.AGENT)
                .createdAt(postedAt)
                .deliveredAt(postedAt)
                .build());
        placeInline(
                inOtherWorkspace,
                "src/A.java",
                "note-elsewhere",
                "https://github.com/o/r/pull/7#discussion_r14",
                postedAt);

        // One issue summary edited in place: the comment now shows the newer feedback only.
        Feedback editedOver = saveFeedback(64, FeedbackDeliveryState.DELIVERED, "The first summary");
        placeSummary(editedOver, "issue-summary", "https://github.com/o/r/issues/8#issuecomment-8", postedAt);
        Feedback newer = saveFeedback(65, FeedbackDeliveryState.DELIVERED, "The summary that replaced it");
        placeSummary(
                newer, "issue-summary", "https://github.com/o/r/issues/8#issuecomment-8", postedAt.plusSeconds(60));

        Map<UUID, List<String>> urls = commentUrls(
                prepared.getId(),
                failed.getId(),
                inChat.getId(),
                toSomeoneElse.getId(),
                inOtherWorkspace.getId(),
                editedOver.getId(),
                newer.getId());

        assertThat(urls)
                .doesNotContainKeys(
                        prepared.getId(),
                        failed.getId(),
                        inChat.getId(),
                        toSomeoneElse.getId(),
                        inOtherWorkspace.getId(),
                        editedOver.getId());
        assertThat(urls.get(newer.getId())).containsExactly("https://github.com/o/r/issues/8#issuecomment-8");
    }

    @Test
    @DisplayName("deleting the parent Feedback cascades the join row away (ON DELETE CASCADE)")
    void deletingFeedbackCascadesJoinRow() {
        Feedback feedback = saveFeedback(0, FeedbackDeliveryState.DELIVERED, "Body");
        Observation observation = saveObservation("obs-cascade");
        bind(feedback, observation);
        assertThat(feedbackObservationRepository.findAll()).hasSize(1);

        feedbackRepository.deleteById(feedback.getId());
        feedbackRepository.flush();

        assertThat(feedbackObservationRepository.findAll()).isEmpty();
        assertThat(observationRepository.findById(observation.getId())).isPresent();
    }

    private void bind(Feedback feedback, Observation observation) {
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observation.getId(), "PRIMARY", 0);
    }

    private Map<UUID, List<String>> commentUrls(UUID... feedbackIds) {
        return feedbackPlacementRepository
                .findDeliveredCommentUrls(workspace.getId(), recipient.getId(), List.of(feedbackIds))
                .stream()
                .collect(Collectors.groupingBy(
                        PostedCommentUrl::getFeedbackId,
                        Collectors.mapping(PostedCommentUrl::getUrl, Collectors.toList())));
    }

    private void placeSummary(Feedback feedback, String ref, String url, Instant createdAt) {
        feedbackPlacementRepository.save(FeedbackPlacement.builder()
                .feedback(feedback)
                .placementType(PlacementType.SUMMARY)
                .postedCommentRef(ref)
                .postedCommentUrl(url)
                .createdAt(createdAt)
                .build());
    }

    private void placeInline(Feedback feedback, String path, String ref, @Nullable String url, Instant createdAt) {
        feedbackPlacementRepository.save(FeedbackPlacement.builder()
                .feedback(feedback)
                .placementType(PlacementType.INLINE)
                .anchorKind(PlacementAnchorKind.LINE)
                .anchorPath(path)
                .anchorStartLine(1)
                .anchorEndLine(1)
                .anchorSide(PlacementAnchorSide.NEW)
                .postedCommentRef(ref)
                .postedCommentUrl(url)
                .createdAt(createdAt)
                .build());
    }

    private Feedback saveFeedback(int position, FeedbackDeliveryState state, @Nullable String body) {
        return saveFeedback(null, position, state, body, Instant.now());
    }

    private Feedback saveFeedback(
            @Nullable UUID id, int position, FeedbackDeliveryState state, @Nullable String body, Instant createdAt) {
        return saveFeedback(id, position, state, body, createdAt, FeedbackChannel.IN_CONTEXT);
    }

    private Feedback saveFeedback(
            @Nullable UUID id,
            int position,
            FeedbackDeliveryState state,
            @Nullable String body,
            Instant createdAt,
            FeedbackChannel channel) {
        boolean anchored = channel == FeedbackChannel.IN_CONTEXT;
        return feedbackRepository.save(Feedback.builder()
                .id(id)
                .agentJobId(agentJob.getId())
                .workspaceId(workspace.getId())
                .artifactKind(anchored ? ArtifactKinds.PULL_REQUEST : null)
                .artifactId(anchored ? 42L : null)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(channel)
                .position(position)
                .deliveryState(state)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? Instant.now() : null)
                .build());
    }

    private Observation saveObservation(String occurrenceKey) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                occurrenceKey,
                agentJob.getId(),
                agentJob.getWorkspace().getId(),
                practice.getId(),
                null,
                "scm.pull_request",
                42L,
                recipient.getId(),
                "Observation title",
                "NOT_MET",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationRepository.findById(id).orElseThrow();
    }
}
