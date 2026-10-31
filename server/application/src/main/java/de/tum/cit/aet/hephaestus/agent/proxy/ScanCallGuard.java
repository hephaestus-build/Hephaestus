package de.tum.cit.aet.hephaestus.agent.proxy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.scan.ScanBudget;
import de.tum.cit.aet.hephaestus.agent.scan.ScanModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnWorkerRole
@RequiredArgsConstructor
public class ScanCallGuard {
    private final ReviewJobRepository jobs;
    private final ReviewSnapshotQuery reviews;
    private final ScanBudget budget;
    private final SandboxGatewayProperties gateway;
    private final ObjectMapper mapper;
    // Active leases and their waiters hold strong references; idle locks can be reclaimed without evicting a live
    // fence.
    private final Cache<Attempt, ReentrantLock> locks =
            Caffeine.newBuilder().weakValues().build();

    public Optional<Lease> acquire(ProxyRouting route, byte[] body) {
        var attempt = route.attempt();
        Long workspace = route.workspaceId();
        if (workspace == null || attempt.sourceType() == UsageSourceType.MENTOR_TURN) return Optional.empty();
        var job = jobs.findByIdAndWorkspaceId(attempt.sourceId(), workspace).orElseThrow(ScanCallGuard::refused);
        var review = reviews.find(workspace, job.getReviewId()).orElseThrow(ScanCallGuard::refused);
        if (review.kind() != ReviewKind.SCAN) return Optional.empty();
        var lock = locks.get(new Attempt(job.getId(), attempt.number()), key -> new ReentrantLock());
        // The attempt's previous call holds the lease; waiting for it counts against the same work deadline.
        Duration remaining = ProxyStreamingUtils.remainingUntil(Objects.requireNonNull(route.workDeadline()));
        try {
            if (!lock.tryLock(remaining.toNanos(), TimeUnit.NANOSECONDS)) throw workTimeOver(null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw workTimeOver(interrupted);
        }
        try {
            job = jobs.findByIdAndWorkspaceId(job.getId(), workspace).orElseThrow(ScanCallGuard::refused);
            if (attempt.leaseId() == null
                    || !jobs.hasLiveLease(job.getId(), workspace, attempt.number(), attempt.leaseId())) throw refused();
            var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), mapper);
            var model = route.practiceScriptSlot() == null || route.practiceScriptSlot() == ModelKind.CHAT
                    ? snapshot.model()
                    : snapshot.practiceScriptSlot(route.practiceScriptSlot());
            if (model == null || model.priceSnapshot() == null) throw refused();
            var cap = reviews.budgetUsd(workspace, job.getReviewId()).orElseThrow(ScanCallGuard::refused);
            var spent = budget.spent(workspace, job.getReviewId());
            BigDecimal inFlight;
            try {
                inFlight = budget.inFlight(job, snapshot);
            } catch (ScanModelUnavailableException unavailable) {
                throw new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "The scan budget or its frozen execution bounds do not permit this call.",
                        unavailable);
            }
            String slot = route.practiceScriptSlot() == null
                    ? "review"
                    : route.practiceScriptSlot().name().toLowerCase(Locale.ROOT);
            var assigned = job.getInput().assignments();
            var pinned = review.practices().stream()
                    .filter(practice -> assigned.stream().anyMatch(id -> id.practiceId() == practice.practiceId()))
                    .findFirst()
                    .orElseThrow(ScanCallGuard::refused);
            var options = pinned.method().models().stream()
                    .filter(bound -> bound.slot().equals(slot))
                    .map(bound -> bound.model())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow(ScanCallGuard::refused)
                    .options();
            var context = options.get("contextWindow");
            var maxOutput = options.get("maxOutputTokens");
            long maximumInput = context == null ? 0 : context.asLong();
            long maximumOutput = maxOutput == null ? maximumInput : maxOutput.asLong();
            if (maximumOutput <= 0
                    || maximumInput <= 0
                    || body.length > Math.min(gateway.maxRequestBytes(), maximumInput)) throw refused();
            var request = mapper.readTree(body);
            requireTextOnly(request);
            long output = requestedOutput(request, maximumOutput);
            BigDecimal next =
                    ScanBudget.cost(model.priceSnapshot(), body.length, output).setScale(6, RoundingMode.CEILING);
            if (!spent.priced() || spent.cost().add(inFlight).add(next).compareTo(cap) > 0) throw refused();
            if (route.practiceScriptSlot() == null
                    && job.getLlmCalls()
                            >= budget.callCap(job.getInput().assignments().size())) throw refused();
            return Optional.of(new Lease(lock));
        } catch (RuntimeException | Error failure) {
            lock.unlock();
            throw failure;
        }
    }

    private static long requestedOutput(JsonNode request, long limit) {
        long requested = limit;
        for (String field : List.of("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            if (!request.has(field)) continue;
            var value = request.path(field);
            if (!value.isIntegralNumber() || value.asLong() <= 0 || value.asLong() > limit) throw refused();
            requested = Math.min(requested, value.asLong());
        }
        var generation = request.path("generationConfig");
        if (generation.has("maxOutputTokens")) {
            var value = generation.path("maxOutputTokens");
            if (!value.isIntegralNumber() || value.asLong() <= 0 || value.asLong() > limit) throw refused();
            requested = Math.min(requested, value.asLong());
        }
        return requested;
    }

    private static void requireTextOnly(JsonNode value) {
        if (value.isObject()) {
            String type = value.path("type").asString("");
            if (Set.of("image", "image_url", "input_image", "input_audio", "input_file")
                            .contains(type)
                    || value.has("inlineData")
                    || value.has("fileData")) throw refused();
        }
        if ((value.isObject() || value.isArray())) for (var child : value) requireTextOnly(child);
    }

    private static ResponseStatusException workTimeOver(@Nullable Throwable cause) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, LlmProxyService.WORK_TIME_OVER, cause);
    }

    private static ResponseStatusException refused() {
        return new ResponseStatusException(
                HttpStatus.PAYMENT_REQUIRED, "The scan budget or its frozen execution bounds do not permit this call.");
    }

    private record Attempt(UUID jobId, int number) {}

    public static final class Lease implements AutoCloseable {
        private final ReentrantLock lock;

        private Lease(ReentrantLock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }
}
