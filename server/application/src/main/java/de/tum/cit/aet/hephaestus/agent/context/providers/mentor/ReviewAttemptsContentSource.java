package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextPaths;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewShownStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class ReviewAttemptsContentSource implements ContentSource {
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "review_attempts.json";

    /** One item path, which a {@code read} of the turn's context folder stages on demand. */
    private static final Pattern ITEM_KEY =
            Pattern.compile("inputs/context/review_attempts/(pull_request|issue)/([1-9][0-9]{0,18})\\.json");

    static final int LOOKBACK_DAYS = 90;
    static final int MAX_ATTEMPTS = 20;
    private final ReviewJobRepository jobs;
    private final ReviewAssignmentQuery assignments;
    private final ReviewRuntimeQuery runtime;
    private final List<ItemSourceAccess> sources;
    private final RegisteredItemLabels labels;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
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
                .map(segment -> MentorContextPaths.reviewAttempts(segment, sourceId))
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
                        + "merge_readiness detail.");
        var coverage = root.putObject("coverage")
                .put("scope", "RETAINED_REVIEW_JOBS_ABOUT_DEVELOPER")
                .put("lookbackDays", LOOKBACK_DAYS)
                .put("maxEntries", MAX_ATTEMPTS);
        var candidates = jobs.findAttemptsByWorkspaceIdAndAvailableAtGreaterThanEqualOrderByAvailableAtDesc(
                workspaceId, now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS), PageRequest.of(0, 201));
        var read = candidates.stream().limit(200).toList();
        // Every run's assignments in one read; each work item's mentoring permission once, however many runs it had.
        var retainedByJob = assignments.retained(
                workspaceId,
                read.stream()
                        .map(job -> new ReviewAssignmentQuery.RetainedRun(
                                job.getReviewId(), job.getId(), job.getInput().assignments()))
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
            var asked = job.getInput().assignments();
            var retained = retainedByJob.getOrDefault(job.getId(), List.of());
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
                    .filter(entry -> permitted.computeIfAbsent(entry.item(), this::permitsMentoring))
                    .findFirst();
            if (pinned.isEmpty()) continue;
            if (rows.size() == MAX_ATTEMPTS) {
                coverage.put("hasMore", true);
                break;
            }
            var item = pinned.get().item();
            var row = rows.addObject()
                    .put("reviewId", job.getReviewId().toString())
                    .put("jobId", job.getId().toString())
                    .put("status", job.getStatus().name())
                    .put("attempt", job.getAttempt())
                    .put("processingStatus", job.getProcessingStatus().name())
                    .put("itemId", Long.toString(item.itemId()))
                    .put("itemKind", item.kind().value())
                    .put("availableAt", job.getAvailableAt().toString());
            Long sourceId = item.sourceId();
            Optional<String> segment = segmentOf(item.kind());
            if (sourceId != null && segment.isPresent()) {
                row.put("reviewsResource", MentorContextPaths.reviewAttempts(segment.get(), sourceId));
            }
            if (job.getStartedAt() != null)
                row.put("startedAt", job.getStartedAt().toString());
            if (job.getCompletedAt() != null)
                row.put("completedAt", job.getCompletedAt().toString());
            listed.add(new Listed(row, item, new ReviewAssignmentQuery.ReviewedItem(job.getReviewId(), item.itemId())));
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
        listed.forEach(entry -> name(entry.row(), named.get(entry.item().itemId())));
        // Asked only for reviewed work the source still permits, once per review and item however many runs it had.
        Map<ReviewAssignmentQuery.ReviewedItem, ReviewAssignmentQuery.Recorded> recorded = listed.isEmpty()
                ? Map.of()
                : assignments.recorded(
                        workspaceId,
                        developerId,
                        listed.stream().map(Listed::reviewed).collect(Collectors.toSet()));
        // The review's status as every screen shows it: a started review whose job waits reads Queued.
        Map<UUID, Map<ReviewJobStatus, Long>> jobCounts = recorded.isEmpty()
                ? Map.of()
                : runtime.jobCounts(
                        workspaceId,
                        listed.stream()
                                .map(entry -> entry.reviewed().reviewId())
                                .collect(Collectors.toSet()));
        listed.forEach(entry -> state(
                entry.row(),
                recorded.get(entry.reviewed()),
                jobCounts.getOrDefault(entry.reviewed().reviewId(), Map.of())));
        // Absence is claimed only from a complete read: an unattributed run or a cut listing could be this item's.
        if (selection.isPresent() && rows.isEmpty())
            root.put("status", attributionComplete && candidates.size() <= 200 ? "NOT_FOUND" : "UNAVAILABLE");
        return root;
    }

    private boolean permitsMentoring(ItemRef item) {
        return sources.stream()
                .filter(source -> source.kinds().contains(item.kind()))
                .anyMatch(source -> source.permits(item, SourceUsePurpose.CONVERSATIONAL_MENTORING));
    }

    private record Listed(ObjectNode row, ItemRef item, ReviewAssignmentQuery.ReviewedItem reviewed) {}

    /**
     * The review's recorded state and assignments for the developer on this work, beside the run's own state. Left
     * out when the review cannot be read; the counts alone are left out when they cannot be complete.
     */
    private static void state(
            ObjectNode row, ReviewAssignmentQuery.@Nullable Recorded recorded, Map<ReviewJobStatus, Long> jobs) {
        if (recorded == null) return;
        row.put("reviewStatus", ReviewShownStatus.of(recorded.status(), jobs).name());
        var stopReason = recorded.stopReason();
        if (stopReason != null) row.put("reviewStopReason", stopReason.name());
        var counts = recorded.counts();
        if (counts == null) return;
        row.putObject("assignmentCounts")
                .put("scope", "REVIEWED_ITEM")
                .put("total", counts.total())
                .put("judged", counts.judged())
                .put("reused", counts.reused())
                .put("withheld", counts.withheld())
                .put("pending", counts.pending());
    }

    /** The reviewed work's number, title and link as its source names it; absent when the source no longer can. */
    private static void name(ObjectNode row, @Nullable ItemLabel label) {
        if (label == null) return;
        if (label.number() != null) row.put("number", label.number());
        row.put("title", label.title());
        if (label.url() != null) row.put("url", label.url());
    }
}
