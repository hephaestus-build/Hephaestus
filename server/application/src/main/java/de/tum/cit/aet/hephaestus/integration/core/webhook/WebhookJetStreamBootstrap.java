package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.CompressionOption;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamState;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotently creates one JetStream stream per registered integration kind at startup, and keeps
 * the <em>limits</em> of a stream that already exists in step with configuration.
 *
 * <p>Limits, and only limits: the update is built with
 * {@link StreamConfiguration#builder(StreamConfiguration)} over the live configuration, so subjects,
 * retention, storage and discard — the fields that decide what a stream <em>is</em> — survive
 * verbatim. ADR 0008 §"Limits may be corrected in place; shape still may not" carries the decision
 * table this implements, row by row, and why each row is what it is.
 *
 * <p>Anything that leaves the receiver unable to publish fails bean initialisation instead: a
 * container that reports healthy while dropping every delivery is the outage ADR 0008 exists for.
 *
 * <p>Streams are created only for kinds that flow over NATS: {@code github} and {@code gitlab}
 * (HMAC webhook receiver), {@code slack} (monitored-channel {@code message} ingest), and
 * {@code outline} (change-notification webhook). Slack interactivity uses a separate signed HTTP
 * endpoint and is not published here.
 */
public class WebhookJetStreamBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WebhookJetStreamBootstrap.class);
    static final String[] STREAMS = {"gitlab", "github", "slack", "outline"};

    /** Deliveries are JSON that repeats itself, and the broker compresses a block only once it is full. */
    static final CompressionOption COMPRESSION = CompressionOption.S2;

    /**
     * {@code Old} sheds the oldest retained messages to admit the newest. {@code New} would reject
     * the publish, destroying a message nobody has seen and stopping ingestion for every workspace
     * at once.
     */
    static final DiscardPolicy DISCARD_POLICY = DiscardPolicy.Old;

    /** JetStream's encoding of "no bound" for {@code maxMsgs} and {@code maxBytes}. */
    static final long UNLIMITED = -1L;

    private static final String UNPROVABLE = "stream state unavailable, cannot prove the change is non-destructive";
    private static final String UNREPLACED = "no byte bound is in force to replace it";

    private final JetStreamManagement jsm;
    private final WebhookProperties properties;

    WebhookJetStreamBootstrap(JetStreamManagement jsm, WebhookProperties properties) {
        this.jsm = jsm;
        this.properties = properties;
    }

    @PostConstruct
    void bootstrap() {
        requireStorageBudgetFits();
        for (String name : STREAMS) {
            ensureStream(name);
        }
    }

    /**
     * The arithmetic that keeps a full stream from becoming a broker that cannot write at all is
     * knowable at startup, and a deploy that fails it must not run.
     */
    private void requireStorageBudgetFits() {
        WebhookProperties.Stream s = properties.stream();
        long budget = s.storageBudget().toBytes();
        long total = 0;
        for (String name : STREAMS) {
            total += s.maxBytesFor(name);
        }
        if (total > budget) {
            throw new IllegalStateException("Webhook stream bounds total " + total
                    + " bytes, over the "
                    + budget
                    + "-byte broker storage budget (hephaestus.webhook.stream.storage-budget). "
                    + "Lower hephaestus.webhook.stream.max-bytes[-by-stream], or raise the budget and the "
                    + "JetStream volume with it.");
        }
    }

    private void ensureStream(String name) {
        try {
            StreamInfo info = jsm.getStreamInfo(name);
            log.info("JetStream stream already exists: name={}", name);
            reconcileLimits(name, info);
        } catch (JetStreamApiException e) {
            if (e.getErrorCode() == 404) {
                createStream(name);
                return;
            }
            throw new IllegalStateException(
                    "Failed to inspect JetStream stream: " + name + " (code=" + e.getErrorCode() + ")", e);
        } catch (IOException e) {
            throw new IllegalStateException("I/O error inspecting JetStream stream: " + name, e);
        }
    }

    private void createStream(String name) {
        WebhookProperties.Stream s = properties.stream();
        StreamConfiguration config = StreamConfiguration.builder()
                .name(name)
                .subjects(name + ".>")
                .retentionPolicy(RetentionPolicy.Limits)
                .discardPolicy(DISCARD_POLICY)
                .storageType(StorageType.File)
                .duplicateWindow(s.duplicateWindow())
                .maxAge(s.maxAgeFor(name))
                .maxMessages(UNLIMITED)
                .maxBytes(s.maxBytesFor(name))
                .compressionOption(COMPRESSION)
                .build();
        try {
            jsm.addStream(config);
            log.info(
                    "Created JetStream stream: name={} dedupWindow={} maxAge={} maxBytes={}",
                    name,
                    s.duplicateWindow(),
                    s.maxAgeFor(name),
                    s.maxBytesFor(name));
        } catch (JetStreamApiException | IOException ex) {
            throw new IllegalStateException("Failed to create JetStream stream: " + name, ex);
        }
    }

    /**
     * Brings the limit fields in line with configuration. Every other field of the live
     * configuration is carried over untouched, so this can never reshape subjects, retention,
     * storage or discard.
     */
    private void reconcileLimits(String name, StreamInfo info) {
        WebhookProperties.Stream s = properties.stream();
        StreamConfiguration live = info.getConfiguration();
        if (reportShapeDrift(name, live)) {
            return;
        }
        StreamState state = info.getStreamState();
        boolean allowDestructive = s.allowDestructiveLimitUpdates();

        Duration maxAge = s.maxAgeFor(name);
        long maxBytes = s.maxBytesFor(name);

        LimitPlan plan = new LimitPlan(live);
        // A narrower dedup window forgets earlier, but it never deletes a stored message.
        if (plan.consider("duplicateWindow", live.getDuplicateWindow(), s.duplicateWindow(), null, allowDestructive)) {
            plan.update.duplicateWindow(s.duplicateWindow());
        }
        if (plan.consider("maxAge", live.getMaxAge(), maxAge, expiryLoss(state, maxAge), allowDestructive)) {
            plan.update.maxAge(maxAge);
        }
        boolean byteBoundApplied =
                plan.consider("maxBytes", live.getMaxBytes(), maxBytes, byteLoss(state, maxBytes), allowDestructive);
        if (byteBoundApplied) {
            plan.update.maxBytes(maxBytes);
        }
        // The broker reads this when it opens the store: a stream that had none compresses new blocks after its
        // next restart. It rewrites no block, so stored messages stay readable and age out as they are.
        if (plan.consider("compression", live.getCompressionOption(), COMPRESSION, null, allowDestructive)) {
            plan.update.compressionOption(COMPRESSION);
        }
        // Whether a byte bound is in force afterwards is the condition, not whether this pass wrote
        // one: a stream already at the configured bound produces no update at all.
        long resultingMaxBytes = byteBoundApplied ? maxBytes : live.getMaxBytes();
        if (plan.relax("maxMessages", live.getMaxMsgs(), UNLIMITED, resultingMaxBytes > 0 ? null : UNREPLACED)) {
            plan.update.maxMessages(UNLIMITED);
        }

        if (!plan.withheld.isEmpty()) {
            log.error(
                    "Stream {} limit change withheld because it would delete stored messages: {} — "
                            + "set hephaestus.webhook.stream.allow-destructive-limit-updates=true to apply it",
                    name,
                    plan.withheld);
        }
        if (!plan.unbounding.isEmpty()) {
            log.error(
                    "Stream {} bound removal withheld because it would leave the stream unbounded: {} — "
                            + "get hephaestus.webhook.stream.max-bytes[-by-stream] applied first",
                    name,
                    plan.unbounding);
        }
        boolean written = !plan.applied.isEmpty() && applyLimits(name, plan);
        reportRemainingBounds(
                name,
                byteBoundApplied && written ? maxBytes : live.getMaxBytes(),
                plan.relaxed.contains("maxMessages") && written ? UNLIMITED : live.getMaxMsgs());
    }

    /** Both states are reported, because a count bound and no bound at all both end at a full volume. */
    private void reportRemainingBounds(String name, long maxBytes, long maxMessages) {
        if (maxBytes > 0) {
            return;
        }
        if (maxMessages > 0) {
            log.error(
                    "JetStream stream {} has no storage bound — a {}-message cap is all that limits its disk, "
                            + "and a message count does not predict bytes",
                    name,
                    maxMessages);
            return;
        }
        log.error(
                "JetStream stream {} has no storage bound and no message bound, so it will grow until the "
                        + "broker's volume is full, at which point NATS cannot write and every inbound webhook is dropped",
                name);
    }

    /** @return whether the update landed. */
    private boolean applyLimits(String name, LimitPlan plan) {
        try {
            jsm.updateStream(plan.update.build());
            if (plan.deletes.isEmpty()) {
                log.info("Reconciled JetStream stream limits: name={} changed={}", name, plan.applied);
            } else {
                log.warn(
                        "Reconciled JetStream stream limits and DELETED stored messages: name={} changed={} deleted={}",
                        name,
                        plan.applied,
                        plan.deletes);
            }
            return true;
        } catch (JetStreamApiException | IOException ex) {
            // A timeout here says the broker did not answer in time, not that it did nothing: shedding
            // the excess a new bound deletes happens before the reply, so the update can land while the
            // client gives up. Report the limits the stream actually has rather than assert an outcome.
            log.error(
                    "Failed to reconcile JetStream stream limits: name={} changed={} — live configuration is now {}",
                    name,
                    plan.applied,
                    describeLiveLimits(name),
                    ex);
            return false;
        }
    }

    /**
     * The stream's limits as the broker reports them right now, for a failure that cannot say whether
     * its own update landed. Never throws: it runs on a path that is already handling a failure, and
     * an unreadable broker is itself the answer.
     */
    private String describeLiveLimits(String name) {
        try {
            StreamConfiguration live = jsm.getStreamInfo(name).getConfiguration();
            return String.format(
                    "maxAge=%s maxBytes=%d maxMessages=%d", live.getMaxAge(), live.getMaxBytes(), live.getMaxMsgs());
        } catch (JetStreamApiException | IOException | RuntimeException ex) {
            return "unreadable (" + ex.getClass().getSimpleName() + ") — check the broker directly";
        }
    }

    /**
     * Fields that define what the stream <em>is</em>. Changing any of them reshapes or re-homes the
     * data, so they are reported and never written — {@code nats stream edit} with a human deciding
     * remains the only path.
     *
     * @return whether drift was found, in which case the limits are left alone too: what a limit
     *     does at the bound is decided by the discard policy, so a limit written onto a stream of
     *     unknown shape has unknown consequences.
     */
    private boolean reportShapeDrift(String name, StreamConfiguration live) {
        List<String> drift = new ArrayList<>();
        if (live.getStorageType() != StorageType.File) {
            drift.add("storageType=" + live.getStorageType() + " (expected " + StorageType.File + ")");
        }
        if (live.getRetentionPolicy() != RetentionPolicy.Limits) {
            drift.add("retentionPolicy=" + live.getRetentionPolicy() + " (expected " + RetentionPolicy.Limits + ")");
        }
        if (live.getDiscardPolicy() != DISCARD_POLICY) {
            drift.add("discardPolicy=" + live.getDiscardPolicy() + " (expected " + DISCARD_POLICY + ")");
        }
        if (drift.isEmpty()) {
            return false;
        }
        log.error(
                "Stream {} has drifted from the shape this deployment expects: {} — limits left unreconciled, "
                        + "because a bound on a stream of unknown shape has unknown consequences. Repair it with "
                        + "`nats stream edit {}`.",
                name,
                drift,
                name);
        return true;
    }

    /** Non-null when applying {@code desired} would drop messages for exceeding the byte bound. */
    private static @Nullable String byteLoss(@Nullable StreamState state, long desired) {
        if (state == null) {
            return UNPROVABLE;
        }
        long held = state.getByteCount();
        return held > desired ? held + " bytes stored, " + (held - desired) + " would be deleted" : null;
    }

    /** Non-null when applying {@code desired} would immediately expire the oldest stored message. */
    private static @Nullable String expiryLoss(@Nullable StreamState state, Duration desired) {
        if (state == null) {
            return UNPROVABLE;
        }
        ZonedDateTime first = state.getFirstTime();
        if (state.getMsgCount() == 0 || first == null) {
            return null;
        }
        Instant cutoff = Instant.now().minus(desired);
        return first.toInstant().isBefore(cutoff) ? "oldest stored message (" + first + ") is already older" : null;
    }

    /**
     * Accumulates the limit changes for one stream and the reason any of them was held back.
     * Seeded from the live configuration so unnamed fields survive the update untouched.
     */
    private static final class LimitPlan {

        private final StreamConfiguration.Builder update;
        private final List<String> applied = new ArrayList<>();
        private final List<String> withheld = new ArrayList<>();
        private final List<String> unbounding = new ArrayList<>();
        private final List<String> deletes = new ArrayList<>();
        private final Set<String> relaxed = new HashSet<>();

        private LimitPlan(StreamConfiguration live) {
            this.update = StreamConfiguration.builder(live);
        }

        /**
         * @param loss {@code null} when the new value cannot delete anything the stream holds today
         * @return whether the caller should write the new value onto {@link #update}
         */
        private boolean consider(
                String field, @Nullable Object from, Object to, @Nullable String loss, boolean allowDestructive) {
            if (Objects.equals(from, to)) {
                return false;
            }
            String move = field + " " + from + " -> " + to;
            if (loss != null && !allowDestructive) {
                withheld.add(move + " (" + loss + ")");
                return false;
            }
            applied.add(move);
            if (loss != null) {
                deletes.add(move + " (" + loss + ")");
            }
            return true;
        }

        /**
         * A change that can only ever admit more, and so deletes nothing — but that removes a bound
         * rather than moving one.
         *
         * @param blocker {@code null} when a bound remains in force afterwards, else why it does not
         * @return whether the caller should write the new value onto {@link #update}
         */
        private boolean relax(String field, Object from, Object to, @Nullable String blocker) {
            if (Objects.equals(from, to)) {
                return false;
            }
            String move = field + " " + from + " -> " + to;
            if (blocker != null) {
                unbounding.add(move + " (" + blocker + ")");
                return false;
            }
            applied.add(move);
            relaxed.add(field);
            return true;
        }
    }
}
