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
 * Shared webhook configuration bound to {@code hephaestus.webhook.*}: the receiver's settings, the GitHub webhook
 * secret, and the routing key and external URL that GitLab and Outline hook registration use. See ADR 0008 and ADR 0066.
 *
 * <p>Incoming request size is capped at the {@code WebhookPayloadSizeFilter} before Spring
 * buffers the body — {@code server.tomcat.max-http-post-size} only enforces on form-encoded
 * payloads, so we don't rely on it for JSON webhooks.
 *
 * @param externalUrl Set the public base URL used to register this instance's GitLab webhooks.
 *     GitLab webhook registration is skipped when it is unset.
 * @param secret Shared secret that verifies GitHub webhook signatures. Must differ from both routing secrets.
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
                            + "4 x hephaestus.webhook.http.max-payload-bytes. A smaller stream rejects payloads that the receiver accepted.")
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
     * A routing key signs credentials bound to one connection, so it must not be {@link #secret}: that value is shared
     * with GitHub and readable by an administrator of the GitHub App or of any repository hook that holds it.
     */
    @AssertTrue(message = "hephaestus.webhook.routing secrets must differ from hephaestus.webhook.secret")
    private boolean isRoutingKeyIndependentOfSharedSecret() {
        if (routing == null || secret == null || secret.isBlank()) {
            return true;
        }
        return !secret.equals(routing.secret()) && !secret.equals(routing.previousSecret());
    }

    /** {@code true} iff a GitLab group hook has a URL to be registered against. Pure predicate — no side effects. */
    public boolean isConfigured() {
        return externalUrl != null && !externalUrl.isBlank();
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

    /**
     * Rotation of the access token that each GitLab connection holds.
     *
     * @param thresholdDays Days before a GitLab connection token expires at which Hephaestus rotates it. Set 0 to only
     *     report the expiring token without rotating it.
     * @param validityDays Lifetime in days of the new token that a GitLab token rotation requests. Must be at least 1.
     */
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

    /**
     * @param deadline How long a delivery may wait to be stored before the receiver answers 503. Must end inside the
     *     providers' 10 s response window.
     * @param retryBaseDelay First backoff after a failure that the broker answers at once; it doubles per attempt.
     */
    public record Publish(
            @DefaultValue("8s") Duration deadline,
            @DefaultValue("200ms") Duration retryBaseDelay) {
        public Publish {
            if (deadline.isZero() || deadline.isNegative() || deadline.compareTo(Duration.ofSeconds(10)) >= 0) {
                throw new IllegalArgumentException("publish.deadline must be positive and below 10s, got: " + deadline);
            }
            if (retryBaseDelay.isZero() || retryBaseDelay.isNegative()) {
                throw new IllegalArgumentException("publish.retryBaseDelay must be positive, got: " + retryBaseDelay);
            }
        }
    }

    /**
     * Retention and storage bounds for the four webhook streams.
     *
     * <p>{@link #maxAge} is the bound that holds a stream in steady state: consumers acknowledge within
     * seconds, so the age only decides how long an offline deployment can resume from the stream, and
     * reconciliation repairs what it misses inside {@code hephaestus.sync.timeframe-days}. It is sized so a
     * busy stream stays below half of {@link #maxBytes}, because a stream that lives at its byte bound
     * deletes its head on every publish. {@link #maxBytes} is the disk-safety bound behind it, sized
     * against what a shed message costs (ADR 0008).
     *
     * @param maxAge Longest time a webhook stream keeps a message, for each stream without an entry in
     *     max-age-by-stream. Must be at least duplicate-window.
     * @param maxBytes Storage bound of each webhook stream without an entry in max-bytes-by-stream. At the bound,
     *     JetStream deletes the oldest messages to admit new ones. Must be at least 4 times http.max-payload-bytes.
     * @param maxBytesByStream Storage bounds keyed by stream name that override max-bytes for that stream. The github
     *     stream carries most of the traffic. Each entry must be at least 4 times http.max-payload-bytes.
     * @param storageBudget What the broker may hold for all webhook streams together. Keep it at or below the free
     *                      space on the broker volume. The per-stream bounds must stay inside it, so a full stream
     *                      refuses messages instead of stopping the broker.
     * @param allowDestructiveLimitUpdates Lets startup apply a limit change that deletes messages that the stream
     *                                     already holds. A smaller bound deletes the excess at once, so the operator
     *                                     must turn this on deliberately.
     * @param limitUpdateTimeout How long startup waits for one stream limit update. A smaller bound deletes the
     *                           excess before the broker answers, so a large stream can take long. It is separate
     *                           from the consumer request timeout, so a slow update does not delay readiness.
     * @param monitorInterval how often the stream monitor reads stream and consumer state
     * @see <a href="https://docs.hephaestus.build/admin/webhook-ingestion-operations">Webhook
     *     ingestion operations</a>
     */
    public record Stream(
            // Replay-defense invariant: the JetStream dedup window MUST be >= the largest per-vendor
            // timestamp replay tolerance, otherwise a captured-but-still-timestamp-valid request can be
            // replayed once its dedup entry has expired (the 2-5 min band). The widest vendor tolerance
            // is 5m (Slack v0 MAX_DRIFT_SECONDS). GitHub has NO timestamp,
            // so the dedup window keyed on X-GitHub-Delivery is its ONLY replay defense, and 3h outlasts the
            // 2h look-back of GitHubWebhookRedelivery. See REPLAY_TOLERANCE_FLOOR.
            @DefaultValue("3h") Duration duplicateWindow,
            @DefaultValue("7d") Duration maxAge,
            @DefaultValue Map<String, Duration> maxAgeByStream,
            @DefaultValue("1GB") DataSize maxBytes,
            @DefaultValue Map<String, DataSize> maxBytesByStream,
            @DefaultValue("16GB") DataSize storageBudget,
            @DefaultValue("false") boolean allowDestructiveLimitUpdates,
            @DefaultValue("5m") Duration limitUpdateTimeout,
            @DefaultValue("60s") Duration monitorInterval) {
        /**
         * Lower bound for {@link #duplicateWindow}: the maximum per-vendor timestamp replay
         * tolerance across all webhook verifiers (Slack v0 uses 5 minutes).
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
            if (maxAge.compareTo(duplicateWindow) < 0) {
                throw new IllegalArgumentException("stream.maxAge (" + maxAge + ") must be >= duplicateWindow ("
                        + duplicateWindow + "): the broker refuses a dedup window longer than the age bound");
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
     * Routing keys for the webhooks Hephaestus registers: the connection-scoped GitLab credential and each Outline
     * subscription's signing secret. {@code secret} signs new hooks; {@code previousSecret} is still accepted while an
     * operator rotates, until every hook signed with it has been replaced. Without {@code secret} neither is registered.
     *
     * @param secret Key that signs the webhook token of each GitLab connection and derives the signing secret of each
     *     Outline subscription. At least 32 characters, and must differ from the webhook secret. GitLab and Outline
     *     webhook registration is skipped when it is unset.
     * @param previousSecret Previous routing key that the receiver still accepts during a rotation. Keep it until every
     *     hook signed with it is replaced. Requires routing.secret and must differ from it.
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
