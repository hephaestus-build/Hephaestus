package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded authorized history; detail always rechecks the same developer scope. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ResultHistoryContentSource implements ContentSource {
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "results_history.json";
    /**
     * One item path, which a {@code read} of the turn's context folder stages on demand: one canonical lowercase id.
     */
    private static final Pattern DETAIL_KEY = Pattern.compile(
            "inputs/context/results_history/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json");

    static final int OVERVIEW_MAX_CHARS = 48_000;
    static final int DETAIL_MAX_CHARS = 32_000;
    private static final int LIMIT = 90;
    private final AssessmentPort assessments;
    private final ObjectMapper objectMapper;
    private final HistoryCopies copies;
    private final ConversationConsentGate consentGate;

    @Override
    public boolean supports(ConversationContextRequest request) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ConversationContextRequest request, Map<String, byte[]> files) {
        files.put(
                OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(request.workspaceId(), request.developerId())));
    }

    /** One listed result's detail, read now for the developer; {@code NOT_FOUND} when it is not theirs to read. */
    @Override
    @Transactional(readOnly = true)
    public Optional<JsonNode> read(long workspaceId, long accountId, String path) {
        return resultIdOf(path).map(id -> inspect(workspaceId, accountId, id));
    }

    public static String resourceOf(UUID resultId) {
        return OUTPUT_PREFIX + "results_history/" + resultId + ".json";
    }

    public static Optional<UUID> resultIdOf(String key) {
        var match = DETAIL_KEY.matcher(key);
        if (!match.matches()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(match.group(1)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        Instant now = Instant.now();
        var selected = assessments.observations(workspaceId, developerId, now.minus(90, ChronoUnit.DAYS), LIMIT);
        var root = objectMapper.createObjectNode().put("readAt", now.toString());
        root.put(
                "about",
                "This is a bounded history. When coverage.omittedForSource is above zero, the review recorded "
                        + "observations that the conversation cannot show.");
        root.putObject("coverage")
                .put("scope", "AUTHORIZED_RESULTS_ABOUT_DEVELOPER")
                .put("lookbackDays", 90)
                .put("maxEntries", LIMIT)
                .put("hasMore", selected.hasMore())
                .put("omittedForSource", selected.withheld());
        consentGate.writeUntrustedEnvelope(root);
        var rows = root.putArray("results");
        int omittedForSize = 0;
        for (var entry : selected.entries()) {
            copies.record(entry.custody());
            var row = describe(entry, false);
            rows.add(row);
            if (objectMapper.writeValueAsString(root).length() > OVERVIEW_MAX_CHARS) {
                rows.remove(rows.size() - 1);
                omittedForSize++;
            }
        }
        root.put("omittedForSize", omittedForSize);
        return root;
    }

    ObjectNode inspect(long workspaceId, long developerId, UUID resultId) {
        var root = objectMapper.createObjectNode().put("readAt", Instant.now().toString());
        var selected = assessments.observation(workspaceId, developerId, resultId);
        if (selected.isEmpty()) return root.put("status", "NOT_FOUND");
        var entry = selected.get();
        copies.record(entry.custody());
        consentGate.writeUntrustedEnvelope(root);
        root.put("status", "FOUND");
        var row = describe(entry, true);
        root.set("result", row);
        if (objectMapper.writeValueAsString(root).length() > DETAIL_MAX_CHARS) {
            row.remove(List.of("evidence", "example", "explanation", "criteria", "problems", "openQuestions"));
            row.put("detailLoaded", false);
            root.put("status", "INCOMPLETE");
        }
        return root;
    }

    /** The reviewed work as its source names it now, under one key in every list; absent when it no longer can. */
    static void putWork(ObjectNode row, @Nullable ItemLabel work) {
        if (work == null) return;
        putWork(row, work.title(), work.number(), work.url());
    }

    private static void putWork(ObjectNode row, String title, @Nullable Integer number, @Nullable String url) {
        var named = row.putObject("work").put("title", title);
        if (number != null) named.put("number", number);
        if (url != null) named.put("url", url);
    }

    private ObjectNode describe(AssessmentPort.Observation entry, boolean detail) {
        var row = objectMapper
                .createObjectNode()
                .put("resultId", entry.resultId().toString())
                .put("reviewId", entry.reviewId().toString())
                .put("practiceSlug", entry.practiceSlug())
                .put("practiceName", entry.practiceName())
                .put("practiceVersion", entry.practiceVersion())
                .put("itemId", Long.toString(entry.itemId()))
                .put("itemKind", entry.itemKind())
                .put("itemVersion", entry.itemVersion())
                .put("outcome", entry.outcome().name())
                .put("recordedAt", entry.recordedAt().toString())
                .put("resource", resourceOf(entry.resultId()));
        Instant endedAt = entry.endedAt();
        if (endedAt != null) row.put("endedAt", endedAt.toString());
        ResultEndReason endReason = entry.endReason();
        if (endReason != null) row.put("endReason", endReason.name());
        var work = entry.work();
        if (work != null) putWork(row, work.title(), work.number(), work.url());
        row.set("activeMarks", objectMapper.valueToTree(entry.activeMarks()));
        Instant capturedAt = entry.capturedAt();
        if (capturedAt != null) row.put("capturedAt", capturedAt.toString());
        row.put("storedWorkVersion", entry.storedWorkVersion().name());
        // The summary and each problem's summary are what the practice page lists for this result.
        row.put("summary", entry.summary());
        row.set("problems", objectMapper.valueToTree(entry.problems()));
        if (detail) {
            row.put("explanation", entry.explanation());
            row.set("criteria", objectMapper.valueToTree(entry.criteria()));
            row.set("evidence", objectMapper.valueToTree(entry.evidence()));
            row.set("example", objectMapper.valueToTree(entry.example()));
            row.set("coverage", objectMapper.valueToTree(entry.coverage()));
            row.set("openQuestions", objectMapper.valueToTree(entry.openQuestions()));
        }
        return row;
    }
}
