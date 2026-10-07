package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.context.StagedArtifactNames;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationSourceLiveness;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.FeedbackObservationVisibility;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Projects visible observations and feedback through the shared currentness, withdrawal and consent checks.
 * History guides inspection; it does not establish that two observations describe the same behavior.
 * Developer reactions are not review evidence.
 */
@Component
@Order(500)
public class ReviewHistoryContentSource implements EvidenceSource {

    private static final Logger log = LoggerFactory.getLogger(ReviewHistoryContentSource.class);

    static final SourceKind OBSERVATION_HISTORY = new SourceKind("hephaestus.observation-history");
    static final SourceKind FEEDBACK_HISTORY = new SourceKind("hephaestus.feedback-history");

    static final String OBSERVATIONS_FILE = SandboxLayout.HISTORY_PREFIX + "observations.json";
    static final String FEEDBACK_FILE = SandboxLayout.HISTORY_PREFIX + "feedback.json";
    static final String PREPARED_FILE = SandboxLayout.HISTORY_PREFIX + "prepared.json";

    private final ObservationRepository observationRepository;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final FeedbackWithdrawalRepository withdrawalRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final ConversationSourceLiveness conversationLiveness;
    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final StagedArtifactNames artifactNames;
    private final ObjectMapper objectMapper;

    public ReviewHistoryContentSource(
            ObservationRepository observationRepository,
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            FeedbackWithdrawalRepository withdrawalRepository,
            ObservationVisibilityPolicy visibilityPolicy,
            ConversationSourceLiveness conversationLiveness,
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            StagedArtifactNames artifactNames,
            ObjectMapper objectMapper) {
        this.observationRepository = observationRepository;
        this.feedbackRepository = feedbackRepository;
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.visibilityPolicy = visibilityPolicy;
        this.conversationLiveness = conversationLiveness;
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.artifactNames = artifactNames;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(OBSERVATION_HISTORY, FEEDBACK_HISTORY);
    }

    /** Prepared and delivered feedback share a source kind; observations have their own. */
    @Override
    public SourceKind sourceKindFor(String path) {
        return FEEDBACK_FILE.equals(path) || PREPARED_FILE.equals(path) ? FEEDBACK_HISTORY : OBSERVATION_HISTORY;
    }

    /** Owns the normalized {@code inputs/history/} view used by readiness and composition. */
    @Override
    public boolean ownsPath(String path) {
        return path.startsWith(SandboxLayout.HISTORY_PREFIX);
    }

    /** Every review of an artifact. History is about the person, so what they produced does not change it. */
    @Override
    public boolean supports(ContextRequest request) {
        return reviewJob(request) != null;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        files.putAll(captureSelected(request, sourceKinds()).files());
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        return captureSelected(request, selectedKinds);
    }

