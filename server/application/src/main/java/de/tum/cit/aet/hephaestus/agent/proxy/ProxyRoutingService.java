package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptJwt;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.FrozenCharge;
import de.tum.cit.aet.hephaestus.agent.job.PracticeScriptKindTotal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ScriptUsage;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnWorkerRole
class ProxyRoutingService {
    private final ReviewJobRepository agentJobRepository;
    private final MentorTurnRepository mentorTurns;
    private final ObjectMapper objectMapper;
    private final ReviewAttemptUsageService attemptUsage;
    private final ReviewSnapshotQuery reviews;

    ProxyRoutingService(
            ReviewJobRepository jobs,
            MentorTurnRepository mentorTurns,
            ObjectMapper mapper,
            ReviewAttemptUsageService attemptUsage,
            ReviewSnapshotQuery reviews) {
        this.agentJobRepository = jobs;
        this.mentorTurns = mentorTurns;
        this.objectMapper = mapper;
        this.attemptUsage = attemptUsage;
        this.reviews = reviews;
    }

    /**
     * Whose call this is, for its pool: a conversation turn, or the origin of the review that makes it.
     *
     * @throws ResponseStatusException 409 when the call's review no longer exists
     */
    LlmCallClass callClass(ProxyRouting routing) {
        if (routing.hephTurn()) return LlmCallClass.TURN;
        var job = agentJobRepository.findById(routing.sourceId()).orElseThrow(ProxyRoutingService::attemptEnded);
        if (ResultAdmissionService.isAdmitted(job)) return LlmCallClass.COMPOSITION;
        return reviews.find(job.getWorkspaceId(), job.getReviewId())
                .map(review -> LlmCallClass.of(review.origin()))
                .orElseThrow(ProxyRoutingService::attemptEnded);
    }

    boolean ownsAttempt(ProxyRouting routing) {
        var attempt = routing.attempt();
        UUID lease = attempt.leaseId();
        Long workspace = routing.workspaceId();
        if (lease == null || workspace == null) return false;
        return routing.hephTurn()
                ? mentorTurns.hasLiveLease(attempt.sourceId(), workspace, attempt.number(), lease)
                : agentJobRepository.hasLiveLease(attempt.sourceId(), workspace, attempt.number(), lease);
    }

