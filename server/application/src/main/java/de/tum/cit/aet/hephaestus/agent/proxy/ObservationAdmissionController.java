package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.handler.ObservationAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.signal.PracticeReviewRefusalMetrics;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/internal/llm")
@Hidden
@PreAuthorize("isAuthenticated()")
public class ObservationAdmissionController {

    private static final Logger log = LoggerFactory.getLogger(ObservationAdmissionController.class);

    private final ObservationAdmissionService admission;
    private final PracticeReviewRefusalMetrics refusals;

    public ObservationAdmissionController(
            ObservationAdmissionService admission, PracticeReviewRefusalMetrics refusals) {
        this.admission = admission;
        this.refusals = refusals;
    }

    @PostMapping("/admit-observations")
    @WorkspaceAgnostic("Authenticated sandbox token carries and constrains workspace route")
    public ObjectNode admit(@RequestBody JsonNode request, Authentication authentication) {
        if (request.path("schemaVersion").asInt(-1) != 1
                || !request.path("observations").isArray()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Expected schemaVersion 1 and observations array");
        }
        if (!(authentication.getPrincipal() instanceof ProxyRouting routing)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent-job credential required");
        }
        UUID jobId = routing.sourceId();
        if (jobId == null
                || routing.attempt() == null
                || routing.attempt().sourceType() != LlmUsageSourceType.AGENT_JOB) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent-job credential required");
        }
        try {
            return admission.admit(jobId, request.path("observations"));
        } catch (ObservationAdmissionService.AdmissionConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Observations differ from the admitted payload", e);
        } catch (ObservationsRefusedException e) {
            // A 5xx would retry a policy or evidence refusal and misreport it as an internal error.
            refusals.recordExecutionRefusal(e.reasonCode());
            admission.recordRefusal(jobId, e.reasonCode(), e.reason());
            log.info("Refused this review's observations ({}): jobId={}, {}", e.reasonCode(), jobId, e.reason());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.reason(), e);
        }
    }
}
