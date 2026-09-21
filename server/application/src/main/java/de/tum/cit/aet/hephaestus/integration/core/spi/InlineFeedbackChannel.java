package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Capability-gated SPI for posting inline feedback (SCM diff notes, knowledge-base
 * document-anchor comments). Kinds that don't declare {@link Capability#INLINE_FEEDBACK}
 * never resolve via this registry — Slack and similar messaging vendors are
 * compile-time excluded.
 */
public interface InlineFeedbackChannel {
    IntegrationKind kind();

    InlineResult postInlineFeedback(SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedback);

    default InlineResult postImmutablePackage(SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedback) {
        return postInlineFeedback(target, feedback);
    }

    void clearStaleFeedback(SummaryChannel.FeedbackTarget target, String marker);

    /**
     * One piece of feedback to post inline. {@code deliveryKey} carries the exact delivery identity so a retry can be matched
     * back to its placement; it is {@code null} when the caller has no key.
     */
    record InlineFeedback(
            FeedbackAnchor anchor,
            String body,
            String marker,
            @Nullable String deliveryKey) {}

    /**
     * Per-unit outcome of a delivery attempt, reported in {@link DeliveredSignal} so the placement layer can
     * persist {@code posted_state} / {@code external_ref} without re-deriving it.
     *
     * <ul>
     *   <li>{@code POSTED} — a new inline note/thread was created.
     *   <li>{@code FELL_BACK} — the anchor was out of the diff hunk, posted as a plain comment instead.
     *   <li>{@code PRESERVED_EXISTING} — an equivalent note already exists (e.g. a human-replied thread) and was
     *       intentionally left untouched rather than re-posted.
     *   <li>{@code FAILED} — the note could not be delivered (unsupported anchor, API error, rate limit).
     * </ul>
     */
    enum Disposition {
        POSTED,
        FELL_BACK,
        PRESERVED_EXISTING,
        FAILED,
    }

    /**
     * What actually happened to one feedback unit, keyed by {@code deliveryKey} so the caller can reconcile it
     * against the persisted placement. {@code externalRef} is the vendor note id and {@code threadExternalRef}
     * the enclosing discussion/thread id; both are {@code null} when no durable handle exists (e.g. a failure).
     */
    record DeliveredSignal(
            @Nullable String deliveryKey,
            FeedbackAnchor anchor,
            Disposition disposition,
            @Nullable String externalRef,
            @Nullable String threadExternalRef) {}

    /**
     * Aggregate delivery result. {@code signals} carries the per-unit {@link DeliveredSignal}s the placement
     * layer persists; a path with no per-unit outcomes (rate-limit short-circuit, empty input) reports via
     * {@link #counts}, which leaves {@code signals} empty.
     *
     * <p>Counting invariant (both shipped impls): {@code posted} counts every signal whose {@link Disposition}
     * is not {@code FAILED} (i.e. {@code POSTED}, {@code FELL_BACK}, {@code PRESERVED_EXISTING}); {@code failed}
     * counts {@code FAILED} signals. Every unit that produced an outcome contributes exactly one
     * signal, so {@code posted + failed == signals.size()} across the signalled feedback. A unit the channel
     * skips outright (e.g. a blank body the GitHub impl drops without posting) yields no signal and no count, so
     * it is simply absent from both sides rather than violating the equality.
     */
    record InlineResult(
            int posted,
            int failed,
            List<DeliveredSignal> signals,
            boolean suppressed,
            List<String> suppressedDeliveryKeys) {
        public InlineResult(int posted, int failed, List<DeliveredSignal> signals) {
            this(posted, failed, signals, false, List.of());
        }

        /** Count-only result with no per-unit signals (rate-limit short-circuit / empty input). */
        public static InlineResult counts(int posted, int failed) {
            return new InlineResult(posted, failed, List.of());
        }

        public static InlineResult suppressed(
                int posted, int failed, List<DeliveredSignal> signals, List<String> suppressedDeliveryKeys) {
            return new InlineResult(posted, failed, List.copyOf(signals), true, List.copyOf(suppressedDeliveryKeys));
        }
    }
}
