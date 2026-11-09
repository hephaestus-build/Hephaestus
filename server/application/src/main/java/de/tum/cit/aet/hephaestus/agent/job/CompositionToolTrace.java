package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exact tool arguments and outcomes share the original composition capsule's custody and expiry. Each call also keeps
 * what it decided, typed, so that the composition reads these back instead of trusting what its loop remembers.
 *
 * <p>The trace is the only home of three facts of an attempt's own work: the package each check accepted under its
 * draft ID, the reason the server refused a publication, and which packages the attempt itself published. None of them
 * is a guardrail. Whether a destination is decided lives in its prepared feedback rows, and publication reads those.
 * So when the trace reaches its byte budget, a call keeps only its decision, or nothing; it never fails. A check kept
 * without its package can no longer be published, a lost refusal withholds as not composed, and a lost publication only
 * mislabels this attempt's decision as an earlier one in the turns that follow.
 */
@Component
@ConditionalOnCaptureRole
@RequiredArgsConstructor
public class CompositionToolTrace {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ResultAdmissionService admission;
    private final ReviewCompositionBasisRepository bases;
    private final ReviewCompositionContinuation continuation;

    /**
     * What one tool call decided at its destination.
     *
     * @param draftId the draft a passing check returned
     * @param proposal the package that check accepted, exactly as the server built it
     * @param published whether the call accepted a publication
     * @param refusal the reason the server refused a publication for its context
     */
    public record Decision(
            FeedbackDestination destination,
            @Nullable String draftId,
            @Nullable JsonNode proposal,
            boolean published,
            @Nullable FeedbackStopReason refusal) {
        public static Decision none(FeedbackDestination destination) {
            return new Decision(destination, null, null, false, null);
        }
    }

    @Transactional
    public void record(
            ResultAdmissionService.AdmissionIdentity identity,
            String callId,
            String tool,
            JsonNode arguments,
            String outcome,
            Decision decision) {
        continuation.holdRecoveryCustody();
        var job = admission.ownedJob(identity);
        var basis = bases.findByWorkspaceIdAndJobId(identity.workspaceId(), identity.jobId())
                .orElseThrow();
        continuation.requireCustody(job, basis);
        var payload = (ObjectNode) basis.getPayload().deepCopy();
        var trace = payload.withArray("tools");
        long room = JobEvidenceFiles.TRACE_BYTE_BUDGET - size(trace) - 1;
        var entry = JSON.objectNode()
                .put("attempt", identity.attempt())
                .put("callId", callId)
                .put("tool", tool)
                .put("destination", decision.destination().name());
        if (decision.draftId() != null) entry.put("draftId", decision.draftId());
        if (decision.published()) entry.put("published", true);
        if (decision.refusal() != null) entry.put("refusal", decision.refusal().name());
        var full = entry.deepCopy();
        full.set("arguments", arguments);
        full.put("outcome", outcome);
        if (decision.proposal() != null) full.set("proposal", decision.proposal());
        if (size(full) <= room) trace.add(full);
        else if (size(entry) <= room) trace.add(entry.put("cut", true));
        else return;
        basis.setPayload(payload);
        bases.save(basis);
    }

    private static long size(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** What this attempt's tool calls decided, in the order they ran. */
    @Transactional
    public List<Decision> decisions(ResultAdmissionService.AdmissionIdentity identity) {
        var job = admission.ownedJob(identity);
        var basis = bases.findByWorkspaceIdAndJobId(identity.workspaceId(), identity.jobId())
                .orElseThrow();
        continuation.requireCustody(job, basis);
        var decisions = new ArrayList<Decision>();
        for (JsonNode entry : basis.getPayload().path("tools")) {
            if (entry.path("attempt").asInt(-1) != identity.attempt() || !entry.hasNonNull("destination")) continue;
            JsonNode proposal = entry.path("proposal");
            decisions.add(new Decision(
                    FeedbackDestination.valueOf(entry.path("destination").asString()),
                    entry.path("draftId").asString(null),
                    proposal.isMissingNode() ? null : proposal,
                    entry.path("published").asBoolean(false),
                    entry.hasNonNull("refusal")
                            ? FeedbackStopReason.valueOf(entry.path("refusal").asString())
                            : null));
        }
        return decisions;
    }
}
