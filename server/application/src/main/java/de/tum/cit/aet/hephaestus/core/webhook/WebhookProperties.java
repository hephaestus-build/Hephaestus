package de.tum.cit.aet.hephaestus.core.webhook;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Shared webhook configuration bound to {@code hephaestus.webhook.*}. The same {@code secret} is
 * used by auto-registration ({@code workspace.GitLabWebhookService} → sent to provider) and
 * verification ({@code integration.webhook.*} → matched against the incoming HMAC/token). See
 * ADR 0008.
 *
 * <p>Incoming request size is capped at the {@code WebhookPayloadSizeFilter} before Spring
 * buffers the body — {@code server.tomcat.max-http-post-size} only enforces on form-encoded
 * payloads, so we don't rely on it for JSON webhooks.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.webhook")
public record WebhookProperties(
        @Nullable String externalUrl,
        @Nullable String secret,
        @DefaultValue TokenRotation tokenRotation,
        @DefaultValue Publish publish,
        @DefaultValue Stream stream,
        @DefaultValue Shutdown shutdown,
        @DefaultValue Http http,
        @DefaultValue Routing routing) {
    /** Minimum HMAC-SHA256 secret length recommended by NIST SP 800-107. */
    public static final int MIN_SECRET_LENGTH = 32;

    /** Maximum-size payloads a stream must be able to hold. */
    public static final int MIN_PAYLOADS_PER_STREAM = 4;

    /**
     * Below one maximum payload the stream rejects everything {@code WebhookPayloadSizeFilter}
     * accepted, which is a total ingestion outage behind a receiver that reports healthy. Bean
     * Validation has no comparison constraint between two properties, so the one invariant that spans
     * {@code stream} and {@code http} is asserted here — the shape {@code WorkspaceProperties} uses.
     */
    @AssertTrue(
            message =
                    "hephaestus.webhook.stream.max-bytes, and every max-bytes-by-stream entry, must be at least "
                            + "4 x hephaestus.webhook.http.max-payload-bytes; a smaller stream rejects payloads the receiver accepted")
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private boolean isStreamAbleToHoldWhatTheReceiverAccepts() {
        if (stream == null || http == null) {
            return true;
        }
        long floor = Math.multiplyExact(http.maxPayloadBytes(), (long) MIN_PAYLOADS_PER_STREAM);
        if (stream.maxBytes().toBytes() < floor) {
            return false;
        }
        return stream.maxBytesByStream().values().stream().allMatch(size -> size.toBytes() >= floor);
    }

    /**
     * A routing key signs credentials bound to one connection, so it must not be {@link #secret}: that value is the
     * token every legacy GitLab hook carries and is readable by an administrator of any instance holding one.
     */
    @AssertTrue(message = "hephaestus.webhook.routing secrets must differ from hephaestus.webhook.secret")
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private boolean isRoutingKeyIndependentOfSharedSecret() {
        if (routing == null || secret == null || secret.isBlank()) {
            return true;
        }
        return !secret.equals(routing.secret()) && !secret.equals(routing.previousSecret());
    }

    /** {@code true} iff auto-registration with the provider can be attempted. Pure predicate — no side effects. */
    public boolean isConfigured() {
        return (externalUrl != null
                && !externalUrl.isBlank()
                && secret != null
                && !secret.isBlank()
                && secret.length() >= MIN_SECRET_LENGTH);
    }

    /** Redacts {@code secret} so accidental {@code log.info("config: {}", props)} doesn't leak it. */
    @Override
    public String toString() {
        return ("WebhookProperties[externalUrl=" + externalUrl
                + ", secret="
                + (secret == null || secret.isBlank() ? "<unset>" : "<redacted>")
                + ", tokenRotation="
                + tokenRotation
                + ", publish="
                + publish
                + ", stream="
                + stream
                + ", shutdown="
                + shutdown
                + ", http="
                + http
                + ", routing="
                + routing
                + "]");
    }

    public record TokenRotation(
            @DefaultValue("7") int thresholdDays,
            @DefaultValue("90") int validityDays) {
        public TokenRotation {
            if (thresholdDays < 0) {
                throw new IllegalArgumentException("tokenRotation.thresholdDays must be >= 0, got: " + thresholdDays);
            }
            if (validityDays < 1) {
                throw new IllegalArgumentException("tokenRotation.validityDays must be >= 1, got: " + validityDays);
            }
        }
    }

    public record Publish(
            @DefaultValue("9s") Duration timeout,
            @DefaultValue("5") int maxRetries,
            @DefaultValue("200ms") Duration retryBaseDelay) {
        public Publish {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("publish.timeout must be positive, got: " + timeout);
            }
            if (maxRetries < 1) {
                throw new IllegalArgumentException("publish.maxRetries must be >= 1, got: " + maxRetries);
            }
            if (retryBaseDelay.isZero() || retryBaseDelay.isNegative()) {
                throw new IllegalArgumentException("publish.retryBaseDelay must be positive, got: " + retryBaseDelay);
            }
        }
    }

    /**
     * Retention and storage bounds for the four webhook streams.
     *
     * <p>{@link #maxAge} is the retention <em>ceiling</em> and {@link #maxBytes} the disk-safety
     * <em>floor</em> under it; which of the two binds is a function of a deployment's volume, and the
     * answer for a given deployment is published as {@code webhook.stream.oldest.message.age} rather
     * than predicted here. {@link #maxBytes} is sized against what a shed message costs: nightly
     * {@code SyncJobType.RECONCILIATION} re-fetches over {@code hephaestus.sync.timeframe-days}, so
     * inside that window a shed webhook is recoverable from the provider API and outside it, by
     * nothing (ADR 0008: webhook deliveries are not redeliverable).
     *
     * @see <a href="https://docs.hephaestus.build/admin/webhook-ingestion-operations">Webhook
     *     ingestion operations</a>
     */
    public record Stream(
            // Replay-defense invariant: the JetStream dedup window MUST be >= the largest per-vendor
            // timestamp replay tolerance, otherwise a captured-but-still-timestamp-valid request can be
            // replayed once its dedup entry has expired (the 2-5 min band). The widest vendor tolerance
            // is 5m (GitLab whsec TIMESTAMP_TOLERANCE, Slack v0 MAX_DRIFT_SECONDS); we set 10m to also
            // cover GitHub — which has NO timestamp, so the dedup window keyed on X-GitHub-Delivery is
            // its ONLY replay defense — plus provider redelivery horizons. See REPLAY_TOLERANCE_FLOOR.
            @DefaultValue("10m") Duration duplicateWindow,
            @DefaultValue("180d") Duration maxAge,
            @DefaultValue Map<String, Duration> maxAgeByStream,
            @DefaultValue("1GB") DataSize maxBytes,
            /** Per-stream {@link #maxBytes} overrides keyed by stream name — {@code github} dwarfs the rest. */
            @DefaultValue Map<String, DataSize> maxBytesByStream,
            /**
             * What the broker may hold for all webhook streams together, which must stay at or below the
             * free space on its volume. Keeping the per-stream bounds inside it is what keeps a full
             * stream a stream that refuses messages rather than a broker that cannot write at all.
             */
            @DefaultValue("16GB") DataSize storageBudget,
            /**
             * Lets startup apply a limit change that would delete messages the stream already holds.
             * Off by default: bounding a stream that has outgrown the new limit deletes the excess
             * immediately, so it is a decision an operator makes rather than one a deploy makes for them.
             */
            @DefaultValue("false") boolean allowDestructiveLimitUpdates,
            /**
             * How long startup waits for one stream limit update. Bounding a stream that has outgrown
             * the new limit deletes the excess before the broker answers, so the work is proportional to
             * the bytes being shed rather than to the size of the request — tens of GB take far longer
             * than the request timeout the health probes want. This is deliberately separate from the
             * consumer request timeout so a slow admin call cannot slow a readiness answer.
             */
            @DefaultValue("5m") Duration limitUpdateTimeout,
            /** How often the stream monitor reads stream and consumer state. */
            @DefaultValue("60s") Duration monitorInterval) {
        /**
         * Lower bound for {@link #duplicateWindow}: the maximum per-vendor timestamp replay
         * tolerance across all webhook verifiers (GitLab whsec + Slack v0 both use 5 minutes).
         * The dedup window must be at least this large so a request can never outlive its dedup
         * entry while still being timestamp-valid. GitHub deliveries carry no timestamp at all,
         * so for them the dedup window is the sole replay defense and a larger value is safer.
         */
        public static final Duration REPLAY_TOLERANCE_FLOOR = Duration.ofMinutes(5);

        public Stream {
            if (duplicateWindow.isZero() || duplicateWindow.isNegative()) {
                throw new IllegalArgumentException("stream.duplicateWindow must be positive, got: " + duplicateWindow);
            }
            if (duplicateWindow.compareTo(REPLAY_TOLERANCE_FLOOR) < 0) {
                throw new IllegalArgumentException("stream.duplicateWindow (" + duplicateWindow
                        + ") must be >= the max vendor replay tolerance ("
                        + REPLAY_TOLERANCE_FLOOR
                        + ") so a timestamp-valid request cannot outlive its dedup entry");
            }
            if (maxAge.isZero() || maxAge.isNegative()) {
                throw new IllegalArgumentException("stream.maxAge must be positive, got: " + maxAge);
            }
            maxAgeByStream = maxAgeByStream == null ? Map.of() : Map.copyOf(maxAgeByStream);
            for (Map.Entry<String, Duration> e : maxAgeByStream.entrySet()) {
                Duration v = e.getValue();
                if (v == null || v.isZero() || v.isNegative()) {
                    throw new IllegalArgumentException(
                            "stream.maxAgeByStream." + e.getKey() + " must be positive, got: " + v);
                }
                if (v.compareTo(duplicateWindow) < 0) {
                    throw new IllegalArgumentException("stream.maxAgeByStream." + e.getKey()
                            + " ("
                            + v
                            + ") must be >= duplicateWindow ("
                            + duplicateWindow
                            + ") or the dedup guarantee is meaningless");
                }
            }
            requirePositive("stream.maxBytes", maxBytes);
            maxBytesByStream = maxBytesByStream == null ? Map.of() : Map.copyOf(maxBytesByStream);
            for (Map.Entry<String, DataSize> e : maxBytesByStream.entrySet()) {
                requirePositive("stream.maxBytesByStream." + e.getKey(), e.getValue());
            }
            requirePositive("stream.storageBudget", storageBudget);
            if (limitUpdateTimeout.isZero() || limitUpdateTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "stream.limitUpdateTimeout must be positive, got: " + limitUpdateTimeout);
            }
            if (monitorInterval.isZero() || monitorInterval.isNegative()) {
                throw new IllegalArgumentException("stream.monitorInterval must be positive, got: " + monitorInterval);
            }
        }

        private static void requirePositive(String key, @Nullable DataSize size) {
            if (size == null || size.toBytes() < 1) {
                throw new IllegalArgumentException(key + " must be at least 1 byte, got: " + size);
            }
        }

        /** Effective retention for one stream: the per-stream override, else the shared {@link #maxAge}. */
        public Duration maxAgeFor(String streamName) {
            return maxAgeByStream.getOrDefault(streamName, maxAge);
        }

        /** Effective storage bound for one stream: the per-stream override, else the shared {@link #maxBytes}. */
        public long maxBytesFor(String streamName) {
            return maxBytesByStream.getOrDefault(streamName, maxBytes).toBytes();
        }
    }

    /**
     * Graceful-shutdown drain budget for in-flight publishes after HTTP closes. The relationship
     * to Docker's {@code stop_grace_period} is:
     * {@code stop_grace_period ≥ server.shutdown=graceful timeout + drainTimeout + margin}.
     */
    public record Shutdown(@DefaultValue("15s") Duration drainTimeout) {
        public Shutdown {
            if (drainTimeout.isZero() || drainTimeout.isNegative()) {
                throw new IllegalArgumentException("shutdown.drainTimeout must be positive, got: " + drainTimeout);
            }
        }
    }

    /**
     * Enforced by {@code WebhookPayloadSizeFilter} via {@code Content-Length} header. Tomcat's
     * {@code max-http-post-size} only caps form bodies, so it can't enforce JSON request size on
     * its own.
     */
    public record Http(@DefaultValue("26214400") long maxPayloadBytes) {
        public Http {
            if (maxPayloadBytes < 1) {
                throw new IllegalArgumentException("http.maxPayloadBytes must be >= 1, got: " + maxPayloadBytes);
            }
        }
    }

    /**
     * Signing keys for the connection-scoped GitLab webhook credential. {@code secret} signs new hooks;
     * {@code previousSecret} is still accepted while an operator rotates, until every hook signed with it has been
     * replaced. Without {@code secret} no connection-scoped hook is registered.
     */
    public record Routing(@Nullable String secret, @Nullable String previousSecret) {
        public Routing {
            secret = blankToNull(secret);
            previousSecret = blankToNull(previousSecret);
            requireKeyLength("routing.secret", secret);
            requireKeyLength("routing.previousSecret", previousSecret);
            if (secret == null && previousSecret != null) {
                throw new IllegalArgumentException("routing.previousSecret requires routing.secret");
            }
            if (secret != null && secret.equals(previousSecret)) {
                throw new IllegalArgumentException("routing.previousSecret must differ from routing.secret");
            }
        }

        private static @Nullable String blankToNull(@Nullable String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static void requireKeyLength(String name, @Nullable String value) {
            if (value != null && value.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException(name + " must be at least " + MIN_SECRET_LENGTH + " characters");
            }
        }

        @Override
        public String toString() {
            return "Routing[secret=" + (secret == null ? "<unset>" : "<redacted>")
                    + ", previousSecret="
                    + (previousSecret == null ? "<unset>" : "<redacted>")
                    + "]";
        }
    }
}
