package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticeQuestion;
import de.tum.cit.aet.hephaestus.practices.PracticeRule;
import de.tum.cit.aet.hephaestus.practices.feedback.DeveloperTextSanitizer;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises {@code inputs/context/observations_history.json}: a bounded recent sample of what reviews recorded
 * about the developer, never their whole history. {@code recentObservations} and {@code abstentions} are the verdicts
 * and the {@code NOT_APPLICABLE}/{@code UNDETERMINED} results of each claim's latest run; {@code earlierObservations}
 * are earlier runs of a claim whose latest run is listed. Every row passes the same visibility and consent checks,
 * and an earlier run is listed only beside a latest run this conversation may see, so none hints at one it may not.
 *
 * <p>The list is an overview: a row's evidence and rationale are read on demand from the {@code resource} the row
 * carries, which selects the history afresh and answers only for an observation it lists.
 */
@Component
@RequiredArgsConstructor
public class ObservationHistoryContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "observations_history.json";

    private static final Pattern DETAIL_KEY = Pattern.compile(
            "inputs/context/observations_history/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json");

    private static final int LOOKBACK_DAYS = 90;
    private static final int MAX_RECENT_OBSERVATIONS = 50;
    private static final int MAX_ABSTENTIONS = 20;
    private static final int MAX_EARLIER_OBSERVATIONS = 20;
    private static final int MAX_RECENT_REVIEWS = 20;

    /**
     * Serialized-size bounds of the overview and of one detail. They keep a model request small; they are not token
     * counts, and what a bound leaves out is always named in the document.
     */
    static final int OVERVIEW_MAX_CHARS = 48_000;

    static final int DETAIL_MAX_CHARS = 32_000;

    /** A shortened text keeps at least this much of its start, so it is never cut down to nothing. */
    private static final int MIN_TEXT_PREFIX = 200;

    /** The free-text fields a detail may shorten; source locations and identities are never shortened. */
    private static final Set<String> PROSE_FIELDS = Set.of(
            "summary",
            "evidenceRationale",
            "quote",
            "lookedFor",
            "boundary",
            "subject",
            "ruledOutBy",
            "openQuestion",
            "wouldSettleIt");

    private final UserRepository userRepository;
    private final ObservationRepository observationRepository;
    private final MentorContextQueryRepository queryRepository;
    private final ConversationConsentGate conversationConsentGate;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final ReviewedWorkCoverage reviewedWorkCoverage;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        ObjectNode payload = buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize observation history context", e);
        }
    }

    /** The on-demand key of one observation's detail, published as the {@code resource} of its row. */
    public static String resourceOf(UUID observationId) {
        return OUTPUT_PREFIX + "observations_history/" + observationId + ".json";
    }

    /** The observation id an on-demand key names, if {@code key} is one. */
    public static Optional<UUID> observationIdOf(String key) {
        Matcher matcher = DETAIL_KEY.matcher(key);
        return matcher.matches() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
    }

    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));
        Selection selection = select(workspaceId, developerId);
        List<PullRequestReview> reviews = queryRepository.findReviewsReceivedSince(
                workspaceId, developerId, selection.since(), PageRequest.of(0, MAX_RECENT_REVIEWS));
        Map<UUID, ObjectNode> reviewedWork =
                reviewedWorkCoverage.of(workspaceId, selection.all().toList());
        return fittedOverview(user, selection, reviews, reviewedWork);
    }

    /**
     * One observation the history lists, with its evidence and rationale. Anything the history would not list — another
     * workspace's or developer's, hidden, no longer authorized, outside consent, or an earlier run without its listed
     * latest one — gets the same {@code NOT_FOUND}, so the answer never tells those apart.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode inspect(long workspaceId, long developerId, UUID observationId) {
        Selection selection = select(workspaceId, developerId);
        ObjectNode root = objectMapper.createObjectNode();
        Optional<Map.Entry<String, Observation>> listed = selection.lists().stream()
                .flatMap(list -> list.rows().stream().map(o -> Map.entry(list.name(), o)))
                .filter(entry -> entry.getValue().getId().equals(observationId))
                .findFirst();
        if (listed.isEmpty()) {
            root.put("readAt", selection.preparedAt().toString());
            root.put("status", "NOT_FOUND");
            root.put(
                    "reason",
                    "No observation listed in observations_history.json has that id. Copy the resource of a listed "
                            + "row.");
            return root;
        }
        Observation observation = listed.get().getValue();
        if (ArtifactKinds.CONVERSATION_THREAD.equals(observation.getArtifactKind())) {
            conversationConsentGate.writeUntrustedEnvelope(root);
        }
        root.put("readAt", selection.preparedAt().toString());
        root.put("list", listed.get().getKey());
        Map<UUID, ObjectNode> reviewedWork = reviewedWorkCoverage.of(workspaceId, List.of(observation));
        ObjectNode row = root.putObject("observation");
        describe(row, observation, reviewedWork);
        describeEvidence(row, observation);
        return fittedDetail(root, row);
    }

    /** The history's rows, selected by the one set of rules both the overview and a detail read apply. */
    private Selection select(Long workspaceId, Long developerId) {
        Instant preparedAt = Instant.now();
        Instant since = preparedAt.minus(LOOKBACK_DAYS, ChronoUnit.DAYS);

        List<Observation> recent = new ArrayList<>(usable(
                workspaceId,
                observationRepository.findRecentByDeveloperAndWorkspace(
                        developerId,
                        workspaceId,
                        since,
                        List.of(Outcome.MET.name(), Outcome.NOT_MET.name()),
                        PageRequest.of(0, MAX_RECENT_OBSERVATIONS))));
        List<Observation> abstentions = new ArrayList<>(usable(
                workspaceId,
                observationRepository.findRecentByDeveloperAndWorkspace(
                        developerId,
                        workspaceId,
                        since,
                        List.of(Outcome.NOT_APPLICABLE.name(), Outcome.UNDETERMINED.name()),
                        PageRequest.of(0, MAX_ABSTENTIONS))));
        List<Observation> latest = new ArrayList<>(recent);
        latest.addAll(abstentions);
        List<Observation> earlier = new ArrayList<>(usable(
                        workspaceId,
                        observationRepository.findEarlierRunsByDeveloperAndWorkspace(
                                developerId, workspaceId, since, PageRequest.of(0, MAX_EARLIER_OBSERVATIONS)))
                .stream()
                .filter(candidate -> outrankedByListedRun(latest, candidate))
                .toList());
        return new Selection(preparedAt, since, recent, abstentions, earlier);
    }

    private record Selection(
            Instant preparedAt,
            Instant since,
            List<Observation> recent,
            List<Observation> abstentions,
            List<Observation> earlier) {

        /** The lists in the overview's order: verdicts first, earlier runs last. */
        List<Listed> lists() {
            return List.of(
                    new Listed("recentObservations", recent),
                    new Listed("abstentions", abstentions),
                    new Listed("earlierObservations", earlier));
        }

        Stream<Observation> all() {
            return Stream.of(recent, abstentions, earlier).flatMap(List::stream);
        }
    }

    /** One of the overview's lists, by its name there. */
    private record Listed(String name, List<Observation> rows) {}

    /** Whether a listed latest run of the same claim is newer, by the one rule that picks a claim's latest run. */
    private static boolean outrankedByListedRun(List<Observation> latest, Observation candidate) {
        List<Observation> claim = new ArrayList<>(latest);
        claim.add(candidate);
        return LatestRun.perClaim(claim).stream().noneMatch(o -> o.getId().equals(candidate.getId()));
    }

    /** The rows this conversation may use: evidence authorized for it, and a conversation still consented to. */
    private List<Observation> usable(Long workspaceId, List<Observation> rows) {
        Set<UUID> authorizedIds =
                visibilityPolicy.permitsAll(workspaceId, rows, SourceUsePurpose.CONVERSATIONAL_MENTORING);
        List<Observation> authorized =
                rows.stream().filter(o -> authorizedIds.contains(o.getId())).toList();
        Set<Long> activeThreadIds =
                conversationConsentGate.activeThreadIds(workspaceId, conversationThreadIds(authorized));
        return authorized.stream()
                .filter(o -> !ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind())
                        || isSurvivingConversation(o, activeThreadIds))
                .toList();
    }

    /**
     * Fits the overview by leaving out the longest summaries first, marked on their rows, then the oldest received
     * reviews, then whole rows — earlier runs before abstentions before verdicts — each counted in
     * {@code omittedForSize}. Every candidate is measured whole, counts included. A history that fits in no form
     * says so rather than pretending to be complete.
     */
    private ObjectNode fittedOverview(
            User user, Selection selection, List<PullRequestReview> reviews, Map<UUID, ObjectNode> reviewedWork) {
        List<Listed> lists = selection.lists().stream()
                .map(list -> new Listed(list.name(), new ArrayList<>(list.rows())))
                .toList();
        List<PullRequestReview> shownReviews = new ArrayList<>(reviews);
        Set<UUID> summariesLeftOut = new HashSet<>();
        for (; ; ) {
            ObjectNode root =
                    render(user, selection, lists, reviews.size(), shownReviews, reviewedWork, summariesLeftOut);
            if (objectMapper.writeValueAsString(root).length() <= OVERVIEW_MAX_CHARS) {
                return root;
            }
            Optional<Observation> longestSummary = lists.stream()
                    .flatMap(list -> list.rows().stream())
                    .filter(o -> o.getSummary() != null && !summariesLeftOut.contains(o.getId()))
                    .max(Comparator.comparingInt(o -> o.getSummary().length()));
            Optional<Listed> shrink = lists.reversed().stream()
                    .filter(list -> !list.rows().isEmpty())
                    .findFirst();
            if (longestSummary.isPresent()) {
                summariesLeftOut.add(longestSummary.get().getId());
            } else if (!shownReviews.isEmpty()) {
                shownReviews.removeLast();
            } else if (shrink.isPresent()) {
                shrink.get().rows().removeLast();
            } else {
                ObjectNode incomplete = objectMapper.createObjectNode();
                incomplete.put("status", "INCOMPLETE");
                incomplete.put("reason", "The review history is too large to list.");
                incomplete.set("coverage", objectMapper.valueToTree(Coverage.of(selection.preparedAt())));
                return incomplete;
            }
        }
    }

    private ObjectNode render(
            User user,
            Selection selection,
            List<Listed> lists,
            int selectedReviews,
            List<PullRequestReview> reviews,
            Map<UUID, ObjectNode> reviewedWork,
            Set<UUID> summariesLeftOut) {
        Instant preparedAt = selection.preparedAt();
        List<Observation> recent = lists.getFirst().rows();
        ObjectNode root = objectMapper.createObjectNode();
        // Untrusted-content quarantine: only when a Slack-derived (attacker-controllable) reasoning survives the gate
        // does this file carry the envelope — a PR/issue-only payload stays byte-identical (no _meta).
        if (lists.stream()
                .flatMap(list -> list.rows().stream())
                .anyMatch(o -> ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind()))) {
            conversationConsentGate.writeUntrustedEnvelope(root);
        }
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());
        root.set("coverage", objectMapper.valueToTree(Coverage.of(preparedAt)));
        root.putObject("detail")
                .put("loaded", false)
                .set(
                        "adds",
                        objectMapper
                                .createArrayNode()
                                .add("evidence")
                                .add("evidenceRationale")
                                .add("criteria"));

        ObjectNode summary = root.putObject("summary");
        summary.put("includedObservations", recent.size());

        ObjectNode outcomeSummary = summary.putObject("byOutcome");
        for (Outcome outcome : Outcome.values()) {
            outcomeSummary.put(outcome.name(), 0L);
        }
        for (Observation observation : recent) {
            String outcome = observation.getOutcome().name();
            outcomeSummary.put(outcome, outcomeSummary.path(outcome).asLong() + 1);
        }

        ObjectNode severityNode = summary.putObject("bySeverity");
        for (Severity s : Severity.values()) {
            severityNode.put(s.name(), 0L);
        }
        for (Observation observation : recent) {
            if (observation.getSeverity() != null) {
                String severity = observation.getSeverity().name();
                severityNode.put(severity, severityNode.path(severity).asLong() + 1);
            }
        }

        lists.forEach(list -> {
            ArrayNode into = root.putArray(list.name());
            for (Observation o : list.rows()) {
                ObjectNode row = into.addObject();
                describe(row, o, reviewedWork);
                if (summariesLeftOut.contains(o.getId())) {
                    row.putNull("summary");
                    row.put("summaryNotLoaded", true);
                }
            }
        });

        ArrayNode reviewsArr = root.putArray("reviewsReceived");
        for (PullRequestReview review : reviews) {
            ObjectNode node = reviewsArr.addObject();
            if (review.getPullRequest() != null) {
                node.put("prNumber", review.getPullRequest().getNumber());
                node.put("prTitle", review.getPullRequest().getTitle());
                node.put("url", review.getHtmlUrl());
            }
            if (review.getAuthor() != null) {
                node.put("reviewer", review.getAuthor().getLogin());
            }
            if (review.getState() != null) {
                node.put("state", review.getState().name());
            }
            node.put("hasComment", review.getBody() != null && !review.getBody().isBlank());
            Instant submittedAt = review.getSubmittedAt();
            if (submittedAt != null) {
                node.put("submittedAt", submittedAt.toString());
            }
        }

        ObjectNode omitted = objectMapper.createObjectNode();
        List<Listed> selected = selection.lists();
        for (int i = 0; i < lists.size(); i++) {
            int left = selected.get(i).rows().size() - lists.get(i).rows().size();
            if (left > 0) {
                omitted.put(lists.get(i).name(), left);
            }
        }
        if (reviews.size() < selectedReviews) {
            omitted.put("reviewsReceived", selectedReviews - reviews.size());
        }
        if (!omitted.isEmpty()) {
            root.set("omittedForSize", omitted);
        }
        return root;
    }

    private static void describe(ObjectNode node, Observation o, Map<UUID, ObjectNode> reviewedWork) {
        node.put("id", o.getId().toString());
        node.put("resource", resourceOf(o.getId()));
        node.put("reviewId", o.getAgentJobId().toString());
        node.put("origin", o.getOrigin().name());
        node.put("summary", o.getSummary());
        node.put("practiceSlug", o.getPractice().getSlug());

        node.put("outcome", o.getOutcome().name());
        Severity severity = o.getSeverity();
        node.put("severity", severity == null ? null : severity.name());
        node.put("observedAt", o.getObservedAt().toString());
        if (o.getArtifactKind() != null) {
            node.put("artifactKind", o.getArtifactKind().value());
        }
        if (o.getArtifactId() != null) {
            node.put("artifactId", o.getArtifactId());
        }
        node.set("reviewedWork", Objects.requireNonNull(reviewedWork.get(o.getId())));
    }

    private static void describeEvidence(ObjectNode node, Observation o) {
        var revision = o.getPracticeRevision();
        node.put("practiceRevisionId", revision == null ? null : revision.getId());
        if (revision == null) {
            node.put("criteriaNotLoaded", true);
        } else {
            node.put("criteria", revision.getCriteria());
        }
        if (o.getEvidence() != null && !o.getEvidence().isNull()) {
            node.set("evidence", o.getEvidence().deepCopy());
        }
        node.put("evidenceRationale", DeveloperTextSanitizer.sanitize(o.getEvidenceRationale()));
        describeAnswers(node, o, revision == null ? null : revision.getJudgment());
    }

    /**
     * The reviewer's answers to the revision's questions and the rule that decided from them: what a result is
     * interpreted by. Absent on a result recorded before reviews answered questions.
     */
    private static void describeAnswers(ObjectNode node, Observation o, @Nullable PracticeJudgment judgment) {
        List<ObservationAnswer> answers = o.getAnswers();
        if (answers == null || judgment == null) {
            return;
        }
        ArrayNode list = node.putArray("answers");
        for (ObservationAnswer answer : answers) {
            PracticeQuestion question = judgment.question(answer.question());
            ObjectNode entry = list.addObject();
            entry.put("question", answer.question());
            entry.put("title", question == null ? null : question.title());
            entry.put("asked", question == null ? null : question.question());
            entry.put("answer", answer.answer().name());
            entry.put("because", DeveloperTextSanitizer.sanitize(answer.because()));
        }
        PracticeRule rule = o.getRuleId() == null ? null : judgment.rule(o.getRuleId());
        node.put("decidedBy", rule == null ? null : rule.reason());
    }

    /**
     * Fits one detail by shortening its longest free text, halving it each time and marking it with
     * {@code <field>Truncated} and its full length, so a shortened quote or warrant never reads as complete. Source
     * locations stay whole. Evaluated criteria stay whole or are left out, marked, before evidence that cannot fit
     * is left out too.
     */
    private ObjectNode fittedDetail(ObjectNode root, ObjectNode row) {
        while (objectMapper.writeValueAsString(root).length() > DETAIL_MAX_CHARS) {
            Optional<ProseField> longest = proseFields(row).stream()
                    .filter(field -> field.length() > MIN_TEXT_PREFIX)
                    .max(Comparator.comparingInt(ProseField::length));
            if (row.has("criteria")
                    && (objectMapper.writeValueAsString(row.get("criteria")).length() > DETAIL_MAX_CHARS
                            || longest.isEmpty())) {
                row.remove("criteria");
                row.put("criteriaNotLoaded", true);
                continue;
            }
            if (longest.isEmpty()) {
                row.remove(List.of("evidence", "evidenceRationale"));
                row.put("evidenceLoaded", false);
                root.put("status", "INCOMPLETE");
                root.put(
                        "reason", "This observation's evidence is too large to load; its source locations are unseen.");
                return root;
            }
            longest.get().shorten();
        }
        return root;
    }

    private static List<ProseField> proseFields(JsonNode node) {
        List<ProseField> fields = new ArrayList<>();
        collectProse(node, fields);
        return fields;
    }

    private static void collectProse(JsonNode node, List<ProseField> into) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode value = object.get(name);
                if (PROSE_FIELDS.contains(name) && value.isString()) {
                    into.add(new ProseField(object, name));
                } else if (!"reviewedWork".equals(name)) {
                    collectProse(value, into);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(element -> collectProse(element, into));
        }
    }

    /** One free-text field in a detail, shortened in place to an exact prefix of itself. */
    private record ProseField(ObjectNode parent, String name) {

        int length() {
            return parent.get(name).asString().length();
        }

        void shorten() {
            String text = parent.get(name).asString();
            if (!parent.has(name + "Truncated")) {
                parent.put(name + "Truncated", true);
                parent.put(name + "Chars", text.length());
            }
            int keep = Math.max(MIN_TEXT_PREFIX, text.length() / 2);
            // Never split a surrogate pair: the prefix stays valid text.
            if (Character.isHighSurrogate(text.charAt(keep - 1))) {
                keep--;
            }
            parent.put(name, text.substring(0, keep));
        }
    }

    /**
     * The bounds of the three observation lists. Apart from {@code preparedAt} it is constant, so it reveals nothing
     * about rows outside the scope, including whether any exist.
     */
    record Coverage(
            Scope scope, Instant preparedAt, int lookbackDays, MaxEntries maxEntries, List<OutsideScope> outsideScope) {

        static Coverage of(Instant preparedAt) {
            return new Coverage(
                    Scope.CONVERSATION_AUTHORIZED_OBSERVATIONS_ABOUT_DEVELOPER,
                    preparedAt,
                    LOOKBACK_DAYS,
                    new MaxEntries(
                            MAX_RECENT_OBSERVATIONS, MAX_ABSTENTIONS, MAX_EARLIER_OBSERVATIONS, MAX_RECENT_REVIEWS),
                    List.of(OutsideScope.values()));
        }

        record MaxEntries(int recentObservations, int abstentions, int earlierObservations, int reviewsReceived) {}

        enum Scope {
            CONVERSATION_AUTHORIZED_OBSERVATIONS_ABOUT_DEVELOPER,
        }

        enum OutsideScope {
            INVALIDATED,
            MEASURED_UNDER_CHANGED_REVIEW_RULES,
            EVIDENCE_NOT_USABLE_IN_CONVERSATION,
            CONVERSATION_CONSENT_NOT_ACTIVE,
            HIDDEN_REPOSITORIES,
        }
    }

    private static List<Long> conversationThreadIds(List<Observation> observations) {
        List<Long> ids = new ArrayList<>();
        for (Observation o : observations) {
            if (ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind()) && o.getArtifactId() != null) {
                ids.add(o.getArtifactId());
            }
        }
        return ids;
    }

    private static boolean isSurvivingConversation(Observation o, Set<Long> activeThreadIds) {
        return (ArtifactKinds.CONVERSATION_THREAD.equals(o.getArtifactKind())
                && o.getArtifactId() != null
                && activeThreadIds.contains(o.getArtifactId()));
    }
}
