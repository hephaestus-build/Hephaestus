package de.tum.cit.aet.hephaestus.agent.mentor.chat.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wire fixtures pinned against the AI SDK {@code UIMessageChunk} schema. A protocol bump in
 * Vercel's package surfaces here before reaching the webapp.
 *
 * <p>Reference: https://github.com/vercel/ai/blob/main/packages/ai/src/ui-message-stream/ui-message-chunks.ts
 */
class UIMessageChunkSerializationTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Each row pins the JSON shape of one chunk variant. Covers every chunk the translator emits
     * (one representative per discriminator). The single test is a smoke-screen against schema
     * drift; deeper per-chunk semantics live in {@code PiEventToUiChunkTranslatorTest}.
     */
    static List<Object[]> chunkFixtures() {
        UUID messageId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID observationId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID partId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        UIMessageChunk.MessageMetadata finishMeta = new UIMessageChunk.MessageMetadata(
                "openai/gpt-oss-120b", new UIMessageChunk.MessageMetadata.Usage(655, 65, null, null, 720), 0.0042);
        ObjectNode toolOutput = NODES.objectNode().put("status", "ok").put("count", 7);
        return List.of(
                new Object[] {
                    new UIMessageChunk.Start(messageId, null),
                    "{\"type\":\"start\",\"messageId\":\"" + messageId + "\"}",
                },
                new Object[] {new UIMessageChunk.StartStep(), "{\"type\":\"start-step\"}"},
                new Object[] {new UIMessageChunk.FinishStep(), "{\"type\":\"finish-step\"}"},
                new Object[] {new UIMessageChunk.TextStart("t-0"), "{\"type\":\"text-start\",\"id\":\"t-0\"}"},
                new Object[] {
                    new UIMessageChunk.TextDelta("t-0", "Hello"),
                    "{\"type\":\"text-delta\",\"id\":\"t-0\",\"delta\":\"Hello\"}",
                },
                new Object[] {new UIMessageChunk.TextEnd("t-0"), "{\"type\":\"text-end\",\"id\":\"t-0\"}"},
                new Object[] {
                    new UIMessageChunk.ToolOutputAvailable("call-1", toolOutput),
                    "{\"type\":\"tool-output-available\",\"toolCallId\":\"call-1\",\"output\":{\"status\":\"ok\",\"count\":7}}",
                },
                new Object[] {
                    new UIMessageChunk.Finish(null, finishMeta),
                    "{\"type\":\"finish\",\"messageMetadata\":{\"model\":\"openai/gpt-oss-120b\","
                            + "\"usage\":{\"input\":655,\"output\":65,\"totalTokens\":720},\"costUsd\":0.0042}}",
                },
                // MessageMetadata.of(null,null,null) collapses to null → Finish.messageMetadata omitted entirely.
                new Object[] {
                    new UIMessageChunk.Finish(
                            UIMessageChunk.FinishReason.STOP, UIMessageChunk.MessageMetadata.of(null, null, null)),
                    "{\"type\":\"finish\",\"finishReason\":\"stop\"}",
                },
                // All six FinishReason enum values must serialise to their canonical kebab-case wire form.
                new Object[] {
                    new UIMessageChunk.Finish(UIMessageChunk.FinishReason.CONTENT_FILTER, null),
                    "{\"type\":\"finish\",\"finishReason\":\"content-filter\"}",
                },
                new Object[] {
                    new UIMessageChunk.Finish(UIMessageChunk.FinishReason.TOOL_CALLS, null),
                    "{\"type\":\"finish\",\"finishReason\":\"tool-calls\"}",
                },
                // Reasoning lifecycle: start / delta / end — block-id propagation pinned.
                new Object[] {new UIMessageChunk.ReasoningStart("r-0"), "{\"type\":\"reasoning-start\",\"id\":\"r-0\"}"
                },
                new Object[] {
                    new UIMessageChunk.ReasoningDelta("r-0", "Let me think…"),
                    "{\"type\":\"reasoning-delta\",\"id\":\"r-0\",\"delta\":\"Let me think…\"}",
                },
                new Object[] {new UIMessageChunk.ReasoningEnd("r-0"), "{\"type\":\"reasoning-end\",\"id\":\"r-0\"}"},
                // Tool input lifecycle — strict zod requires toolCallId + toolName + input on `*-available`.
                new Object[] {
                    new UIMessageChunk.ToolInputStart("call-1", "fetch_context"),
                    "{\"type\":\"tool-input-start\",\"toolCallId\":\"call-1\",\"toolName\":\"fetch_context\"}",
                },
                new Object[] {
                    new UIMessageChunk.ToolInputAvailable(
                            "call-1", "fetch_context", NODES.objectNode().put("path", "inputs/context/workspace.json")),
                    "{\"type\":\"tool-input-available\",\"toolCallId\":\"call-1\","
                            + "\"toolName\":\"fetch_context\",\"input\":{\"path\":\"inputs/context/workspace.json\"}}",
                },
                // Error tool output — errorText only, no output field on wire.
                new Object[] {
                    new UIMessageChunk.ToolOutputError("call-1", "fetch_context: path not allowed"),
                    "{\"type\":\"tool-output-error\",\"toolCallId\":\"call-1\","
                            + "\"errorText\":\"fetch_context: path not allowed\"}",
                },
                // NON_NULL: finishReason omitted, but messageMetadata kept.
                // data-* envelope shape per AI SDK strict-object schema:
                // {type, id?, data: unknown, transient?: boolean}. status is transient (banner only);
                // observation is permanent (linked chip in message history).
                new Object[] {
                    UIMessageChunk.DataMentorStatus.of("warming-up", "container-cold"),
                    "{\"type\":\"data-mentor-status\",\"id\":\"mentor-status\","
                            + "\"data\":{\"state\":\"warming-up\",\"reason\":\"container-cold\"},\"transient\":true}",
                },
                new Object[] {
                    new UIMessageChunk.DataObservation(
                            partId,
                            new UIMessageChunk.DataObservation.DataObservationPayload(observationId, "Say why.")),
                    "{\"type\":\"data-observation\",\"id\":\"" + partId
                            + "\",\"data\":{\"observationId\":\""
                            + observationId
                            + "\",\"text\":\"Say why.\"}}",
                },
                new Object[] {
                    new UIMessageChunk.Error("container died mid-turn"),
                    "{\"type\":\"error\",\"errorText\":\"container died mid-turn\"}",
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chunkFixtures")
    void serialisesMatchesUpstreamShape(UIMessageChunk chunk, String expectedJson) throws Exception {
        String json = MAPPER.writeValueAsString(chunk);
        JsonNode actual = MAPPER.readTree(json);
        JsonNode expected = MAPPER.readTree(expectedJson);
        assertThat(actual).as("chunk %s", chunk).isEqualTo(expected);
    }

    // A `dataChunkDiscriminatorSpelling` test would be pure duplication: the parameterised
    // chunkFixtures test above already pins the exact JSON for `data-mentor-status` and
    // `data-observation` chunks, and a Jackson SNAKE_CASE override would fail those fixture tests
    // BEFORE reaching the spelling assertion.

    @Test
    void everyChunkSubtypeIsRegisteredInJsonSubTypes() {
        // Jackson's @JsonTypeInfo(use=NAME, property="type") only writes the discriminator for
        // subtypes listed in @JsonSubTypes. Adding a new `record Foo implements UIMessageChunk`
        // without registering it would emit `{...}` with no `type` field — silently corrupting the
        // wire and only failing client-side after the chunk leaves the JVM. This test asserts the
        // class-level annotation contains every permitted subtype of the sealed interface.
        JsonSubTypes ann = UIMessageChunk.class.getAnnotation(JsonSubTypes.class);
        assertThat(ann).as("@JsonSubTypes annotation present").isNotNull();
        Set<Class<?>> registered = new LinkedHashSet<>();
        for (JsonSubTypes.Type t : ann.value()) {
            registered.add(t.value());
        }
        // Sealed interface auto-permits its directly-nested record subtypes plus DataMentorStatus /
        // DataObservation which are top-level nested records. Reflectively enumerate permits via the
        // class-level `getPermittedSubclasses()` (Java 17+).
        Class<?>[] permitted = UIMessageChunk.class.getPermittedSubclasses();
        assertThat(permitted).as("sealed permits resolves").isNotEmpty();
        Set<Class<?>> permittedSet = new LinkedHashSet<>(Arrays.asList(permitted));
        Set<Class<?>> missing = new LinkedHashSet<>(permittedSet);
        missing.removeAll(registered);
        // MessageMetadata is a payload record, not a chunk — it doesn't `implements UIMessageChunk`,
        // so it won't appear in the permits anyway. If anything is in `missing` here, someone added
        // a chunk record and forgot the `@JsonSubTypes.Type(...)` entry.
        assertThat(missing)
                .as("Every UIMessageChunk subtype must be listed in @JsonSubTypes (missing: %s)", missing)
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(UIMessageChunk.FinishReason.class)
    void finishReasonRoundTrips(UIMessageChunk.FinishReason reason) throws Exception {
        String wire = MAPPER.writeValueAsString(reason);
        // Wire form is the kebab-case string literal — NOT the enum constant name.
        assertThat(wire).isEqualTo("\"" + reason.wire() + "\"");
        UIMessageChunk.FinishReason back = MAPPER.readValue(wire, UIMessageChunk.FinishReason.class);
        assertThat(back).isSameAs(reason);
    }

    @Test
    void finishReasonRejectsUnknown() {
        // If AI SDK ever ships a `finish` chunk with a new value (e.g. "rate-limit"), we want a
        // fast, loud failure — not a silent null fallback that hides the protocol drift.
        assertThatThrownBy(() -> MAPPER.readValue("\"rate-limit\"", UIMessageChunk.FinishReason.class))
                .hasMessageContaining("rate-limit");
    }
}
