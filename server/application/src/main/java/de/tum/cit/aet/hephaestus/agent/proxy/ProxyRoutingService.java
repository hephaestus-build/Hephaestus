package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnWorkerRole
class ProxyRoutingService {
    private final ReviewJobRepository agentJobRepository;
    private final MentorTurnRepository mentorTurns;
    private final ObjectMapper objectMapper;
    private final LlmUsageEventRepository usage;
    private final ReviewSnapshotQuery reviews;
    private final AgentRuns runs;

    ProxyRoutingService(
            ReviewJobRepository jobs,
            MentorTurnRepository mentorTurns,
            ObjectMapper mapper,
            LlmUsageEventRepository usage,
            ReviewSnapshotQuery reviews,
            AgentRuns runs) {
        this.agentJobRepository = jobs;
        this.runs = runs;
        this.mentorTurns = mentorTurns;
        this.objectMapper = mapper;
        this.usage = usage;
        this.reviews = reviews;
    }

    /**
     * The band of the call, for its pool: a conversation turn is interactive, a review's composition is batch, and any
     * other review call takes the band of the review's origin.
     *
     * @throws ResponseStatusException 409 when the call's review no longer exists
     */
    AgentRunBand band(ProxyRouting routing) {
        if (routing.hephTurn()) return AgentRunBand.INTERACTIVE;
        var job = agentJobRepository.findById(routing.sourceId()).orElseThrow(ProxyRoutingService::attemptEnded);
        if (ResultAdmissionService.isAdmitted(job)) return AgentRunBand.BATCH;
        return reviews.find(job.getWorkspaceId(), job.getReviewId())
                .map(review -> ReviewRunKind.band(review.origin()))
                .orElseThrow(ProxyRoutingService::attemptEnded);
    }

    boolean ownsAttempt(ProxyRouting routing) {
        var attempt = routing.attempt();
        UUID lease = attempt.runId();
        Long workspace = routing.workspaceId();
        if (lease == null || workspace == null) return false;
        return routing.hephTurn()
                ? mentorTurns.hasLiveLease(attempt.sourceId(), workspace, attempt.number(), lease)
                : runs.isCurrent(new AgentRunRef(lease, workspace), ReviewRunKind.epoch(attempt.number()));
    }

    ProxyRouting resolve(ProxyIdentity identity, HttpServletRequest request) {
        if (identity.turn() != null) {
            if (request.getRequestURI().startsWith(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PATH))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Review job credential required");
            return turnRouting(identity.turn());
        }
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
        if (!request.getRequestURI().startsWith(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PATH)) {
            return reviewRouting(job, caller.runId(), snapshot, workDeadline);
        }
        var slot = practiceScriptSlot(request.getRequestURI());
        if (slot == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        String practice = request.getHeader(JobTokenAuthenticationFilter.PRACTICE_SCRIPT_PRACTICE_HEADER);
        return practiceScriptRouting(job, caller.runId(), snapshot, slot, practice, workDeadline)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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
        var turn = mentorTurns
                .findByIdAndWorkspaceId(caller.id(), caller.workspaceId())
                .filter(found ->
                        mentorTurns.hasLiveLease(caller.id(), caller.workspaceId(), caller.epoch(), caller.runId()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "This turn attempt no longer holds its lease"));
        var admitted = MentorTurnAdmission.read(turn.getAdmission())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This turn attempt has no model"));
        var route = admitted.route();
        return new ProxyRouting(
                "mentor-turn:" + turn.getId(),
                route.apiProtocol(),
                route.baseUrl(),
                route.connectionScope(),
                route.connectionId(),
                route.modelId(),
                turn.getWorkspaceId(),
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.MENTOR_TURN, turn.getId(), turn.getAttempt(), caller.runId()),
                null,
                null,
                null,
                ProxyRouting.outputLimitOf(admitted.contextWindow(), admitted.maxOutputTokens()));
    }

    private ProxyRouting reviewRouting(ReviewJob job, UUID runId, ConfigSnapshot snapshot, Instant workDeadline) {
        return routing(
                "job:" + job.getId(),
                job,
                runId,
                snapshot.model(),
                UsageSourceType.REVIEW_JOB,
                0L,
                0L,
                null,
                workDeadline,
                ProxyRouting.outputLimitOf(snapshot.contextWindow(), snapshot.maxOutputTokens()));
    }

    private Optional<ProxyRouting> practiceScriptRouting(
            ReviewJob job,
            UUID runId,
            ConfigSnapshot snapshot,
            ModelKind slot,
            @Nullable String practiceSlug,
            Instant workDeadline) {
        FrozenModel model = slot == ModelKind.CHAT ? snapshot.model() : snapshot.practiceScriptSlot(slot);
        if (model == null) {
            return Optional.empty();
        }
        var used = usage.sumDirectModelUsage(job.getWorkspaceId(), runId, ReviewRunKind.epoch(job.getAttempt()));
        Long outputLimit = switch (slot) {
            case CHAT -> ProxyRouting.outputLimitOf(snapshot.contextWindow(), snapshot.maxOutputTokens());
            case DECISION -> pinnedOutputLimit(job, slot, practiceSlug);
            case EMBEDDING, RERANKING -> null;
        };
        return Optional.of(routing(
                "job:" + job.getId() + ":practice-script",
                job,
                runId,
                model,
                UsageSourceType.ofReviewCall(slot),
                used.getTokens(),
                used.getSearchUnits(),
                slot,
                workDeadline,
                outputLimit));
    }

    /**
     * The output limit of a practice script model that the config snapshot does not describe, as the method of the
     * calling practice pinned it with the model's options. Claim admits the job only while the pinned options equal the
     * model's.
     *
     * @param practiceSlug the practice that the call names; when it names none, the first assigned practice
     */
    private @Nullable Long pinnedOutputLimit(ReviewJob job, ModelKind slot, @Nullable String practiceSlug) {
        var assigned = job.getInput().assignments();
        Long calling = practiceSlug == null
                ? null
                : agentJobRepository
                        .findPracticeId(job.getWorkspaceId(), practiceSlug)
                        .orElse(null);
        String name = slot.name().toLowerCase(Locale.ROOT);
        Map<String, JsonNode> options = reviews.find(job.getWorkspaceId(), job.getReviewId()).stream()
                .flatMap(review -> review.practices().stream())
                .filter(practice -> calling == null
                        ? assigned.stream().anyMatch(id -> id.practiceId() == practice.practiceId())
                        : practice.practiceId() == calling)
                .findFirst()
                .flatMap(practice -> practice.method().models().stream()
                        .filter(bound -> bound.slot().equals(name))
                        .map(BoundModelSlot::model)
                        .filter(Objects::nonNull)
                        .findFirst())
                .map(model -> model.options())
                .orElse(Map.of());
        return ProxyRouting.outputLimitOf(tokens(options.get("contextWindow")), tokens(options.get("maxOutputTokens")));
    }

    private static @Nullable Integer tokens(@Nullable JsonNode option) {
        return option != null && option.isIntegralNumber() && option.canConvertToInt() ? option.asInt() : null;
    }

    private static ProxyRouting routing(
            String principal,
            ReviewJob job,
            UUID runId,
            FrozenModel model,
            UsageSourceType sourceType,
            long directModelTokens,
            long directModelSearchUnits,
            @Nullable ModelKind slot,
            Instant workDeadline,
            @Nullable Long outputLimit) {
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
                        runId,
                        directModelTokens,
                        directModelSearchUnits,
                        job.getWorkspaceId()),
                slot,
                slot == null ? null : model.dataHandlingTier(),
                workDeadline,
                outputLimit);
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
}
