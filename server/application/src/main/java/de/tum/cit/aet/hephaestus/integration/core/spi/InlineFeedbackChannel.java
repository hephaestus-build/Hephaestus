package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Capability-gated SPI for posting inline feedback (SCM diff notes, knowledge-base
 * document-anchor comments). Kinds that don't declare {@link Capability#INLINE_FEEDBACK}
 * never resolve via this registry — Slack and similar messaging vendors are
 * compile-time excluded.
 *
 * <p>Inline feedback is delivered as one sealed package. A channel appends the items that have no copy yet and
 * never edits, deletes, minimizes or recreates a copy, whoever replied to it and whatever the provider marked it.
 */
public interface InlineFeedbackChannel {
    IntegrationKind kind();

    /**
     * Appends the items that have no copy on the target yet. A new note is requested only after a complete scan of
     * the target's existing copies, and only after {@code fence} durably accepted it; a refused fence ends the call
     * before the request. Every item given yields exactly one signal.
     */
    InlineResult postImmutablePackage(
            SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedback, Readback readback, WriteFence fence);

    /**
     * The items that already have a verified copy on the target, reported as {@code PRESERVED_EXISTING} with its
     * native handle, without writing anything. {@code null} when the channel cannot tell, so a caller that may not
     * post again never reads an unanswered lookup as proof that nothing was posted. A conflicting copy is not
     * reported: it proves neither delivery nor absence.
     */
    @Nullable
    List<DeliveredSignal> findPosted(
            SummaryChannel.FeedbackTarget target, List<InlineFeedback> feedback, Readback readback);

    /**
     * How the copies of a package were rendered; sealed with the package, never chosen later. In every mode a copy
     * counts only with its key, the exact body this mode renders, its native anchor (or the fallback’s static location metadata) and authorship by the
     * identity the same response authenticated. A copy that carries the key without all of that is a conflict:
     * neither delivered nor absent.
     */
    enum Readback {
        /** The package sealed its own marker and its final text. */
        AUTHORED,
        /** A historical approved package: its marker, with the text rebuilt as it was then. */
        PACKAGE,
        /** A historical automatic package under the shared marker, which earlier GitHub copies did not carry. */
        SHARED,
    }

    /**
     * Durably records, before a create request leaves, which items it carries and every signal already returned in
     * this call. False when the record could not be made, so nothing is requested.
     */
    @FunctionalInterface
    interface WriteFence {
        boolean beforeCreate(List<InlineFeedback> attempting, List<DeliveredSignal> completed);
    }

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
     *   <li>{@code PRESERVED_EXISTING} — a verified copy already exists and was left untouched.
     *   <li>{@code FAILED} — no copy is known; {@link DeliveredSignal#writeMayHaveStarted()} says whether one may
     *       still exist.
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
     *
     * @param writeMayHaveStarted for an unacknowledged unit: {@code false} when no copy can exist from this
     *     attempt, {@code true} when creation remains unconfirmed, {@code null} when unrecorded and unknown
     */
    record DeliveredSignal(
            @Nullable String deliveryKey,
            FeedbackAnchor anchor,
            Disposition disposition,
            @Nullable String externalRef,
            @Nullable String threadExternalRef,
            @Nullable String externalUrl,
            @Nullable Boolean writeMayHaveStarted) {
        public DeliveredSignal(
                @Nullable String deliveryKey,
                FeedbackAnchor anchor,
                Disposition disposition,
                @Nullable String externalRef,
                @Nullable String threadExternalRef,
                @Nullable String externalUrl) {
            this(deliveryKey, anchor, disposition, externalRef, threadExternalRef, externalUrl, null);
        }

        public DeliveredSignal(
                @Nullable String deliveryKey,
                FeedbackAnchor anchor,
                Disposition disposition,
                @Nullable String externalRef,
                @Nullable String threadExternalRef) {
            this(deliveryKey, anchor, disposition, externalRef, threadExternalRef, null, null);
        }

        /** No copy can exist from this attempt: it was unrequested or provably refused before creation. */
        public static DeliveredSignal notSent(@Nullable String deliveryKey, FeedbackAnchor anchor) {
            return new DeliveredSignal(deliveryKey, anchor, Disposition.FAILED, null, null, null, false);
        }

        /** A create request for this unit may have left, and no copy of it is known. */
        public static DeliveredSignal attempted(@Nullable String deliveryKey, FeedbackAnchor anchor) {
            return new DeliveredSignal(deliveryKey, anchor, Disposition.FAILED, null, null, null, true);
        }

        /** Whether a copy is known on the target: a placed or kept unit with a durable native id. */
        public boolean acknowledged() {
            return disposition != Disposition.FAILED && externalRef != null && !externalRef.isBlank();
        }

        /** Whether this unacknowledged unit may still have a copy that a later lookup could find. */
        public boolean unconfirmed() {
            return !acknowledged() && !Boolean.FALSE.equals(writeMayHaveStarted);
        }
    }

    /**
     * Aggregate delivery result: one {@link DeliveredSignal} per item given, which the placement layer persists.
     * {@code posted} counts acknowledged signals and {@code failed} the others.
     */
    record InlineResult(
            int posted,
            int failed,
            List<DeliveredSignal> signals,
            boolean suppressed,
            List<String> suppressedDeliveryKeys) {
        /** The signals of one call, counted by acknowledgement. */
        public static InlineResult of(List<DeliveredSignal> signals) {
            int posted =
                    (int) signals.stream().filter(DeliveredSignal::acknowledged).count();
            return new InlineResult(posted, signals.size() - posted, List.copyOf(signals), false, List.of());
        }

        public static InlineResult suppressed(List<DeliveredSignal> signals, List<String> suppressedDeliveryKeys) {
            int posted =
                    (int) signals.stream().filter(DeliveredSignal::acknowledged).count();
            return new InlineResult(
                    posted, signals.size() - posted, List.copyOf(signals), true, List.copyOf(suppressedDeliveryKeys));
        }
    }
}
