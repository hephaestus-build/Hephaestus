package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import org.slf4j.spi.LoggingEventBuilder;

/** A sandbox record names its sandbox, its subject and its workspace the same way wherever it is written. */
final class SandboxEvents {

    private SandboxEvents() {}

    static LoggingEventBuilder event(LoggingEventBuilder builder, String name, AgentSandboxRepository.SandboxRow row) {
        return builder.addKeyValue("event.name", name)
                .addKeyValue(StructuredLogKeys.SANDBOX_NAME, row.getClaimName())
                .addKeyValue(StructuredLogKeys.SUBJECT_KEY, row.getSubjectKey())
                .addKeyValue(StructuredLogKeys.WORKSPACE_ID, row.getWorkspaceId())
                .addKeyValue("sandbox.kind", row.getKind());
    }
}
