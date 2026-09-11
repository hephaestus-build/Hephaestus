package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.context.StagedArtifactNames;
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
import de.tum.cit.aet.hephaestus.practices.feedback.DeveloperTextSanitizer;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationDelta;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stages what earlier reviews recorded about this person: the observations earlier runs filed against
 * them, carrying the recurrence key that says which are about the same underlying problem, the feedback
 * already delivered, the feedback composed for them but not yet received, and how each measured locus
 * moved — so a run can tell "this is new" from "we said this before" from "that is already queued".
 *
 * <p>The four files answer four different questions and are staged together because composing feedback
 * needs all four at once: what is true of this person's work, what they have been told, what they are
 * about to be told, and what changed. {@code prepared.json} is what makes supersession possible at all —
 * without it a composer choosing to replace a queued message is guessing at what it is replacing.
 *
 * <p>Every file is written even when a person has no history: an empty {@code observations.json} says
 * the record was read and held nothing, distinct from a source that was never staged.
 *
 * <p>Never reported COMPLETE — the window is a bounded read of a record that keeps growing, so it can
 * show that something recurred but never that it has never happened before.
 *
 * <p>Only what earlier reviews observed and delivered is staged, not how a contributor reacted to it
 * (applied, dismissed, disputed): this class does not reach the reaction package (ADR 0021 F-9, pinned by
 * {@code DetectionReactionFirewallTest}). Detection that knew a finding had been disputed would have a
 * reason to stop reporting a true positive.
 *
 * <p>History can anchor a model into echoing an earlier observation instead of looking; the delivery
 * boundary bounds that by requiring every observation to quote the artifact under review, so history may
 * be cited for recurrence but never for what is present in the current work.
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
    static final String DELTA_FILE = SandboxLayout.HISTORY_PREFIX + "delta.json";

    /** Exposure bounds, not cost bounds — they cap how much of a contributor's record can anchor a model. */
    private static final int LOOKBACK_DAYS = 90;

    private static final int MAX_OBSERVATIONS = 50;
    private static final int MAX_FEEDBACK = 30;

    /**
     * Queued messages staged for supersession. Smaller than the delivered window because it is not a
     * record: a recipient holding more than this many unread messages has a delivery problem, not a
     * history to reason over.
     */
    private static final int MAX_PREPARED = 20;

    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final StagedArtifactNames artifactNames;
    private final ObjectMapper objectMapper;

    public ReviewHistoryContentSource(
            ObservationRepository observationRepository,
            FeedbackRepository feedbackRepository,
            ObservationVisibilityPolicy visibilityPolicy,
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            StagedArtifactNames artifactNames,
            ObjectMapper objectMapper) {
        this.observationRepository = observationRepository;
        this.feedbackRepository = feedbackRepository;
        this.visibilityPolicy = visibilityPolicy;
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.artifactNames = artifactNames;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(OBSERVATION_HISTORY, FEEDBACK_HISTORY);
    }

    /**
     * Four files, two kinds. {@code delta.json} is arithmetic over the observations and nothing else, and
     * {@code prepared.json} is feedback that has been written but not yet received — so each is the same
     * data, under the same authorization, retention and erasure rules as the file it is derived from. A
     * source kind names what a reading is <em>of</em>, not which file it landed in, and the versioned
     * artifact-source contract is frozen: minting a kind for a projection of an existing one would ask an
     * operator to grant a second permission over data they have already granted one for.
     */
    @Override
    public SourceKind sourceKindFor(String path) {
        return FEEDBACK_FILE.equals(path) || PREPARED_FILE.equals(path) ? FEEDBACK_HISTORY : OBSERVATION_HISTORY;
    }

    /** Owns {@code inputs/history/}, not the per-event {@code inputs/context/} namespace. */
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
        Instant since = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);

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
                    visibleObservations(workspaceId, subjectUserId, since, sourceJobExcludedFromHistory(job));
            observationCount = observations.size();
            files.put(
                    OBSERVATIONS_FILE,
                    serialize(observationsPayload(workspaceId, observations, since), OBSERVATIONS_FILE));
            // Arithmetic over exactly the observations staged above, from the same read: a second query
            // could only make the delta and the record it summarises disagree.
            ObservationDelta delta = ObservationDelta.classify(
                    observations.stream().map(this::locusOf).toList());
            files.put(DELTA_FILE, serialize(deltaPayload(delta, since), DELTA_FILE));
            completeness.put(OBSERVATION_HISTORY, SourceCompleteness.PARTIAL);
            // Reported explicitly rather than inferred from file presence: the file is always written,
            // so "there is a file" would wrongly answer NON_EMPTY for a person with no history.
            contentStates.put(
                    OBSERVATION_HISTORY,
                    observations.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }

        if (selectedKinds.contains(FEEDBACK_HISTORY)) {
            List<Feedback> delivered = feedbackRepository.findRecentDeliveredForRecipient(
                    workspaceId, subjectUserId, since, PageRequest.of(0, MAX_FEEDBACK));
            feedbackCount = delivered.size();
            files.put(FEEDBACK_FILE, serialize(feedbackPayload(workspaceId, delivered, since), FEEDBACK_FILE));
            List<Feedback> queued = feedbackRepository.findPreparedForRecipient(
                    workspaceId, subjectUserId, PageRequest.of(0, MAX_PREPARED));
            preparedCount = queued.size();
            files.put(PREPARED_FILE, serialize(preparedPayload(workspaceId, queued), PREPARED_FILE));
            completeness.put(FEEDBACK_HISTORY, SourceCompleteness.PARTIAL);
            // Reported off what has been delivered, not off the queue: the kind is "what was said to this
            // person", and a full queue with nothing delivered is still an empty record of having spoken.
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

    private List<Observation> visibleObservations(
            long workspaceId, Long subjectUserId, Instant since, @Nullable UUID excludedJobId) {
        List<Observation> recent = observationRepository.findRecentByDeveloperAndWorkspace(
                subjectUserId,
                workspaceId,
                since,
                // Verdicts only: a run that produced none is nothing this context can quote, and it would spend
                // the page budget burying the rows that can be.
                true,
                PageRequest.of(0, MAX_OBSERVATIONS));
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, recent, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
        return recent.stream()
                .filter(o -> visible.contains(o.getId()))
                // Composition receives the current observations separately, with durable ids. Counting them
                // again as history would turn a first occurrence into an apparent recurrence.
                .filter(o -> excludedJobId == null || !excludedJobId.equals(o.getAgentJobId()))
                .toList();
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

    private ObservationDelta.Locus locusOf(Observation observation) {
        return new ObservationDelta.Locus(
                observation.getRecurrenceKey(),
                observation.getPractice() == null
                        ? ""
                        : observation.getPractice().getSlug(),
                observation.getArtifactKind(),
                observation.getArtifactId(),
                observation.getAgentJobId(),
                observation.getObservedAt(),
                observation.getOutcome(),
                observation.getSeverity());
    }

    /**
     * The delta, as statuses and practice slugs — never as a recurrence key. The key is a hash of the
     * subject and the artifact row id, so it is both meaningless to a reader and the one field in this
     * payload a model could quote back to a person as if it named their work.
     */
    private ObjectNode deltaPayload(ObservationDelta delta, Instant since) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("window", "how each measured locus moved, over runs since " + since);
        root.put("count", delta.loci().size());
        root.put(
                "completeness",
                "PARTIAL: computed over the staged observation window only. A locus first measured before the "
                        + "window looks NEW here, and a run before it is invisible.");
        ArrayNode items = root.putArray("loci");
        for (ObservationDelta.LocusChange change : delta.loci()) {
            ObjectNode node = items.addObject();
            node.put("practiceSlug", change.practiceSlug());
            node.put("status", change.status().name());
            node.put("runsSeen", change.runsSeen());
            node.put(
                    "firstSeenAt",
                    change.firstSeenAt() == null ? null : change.firstSeenAt().toString());
            node.put(
                    "lastSeenAt",
                    change.lastSeenAt() == null ? null : change.lastSeenAt().toString());
            node.put(
                    "assessment",
                    change.latestOutcome() == null
                            ? null
                            : change.latestOutcome().name());
            node.put(
                    "severity",
                    change.latestSeverity() == null
                            ? null
                            : change.latestSeverity().name());
        }
        return root;
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
    private ObjectNode preparedPayload(long workspaceId, List<Feedback> queued) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("window", "composed for this developer and not yet received, newest first");
        root.put("count", queued.size());
        root.put(
                "completeness",
                "PARTIAL: the most recent " + MAX_PREPARED
                        + " queued items. Absence here is not proof nothing is queued.");
        StagedArtifactNames.Resolved names = artifactNames.resolve(
                workspaceId,
                queued.stream()
                        .map(f -> new StagedArtifactNames.Reference(f.getArtifactKind(), f.getArtifactId()))
                        .toList());
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
            node.put("body", DeveloperTextSanitizer.sanitize(f.getBody()));
        }
        return root;
    }

    private ObjectNode observationsPayload(long workspaceId, List<Observation> observations, Instant since) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("window", "observations recorded since " + since + ", newest first");
        root.put("count", observations.size());
        // Stated in the file itself, which the model reads directly, so an empty list can't be read as
        // "never happened".
        root.put(
                "completeness",
                "PARTIAL: the most recent " + MAX_OBSERVATIONS
                        + " observations within the window. An observation absent here may still have been recorded.");
        StagedArtifactNames.Resolved names = artifactNames.resolve(
                workspaceId,
                observations.stream()
                        .map(o -> new StagedArtifactNames.Reference(o.getArtifactKind(), o.getArtifactId()))
                        .toList());
        ArrayNode items = root.putArray("observations");
        for (Observation o : observations) {
            ObjectNode node = items.addObject();
            node.put(
                    "practiceSlug",
                    o.getPractice() == null ? null : o.getPractice().getSlug());
            node.put("recurrenceKey", o.getRecurrenceKey());
            node.put("summary", o.getSummary());
            node.put("assessmentStatus", o.getAssessmentStatus().name());
            node.put("outcome", o.getOutcome() == null ? null : o.getOutcome().name());
            node.put(
                    "presence", o.getPresence() == null ? null : o.getPresence().name());
            node.put(
                    "assessment",
                    o.getAssessment() == null ? null : o.getAssessment().name());
            node.put(
                    "severity", o.getSeverity() == null ? null : o.getSeverity().name());
            names.stageInto(node, o.getArtifactKind(), o.getArtifactId());
            node.put(
                    "observedAt",
                    o.getObservedAt() == null ? null : o.getObservedAt().toString());
            node.put("evidenceRationale", DeveloperTextSanitizer.sanitize(o.getEvidenceRationale()));
        }
        return root;
    }

    private ObjectNode feedbackPayload(long workspaceId, List<Feedback> delivered, Instant since) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("window", "feedback delivered since " + since + ", newest first");
        root.put("count", delivered.size());
        root.put(
                "completeness",
                "PARTIAL: the most recent " + MAX_FEEDBACK
                        + " delivered items within the window. Feedback absent here may still have been delivered.");
        StagedArtifactNames.Resolved names = artifactNames.resolve(
                workspaceId,
                delivered.stream()
                        .map(f -> new StagedArtifactNames.Reference(f.getArtifactKind(), f.getArtifactId()))
                        .toList());
        ArrayNode items = root.putArray("feedback");
        for (Feedback f : delivered) {
            ObjectNode node = items.addObject();
            node.put("channel", f.getChannel() == null ? null : f.getChannel().name());
            names.stageInto(node, f.getArtifactKind(), f.getArtifactId());
            node.put(
                    "deliveredAt",
                    f.getDeliveredAt() == null ? null : f.getDeliveredAt().toString());
            node.put("body", DeveloperTextSanitizer.sanitize(f.getBody()));
        }
        return root;
    }

    /**
     * The person the review is about: the author for work with one, the explicitly carried subject
     * otherwise — mirrors the resolution delivery performs, so history can't be staged for one person
     * while observations are filed against another.
     */
    private @Nullable Long resolveSubject(ContextRequest request, AgentJob job) {
        return switch (request) {
            case ContextRequest.PracticeReviewRequest ignored -> authorOfPullRequest(job);
            case ContextRequest.IssueReviewRequest ignored -> authorOfIssue(job);
            case ContextRequest.DocumentReviewRequest ignored -> metadataSubject(job);
            case ContextRequest.ConversationReviewRequest ignored -> metadataSubject(job);
            case ContextRequest.MentorChatRequest ignored -> null;
        };
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
            return objectMapper.writeValueAsBytes(payload);
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
