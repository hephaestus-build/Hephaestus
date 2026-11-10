package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.util.Arrays;
import java.util.List;

/** The things a workspace runs an LLM for; at most one {@link WorkspaceAgentBinding} per purpose and data-handling tier. */
public enum AgentPurpose {
    /** Pull-request, issue, and conversation practice review. */
    PRACTICE_REVIEW(ModelKind.CHAT),
    /** Interactive mentor turns (web SSE and Slack). */
    MENTOR(ModelKind.CHAT);

    private final ModelKind kind;

    AgentPurpose(ModelKind kind) {
        this.kind = kind;
    }

    public ModelKind kind() {
        return kind;
    }

    public static List<AgentPurpose> servedBy(LlmApiProtocol protocol) {
        return Arrays.stream(values())
                .filter(purpose -> protocol.serves(purpose.kind))
                .toList();
    }
}
