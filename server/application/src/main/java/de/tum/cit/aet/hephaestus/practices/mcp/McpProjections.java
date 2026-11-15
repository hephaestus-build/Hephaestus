package de.tum.cit.aet.hephaestus.practices.mcp;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.dto.FeedbackModeAssignmentDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPracticeDTO;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Projections shared by the practice tools. They carry only the fields of ADR 0110 § Data contract: no
 * text that a developer or a provider wrote, and codes only where the stored value is code-shaped.
 */
final class McpProjections {

    /** A stored reason that is not a code goes out as UNKNOWN, never as text. */
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private McpProjections() {}

    record FeedbackMode(@Nullable String override, String effective, String source) {
        static FeedbackMode of(FeedbackModeAssignmentDTO mode) {
            return new FeedbackMode(
                    mode.override() == null ? null : mode.override().name(), mode.effective().name(), mode.source().name());
        }
    }

    /**
     * A reference to work by its identifiers. The label goes out only for source control items, whose label
     * is built from the repository and the number; an Outline document's label is its title, and a Slack
     * thread's container is a channel name, so neither goes out.
     */
    record ItemReference(String id, String kind, @Nullable String provider, @Nullable String label) {
        static ItemReference of(ItemRefDTO item) {
            boolean sourceControl = item.kind().startsWith("scm.");
            return new ItemReference(
                    item.id(),
                    item.kind(),
                    item.provider() == null ? null : item.provider().name(),
                    sourceControl ? item.label() : null);
        }
    }

    record PracticeStatus(String slug, String status, @Nullable String reasonCode) {
        static PracticeStatus of(ReviewPracticeDTO practice) {
            return new PracticeStatus(practice.practiceSlug(), practice.status().name(), code(practice.reason()));
        }
    }

    static @Nullable String code(@Nullable String stored) {
        if (stored == null) {
            return null;
        }
        return CODE.matcher(stored).matches() ? stored : "UNKNOWN";
    }

    static @Nullable String time(@Nullable Instant instant) {
        return instant == null ? null : instant.toString();
    }

    static List<PracticeStatus> practices(List<ReviewPracticeDTO> practices) {
        return practices.stream().map(PracticeStatus::of).toList();
    }

    /** The JSON Schema fragments that the projections above produce. */
    static final String FEEDBACK_MODE_SCHEMA = """
            {"type": "object", "required": ["override", "effective", "source"], "additionalProperties": false,
             "properties": {"override": {"type": ["string", "null"]}, "effective": {"type": "string"}, "source": {"type": "string"}}}
            """;

    static final String ITEM_REFERENCE_SCHEMA = """
            {"type": "object", "required": ["id", "kind", "provider", "label"], "additionalProperties": false,
             "properties": {"id": {"type": "string"}, "kind": {"type": "string"}, "provider": {"type": ["string", "null"]},
                            "label": {"type": ["string", "null"], "description": "owner/name#number for source control items; absent otherwise"}}}
            """;

    static final String PRACTICE_STATUS_SCHEMA = """
            {"type": "object", "required": ["slug", "status", "reasonCode"], "additionalProperties": false,
             "properties": {"slug": {"type": "string"}, "status": {"type": "string"},
                            "reasonCode": {"type": ["string", "null"], "description": "A stop, withheld or practice readiness reason code (ADR 0069 sense)"}}}
            """;
}
