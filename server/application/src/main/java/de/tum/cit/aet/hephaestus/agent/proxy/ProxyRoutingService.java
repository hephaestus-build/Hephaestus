package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.conversation.ConversationScopeFence;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationTurnAccess;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import java.io.Serial;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnAgentRuntime
class ProxyRoutingService {
    private final ReviewJobRepository agentJobRepository;
    private final ConversationTurnAccess conversationTurns;
    private final ConversationScopeFence scopeFence;
    private final ObjectMapper objectMapper;
    private final ReviewSnapshotQuery reviews;
    private final AgentRuns runs;
    private final ProductFeedbackDraftCalls drafts;
    private final LlmModelResolver models;
    private final AgentRunAuthority authority;

    ProxyRoutingService(
            ReviewJobRepository jobs,
            ConversationTurnAccess conversationTurns,
            ConversationScopeFence scopeFence,
            ObjectMapper mapper,
            ReviewSnapshotQuery reviews,
            AgentRuns runs,
            ProductFeedbackDraftCalls drafts,
            LlmModelResolver models,
            AgentRunAuthority authority) {
        this.models = models;
        this.authority = authority;
        this.drafts = drafts;
        this.agentJobRepository = jobs;
        this.runs = runs;
        this.conversationTurns = conversationTurns;
        this.scopeFence = scopeFence;
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
        Long workspace = routing.workspaceId();
        if (workspace == null) throw attemptEnded();
        var job = agentJobRepository
                .findCall(routing.sourceId(), workspace)
                .orElseThrow(ProxyRoutingService::attemptEnded);
        if (job.admitted()) return AgentRunBand.BATCH;
        return reviews.find(job.workspaceId(), job.reviewId())
                .map(review -> ReviewRunKind.band(review.origin()))
                .orElseThrow(ProxyRoutingService::attemptEnded);
    }

    boolean ownsAttempt(ProxyRouting routing) {
        var attempt = routing.attempt();
        if (attempt.sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT)
            return attempt.runId() != null
                    && runs.isCurrent(new AgentRunRef(attempt.runId(), AgentRunRef.INSTANCE_SCOPE), attempt.epoch())
                    && drafts.waits(attempt.sourceId(), attempt.number());
        if (attempt.sourceType() == UsageSourceType.PROBE)
            return routing.workDeadline() != null && Instant.now().isBefore(routing.workDeadline());
        UUID lease = attempt.runId();
        Long workspace = routing.workspaceId();
        if (lease == null || workspace == null) return false;
        return routing.conversationTurn()
                ? conversationTurns.hasLiveLease(workspace, attempt.sourceId(), attempt.number(), lease)
                : runs.isCurrent(new AgentRunRef(lease, workspace), ReviewRunKind.epoch(attempt.number()));
    }

    /**
     * The authorization recheck at model dispatch (ADR 0134): the proxy's refusal when the run's authority narrowed
     * since the work was requested, and the run ended, or when the claim no longer holds the run. {@code null} while
     * both hold. A capability probe serves no one's request, and a report draft is instance work that carries no
     * workspace authority. A call without a run or workspace never owns its attempt, so {@link #ownsAttempt} has
     * refused it.
     */
    @Nullable
    ProxyRefusal dispatchRefusal(ProxyRouting routing) {
        var attempt = routing.attempt();
        if (attempt.sourceType() == UsageSourceType.PROBE
                || attempt.sourceType() == UsageSourceType.PRODUCT_FEEDBACK_DRAFT) return null;
        UUID run = attempt.runId();
        Long workspace = routing.workspaceId();
        if (run == null || workspace == null) return null;
        try {
            authority.require(new AgentRunFence(run, workspace, attempt.epoch()), AuthorityBoundary.MODEL_DISPATCH);
            return null;
        } catch (AuthorityRefusedException narrowed) {
            return ProxyRefusal.AUTHORITY_CHANGED;
        } catch (StaleRunException ended) {
            return ProxyRefusal.ATTEMPT_ENDED;
        }
    }

