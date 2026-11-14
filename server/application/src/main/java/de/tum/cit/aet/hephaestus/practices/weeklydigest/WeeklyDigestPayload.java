package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a weekly digest entry stores in {@code conversation_entry.payload}: the week and which feedback and changes it held.
 * It freezes membership, never words or state ({@link WeeklyDigestFacts}).
 *
 * <p>It does not store whose digest it is: the digest is rendered for the account that owns its conversation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WeeklyDigestPayload(
        WeeklyDigestWindow window, List<UUID> feedbackIds, List<WeeklyDigestChangeKey> changeKeys) {

    public JsonNode toJson(ObjectMapper mapper) {
        return mapper.valueToTree(this);
    }

    /** The payload of a stored digest entry; empty when it is malformed or was erased. */
    public static Optional<WeeklyDigestPayload> fromJson(@Nullable JsonNode node, ObjectMapper mapper) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.treeToValue(node, WeeklyDigestPayload.class));
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }
}
