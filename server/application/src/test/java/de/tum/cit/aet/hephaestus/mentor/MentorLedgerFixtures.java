package de.tum.cit.aet.hephaestus.mentor;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Pi session entries as a Heph host reports them (Pi's session format), for ledger tests. */
public final class MentorLedgerFixtures {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MentorLedgerFixtures() {}

    public static String piId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** The Pi session entry id of a stored entry: its Pi entry's id, or the row's id for an entry Pi never wrote. */
    public static String piIdOf(MentorEntry entry) {
        JsonNode payload = entry.getPayload();
        if (payload != null
                && entry.getKind() != MentorEntry.Kind.DIGEST
                && payload.path("id").isString()) {
            return payload.path("id").asString();
        }
        return entry.getId().toString();
    }

    public static ObjectNode message(String id, String role, JsonNode content) {
        ObjectNode entry = NODES.objectNode()
                .put("type", "message")
                .put("id", id)
                .put("parentId", "host-parent")
                .put("timestamp", Instant.parse("2026-10-08T07:00:00Z").toString());
        entry.putObject("message").put("role", role).set("content", content);
        return entry;
    }

    public static ObjectNode user(String id, String text) {
        return message(id, "user", NODES.stringNode(text));
    }

    /** Every entry of a turn, in order; production reads a turn's entries by kind only. */
    public static List<MentorEntry> turnEntries(EntityManager entityManager, long workspaceId, UUID turnId) {
        return entityManager
                .createQuery(
                        "SELECT e FROM MentorEntry e WHERE e.workspaceId = :workspaceId AND e.turnId = :turnId"
                                + " ORDER BY e.seq",
                        MentorEntry.class)
                .setParameter("workspaceId", workspaceId)
                .setParameter("turnId", turnId)
                .getResultList();
    }

    public static ObjectNode answer(String id, String text) {
        ArrayNode content = NODES.arrayNode();
        content.addObject().put("type", "text").put("text", text);
        return message(id, "assistant", content);
    }

    /** An assistant message that only calls a tool. */
    public static ObjectNode toolCall(String id, String callId, String tool, ObjectNode arguments) {
        ArrayNode content = NODES.arrayNode();
        content.addObject()
                .put("type", "toolCall")
                .put("id", callId)
                .put("name", tool)
                .set("arguments", arguments);
        return message(id, "assistant", content);
    }

    /** An assistant message that calls {@code link_observation} for each observation, by call id, in order. */
    public static ObjectNode linkObservations(String id, Map<String, UUID> observationsByCallId, String text) {
        ArrayNode content = NODES.arrayNode();
        observationsByCallId.forEach((callId, observationId) -> content.addObject()
                .put("type", "toolCall")
                .put("id", callId)
                .put("name", "link_observation")
                .putObject("arguments")
                .put("observationId", observationId.toString())
                .put("text", text));
        return message(id, "assistant", content);
    }

    public static ObjectNode toolResult(String id, String callId, String tool, String text, boolean error) {
        ArrayNode content = NODES.arrayNode();
        content.addObject().put("type", "text").put("text", text);
        ObjectNode entry = message(id, "toolResult", content);
        ((ObjectNode) entry.path("message"))
                .put("toolCallId", callId)
                .put("toolName", tool)
                .put("isError", error);
        return entry;
    }

    public static ObjectNode compaction(String id, String summary, String firstKeptEntryId) {
        return NODES.objectNode()
                .put("type", "compaction")
                .put("id", id)
                .put("parentId", "host-parent")
                .put("timestamp", Instant.parse("2026-10-08T07:00:00Z").toString())
                .put("summary", summary)
                .put("firstKeptEntryId", firstKeptEntryId)
                .put("tokensBefore", 50_000);
    }

    public static ObjectNode thinkingLevel(String id) {
        return NODES.objectNode()
                .put("type", "thinking_level_change")
                .put("id", id)
                .put("parentId", "host-parent")
                .put("timestamp", Instant.parse("2026-10-08T07:00:00Z").toString())
                .put("thinkingLevel", "off");
    }

    /** One of Heph's app messages, with its Pi entry. */
    public static MentorEntryDraft heph(String text) {
        return new MentorEntryDraft(
                MentorEntry.Kind.HEPH, MentorEntry.Surface.APP, text, answer(piId(), text), null, null, null);
    }

    static MentorEntryDraft digest(String requestId) {
        return MentorEntryDraft.digest(
                "Weekly digest for 5–11 October 2026. Use fetch_context weekly_digest.json to read it.",
                NODES.objectNode().put("weekStart", "2026-10-05"),
                requestId);
    }
}
