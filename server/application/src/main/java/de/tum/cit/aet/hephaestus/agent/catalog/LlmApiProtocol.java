package de.tum.cit.aet.hephaestus.agent.catalog;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The wire shapes a connection can speak. {@link #wire()} is the stored and the API form. A wire name
 * names the request shape, not a vendor: vLLM, Azure and other gateways serve the {@code openai-*}
 * shapes too.
 */
public enum LlmApiProtocol {
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

    /** The value stored in {@code api_protocol} and sent on the API, in both directions. */
    @JsonValue
    public String wire() {
        return wire;
    }

    public ModelKind kind() {
        return kind;
    }

    /**
     * Whether a model on this protocol can fill a slot of {@code slot} kind. A chat completions model
     * also answers decisions: the precompute runner reads them from its token log probabilities.
     */
    public boolean serves(ModelKind slot) {
        return kind == slot || (this == OPENAI_COMPLETIONS && slot == ModelKind.DECISION);
    }

    /** The path appended to the connection's base URL for one call. */
    public String upstreamPath() {
        return upstreamPath;
    }

    /** Empty for a value no connection may hold, such as the protocols of pre-catalog snapshots. */
    public static Optional<LlmApiProtocol> parse(@Nullable String wire) {
        return Arrays.stream(values())
                .filter(protocol -> protocol.wire.equals(wire))
                .findFirst();
    }
}