    private EvidenceContribution captureSelected(ContextRequest request, Set<SourceKind> selectedKinds) {
        AgentJob job = reviewJob(request);
        if (job == null || job.getWorkspace() == null) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        long workspaceId = job.getWorkspace().getId();
        Long subjectUserId = resolveSubject(request, job);
        if (subjectUserId == null) {
            // Unavailable, not empty: there is no person to read a history for, so nothing was read.
            return absent(selectedKinds, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
        }

        // Each kind is queried and reported only when selected — the builder rejects completeness or
        // content-state facts about a source that wasn't asked for.
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new LinkedHashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new LinkedHashMap<>();
        int observationCount = -1;
        int feedbackCount = -1;
        int preparedCount = -1;

        if (selectedKinds.contains(OBSERVATION_HISTORY)) {
            List<Observation> observations =
                    visibleObservations(workspaceId, subjectUserId, sourceJobExcludedFromHistory(job));
            observationCount = observations.size();
            files.put(OBSERVATIONS_FILE, serialize(observationsPayload(workspaceId, observations), OBSERVATIONS_FILE));
            completeness.put(OBSERVATION_HISTORY, SourceCompleteness.COMPLETE);
            // Reported explicitly rather than inferred from file presence: the file is always written,
            // so "there is a file" would wrongly answer NON_EMPTY for a person with no history.
            contentStates.put(
                    OBSERVATION_HISTORY,
                    observations.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }

        if (selectedKinds.contains(FEEDBACK_HISTORY)) {
            List<Feedback> deliveredRows = feedbackRepository.findDeliveredForPersonHistory(workspaceId, subjectUserId);
            List<Feedback> queuedRows =
                    feedbackRepository.findPreparedForRecipient(workspaceId, subjectUserId, Pageable.unpaged());
            ShownFeedback shown = shownFeedback(workspaceId, deliveredRows, queuedRows);
            List<Feedback> delivered = deliveredRows.stream()
                    .filter(f -> shown.currentness().containsKey(f.getId()))
                    .toList();
            List<Feedback> queued = queuedRows.stream()
                    .filter(f -> shown.currentness().containsKey(f.getId()))
                    .toList();
            Set<UUID> withdrawn = withdrawalRepository.withdrawnAmong(
                    workspaceId, shown.currentness().keySet());
            feedbackCount = delivered.size();
            files.put(
                    FEEDBACK_FILE, serialize(feedbackPayload(workspaceId, delivered, shown, withdrawn), FEEDBACK_FILE));
            preparedCount = queued.size();
            files.put(
                    PREPARED_FILE,
                    serialize(preparedPayload(workspaceId, queued, shown.currentness(), withdrawn), PREPARED_FILE));
            completeness.put(FEEDBACK_HISTORY, SourceCompleteness.COMPLETE);
            // Prepared feedback does not make the delivered feedback record non-empty.
            contentStates.put(
                    FEEDBACK_HISTORY, delivered.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }

        log.debug(
                "Review history: workspaceId={}, subjectUserId={}, observations={}, feedback={}, prepared={}",
                workspaceId,
                subjectUserId,
                observationCount,
                feedbackCount,
                preparedCount);
        return new EvidenceContribution(
                files, Map.copyOf(completeness), Map.of(), Map.of(), Map.of(), Map.copyOf(contentStates));
    }

    /** Full file projection; both readers use the same visibility and withdrawal checks. */
    public void renderPersonHistory(
            long workspaceId,
            long personId,
            Set<SourceKind> selectedKinds,
            UUID excludedJobId,
            Consumer<ObjectNode> observations,
            Consumer<ObjectNode> feedback) {
        if (selectedKinds.contains(OBSERVATION_HISTORY)) {
            List<Observation> rows = authorizeObservations(
                    workspaceId, observationRepository.findForPersonHistory(personId, workspaceId), excludedJobId);
            var items = observationsPayload(workspaceId, rows).path("observations");
            for (int i = 0; i < rows.size(); i++) {
                ObjectNode record = (ObjectNode) items.get(i);
                record.put("synced_at", rows.get(i).getObservedAt().toString());
                observations.accept(record);
            }
        }
        if (selectedKinds.contains(FEEDBACK_HISTORY)) {
            List<Feedback> rows = feedbackRepository.findDeliveredForPersonHistory(workspaceId, personId);
            ShownFeedback shown = shownFeedback(workspaceId, rows, List.of());
            List<Feedback> permitted = rows.stream()
                    .filter(row -> shown.currentness().containsKey(row.getId()))
                    .toList();
            Set<UUID> withdrawn = withdrawalRepository.withdrawnAmong(
                    workspaceId, shown.currentness().keySet());
            var items =
                    feedbackPayload(workspaceId, permitted, shown, withdrawn).path("feedback");
            for (int i = 0; i < permitted.size(); i++) {
                ObjectNode record = (ObjectNode) items.get(i);
                record.put("synced_at", permitted.get(i).getCreatedAt().toString());
                feedback.accept(record);
            }
        }
    }

    /** Admission and later disclosure use the same observation visibility and feedback withdrawal decisions. */
    public boolean permitsHistoryRecord(long workspaceId, String type, UUID id, SourceUsePurpose purpose) {
        if (type.equals("observation")) {
            var row = observationRepository
                    .findByIdAndWorkspaceId(id, workspaceId)
                    .orElse(null);
            return row != null
                    && !authorizeObservations(workspaceId, List.of(row), null, purpose)
                            .isEmpty();
        }
        if (!type.equals("feedback")) return false;
        var row = feedbackRepository.findPersonHistoryRecord(id, workspaceId).orElse(null);
        if (row == null
                || withdrawalRepository.withdrawnAmong(workspaceId, Set.of(id)).contains(id)) return false;
        return shownFeedback(workspaceId, List.of(row), List.of(), purpose)
                        .currentness()
                        .get(id)
                == ReviewClaimCurrentness.CURRENT;
    }

    private List<Observation> visibleObservations(long workspaceId, Long subjectUserId, @Nullable UUID excludedJobId) {
        return authorizeObservations(
                workspaceId, observationRepository.findForPersonHistory(subjectUserId, workspaceId), excludedJobId);
    }

    private List<Observation> authorizeObservations(
            long workspaceId, List<Observation> recent, @Nullable UUID excludedJobId) {
        return authorizeObservations(workspaceId, recent, excludedJobId, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
    }

    private List<Observation> authorizeObservations(
            long workspaceId, List<Observation> recent, @Nullable UUID excludedJobId, SourceUsePurpose purpose) {
        Set<UUID> visible = visibilityPolicy.permitsAll(workspaceId, recent, purpose);
        List<Observation> authorized =
                recent.stream().filter(o -> visible.contains(o.getId())).toList();
        Set<Long> activeThreads =
                activeThreads(workspaceId, authorized.stream().map(ReviewHistoryContentSource::referenceOf));
        return authorized.stream()
                .filter(o -> live(referenceOf(o), activeThreads))
                // Composition receives the current observations separately, with durable ids. Counting them
                // again as history would turn a first occurrence into an apparent recurrence.
                .filter(o -> excludedJobId == null || !excludedJobId.equals(o.getAgentJobId()))
                .toList();
    }

    /**
     * The feedback this review may read, as the developer's own surfaces decide it: every observation it is
     * bound to may be shown. A row whose evidence is no longer current keeps its record but not its words, which
     * describe work as it was and are not evidence about the work as it is.
     */
    private ShownFeedback shownFeedback(long workspaceId, List<Feedback> delivered, List<Feedback> queued) {
        return shownFeedback(workspaceId, delivered, queued, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
    }

    /** The rows that may be read, the currentness of their evidence, and the observations each is bound to. */
    private record ShownFeedback(Map<UUID, ReviewClaimCurrentness> currentness, Map<UUID, List<Observation>> basedOn) {}

    private ShownFeedback shownFeedback(
            long workspaceId, List<Feedback> delivered, List<Feedback> queued, SourceUsePurpose purpose) {
        List<UUID> ids = Stream.concat(delivered.stream(), queued.stream())
                .map(Feedback::getId)
                .toList();
        if (ids.isEmpty()) {
            return new ShownFeedback(Map.of(), Map.of());
        }
        List<FeedbackObservationVisibility> bindings =
                feedbackObservationRepository.findForVisibility(workspaceId, ids);
        Set<UUID> visible = new HashSet<>(visibilityPolicy.permitsShown(
                workspaceId,
                bindings.stream()
                        .map(FeedbackObservationVisibility::getObservation)
                        .toList(),
                purpose));
        Set<Long> activeThreads = activeThreads(
                workspaceId,
                Stream.concat(
                        Stream.concat(delivered.stream(), queued.stream()).map(ReviewHistoryContentSource::referenceOf),
                        bindings.stream()
                                .map(FeedbackObservationVisibility::getObservation)
                                .map(ReviewHistoryContentSource::referenceOf)));
        // An observation from a conversation whose channel no longer consents withholds every row it is bound to.
        bindings.stream()
                .map(FeedbackObservationVisibility::getObservation)
                .filter(o -> !live(referenceOf(o), activeThreads))
                .forEach(o -> visible.remove(o.getId()));
        Map<UUID, ReviewClaimCurrentness> shown = new HashMap<>(FeedbackObservationVisibility.shown(bindings, visible));
        Stream.concat(delivered.stream(), queued.stream())
                .filter(f -> !live(referenceOf(f), activeThreads))
                .forEach(f -> shown.remove(f.getId()));
        Map<UUID, List<Observation>> basedOn = bindings.stream()
                .filter(binding -> shown.containsKey(binding.getFeedbackId()))
                .sorted(Comparator.comparing(binding -> binding.getObservation().getId()))
                .collect(Collectors.groupingBy(
                        FeedbackObservationVisibility::getFeedbackId,
                        Collectors.mapping(FeedbackObservationVisibility::getObservation, Collectors.toList())));
        return new ShownFeedback(shown, basedOn);
    }

    private Set<Long> activeThreads(long workspaceId, Stream<StagedArtifactNames.Reference> artifacts) {
        List<Long> threadIds = artifacts
                .filter(a -> ArtifactKinds.CONVERSATION_THREAD.equals(a.kind()))
                .map(StagedArtifactNames.Reference::id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return conversationLiveness.activeThreadIds(workspaceId, threadIds);
    }

    /**
     * Anything but a conversation stands as it is, unanchored feedback included; a conversation only while its channel
     * consents, and never without the thread it came from.
     */
    private static boolean live(StagedArtifactNames.Reference artifact, Set<Long> activeThreads) {
        if (!ArtifactKinds.CONVERSATION_THREAD.equals(artifact.kind())) {
            return true;
        }
        Long threadId = artifact.id();
        return threadId != null && activeThreads.contains(threadId);
    }

    private static StagedArtifactNames.Reference referenceOf(Observation observation) {
        return new StagedArtifactNames.Reference(observation.getArtifactKind(), observation.getArtifactId());
    }

    private static StagedArtifactNames.Reference referenceOf(Feedback feedback) {
        return new StagedArtifactNames.Reference(feedback.getArtifactKind(), feedback.getArtifactId());
    }

    /**
     * The words of a row whose evidence is still current; a stale row is staged without them. So is a row a
     * workspace admin withdrew: its evidence may be sound while its words are not, so only the fact is staged.
     */
    private static void putBody(
            ObjectNode node, Feedback f, Map<UUID, ReviewClaimCurrentness> shown, Set<UUID> withdrawn) {
        ReviewClaimCurrentness currentness = Objects.requireNonNull(shown.get(f.getId()));
        node.put("recordedClaimCurrentness", currentness.name());
        boolean isWithdrawn = withdrawn.contains(f.getId());
        if (isWithdrawn) {
            node.put("withdrawn", true);
        }
        if (currentness == ReviewClaimCurrentness.CURRENT && !isWithdrawn) {
            node.put("body", f.getBody());
            if (f.getBody() != null) {
                node.put("bodyRole", bodyRole(f.getChannel()));
            }
        }
    }

    /** A conversation unit holds mentor notes, not the mentor's spoken words. */
    private static String bodyRole(@Nullable FeedbackChannel channel) {
        if (channel == null) {
            return "UNKNOWN";
        }
        return switch (channel) {
            case IN_CHAT -> "MENTOR_NOTES";
            case IN_CONTEXT, IN_APP -> "COMPOSED_GUIDANCE";
        };
    }

    /**
     * A history file's root: what its records are, and that they are the review records this person's history may
     * supply — not a chronology of their work or of what was communicated to them.
     */
    private ObjectNode historyRoot(String recordRole) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode coverage = root.putObject("coverage");
        coverage.put("scope", "AUTHORIZED_REVIEW_RECORDS_FOR_SUBJECT");
        coverage.put("workChronology", "NOT_ESTABLISHED");
        coverage.put("communicationHistory", "NOT_ESTABLISHED");
        root.put("recordRole", recordRole);
        return root;
    }

    private static @Nullable UUID sourceJobExcludedFromHistory(AgentJob job) {
        String raw = job.getMetadata() == null
                ? ""
                : job.getMetadata().path("source_job_id").asString();
        if (raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * What has been written for this person and not yet reached them. Carries {@code threadKey} because
     * that is the handle a composer names to supersede one of these; the server rejects a key it did not
     * stage here, so the file is the whole vocabulary of supersession for this turn.
     *
     * <p>It carries {@code practiceSlug} for the same reason. The key is a digest, so it says nothing
     * about what the queued message is <em>about</em>, and a composer picking a replacement target off an
     * opaque string is picking blind — most visibly on the conversation lane, where a run that composed
     * nothing leaves the body null and the slug is all there is to recognise the entry by.
     */
    private ObjectNode preparedPayload(
            long workspaceId, List<Feedback> queued, Map<UUID, ReviewClaimCurrentness> shown, Set<UUID> withdrawn) {
        ObjectNode root = historyRoot("PREPARED_NOT_DELIVERED");
        StagedArtifactNames.Resolved names = artifactNames.resolve(
                workspaceId,
                queued.stream().map(ReviewHistoryContentSource::referenceOf).toList());
        Map<UUID, String> practices = queued.isEmpty()
                ? Map.of()
                : feedbackRepository
                        .findHeadlinePractices(
                                workspaceId,
                                queued.stream().map(Feedback::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(
                                FeedbackRepository.HeadlinePracticeRow::getFeedbackId,
                                FeedbackRepository.HeadlinePracticeRow::getPracticeSlug,
                                (first, second) -> first));
        ArrayNode items = root.putArray("prepared");
        for (Feedback f : queued) {
            ObjectNode node = items.addObject();
            node.put("threadKey", f.getThreadKey());
            node.put("practiceSlug", practices.get(f.getId()));
            node.put("channel", f.getChannel() == null ? null : f.getChannel().name());
            names.stageInto(node, f.getArtifactKind(), f.getArtifactId());
            node.put(
                    "preparedAt",
                    f.getCreatedAt() == null ? null : f.getCreatedAt().toString());
            // Notes to the mentor on the conversation lane, never the mentor's words: that lane stores the
            // situation, coaching goal, evidence summary and success signal, and the turn itself is still written live.
            // Null when the run that queued it composed nothing,
            // which leaves only the fact that something is queued.
            putBody(node, f, shown, withdrawn);
        }
        return root;
    }

    private ObjectNode observationsPayload(long workspaceId, List<Observation> observations) {
        ObjectNode root = historyRoot("RECORDED_OBSERVATIONS");
        StagedArtifactNames.Resolved names = artifactNames.resolve(
                workspaceId,
                observations.stream()
                        .map(ReviewHistoryContentSource::referenceOf)
                        .toList());
        ArrayNode items = root.putArray("observations");
        for (Observation o : observations) {
            ObjectNode node = items.addObject();
            // The handle delivered feedback's basedOn names; a trace, not evidence that two observations agree.
            node.put("id", o.getId().toString());
            node.put(
                    "practiceSlug",
                    o.getPractice() == null ? null : o.getPractice().getSlug());
            node.put("summary", o.getSummary());

            node.put("outcome", o.getOutcome().name());
            node.put(
                    "severity", o.getSeverity() == null ? null : o.getSeverity().name());
            // As recorded, with the qualifications and bounds it carried then; a missing value stays unknown.
            node.put("evidenceRationale", o.getEvidenceRationale());
            JsonNode evidence = o.getEvidence();
            if (evidence == null || evidence.isNull() || evidence.isMissingNode()) {
                node.putNull("evidence");
            } else {
                node.set("evidence", evidence.deepCopy());
            }
            putPracticeRevision(node, o.getPracticeRevision());
            names.stageInto(node, o.getArtifactKind(), o.getArtifactId());
            node.put(
                    "observedAt",
                    o.getObservedAt() == null ? null : o.getObservedAt().toString());
        }
        return root;
    }

    /**
     * What was delivered, each row with the observations it rests on as they were recorded: their outcome and the
     * practice revision they were made against, never the practice's current one. A row whose words are withheld
     * keeps these without its body; the ids are trace handles, not evidence.
     */
    private ObjectNode feedbackPayload(
            long workspaceId, List<Feedback> delivered, ShownFeedback shown, Set<UUID> withdrawn) {
        ObjectNode root = historyRoot("RECORDED_DELIVERY");
        StagedArtifactNames.Resolved names = artifactNames.resolve(
                workspaceId,
                delivered.stream().map(ReviewHistoryContentSource::referenceOf).toList());
        ArrayNode items = root.putArray("feedback");
        for (Feedback f : delivered) {
            ObjectNode node = items.addObject();
            node.put("id", f.getId().toString());
            node.put("channel", f.getChannel() == null ? null : f.getChannel().name());
            names.stageInto(node, f.getArtifactKind(), f.getArtifactId());
            node.put(
                    "deliveredAt",
                    f.getDeliveredAt() == null ? null : f.getDeliveredAt().toString());
            node.put("reviewedRevision", f.getReviewedRevision());
            ArrayNode basedOn = node.putArray("basedOn");
            for (Observation o : shown.basedOn().getOrDefault(f.getId(), List.of())) {
                ObjectNode support = basedOn.addObject();
                support.put("id", o.getId().toString());
                support.put(
                        "practiceSlug",
                        o.getPractice() == null ? null : o.getPractice().getSlug());
                putPracticeRevision(support, o.getPracticeRevision());
                support.put("outcome", o.getOutcome().name());
            }
            putBody(node, f, shown.currentness(), withdrawn);
        }
        return root;
    }

    private static void putPracticeRevision(ObjectNode node, @Nullable PracticeRevision revision) {
        if (revision == null) {
            node.putNull("practiceRevision");
            return;
        }
        ObjectNode recorded = node.putObject("practiceRevision");
        recorded.put("id", revision.getId() == null ? null : revision.getId().toString());
        recorded.put("number", revision.getRevisionNumber());
    }

    /**
     * The person the review is about: the author for work with one, the explicitly carried subject
     * otherwise — mirrors the resolution delivery performs, so history can't be staged for one person
     * while observations are filed against another.
     */
    private @Nullable Long resolveSubject(ContextRequest request, AgentJob job) {
        return switch (request) {
            case ContextRequest.PracticeReviewRequest ignored -> pullRequestSubject(job);
            case ContextRequest.IssueReviewRequest ignored -> authorOfIssue(job);
            case ContextRequest.DocumentReviewRequest ignored -> metadataSubject(job);
            case ContextRequest.ConversationReviewRequest ignored -> metadataSubject(job);
            case ContextRequest.MentorChatRequest ignored -> null;
        };
    }

    private @Nullable Long pullRequestSubject(AgentJob job) {
        var metadata = job.getMetadata();
        if (metadata != null && "REVIEWER".equals(MetaJson.optString(metadata, "subject_role"))) {
            Long subject = metadataSubject(job);
            return subject != null && subject > 0 ? subject : null;
        }
        return authorOfPullRequest(job);
    }

    private @Nullable Long authorOfPullRequest(AgentJob job) {
        Long id = metadataLong(job, "pull_request_id");
        if (id == null) return null;
        return pullRequestRepository
                .findByIdWithAuthorAndRepository(id)
                .map(PullRequest::getAuthor)
                .map(author -> author.getId())
                .orElse(null);
    }

    private @Nullable Long authorOfIssue(AgentJob job) {
        Long id = metadataLong(job, "issue_id");
        if (id == null) return null;
        return issueRepository
                .findByIdWithAuthorAndRepository(id)
                .map(Issue::getAuthor)
                .map(author -> author.getId())
                .orElse(null);
    }

    private @Nullable Long metadataSubject(AgentJob job) {
        return metadataLong(job, "about_user_id");
    }

    private static @Nullable Long metadataLong(AgentJob job, String field) {
        var metadata = job.getMetadata();
        if (metadata == null || metadata.isNull()) return null;
        var node = metadata.get(field);
        return node == null || node.isNull() || !node.isNumber() ? null : node.asLong();
    }

    private EvidenceContribution absent(Set<SourceKind> selectedKinds, SourceCaptureState state) {
        Map<SourceKind, SourceCaptureState> overrides = new LinkedHashMap<>();
        for (SourceKind kind : sourceKinds()) {
            if (selectedKinds.contains(kind)) overrides.put(kind, state);
        }
        return new EvidenceContribution(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), overrides);
    }

    private byte[] serialize(ObjectNode payload, String path) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        } catch (RuntimeException e) {
            throw new EvidenceCollectionException("Failed to serialize review history: " + path, e);
        }
    }

    private static @Nullable AgentJob reviewJob(ContextRequest request) {
        return switch (request) {
            case ContextRequest.PracticeReviewRequest r -> r.job();
            case ContextRequest.IssueReviewRequest r -> r.job();
            case ContextRequest.DocumentReviewRequest r -> r.job();
            case ContextRequest.ConversationReviewRequest r -> r.job();
            case ContextRequest.MentorChatRequest ignored -> null;
        };
    }
}