    void sendConversationRequest(ProxyRouting routing, Runnable handoff) {
        var attempt = routing.attempt();
        long workspaceId = Objects.requireNonNull(routing.workspaceId());
        UUID runId = Objects.requireNonNull(attempt.runId());
        var scope = conversationTurns
                .runningScope(workspaceId, attempt.sourceId(), attempt.epoch(), runId)
                .orElseThrow(ModelRequestRefused::new);
        if (!scopeFence.admit(scope, () -> ownsAttempt(routing), handoff)) throw new ModelRequestRefused();
    }

    static final class ModelRequestRefused extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        ModelRequestRefused() {}

        ModelRequestRefused(Throwable cause) {
            super(cause);
        }
    }

    ProxyRouting resolve(ProxyIdentity identity) {
        var draft = identity.draft();
        if (draft != null) return draftRouting(draft);
        var turn = identity.turn();
        if (turn != null) return turnRouting(turn);
        var caller = identity.job();
        if (caller == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Model credential required");
        var job = agentJobRepository
                .findCall(caller.id(), caller.workspaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Review attempt unavailable"));
        if (!job.runId().equals(caller.runId())
                || !runs.isCurrent(new AgentRunRef(caller.runId(), caller.workspaceId()), caller.epoch()))
            throw attemptEnded();
        var snapshot = ConfigSnapshot.fromJson(job.configSnapshot(), objectMapper);
        // The live checks above decide whether this attempt may call at all; its work deadline decides until when.
        Instant workDeadline = Objects.requireNonNull(caller.workDeadline(), "a review attempt has a work deadline");
        return reviewRouting(job, caller.runId(), snapshot, workDeadline);
    }

    private ProxyRouting draftRouting(ProxyAttempt caller) {
        int generation = Objects.requireNonNull(caller.draftGeneration());
        var draft = drafts.waiting(caller.id(), generation).orElseThrow(ProxyRoutingService::attemptEnded);
        var model = models.resolveInstanceModel(draft.llmModelId());
        if (model == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The report model is unavailable");
        var ref = model.ref();
        return new ProxyRouting(
                "report-draft:" + caller.runId(),
                model.protocol().wire(),
                model.credential().baseUrl(),
                ref.scope(),
                ref.connectionId(),
                ref.modelId(),
                null,
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.PRODUCT_FEEDBACK_DRAFT,
                        caller.id(),
                        generation,
                        caller.runId(),
                        null,
                        caller.epoch()),
                caller.workDeadline(),
                ProxyRouting.draftOutputLimit(model, draft.maxOutputTokens()));
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
                .find(caller.workspaceId(), caller.id())
                .filter(found -> conversationTurns.hasLiveLease(
                        caller.workspaceId(), caller.id(), caller.epoch(), caller.runId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This turn attempt no longer holds its lease"));
        var admitted = ConversationTurnAdmission.read(turn.admission())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This turn attempt has no model"));
        var route = admitted.route();
        return new ProxyRouting(
                "conversation-turn:" + turn.id(),
                route.apiProtocol(),
                route.baseUrl(),
                route.connectionScope(),
                route.connectionId(),
                route.modelId(),
                caller.workspaceId(),
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.CONVERSATION_TURN, turn.id(), turn.attempt(), caller.runId()),
                null,
                ProxyRouting.outputLimitOf(admitted.contextWindow(), admitted.maxOutputTokens()));
    }

    private static ProxyRouting reviewRouting(
            ReviewJobRepository.ReviewJobCall job, UUID runId, ConfigSnapshot snapshot, Instant workDeadline) {
        FrozenModel model = snapshot.model();
        return new ProxyRouting(
                "job:" + job.id(),
                model.apiProtocol().wire(),
                model.baseUrl(),
                model.connectionScope(),
                model.connectionId(),
                model.modelId(),
                job.workspaceId(),
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.REVIEW_JOB, job.id(), job.attempt(), runId, job.workspaceId()),
                workDeadline,
                ProxyRouting.outputLimitOf(snapshot.contextWindow(), snapshot.maxOutputTokens()));
    }
}
