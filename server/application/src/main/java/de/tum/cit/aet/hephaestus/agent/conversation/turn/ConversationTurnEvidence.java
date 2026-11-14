package de.tum.cit.aet.hephaestus.agent.conversation.turn;

import de.tum.cit.aet.hephaestus.agent.context.providers.conversation.PreparedConversationFeedbackContentSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a turn's situation says about the turn's context: where to start reading, and the prepared feedback that waits
 * for the developer in this conversation. It names the pieces by id and practice, never by the brief's words; the
 * document holds them. {@code INDEX.md} in the turn's context folder lists everything else.
 */
public final class ConversationTurnEvidence {

    /** The line that starts every turn's reading. */
    public static final String START = "Start with `read INDEX.md`: it lists what this turn can read.";

    /** How many waiting pieces the situation names; the document lists them all. */
    static final int MAX_NAMED = 3;

    private ConversationTurnEvidence() {}

    /** The situation's lines about the context: the waiting feedback, if any, then {@link #START}. */
    public static String receipt(ObjectMapper mapper, Map<String, byte[]> inputs) {
        String waiting = waitingFeedback(mapper, inputs);
        return waiting.isEmpty() ? START : waiting + "\n" + START;
    }

    private static String waitingFeedback(ObjectMapper mapper, Map<String, byte[]> inputs) {
        byte[] bytes = inputs.get(PreparedConversationFeedbackContentSource.OUTPUT_KEY);
        if (bytes == null) {
            return "";
        }
        JsonNode prepared;
        try {
            prepared = mapper.readTree(bytes);
        } catch (JacksonException malformed) {
            return "";
        }
        int waiting = prepared.path("waiting").asInt(0);
        if (waiting <= 0) {
            return "";
        }
        List<String> named = new ArrayList<>();
        for (JsonNode piece : prepared.path("preparedConversationFeedback")) {
            if (named.size() == MAX_NAMED) {
                break;
            }
            List<String> practices = new ArrayList<>();
            piece.path("practiceSlugs").forEach(slug -> practices.add(slug.asString()));
            named.add(piece.path("feedbackId").asString("") + " (" + String.join(", ", practices) + ")");
        }
        return "Prepared feedback waiting for them in this conversation: " + waiting
                + (named.isEmpty() ? "" : ", among them " + String.join("; ", named)) + ". Read "
                + PreparedConversationFeedbackContentSource.OUTPUT_KEY + " before you raise one.";
    }
}
