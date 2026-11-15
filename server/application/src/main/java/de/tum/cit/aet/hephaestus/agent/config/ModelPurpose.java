package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.util.Arrays;
import java.util.List;

/** The things a workspace runs an LLM for; at most one {@link WorkspaceModelAssignment} per purpose and data-handling tier. */
public enum ModelPurpose {
    /** Pull-request, issue, and conversation practice review. */
    PRACTICE_REVIEW(ModelKind.CHAT),
    /** Interactive conversation turns (web SSE and Slack). */
    CONVERSATION(ModelKind.CHAT);

    private final ModelKind kind;

    ModelPurpose(ModelKind kind) {
        this.kind = kind;
    }

    public ModelKind kind() {
        return kind;
    }

    public static List<ModelPurpose> servedBy(LlmApiProtocol protocol) {
        return Arrays.stream(values())
                .filter(purpose -> protocol.serves(purpose.kind))
                .toList();
    }
}
