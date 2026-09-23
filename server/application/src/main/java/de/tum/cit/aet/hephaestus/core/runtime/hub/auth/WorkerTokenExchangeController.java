package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workers")
@ConditionalOnProperty(name = RuntimeRole.SERVER_PROPERTY, havingValue = "true", matchIfMissing = true)
@Hidden
@WorkspaceAgnostic("Fleet-wide worker registration; not workspace-scoped")
public class WorkerTokenExchangeController {

    private static final Logger log = LoggerFactory.getLogger(WorkerTokenExchangeController.class);
    private static final int MAX_FAILURES_PER_IP_PER_MINUTE = 10;

    private final WorkerJwtIssuer issuer;
    private final WorkerTokenProperties properties;
    private final MeterRegistry meterRegistry;
    /**
     * Per-source-IP failure counter: caps brute-force attempts at {@value MAX_FAILURES_PER_IP_PER_MINUTE}
     * per minute. The registration token is high-entropy enough to make exhaustive search
     * impractical, but the counter bounds noise and makes auth-failure alerting tractable.
     */
    private final Cache<String, AtomicInteger> failuresByIp = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();

    public WorkerTokenExchangeController(
            WorkerJwtIssuer issuer, WorkerTokenProperties properties, MeterRegistry meterRegistry) {
        this.issuer = issuer;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/exchange")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> exchange(@Valid @RequestBody ExchangeRequest request, HttpServletRequest http) {
        if (!properties.isExchangeEnabled()) {
            log.warn("worker token exchange attempted but no registration token is configured");
            meterRegistry
                    .counter(CoreMetrics.WORKER_TOKEN_EXCHANGE, "outcome", "failed", "reason", "disabled")
                    .increment();
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String sourceIp = resolveSourceIp(http);
        AtomicInteger failures = failuresByIp.get(sourceIp, k -> new AtomicInteger(0));
        if (failures.get() >= MAX_FAILURES_PER_IP_PER_MINUTE) {
            log.warn("worker token exchange throttled: too many failures from sourceIp={}", sourceIp);
            meterRegistry
                    .counter(CoreMetrics.WORKER_TOKEN_EXCHANGE, "outcome", "failed", "reason", "throttled")
                    .increment();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (request == null || request.workerId() == null || request.workerId().isBlank()) {
            failures.incrementAndGet();
            meterRegistry
                    .counter(CoreMetrics.WORKER_TOKEN_EXCHANGE, "outcome", "failed", "reason", "bad-payload")
                    .increment();
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!constantTimeEquals(request.registrationToken(), properties.registrationToken())) {
            failures.incrementAndGet();
            log.warn(
                    "worker token exchange rejected: bad registration token for workerId={} sourceIp={}",
                    request.workerId(),
                    sourceIp);
            meterRegistry
                    .counter(CoreMetrics.WORKER_TOKEN_EXCHANGE, "outcome", "failed", "reason", "bad-token")
                    .increment();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        WorkerJwtIssuer.IssuedWorkerJwt issued = issuer.issue(request.workerId().trim());
        meterRegistry
                .counter(CoreMetrics.WORKER_TOKEN_EXCHANGE, "outcome", "success")
                .increment();
        log.info(
                "worker token exchange ok: workerId={} sourceIp={} jti={} exp={}",
                request.workerId(),
                sourceIp,
                issued.jti(),
                issued.expiresAt());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ExchangeResponse(issued.token(), issued.expiresAt()));
    }

    /**
     * Prefer the left-most {@code X-Forwarded-For} entry when the request came through a
     * reverse proxy (Coolify / Traefik / Nginx). Falls back to the raw socket peer. Without
     * this, the per-IP rate limit is a per-proxy rate limit and one bad actor exhausts the
     * counter for the entire fleet.
     */
    static String resolveSourceIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return http.getRemoteAddr();
    }

    private static boolean constantTimeEquals(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    public record ExchangeRequest(
            @Nullable String workerId, @Nullable String registrationToken) {}

    public record ExchangeResponse(String token, Instant expiresAt) {}
}
