package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ModelSlot;
import java.util.Arrays;
import java.util.List;

/** The things a workspace runs an LLM for; at most one {@link WorkspaceAgentBinding} per purpose and data-handling tier. */
public enum AgentPurpose {
    /** Pull-request, issue, and conversation practice review. */
    PRACTICE_REVIEW(ModelKind.CHAT),
    /** Interactive mentor turns (web SSE and Slack). */
    MENTOR(ModelKind.CHAT),
    PRACTICE_DECISION(ModelKind.DECISION),
    PRACTICE_EMBEDDING(ModelKind.EMBEDDING),
    PRACTICE_RERANKING(ModelKind.RERANKING);

    private static final List<AgentPurpose> PRACTICE_SCRIPT = Arrays.stream(values())
            .filter(purpose -> purpose.kind != ModelKind.CHAT)
            .toList();

    private final ModelKind kind;

    AgentPurpose(ModelKind kind) {
        this.kind = kind;
    }

    public ModelKind kind() {
        return kind;
    }

    public static AgentPurpose forSlot(ModelSlot slot) {
        return switch (slot) {
            case CHAT -> PRACTICE_REVIEW;
            case DECISION -> PRACTICE_DECISION;
            case EMBEDDING -> PRACTICE_EMBEDDING;
            case RERANKING -> PRACTICE_RERANKING;
        };
    }

    /**
     * Whether a reasoning effort reaches this purpose's model calls. A decision call reads a set effort as a reasoning
     * model, and asks it for none. Embedding and reranking calls take no effort.
     */
    public boolean takesReasoningEffort() {
        return kind == ModelKind.CHAT || kind == ModelKind.DECISION;
    }

    public boolean accepts(LlmApiProtocol protocol) {
        return protocol.serves(kind);
    }

    public static List<AgentPurpose> servedBy(LlmApiProtocol protocol) {
        return Arrays.stream(values())
                .filter(purpose -> purpose.accepts(protocol))
                .toList();
    }

    /** Practice scripts use the review chat model and these additional model purposes. */
    public static List<AgentPurpose> practiceScript() {
        return PRACTICE_SCRIPT;
    }
}
