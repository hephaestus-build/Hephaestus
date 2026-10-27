package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class DeliveredFeedbackContentSource implements ContentSource {
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "delivered_feedback.json";

    /** One delivered piece in detail, which a {@code read} of the turn's context folder stages on demand. */
    private static final Pattern DETAIL_KEY = Pattern.compile(
            "inputs/context/delivered_feedback/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json");

    /** The longest delivered words that the list shows for one piece; the rest are in its detail. */
    static final int LISTED_BODY_CHARS = 600;

    /** The longest delivered words of one piece in detail; a longer one is cut and marked. */
    static final int MAX_BODY_CHARS = 2_000;

    private final FeedbackHistoryQuery feedback;
    private final HistoryCopies copies;
    private final ConversationConsentGate consentGate;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        files.put(
                OUTPUT_KEY, objectMapper.writeValueAsBytes(buildPayload(request.workspaceId(), request.developerId())));
    }

    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        Instant now = Instant.now();
        var root = objectMapper.createObjectNode().put("readAt", now.toString());
        var selected = feedback.forDeveloper(workspaceId, developerId, now.minus(90, ChronoUnit.DAYS), 31);
        root.putObject("coverage").put("lookbackDays", 90).put("maxEntries", 30).put("hasMore", selected.size() > 30);
        consentGate.writeUntrustedEnvelope(root);
        var states = root.putArray("feedbackStates");
        var delivered = root.putArray("deliveredFeedback");
        for (var entry : selected.stream().limit(30).toList()) {
            // The practices and results behind the piece; each result's detail names its review and problems.
            var state = states.addObject()
                    .put("feedbackId", entry.feedbackId().toString())
                    .put("state", entry.state().name())
                    .put("destination", entry.destination().name())
                    .put("createdAt", entry.createdAt().toString());
            var practices = state.putArray("practiceSlugs");
            entry.practiceSlugs().forEach(practices::add);
            var results = state.putArray("resultIds");
            entry.resultIds().forEach(id -> results.add(id.toString()));
            if (!readable(entry)) continue;
            // Joined to its state entry by feedbackId; the words are cut here and whole in the detail.
            var row = delivered
                    .addObject()
                    .put("feedbackId", entry.feedbackId().toString())
                    .put(
                            "deliveredAt",
                            Objects.requireNonNull(entry.deliveredAt()).toString())
                    .put("destination", entry.destination().name());
            putBody(row, Objects.requireNonNull(entry.body()), LISTED_BODY_CHARS);
            if (row.path("bodyTruncated").asBoolean(false)) row.put("resource", resourceOf(entry.feedbackId()));
            response(row, entry);
        }
        return root;
    }

    /** One delivered piece in detail, read now for the developer; {@code NOT_FOUND} when it is not theirs to read. */
    @Override
    @Transactional(readOnly = true)
    public Optional<JsonNode> read(long workspaceId, long accountId, String path) {
        return feedbackIdOf(path).map(id -> {
            var root =
                    objectMapper.createObjectNode().put("readAt", Instant.now().toString());
            consentGate.writeUntrustedEnvelope(root);
            var found = feedback.find(workspaceId, id, accountId).filter(this::readable);
            if (found.isEmpty()) return root.put("status", "NOT_FOUND");
            var entry = found.get();
            root.put("feedbackId", entry.feedbackId().toString())
                    .put(
                            "deliveredAt",
                            Objects.requireNonNull(entry.deliveredAt()).toString())
                    .put("destination", entry.destination().name())
                    .put("reviewId", entry.reviewId().toString());
            putBody(root, Objects.requireNonNull(entry.body()), MAX_BODY_CHARS);
            var practices = root.putArray("practiceSlugs");
            entry.practiceSlugs().forEach(practices::add);
            var results = root.putArray("resultIds");
            entry.resultIds().forEach(result -> results.add(result.toString()));
            var problems = root.putArray("problemIds");
            entry.problemIds().forEach(problem -> problems.add(problem.toString()));
            response(root, entry);
            return root;
        });
    }

    public static String resourceOf(UUID feedbackId) {
        return OUTPUT_PREFIX + "delivered_feedback/" + feedbackId + ".json";
    }

    static Optional<UUID> feedbackIdOf(String key) {
        var match = DETAIL_KEY.matcher(key);
        if (!match.matches()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(match.group(1)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** Whether the piece's delivered words may be shown here, which also records their copy. */
    private boolean readable(FeedbackHistoryEntry entry) {
        return entry.deliveredAt() != null && entry.body() != null && copies.record(copies.of(entry));
    }

    private static void response(ObjectNode row, FeedbackHistoryEntry entry) {
        var resolution = entry.resolution();
        if (resolution != null) row.putObject("developerResponse").put("resolution", resolution.name());
    }

    /** Cuts the words at {@code max} without splitting a surrogate pair, and marks a cut. */
    private static void putBody(ObjectNode row, String body, int max) {
        if (body.length() <= max) {
            row.put("body", body);
            return;
        }
        int end = Character.isHighSurrogate(body.charAt(max - 1)) ? max - 1 : max;
        row.put("body", body.substring(0, end));
        row.put("bodyTruncated", true);
        row.put("bodyChars", body.length());
    }
}
