package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Moves an issue for a request that shows the result at once, through the outbox ({@link TransferIssueAdapter}). */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class IssueTransfers {
    private final ActionExecutor executor;
    private final ObjectMapper mapper;

    /** The attempt's result; empty when another GitHub write of the report runs now. */
    Optional<ActionResult> move(TransferIssueAdapter.Payload payload) {
        try {
            return executor.runNow(TransferIssueAdapter.request(mapper, payload));
        } catch (DataIntegrityViolationException running) {
            // The report's lane holds one running action at a time (ux_agent_action_lane_executing).
            return Optional.empty();
        }
    }
}