    ProxyRouting resolve(ProxyIdentity identity, HttpServletRequest request) {
        if (identity.turn() != null) {
            if (request.getRequestURI().startsWith(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PATH))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Review job credential required");
            return turnRouting(identity.turn());
        }
        var jwt = identity.job();
        if (jwt == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Model credential required");
        var job = agentJobRepository
                .findById(jwt.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Review attempt unavailable"));
        if (job.getWorkspaceId() != jwt.workspaceId())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Review attempt unavailable");
        if (!agentJobRepository.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.attempt(), jwt.leaseId()))
            throw attemptEnded();
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        // The live checks above decide whether this attempt may call at all; the signed deadline decides until when.
        Instant workDeadline = Objects.requireNonNull(jwt.workDeadline(), "a review attempt has a work deadline");
        if (!request.getRequestURI().startsWith(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PATH)) {
            register(job, jwt.leaseId(), ModelKind.CHAT, snapshot.model());
            return reviewRouting(job, jwt.leaseId(), snapshot, workDeadline);
        }
        var slot = practiceScriptSlot(request.getRequestURI());
        if (slot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        ProxyRouting routing = practiceScriptRouting(job, jwt.leaseId(), snapshot, slot, workDeadline)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        FrozenModel model = slot == ModelKind.CHAT ? snapshot.model() : snapshot.practiceScriptSlot(slot);
        register(job, jwt.leaseId(), slot, model);
        return routing;
    }

    /**
     * The routing of a chat call that the worker makes for a review attempt it runs: the same live checks as a
     * sandbox's credential, with the worker's own deadline. Anything else, and the call does not start.
     *
     * @throws ResponseStatusException 401 when the attempt no longer holds this lease
     */
    ProxyRouting attemptRouting(UUID jobId, long workspaceId, int attempt, UUID leaseId, Instant workDeadline) {
        var job = agentJobRepository
                .findById(jobId)
                .filter(found -> agentJobRepository.hasLiveLease(jobId, workspaceId, attempt, leaseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Review attempt unavailable"));
        var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
        register(job, leaseId, ModelKind.CHAT, snapshot.model());
        return reviewRouting(job, leaseId, snapshot, workDeadline);
    }

    /**
     * Registers the call on its attempt before anything is forwarded, and freezes what the call's model costs. A
     * later claim freezes a new configuration onto the row, so a call that returns after a hand-off is priced from
     * this registration. The attempt must still run on this worker; otherwise the call does not start.
     */
    private void register(ReviewJob job, UUID leaseId, ModelKind kind, @Nullable FrozenModel model) {
        boolean running = attemptUsage.registerCall(
                job.getId(),
                job.getWorkspaceId(),
                job.getAttempt(),
                leaseId,
                kind,
                model == null ? FrozenCharge.UNKNOWN : FrozenCharge.of(model));
        if (!running) throw attemptEnded();
    }

    /** The refusal that the authentication filter also gives an attempt that no longer runs, so its runner stops. */
    static ResponseStatusException attemptEnded() {
        return new ResponseStatusException(HttpStatus.CONFLICT, JobTokenAuthenticationFilter.ATTEMPT_ENDED);
    }

    /**
     * The routing of a conversation turn attempt while this worker holds its lease: the route frozen at admission, and what the
     * attempt has spent so far, priced at the admitted rates. An attempt that ended or moved on gets {@code 409}, so its
     * host stops.
     */
    private ProxyRouting turnRouting(AttemptJwt jwt) {
        var turn = mentorTurns
                .findByIdAndWorkspaceId(jwt.id(), jwt.workspaceId())
                .filter(found -> mentorTurns.hasLiveLease(jwt.id(), jwt.workspaceId(), jwt.attempt(), jwt.leaseId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This turn attempt no longer holds its lease"));
        var admitted = MentorTurnAdmission.read(turn.getAdmission())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This turn attempt has no model"));
        var route = admitted.route();
        BigDecimal spent = admitted.price()
                .calculateCost(
                        turn.getInputTokens(),
                        turn.getOutputTokens(),
                        turn.getCachedTokens(),
                        turn.getCacheWriteTokens())
                .usd();
        return new ProxyRouting(
                "mentor-turn:" + turn.getId(),
                route.apiProtocol(),
                route.baseUrl(),
                route.connectionScope(),
                route.connectionId(),
                route.modelId(),
                turn.getWorkspaceId(),
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.MENTOR_TURN,
                        turn.getId(),
                        turn.getAttempt(),
                        spent != null ? spent : BigDecimal.ZERO,
                        jwt.leaseId()),
                null,
                null,
                null);
    }

    private ProxyRouting reviewRouting(ReviewJob job, UUID leaseId, ConfigSnapshot snapshot, Instant workDeadline) {
        return routing(
                "job:" + job.getId(),
                job,
                leaseId,
                snapshot.model(),
                UsageSourceType.REVIEW_JOB,
                spentSoFarUsd(job, snapshot),
                0L,
                0L,
                null,
                workDeadline);
    }

    // Include all direct models' in-flight spend until their ledger rows exist; only the same funding source counts.
    private Optional<ProxyRouting> practiceScriptRouting(
            ReviewJob job, UUID leaseId, ConfigSnapshot snapshot, ModelKind slot, Instant workDeadline) {
        FrozenModel model = slot == ModelKind.CHAT ? snapshot.model() : snapshot.practiceScriptSlot(slot);
        if (model == null) {
            return Optional.empty();
        }
        List<PracticeScriptKindTotal> used =
                agentJobRepository.sumPracticeScriptUsageByKind(job.getWorkspaceId(), job.getId(), job.getAttempt());
        return Optional.of(routing(
                "job:" + job.getId() + ":practice-script",
                job,
                leaseId,
                model,
                ScriptUsage.ledgerSourceType(slot),
                practiceScriptSpentUsd(job, snapshot, used, model.connectionScope()),
                used.stream().mapToLong(PracticeScriptKindTotal::tokens).sum(),
                used.stream().mapToLong(PracticeScriptKindTotal::searchUnits).sum(),
                slot,
                workDeadline));
    }

    private static ProxyRouting routing(
            String principal,
            ReviewJob job,
            UUID leaseId,
            FrozenModel model,
            UsageSourceType sourceType,
            BigDecimal spentUsd,
            long directModelTokens,
            long directModelSearchUnits,
            @Nullable ModelKind slot,
            Instant workDeadline) {
        return new ProxyRouting(
                principal,
                model.apiProtocol().wire(),
                model.baseUrl(),
                model.connectionScope(),
                model.connectionId(),
                model.modelId(),
                job.getWorkspaceId(),
                new ProxyRouting.BilledAttempt(
                        sourceType,
                        job.getId(),
                        job.getAttempt(),
                        spentUsd,
                        leaseId,
                        directModelTokens,
                        directModelSearchUnits,
                        job.getWorkspaceId()),
                slot,
                slot == null ? null : model.dataHandlingTier(),
                workDeadline);
    }

    private static BigDecimal practiceScriptSpentUsd(
            ReviewJob job,
            ConfigSnapshot snapshot,
            List<PracticeScriptKindTotal> used,
            @Nullable UsageFundingSource purse) {
        BigDecimal spent = Objects.equals(snapshot.model().connectionScope(), purse)
                ? spentSoFarUsd(job, snapshot)
                : BigDecimal.ZERO;
        for (PracticeScriptKindTotal total : used) {
            FrozenModel model = snapshot.practiceScriptSlot(total.modelKind());
            LlmPriceSnapshot price = model == null ? null : model.priceSnapshot();
            if (model == null || price == null || !Objects.equals(model.connectionScope(), purse)) {
                continue;
            }
            BigDecimal cost = price.calculateCost(
                            total.inputTokens(),
                            total.outputTokens(),
                            total.cacheReadTokens(),
                            total.cacheWriteTokens())
                    .usd();
            if (cost != null) {
                spent = spent.add(cost);
            }
        }
        return spent;
    }

    static @Nullable ModelKind practiceScriptSlot(String path) {
        String rest = path.substring(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PATH.length());
        int end = rest.indexOf('/');
        if (end <= 0) {
            return null;
        }
        String slot = rest.substring(0, end);
        return Arrays.stream(ModelKind.values())
                .filter(kind -> kind.name().toLowerCase(Locale.ROOT).equals(slot))
                .findFirst()
                .orElse(null);
    }

    // Budget gates use frozen rates; reasoning tokens are already included in output.
    private static BigDecimal spentSoFarUsd(ReviewJob job, ConfigSnapshot snapshot) {
        LlmPriceSnapshot price = snapshot.model().priceSnapshot();
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal cost = price.calculateCost(
                        job.getLlmInputTokens(),
                        job.getLlmOutputTokens(),
                        job.getLlmCacheReadTokens(),
                        job.getLlmCacheWriteTokens())
                .usd();
        return cost != null ? cost : BigDecimal.ZERO;
    }
}
