package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Exact tool arguments and outcomes share the original composition capsule's custody and expiry. */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class CompositionToolTrace {
    private final ResultAdmissionService admission;
    private final ReviewCompositionBasisRepository bases;
    private final ReviewCompositionContinuation continuation;

    @Transactional
    public void record(
            ResultAdmissionService.AdmissionIdentity identity,
            String callId,
            String tool,
            JsonNode arguments,
            String outcome) {
        continuation.holdRecoveryCustody();
        var job = admission.ownedJob(identity);
        var basis = bases.findByWorkspaceIdAndJobId(identity.workspaceId(), identity.jobId())
                .orElseThrow();
        continuation.requireCustody(job, basis);
        var payload = (ObjectNode) basis.getPayload().deepCopy();
        var trace = payload.withArray("tools");
        var entry = trace.addObject()
                .put("attempt", identity.attempt())
                .put("callId", callId)
                .put("tool", tool);
        entry.set("arguments", arguments);
        entry.put("outcome", outcome);
        if (trace.toString().getBytes(StandardCharsets.UTF_8).length > JobEvidenceFiles.TRACE_BYTE_BUDGET)
            throw new IllegalStateException("The composition tool trace reached its bound.");
        basis.setPayload(payload);
        bases.save(basis);
    }
}
