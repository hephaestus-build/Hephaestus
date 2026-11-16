package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The composer's own conversations: {@code review-composer-conversations.ts} runs the real
 * {@code review-composer-model.ts} against fixed answers of each protocol, for one session that a test issues.
 */
public final class ComposerConversations {
    /** The tool answer that each conversation gets for its one call. */
    public static final String CHECKED = "{\"draftId\":\"d-1\"}";

    private static final Path SCRIPT = Path.of("src/test/resources/agent/review-composer-conversations.ts");
    private static final JsonMapper MAPPER = new JsonMapper();

    private ComposerConversations() {}

    public static CompositionSessions.Session session(LlmApiProtocol protocol, String system) {
        return session(protocol, system, "The admitted results.");
    }

    public static CompositionSessions.Session session(LlmApiProtocol protocol, String system, String input) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("draftId").put("type", "string");
        return new CompositionSessions.Session(
                FeedbackDestination.ON_WORK,
                new CompositionSessions.Model(protocol, "model", ReasoningEffort.HIGH, 8, 0.2),
                system,
                input,
                List.of(new CompositionSessions.Tool("check_package", "Checks a draft.", schema)),
                "Finish now.",
                "Two calls left.",
                false,
                true,
                12,
                null);
    }

    /**
     * The conversation of {@link #session(LlmApiProtocol, String)} with system "Compose the review.": three requests,
     * each with the fixed answer it got.
     */
    public static List<JsonNode> conversation(LlmApiProtocol protocol) throws Exception {
        var session = session(protocol, "Compose the review.");
        ObjectNode input = MAPPER.createObjectNode();
        input.putObject("model")
                .put("protocol", protocol.wire())
                .put("modelId", session.model().modelId())
                .put("reasoningEffort", ReasoningEffort.HIGH.name())
                .put("maxOutputTokens", session.model().maxOutputTokens())
                .put("temperature", 0.2);
        input.put("system", session.system())
                .put("input", session.input())
                .put("nudge", session.nudge())
                .put("finish", "Finish now.")
                .put("checked", CHECKED);
        ArrayNode tools = input.putArray("tools");
        for (var tool : session.tools())
            tools.addObject()
                    .put("name", tool.name())
                    .put("description", tool.description())
                    .set("parameters", tool.parameters());
        var process = new ProcessBuilder("node", SCRIPT.toString(), input.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        byte[] printed = process.getInputStream().readAllBytes();
        if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0)
            throw new IllegalStateException("The composer conversation did not finish");
        return MAPPER.readTree(printed).get("steps").valueStream().toList();
    }
}
