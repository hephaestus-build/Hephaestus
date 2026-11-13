package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Reads the composer loop's model requests and the answers to them, exactly in the shapes that
 * {@code review-composer-model.ts} writes and keeps. A request is its session (protocol, model, instructions, tools
 * and settings) and its conversation in order: user turns, blocks of tool answers, and the model's replies. A request
 * in any other shape is refused with a fixed reason, so no field that the composer never sends reaches the model.
 *
 * <p>Parts compare by a digest over a canonical form. The composer parses and serializes each part again, which can
 * change key order and how a number is written, but not its value: keys sort, and a number counts by the double that
 * JavaScript reads it as.
 */
final class ComposerRequests {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** The least output limit the composer asks of the responses protocol. */
    private static final int MIN_RESPONSES_OUTPUT = 16;

    private final ObjectMapper mapper;

    ComposerRequests(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A request's session and conversation, or why it is no request of the composer. */
    sealed interface Read permits Parts, Refused {}

    /**
     * @param session the digest of the request's session, as {@link #session(CompositionSessions.Session)} computes it
     * @param turns the conversation, in order
     */
    record Parts(String session, List<Turn> turns) implements Read {}

    /** @param reason a fixed reason, never content of the request */
    record Refused(String reason) implements Read {}

    /** One turn of a composer conversation. */
    sealed interface Turn permits User, Answers, Reply {}

    /** @param text the digest of a user turn's text */
    record User(String text) implements Turn {}

    /** @param answers the tool answers of one turn, in order */
    record Answers(List<Answer> answers) implements Turn {}

    /** @param text the digest of the answer's text */
    record Answer(String callId, String text) {}

    /**
     * @param digest the digest of the reply as the composer keeps it
     * @param callIds the IDs of the tool calls it holds, in order
     */
    record Reply(String digest, List<String> callIds) implements Turn {}

    /** One tool call as the model wrote it. */
    record Call(String id, String tool, @Nullable JsonNode arguments) {}

    // ------------------------------------------------------------ requests

    Read read(LlmApiProtocol protocol, JsonNode body) {
        if (!body.isObject()) return new Refused("not an object");
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> anthropic(body);
            case OPENAI_COMPLETIONS -> completions(body);
            case OPENAI_RESPONSES -> responses(body);
        };
    }

    private Read anthropic(JsonNode body) {
        if (!only(body, Set.of("model", "system", "messages", "max_tokens", "tool_choice", "tools")))
            return new Refused("unexpected field");
        if (!JSON.objectNode().put("type", "auto").equals(body.get("tool_choice"))) return new Refused("tool_choice");
        if (!body.path("system").isString()) return new Refused("system");
        if (!body.path("tools").isArray() || !body.path("messages").isArray()) return new Refused("shape");
        var tools = JSON.arrayNode();
        for (JsonNode tool : body.path("tools")) {
            if (!keys(tool, Set.of("name", "description", "input_schema"))) return new Refused("tool");
            tools.add(tool(tool.get("name"), tool.get("description"), tool.get("input_schema")));
        }
        var turns = new ArrayList<Turn>();
        for (JsonNode message : body.path("messages")) {
            if (!keys(message, Set.of("role", "content"))) return new Refused("message");
            JsonNode content = message.get("content");
            switch (message.path("role").asString("")) {
                case "assistant" -> {
                    var ids = new ArrayList<String>();
                    for (JsonNode part : array(content))
                        if ("tool_use".equals(part.path("type").asString(""))) ids.add(text(part.get("id")));
                    turns.add(new Reply(digest(content), ids));
                }
                case "user" -> {
                    if (content.isString()) {
                        turns.add(new User(digest(content)));
                        continue;
                    }
                    if (!content.isArray() || content.isEmpty()) return new Refused("user content");
                    var answers = new ArrayList<Answer>();
                    User after = null;
                    for (JsonNode part : content) {
                        String type = part.path("type").asString("");
                        if (after == null
                                && type.equals("tool_result")
                                && keys(part, Set.of("type", "tool_use_id", "content"))
                                && part.get("tool_use_id").isString()
                                && part.get("content").isString())
                            answers.add(new Answer(part.get("tool_use_id").asString(), digest(part.get("content"))));
                        else if (after == null
                                && type.equals("text")
                                && keys(part, Set.of("type", "text"))
                                && part.get("text").isString()) after = new User(digest(part.get("text")));
                        else return new Refused("user part");
                    }
                    if (!answers.isEmpty()) turns.add(new Answers(answers));
                    if (after != null) turns.add(after);
                }
                default -> {
                    return new Refused("role");
                }
            }
        }
        return new Parts(
                session(
                        LlmApiProtocol.ANTHROPIC_MESSAGES,
                        body.get("model"),
                        body.get("system"),
                        tools,
                        body.get("max_tokens"),
                        null,
                        null),
                turns);
    }

