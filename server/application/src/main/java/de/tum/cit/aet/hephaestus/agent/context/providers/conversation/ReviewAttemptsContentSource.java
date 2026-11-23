package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptHistory;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextPaths;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Retained executions about this developer, including failed captures; execution is separate from results. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ReviewAttemptsContentSource implements ContentSource {
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "review_attempts.json";

    /** One item path, which a {@code read} of the turn's context folder stages on demand. */
    private static final Pattern ITEM_KEY =
            Pattern.compile("inputs/context/review_attempts/(pull_request|issue)/([1-9][0-9]{0,18})\\.json");

    static final int LOOKBACK_DAYS = 90;
    static final int MAX_ATTEMPTS = 20;
    private final ReviewAttemptHistory jobs;
    private final ReviewAssignmentQuery assignments;
    private final AssessmentPort assessments;
    private final List<ItemSourceAccess> sources;
    private final RegisteredItemLabels labels;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ConversationContextRequest request) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
        files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(overview(request.workspaceId(), request.developerId())));
    }

    /** One pull request's or issue's retained reviews, read now; {@code NOT_FOUND} when none is the developer's. */
    @Override
    @Transactional(readOnly = true)
    public Optional<JsonNode> read(long workspaceId, long accountId, String path) {
        return selectionOf(path).map(selection -> render(workspaceId, accountId, Optional.of(selection)));
    }

    public record ItemSelection(ItemKind kind, long sourceId) {}

    public static String resourceOf(ItemKind kind, long sourceId) {
        return segmentOf(kind)
                .map(segment -> ConversationContextPaths.reviewAttempts(segment, sourceId))
                .orElseThrow(() -> new IllegalArgumentException("This item kind has no SCM review resource"));
    }

    public static Optional<ItemSelection> selectionOf(String key) {
        var match = ITEM_KEY.matcher(key);
        if (!match.matches()) return Optional.empty();
        try {
            return Optional.of(new ItemSelection(
                    new ItemKind(match.group(1).equals("issue") ? "scm.issue" : "scm.pull_request"),
                    Long.parseLong(match.group(2))));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<String> segmentOf(ItemKind kind) {
        return switch (kind.value()) {
            case "scm.pull_request" -> Optional.of("pull_request");
            case "scm.issue" -> Optional.of("issue");
            default -> Optional.empty();
        };
    }

    ObjectNode overview(long workspaceId, long developerId) {
        return render(workspaceId, developerId, Optional.empty());
    }

    private ObjectNode render(long workspaceId, long developerId, Optional<ItemSelection> selection) {
        var now = Instant.now();
        var root = objectMapper.createObjectNode().put("readAt", now.toString());
        root.put(
                "about",
                "Runs of Hephaestus's automated practice review on the developer's own work, with their results in "
                        + "results_history.json. They are not reviews by people: the reviews the developer gave are "
                        + "in activity.json, and the latest review of each reviewer on their pull request is in its "
                        + "merge_readiness detail. COULD_NOT_CHECK means capture failed before the review measured a practice.");
        var coverage = root.putObject("coverage")
                .put("scope", "RETAINED_REVIEW_JOBS_ABOUT_DEVELOPER")
                .put("lookbackDays", LOOKBACK_DAYS)
                .put("maxEntries", MAX_ATTEMPTS);
        var candidates = jobs.recent(workspaceId, now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS), PageRequest.of(0, 201));
        var read = candidates.stream().limit(200).toList();
        // Every run's assignments in one read; each work item's conversation permission once, however many runs it had.
        var retainedByJob = assignments.retained(
                workspaceId,
                read.stream()
                        .map(job -> new ReviewAssignmentQuery.RetainedRun(
                                job.reviewId(), job.id(), job.input().assignments()))
                        .toList());
        Map<ItemRef, Boolean> permitted = new HashMap<>();
        coverage.put("listingComplete", candidates.size() <= 200);
        var rows = root.putArray("attempts");
        List<Listed> listed = new ArrayList<>();
        // A run is attributed by the developer its review wrote on each assignment, not by what it can still execute: a
        // retired practice keeps its history. A run whose assignments cannot all be read is left out, and only this
        // flag
        // says so, so another developer's runs are not counted here.
        boolean attributionComplete = true;
        for (var job : read) {
            var asked = job.input().assignments();
            var retained = retainedByJob.getOrDefault(job.id(), List.of());
            if (retained.isEmpty() || (!asked.isEmpty() && retained.size() != asked.size())) {
                attributionComplete = false;
                continue;
            }
            var pinned = retained.stream()
                    .filter(entry -> Long.valueOf(developerId).equals(entry.developerId()))
                    .filter(entry -> selection.isEmpty()
                            || (entry.item().kind().equals(selection.get().kind())
                                    && Long.valueOf(selection.get().sourceId())
                                            .equals(entry.item().sourceId())))
                    .filter(entry -> permitted.computeIfAbsent(entry.item(), this::permitsConversation))
                    .findFirst();
            if (pinned.isEmpty()) continue;
            if (rows.size() == MAX_ATTEMPTS) {
                coverage.put("hasMore", true);
                break;
            }
            var item = pinned.get().item();
            var row = rows.addObject()
                    .put("reviewId", job.reviewId().toString())
                    .put("jobId", job.id().toString())
                    .put("status", job.status().name())
                    .put("attempt", job.attempt())
                    .put("processingStatus", job.processingStatus().name())
                    .put("itemId", Long.toString(item.itemId()))
                    .put("itemKind", item.kind().value())
                    .put("availableAt", job.availableAt().toString());
            Long sourceId = item.sourceId();
            Optional<String> segment = segmentOf(item.kind());
            if (sourceId != null && segment.isPresent()) {
                row.put("reviewsResource", ConversationContextPaths.reviewAttempts(segment.get(), sourceId));
            }
            if (job.startedAt() != null) row.put("startedAt", job.startedAt().toString());
            if (job.completedAt() != null)
                row.put("completedAt", job.completedAt().toString());
            listed.add(new Listed(row, item, new AssessmentPort.ReviewedItem(job.reviewId(), item.itemId())));
        }
        coverage.put("attributionComplete", attributionComplete);
        if (!attributionComplete)
            coverage.put(
                    "attributionNote",
                    "Some retained runs in this workspace no longer show whose work they reviewed, so they are left out;"
                            + " this list may be missing runs about the developer.");
        Map<Long, ItemLabel> named = listed.isEmpty()
                ? Map.of()
                : labels.labels(workspaceId, listed.stream().map(Listed::item).toList());
        listed.forEach(entry -> ResultHistoryContentSource.putWork(
                entry.row(), named.get(entry.item().itemId())));
        // Asked only for reviewed work the source still permits, once per review and item however many runs it had.
        Map<AssessmentPort.ReviewedItem, AssessmentPort.ReviewCheck> recorded = listed.isEmpty()
                ? Map.of()
                : assessments.reviewChecks(
                        workspaceId,
                        developerId,
                        listed.stream().map(Listed::reviewed).collect(Collectors.toSet()));
        listed.forEach(entry -> state(entry.row(), recorded.get(entry.reviewed())));
        // Absence is claimed only from a complete read: an unattributed run or a cut listing could be this item's.
        if (selection.isPresent() && rows.isEmpty())
            root.put("status", attributionComplete && candidates.size() <= 200 ? "NOT_FOUND" : "UNAVAILABLE");
        return root;
    }

    private boolean permitsConversation(ItemRef item) {
        return sources.stream()
                .filter(source -> source.kinds().contains(item.kind()))
                .anyMatch(source -> source.permits(item, SourceUsePurpose.CONVERSATIONAL_MENTORING));
    }

    private record Listed(ObjectNode row, ItemRef item, AssessmentPort.ReviewedItem reviewed) {}

    /**
     * The review's recorded state and assignments for the developer on this work, beside the run's own state. Left
     * out when the review cannot be read; the counts alone are left out when they cannot be complete.
     */
    private static void state(ObjectNode row, AssessmentPort.@Nullable ReviewCheck check) {
        if (check == null) return;
        var checkStatus = check.checkStatus();
        if (checkStatus != null) row.put("checkStatus", checkStatus.name());
        row.put("reviewStatus", check.reviewStatus());
        var stopReason = check.stopReason();
        if (stopReason != null) row.put("reviewStopReason", stopReason);
        var counts = check.counts();
        if (counts == null) return;
        row.putObject("assignmentCounts")
                .put("scope", "REVIEWED_ITEM")
                .put("total", counts.total())
                .put("judged", counts.judged())
                .put("reused", counts.reused())
                .put("withheld", counts.withheld())
                .put("pending", counts.pending());
    }
}
