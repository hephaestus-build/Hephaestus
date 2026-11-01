package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import de.tum.cit.aet.hephaestus.agent.gateway.ConversationLoopCredentials;
import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the cluster's TokenReview API whether a token belongs to the release's loop service account, for the loop's
 * audience. A token the cluster accepted is accepted again without a review until the earlier of its own expiry and
 * {@link #REVIEW_AGAIN_AFTER}; a refused token is not remembered.
 *
 * <p>Any process that reaches the gateway can send a token, so a review is asked only for a token that names the loop
 * and its audience, and at most {@link #MAX_REVIEWS} at a time. A token past that is refused, and the loop pulls again.
 */
final class KubernetesConversationLoopCredentials implements ConversationLoopCredentials {

    private static final Logger log = LoggerFactory.getLogger(KubernetesConversationLoopCredentials.class);

    static final Duration REVIEW_AGAIN_AFTER = Duration.ofMinutes(5);

    static final int MAX_REVIEWS = 4;

    private final KubernetesClient client;
    private final String serviceAccount;
    private final Clock clock;
    private final Semaphore reviews = new Semaphore(MAX_REVIEWS);

    /** The expiry of each accepted token, by the SHA-256 of the token. */
    private final Cache<String, Instant> accepted;

    KubernetesConversationLoopCredentials(KubernetesClient client, String serviceAccount, Clock clock) {
        this.client = client;
        this.serviceAccount = serviceAccount;
        this.clock = clock;
        this.accepted = Caffeine.newBuilder()
                .maximumSize(64)
                .expireAfter(Expiry.creating((String digest, Instant until) ->
                        Duration.between(clock.instant(), until).isNegative()
                                ? Duration.ZERO
                                : Duration.between(clock.instant(), until)))
                .build();
    }

    @Override
    public boolean authenticates(String token) {
        if (serviceAccount.isBlank()) {
            return false;
        }
        String digest = sha256(token);
        Instant until = accepted.getIfPresent(digest);
        Instant now = clock.instant();
        if (until != null && now.isBefore(until)) {
            return true;
        }
        Optional<Instant> expiry = claimedExpiry(token, serviceAccount);
        if (expiry.isEmpty() || !now.isBefore(expiry.get()) || !reviews.tryAcquire()) {
            return false;
        }
        try {
            if (!reviewed(token)) {
                return false;
            }
        } finally {
            reviews.release();
        }
        Instant again = now.plus(REVIEW_AGAIN_AFTER);
        accepted.put(digest, expiry.get().isBefore(again) ? expiry.get() : again);
        return true;
    }

    private boolean reviewed(String token) {
        TokenReview review = new TokenReviewBuilder()
                .withNewSpec()
                .withToken(token)
                .withAudiences(AUDIENCE)
                .endSpec()
                .build();
        try {
            TokenReviewStatus status =
                    client.authentication().v1().tokenReviews().create(review).getStatus();
            return status != null
                    && Boolean.TRUE.equals(status.getAuthenticated())
                    && status.getAudiences() != null
                    && status.getAudiences().contains(AUDIENCE)
                    && status.getUser() != null
                    && serviceAccount.equals(status.getUser().getUsername());
        } catch (KubernetesClientException e) {
            // The class and status only: the cluster's message can quote the request.
            log.warn(
                    "A conversation loop token review failed: {} {}",
                    e.getClass().getSimpleName(),
                    e.getCode());
            return false;
        }
    }

    /**
     * The token's own {@code exp} when its unverified claims name the loop's service account and audience. The claims
     * only spare the cluster a review of a token that cannot pass it and bound how long an accepted token is
     * remembered; the review alone checks the signature.
     */
    static Optional<Instant> claimedExpiry(String token, String serviceAccount) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode claims =
                    JsonMapper.shared().readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = claims.path("exp");
            JsonNode aud = claims.path("aud");
            boolean audience = aud.isArray()
                    ? aud.valueStream().anyMatch(value -> AUDIENCE.equals(value.asString(null)))
                    : AUDIENCE.equals(aud.asString(null));
            return exp.canConvertToLong()
                            && audience
                            && serviceAccount.equals(claims.path("sub").asString(null))
                    ? Optional.of(Instant.ofEpochSecond(exp.asLong()))
                    : Optional.empty();
        } catch (IllegalArgumentException | JacksonException malformed) {
            return Optional.empty();
        }
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
