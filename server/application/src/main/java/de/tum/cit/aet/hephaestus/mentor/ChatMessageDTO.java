package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AI SDK UIMessage shape served to the webapp. Parts are the JSONB array (or the legacy
 * fallback reconstruction); metadata is the per-turn {status, model, costUsd, …} object.
 *
 * <p>Springdoc 2.x quirk: a {@code @Schema(type = "object")} override on a {@code Map} or
 * {@code List<Object>} field replaces the inferred type with the literal {@code type: string}
 * (verified against the generated openapi.yaml). The webapp's generated client then types
 * {@code metadata} as {@code string} and any consumer that reads {@code metadata.costUsd}
 * silently returns {@code undefined}. To keep the generator's natural inference (Map →
 * {@code type: object, additionalProperties: true}; {@code List<Object>} → {@code type: array})
 * we leave the field types alone and OMIT the redundant {@code type=…} override. The AI SDK
 * {@code UIMessageChunk} schema remains the source of truth for chunk validation client-side.
 */
@Schema(description = "Mentor chat message in AI SDK UIMessage shape.")
public record ChatMessageDTO(
        UUID id,
        @Nullable UUID parentMessageId,
        String role,

        @ArraySchema(schema = @Schema(description = "AI SDK UIMessage part (text / data-observation)."))
        List<Object> parts,

        @Nullable @Schema(description = "Per-turn metadata: status, model, costUsd, usage, …")
        Map<String, Object> metadata,

        Instant createdAt) {
    /**
     * Build a DTO from the entity + the effective parts {@link JsonNode}. Conversion is via
     * the supplied {@code ObjectMapper} so we honour the application's Jackson config (e.g.
     * Instant serialisation, JavaTimeModule). The shape on the wire is a JSON array of
     * objects for {@code parts} and a JSON object for {@code metadata}, both opaque from
     * Java's perspective.
     */
    @SuppressWarnings("unchecked")
    public static ChatMessageDTO from(ChatMessage message, JsonNode effectiveParts, ObjectMapper mapper) {
        List<Object> parts = effectiveParts != null && effectiveParts.isArray()
                ? mapper.convertValue(effectiveParts, new TypeReference<List<Object>>() {})
                : List.of();
        parts = parts.stream()
                .filter(ChatMessageDTO::isUserVisiblePart)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> metadata =
                message.getMetadata() != null && message.getMetadata().isObject()
                        ? new LinkedHashMap<>(
                                mapper.convertValue(message.getMetadata(), new TypeReference<Map<String, Object>>() {}))
                        : new LinkedHashMap<>();
        // Status was promoted to a real column in migration mentor-1071-add-status-column,
        // but the webapp's generated client still reads it from `metadata.status`. Merge the
        // column back into the metadata bag at the API boundary so the wire shape stays
        // stable across the migration. The server-side reader-of-truth is now the column.
        metadata.put("status", message.getStatus().name());
        // `error` holds the server-side cause of an interrupted turn, which can name internal classes; it
        // stays in the row for diagnostics. The status above is what a reader needs.
        metadata.remove("error");
        // Read the raw FK column instead of dereferencing the lazy parentMessage proxy — a
        // 100-message thread listing would otherwise issue 100 extra SELECTs.
        return new ChatMessageDTO(
                message.getId(),
                message.getParentMessageId(),
                message.getRole().getValue(),
                parts,
                metadata,
                message.getCreatedAt());
    }

    private static boolean isUserVisiblePart(Object part) {
        if (!(part instanceof Map<?, ?> map)) {
            return true;
        }
        Object type = map.get("type");
        if (!(type instanceof String value)) {
            return true;
        }
        return !"reasoning".equals(value) && !value.startsWith("tool-");
    }
}
