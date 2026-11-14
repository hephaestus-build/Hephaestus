package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnAgentRuntime
class ProxyRoutingService {
    private final ReviewJobRepository agentJobRepository;
    private final ConversationTurnRepository conversationTurns;
    private final ObjectMapper objectMapper;
    private final ReviewSnapshotQuery reviews;
    private final AgentRuns runs;
    private final ProductFeedbackDraftCalls drafts;

    ProxyRoutingService(
            ReviewJobRepository jobs,
            ConversationTurnRepository conversationTurns,
            ObjectMapper mapper,
            ReviewSnapshotQuery reviews,
            AgentRuns runs,
            ProductFeedbackDraftCalls drafts) {
        this.drafts = drafts;
        this.agentJobRepository = jobs;
        this.runs = runs;
        this.conversationTurns = conversationTurns;
        this.objectMapper = mapper;
        this.reviews = reviews;
    }

    /**
     * The band of the call, for its pool: a conversation turn is interactive, a review's composition is batch, and any
     * other review call takes the band of the review's origin.
     *
     * @throws ResponseStatusException 409 when the call's review no longer exists
     */
    AgentRunBand band(ProxyRouting routing) {
        if (routing.attempt().sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT
                || routing.attempt().sourceType() == UsageSourceType.PROBE) return AgentRunBand.BACKGROUND;
        if (routing.conversationTurn()) {
            var attempt = routing.attempt();
            if (attempt.runId() == null || routing.workspaceId() == null) throw attemptEnded();
            return runs.find(new AgentRunRef(attempt.runId(), routing.workspaceId()))
                    .map(AgentRun::getBand)
                    .orElseThrow(ProxyRoutingService::attemptEnded);
        }
        var job = agentJobRepository.findById(routing.sourceId()).orElseThrow(ProxyRoutingService::attemptEnded);
        if (ResultAdmissionService.isAdmitted(job)) return AgentRunBand.BATCH;
        return reviews.find(job.getWorkspaceId(), job.getReviewId())
                .map(review -> ReviewRunKind.band(review.origin()))
                .orElseThrow(ProxyRoutingService::attemptEnded);
    }

    boolean ownsAttempt(ProxyRouting routing) {
        var attempt = routing.attempt();
        if (attempt.sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT)
            return drafts.waits(attempt.sourceId(), attempt.number());
        if (attempt.sourceType() == UsageSourceType.PROBE)
            return routing.workDeadline() != null && Instant.now().isBefore(routing.workDeadline());
        UUID lease = attempt.runId();
        Long workspace = routing.workspaceId();
        if (lease == null || workspace == null) return false;
        return routing.conversationTurn()
                ? conversationTurns.hasLiveLease(attempt.sourceId(), workspace, attempt.number(), lease)
                : runs.isCurrent(new AgentRunRef(lease, workspace), ReviewRunKind.epoch(attempt.number()));
    }

    ProxyRouting resolve(ProxyIdentity identity) {
        if (identity.turn() != null) return turnRouting(identity.turn());
        var caller = identity.job();
        if (caller == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Model credential required");
        var job = agentJobRepository
                .findById(caller.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Review attempt unavailable"));
        if (job.getWorkspaceId() != caller.workspaceId())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Review attempt unavailable");
        if (!job.getRunId().equals(caller.runId())
                || !runs.isCurrent(new AgentRunRef(caller.runId(), caller.workspaceId()), caller.epoch()))
            throw attemptEnded();
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        // The live checks above decide whether this attempt may call at all; its work deadline decides until when.
        Instant workDeadline = Objects.requireNonNull(caller.workDeadline(), "a review attempt has a work deadline");
        return reviewRouting(job, caller.runId(), snapshot, workDeadline);
    }

    /** The refusal that the authentication filter also gives an attempt that no longer runs, so its runner stops. */
    static ResponseStatusException attemptEnded() {
        return new ResponseStatusException(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED);
    }

    /**
     * The routing of a conversation turn attempt while this worker holds its lease: the route frozen at admission. An
     * attempt that ended or moved on gets {@code 409}, so its host stops.
     */
    private ProxyRouting turnRouting(ProxyAttempt caller) {
        var turn = conversationTurns
                .findByIdAndWorkspaceId(caller.id(), caller.workspaceId())
                .filter(found -> conversationTurns.hasLiveLease(
                        caller.id(), caller.workspaceId(), caller.epoch(), caller.runId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This turn attempt no longer holds its lease"));
        var admitted = ConversationTurnAdmission.read(turn.getAdmission())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This turn attempt has no model"));
        var route = admitted.route();
        return new ProxyRouting(
                "conversation-turn:" + turn.getId(),
                route.apiProtocol(),
                route.baseUrl(),
                route.connectionScope(),
                route.connectionId(),
                route.modelId(),
                turn.getWorkspaceId(),
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.CONVERSATION_TURN, turn.getId(), turn.getAttempt(), caller.runId()),
                null,
                ProxyRouting.outputLimitOf(admitted.contextWindow(), admitted.maxOutputTokens()));
    }

    private static ProxyRouting reviewRouting(
            ReviewJob job, UUID runId, ConfigSnapshot snapshot, Instant workDeadline) {
        FrozenModel model = snapshot.model();
        return new ProxyRouting(
                "job:" + job.getId(),
                model.apiProtocol().wire(),
                model.baseUrl(),
                model.connectionScope(),
                model.connectionId(),
                model.modelId(),
                job.getWorkspaceId(),
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.REVIEW_JOB, job.getId(), job.getAttempt(), runId, job.getWorkspaceId()),
                workDeadline,
                ProxyRouting.outputLimitOf(snapshot.contextWindow(), snapshot.maxOutputTokens()));
    }
}