    private Read completions(JsonNode body) {
        if (!only(
                body,
                Set.of(
                        "model",
                        "messages",
                        "store",
                        "max_completion_tokens",
                        "tools",
                        "reasoning_effort",
                        "temperature"))) return new Refused("unexpected field");
        if (body.has("store") && !JSON.booleanNode(false).equals(body.get("store"))) return new Refused("store");
        if (!body.path("tools").isArray()) return new Refused("shape");
        var tools = JSON.arrayNode();
        for (JsonNode tool : body.path("tools")) {
            JsonNode function = tool.path("function");
            if (!keys(tool, Set.of("type", "function"))
                    || !"function".equals(tool.path("type").asString(""))
                    || !keys(function, Set.of("name", "description", "parameters"))) return new Refused("tool");
            tools.add(tool(function.get("name"), function.get("description"), function.get("parameters")));
        }
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) return new Refused("shape");
        JsonNode system = messages.get(0);
        if (!keys(system, Set.of("role", "content"))
                || !"system".equals(system.path("role").asString(""))
                || !system.get("content").isString()) return new Refused("system");
        var turns = new ArrayList<Turn>();
        var answers = new ArrayList<Answer>();
        for (int i = 1; i < messages.size(); i++) {
            JsonNode message = messages.get(i);
            String role = message.path("role").asString("");
            if (!role.equals("tool") && !answers.isEmpty()) {
                turns.add(new Answers(List.copyOf(answers)));
                answers.clear();
            }
            switch (role) {
                case "assistant" -> {
                    var ids = new ArrayList<String>();
                    for (JsonNode call : array(message.path("tool_calls"))) ids.add(text(call.get("id")));
                    turns.add(new Reply(digest(message), ids));
                }
                case "user" -> {
                    if (!keys(message, Set.of("role", "content"))
                            || !message.get("content").isString()) return new Refused("user turn");
                    turns.add(new User(digest(message.get("content"))));
                }
                case "tool" -> {
                    if (!keys(message, Set.of("role", "tool_call_id", "content"))
                            || !message.get("tool_call_id").isString()
                            || !message.get("content").isString()) return new Refused("tool answer");
                    answers.add(new Answer(message.get("tool_call_id").asString(), digest(message.get("content"))));
                }
                default -> {
                    return new Refused("role");
                }
            }
        }
        if (!answers.isEmpty()) turns.add(new Answers(List.copyOf(answers)));
        return new Parts(
                session(
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        body.get("model"),
                        system.get("content"),
                        tools,
                        body.get("max_completion_tokens"),
                        body.get("temperature"),
                        body.get("reasoning_effort")),
                turns);
    }

    private Read responses(JsonNode body) {
        if (!only(
                body,
                Set.of(
                        "model",
                        "instructions",
                        "input",
                        "store",
                        "max_output_tokens",
                        "tools",
                        "reasoning",
                        "include",
                        "temperature"))) return new Refused("unexpected field");
        if (body.has("store") && !JSON.booleanNode(false).equals(body.get("store"))) return new Refused("store");
        // The composer asks for the encrypted reasoning exactly when it asks for reasoning.
        if (body.has("include") != body.has("reasoning")
                || (body.has("include")
                        && !JSON.arrayNode().add("reasoning.encrypted_content").equals(body.get("include"))))
            return new Refused("include");
        if (body.has("reasoning")
                && (!keys(body.get("reasoning"), Set.of("effort"))
                        || !body.get("reasoning").get("effort").isString())) return new Refused("reasoning");
        if (!body.path("instructions").isString()) return new Refused("instructions");
        if (!body.path("tools").isArray() || !body.path("input").isArray()) return new Refused("shape");
        var tools = JSON.arrayNode();
        for (JsonNode tool : body.path("tools")) {
            if (!keys(tool, Set.of("type", "name", "description", "parameters", "strict"))
                    || !"function".equals(tool.path("type").asString(""))
                    || !JSON.booleanNode(false).equals(tool.get("strict"))) return new Refused("tool");
            tools.add(tool(tool.get("name"), tool.get("description"), tool.get("parameters")));
        }
        var turns = new ArrayList<Turn>();
        var answers = new ArrayList<Answer>();
        var reply = JSON.arrayNode();
        for (JsonNode item : body.path("input")) {
            if (!item.isObject()) return new Refused("input item");
            boolean user = "user".equals(item.path("role").asString("")) && !item.has("type");
            boolean answer = "function_call_output".equals(item.path("type").asString(""));
            if (!answer && !answers.isEmpty()) {
                turns.add(new Answers(List.copyOf(answers)));
                answers.clear();
            }
            if ((user || answer) && !reply.isEmpty()) {
                turns.add(reply(reply));
                reply = JSON.arrayNode();
            }
            if (user) {
                if (!keys(item, Set.of("role", "content"))
                        || !item.get("content").isString()) return new Refused("user turn");
                turns.add(new User(digest(item.get("content"))));
            } else if (answer) {
                if (!keys(item, Set.of("type", "call_id", "output"))
                        || !item.get("call_id").isString()
                        || !item.get("output").isString()) return new Refused("tool answer");
                answers.add(new Answer(item.get("call_id").asString(), digest(item.get("output"))));
            } else {
                reply.add(item);
            }
        }
        if (!answers.isEmpty()) turns.add(new Answers(List.copyOf(answers)));
        if (!reply.isEmpty()) turns.add(reply(reply));
        JsonNode reasoning = body.get("reasoning");
        return new Parts(
                session(
                        LlmApiProtocol.OPENAI_RESPONSES,
                        body.get("model"),
                        body.get("instructions"),
                        tools,
                        body.get("max_output_tokens"),
                        body.get("temperature"),
                        reasoning == null ? null : reasoning.get("effort")),
                turns);
    }

    /** One reply of the responses protocol: every output item of one answer, as the composer keeps them. */
    private Reply reply(ArrayNode items) {
        var ids = new ArrayList<String>();
        for (JsonNode item : items)
            if ("function_call".equals(item.path("type").asString(""))) ids.add(text(item.get("call_id")));
        return new Reply(digest(items), ids);
    }

    // ------------------------------------------------------------ answers

    /** The model reply of a served answer, as the composer keeps it in its conversation; null when it keeps none. */
    @Nullable
    Reply reply(LlmApiProtocol protocol, JsonNode answer) {
        return switch (protocol) {
            case ANTHROPIC_MESSAGES -> {
                if (!answer.path("content").isArray()) yield null;
                var ids = new ArrayList<String>();
                for (JsonNode part : answer.get("content"))
                    if ("tool_use".equals(part.path("type").asString(""))) ids.add(text(part.get("id")));
                yield new Reply(digest(answer.get("content")), ids);
            }
            case OPENAI_COMPLETIONS -> {
                JsonNode message = answer.path("choices").path(0).path("message");
                if (!message.isObject()) yield null;
                ObjectNode kept = JSON.objectNode().put("role", "assistant");
                JsonNode content = message.get("content");
                kept.set("content", content != null && content.isString() ? content : JSON.nullNode());
                JsonNode calls = message.get("tool_calls");
                var ids = new ArrayList<String>();
                if (calls != null && calls.isArray() && !calls.isEmpty()) {
                    kept.set("tool_calls", calls);
                    for (JsonNode call : calls) ids.add(text(call.get("id")));
                }
                JsonNode reasoning = message.get("reasoning_content");
                if (reasoning != null && reasoning.isString()) kept.set("reasoning_content", reasoning);
                yield new Reply(digest(kept), ids);
            }
            case OPENAI_RESPONSES -> {
                var items = JSON.arrayNode();
                for (JsonNode item : array(answer.path("output"))) if (item.isObject()) items.add(item);
                yield items.isEmpty() ? null : reply(items);
            }
        };
    }

    /** The tool calls of a served answer, read as the composer loop reads them. */
    List<Call> calls(LlmApiProtocol protocol, JsonNode answer) {
        var found = new ArrayList<Call>();
        switch (protocol) {
            case ANTHROPIC_MESSAGES -> {
                for (JsonNode part : array(answer.path("content"))) {
                    if ("tool_use".equals(text(part.get("type"))))
                        found.add(new Call(text(part.get("id")), text(part.get("name")), part.get("input")));
                }
            }
            case OPENAI_COMPLETIONS -> {
                JsonNode message = answer.path("choices").path(0).path("message");
                for (JsonNode call : array(message.path("tool_calls"))) {
                    JsonNode function = call.path("function");
                    found.add(new Call(
                            text(call.get("id")), text(function.get("name")), parsed(function.get("arguments"))));
                }
            }
            case OPENAI_RESPONSES -> {
                for (JsonNode item : array(answer.path("output"))) {
                    if ("function_call".equals(text(item.get("type"))))
                        found.add(new Call(
                                text(item.get("call_id")), text(item.get("name")), parsed(item.get("arguments"))));
                }
            }
        }
        return found;
    }

    // ------------------------------------------------------------ digests

    /** The digest of a session as the composer's requests of its protocol carry it. */
    String session(CompositionSessions.Session session) {
        var model = session.model();
        var tools = JSON.arrayNode();
        for (var tool : session.tools())
            tools.add(tool(StringNode.valueOf(tool.name()), StringNode.valueOf(tool.description()), tool.parameters()));
        boolean anthropic = model.protocol() == LlmApiProtocol.ANTHROPIC_MESSAGES;
        int max = model.protocol() == LlmApiProtocol.OPENAI_RESPONSES
                ? Math.max(MIN_RESPONSES_OUTPUT, model.maxOutputTokens())
                : model.maxOutputTokens();
        Double temperature = anthropic ? null : model.temperature();
        var reasoningEffort = model.reasoningEffort();
        String effort = anthropic || reasoningEffort == null
                ? null
                : reasoningEffort.name().toLowerCase(Locale.ROOT);
        return session(
                model.protocol(),
                StringNode.valueOf(model.modelId()),
                StringNode.valueOf(session.system()),
                tools,
                JSON.numberNode(max),
                temperature == null ? null : JSON.numberNode(temperature),
                effort == null ? null : StringNode.valueOf(effort));
    }

    private String session(
            LlmApiProtocol protocol,
            @Nullable JsonNode model,
            @Nullable JsonNode system,
            ArrayNode tools,
            @Nullable JsonNode max,
            @Nullable JsonNode temperature,
            @Nullable JsonNode effort) {
        ObjectNode session = JSON.objectNode().put("protocol", protocol.name());
        session.set("model", orNull(model));
        session.set("system", orNull(system));
        session.set("tools", tools);
        session.set("max", orNull(max));
        session.set("temperature", orNull(temperature));
        session.set("effort", orNull(effort));
        return digest(session);
    }

    /** The SHA-256 of a part's canonical form; an absent part counts as JSON null. */
    String digest(@Nullable JsonNode part) {
        var canonical = new StringBuilder();
        canonical(part, canonical);
        return ProvenanceDigest.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The digest of a text. */
    String textDigest(String text) {
        return digest(StringNode.valueOf(text));
    }

    /** The digest that links the given parts, in order. */
    static String link(String... parts) {
        return ProvenanceDigest.sha256Hex(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8));
    }

    private void canonical(@Nullable JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            out.append('{');
            var names = new ArrayList<>(node.propertyNames());
            names.sort(null);
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) out.append(',');
                out.append(mapper.writeValueAsString(names.get(i))).append(':');
                canonical(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                canonical(node.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            // JavaScript reads every number as a double and writes one that is not finite as null.
            double value = node.doubleValue();
            out.append(Double.isFinite(value) ? BigDecimal.valueOf(value).stripTrailingZeros() : "null");
        } else {
            out.append(mapper.writeValueAsString(node));
        }
    }

    // ------------------------------------------------------------ helpers

    private static JsonNode orNull(@Nullable JsonNode node) {
        return node == null ? JSON.nullNode() : node;
    }

    private static ObjectNode tool(
            @Nullable JsonNode name, @Nullable JsonNode description, @Nullable JsonNode parameters) {
        ObjectNode tool = JSON.objectNode();
        tool.set("name", orNull(name));
        tool.set("description", orNull(description));
        tool.set("parameters", orNull(parameters));
        return tool;
    }

    /** Whether the object has no field outside the allowed ones. */
    private static boolean only(JsonNode node, Set<String> allowed) {
        return allowed.containsAll(node.propertyNames());
    }

    /** Whether the node is an object with exactly these fields. */
    private static boolean keys(@Nullable JsonNode node, Set<String> expected) {
        return node != null
                && node.isObject()
                && Set.copyOf(node.propertyNames()).equals(expected);
    }

    private static Iterable<JsonNode> array(@Nullable JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }

    private static String text(@Nullable JsonNode node) {
        return node != null && node.isString() ? node.asString() : "";
    }

    /** A model sends arguments as a JSON string; anything unreadable, or JSON null, stays the raw string. */
    private @Nullable JsonNode parsed(@Nullable JsonNode raw) {
        if (raw == null || !raw.isString()) return raw;
        try {
            JsonNode value = mapper.readTree(raw.asString());
            return value == null || value.isNull() || value.isMissingNode() ? raw : value;
        } catch (JacksonException unreadable) {
            return raw;
        }
    }
}
