package de.tum.cit.aet.hephaestus.agent.catalog;

import com.fasterxml.jackson.annotation.JsonValue;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Protocol names identify wire shapes, not vendors; {@link #wire()} is the persisted and API form. */
public enum LlmApiProtocol {
    ANTHROPIC_MESSAGES("anthropic-messages", ModelKind.CHAT, "/v1/messages"),
    OPENAI_COMPLETIONS("openai-completions", ModelKind.CHAT, "/chat/completions"),
    OPENAI_RESPONSES("openai-responses", ModelKind.CHAT, "/responses"),
    OPENAI_DECISIONS("openai-decisions", ModelKind.DECISION, "/decisions"),
    OPENAI_EMBEDDINGS("openai-embeddings", ModelKind.EMBEDDING, "/embeddings"),
    COHERE_RERANK("cohere-rerank", ModelKind.RERANKING, "/rerank");

    private final String wire;
    private final ModelKind kind;
    private final String upstreamPath;

    LlmApiProtocol(String wire, ModelKind kind, String upstreamPath) {
        this.wire = wire;
        this.kind = kind;
        this.upstreamPath = upstreamPath;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    public ModelKind kind() {
        return kind;
    }

    /** Chat completions can also serve decisions through token log probabilities. */
    public boolean serves(ModelKind slot) {
        return kind == slot || (this == OPENAI_COMPLETIONS && slot == ModelKind.DECISION);
    }

    /** The path appended to the connection's base URL for one call. */
    public String upstreamPath() {
        return upstreamPath;
    }

    public static Optional<LlmApiProtocol> parse(@Nullable String wire) {
        return Arrays.stream(values())
                .filter(protocol -> protocol.wire.equals(wire))
                .findFirst();
    }
}
