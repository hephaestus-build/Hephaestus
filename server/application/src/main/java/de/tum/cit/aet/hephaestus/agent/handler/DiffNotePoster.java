package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineFeedback;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers the inline notes of one sealed dispatch through its provider's {@link InlineFeedbackChannel}.
 *
 * <p>Each note is classified by its receipt: acknowledged with a native id, positively absent, or possibly
 * created. A possibly requested note is only ever read back, so a lost response can never become a second copy;
 * only a positively absent note reaches a create, and only after its request is recorded.
 */
class DiffNotePoster {

    private static final Logger log = LoggerFactory.getLogger(DiffNotePoster.class);

    private final PullRequestCommentPoster commentPoster;
    private final PracticeFeedbackCommentFormatter commentFormatter;
    private final Map<IntegrationKind, InlineFeedbackChannel> channels;

    DiffNotePoster(
            PullRequestCommentPoster commentPoster,
            PracticeFeedbackCommentFormatter commentFormatter,
            List<InlineFeedbackChannel> inlineFeedbackChannels) {
        this.commentPoster = commentPoster;
        this.commentFormatter = commentFormatter;
        EnumMap<IntegrationKind, InlineFeedbackChannel> map = new EnumMap<>(IntegrationKind.class);
        for (InlineFeedbackChannel channel : inlineFeedbackChannels) {
            InlineFeedbackChannel previous = map.putIfAbsent(channel.kind(), channel);
            if (previous != null) {
                throw new IllegalStateException("Duplicate InlineFeedbackChannel for kind " + channel.kind()
                        + ": "
                        + previous.getClass().getName()
                        + " conflicts with "
                        + channel.getClass().getName());
            }
        }
        this.channels = map;
    }

    /**
     * Delivers what the package still owes: notes whose request may have left are read back, notes proven absent
     * are appended, each create preceded by {@code recordAttempt} durably storing the whole receipt. Once a
     * request was recorded nothing is thrown: the returned receipt is the one to persist.
     */
    DiffNoteResult deliverPackage(
            AgentJob job,
            InlinePackageScope scope,
            List<DiffNote> diffNotes,
            List<DeliveredSignal> recorded,
            Predicate<List<DeliveredSignal>> recordAttempt) {
        IntegrationKind kind =
                Objects.requireNonNull(job.getIntegrationKind(), "AgentJob.integrationKind must not be null");
        InlineFeedbackChannel channel = channels.get(kind);
        if (channel == null) {
            throw new JobDeliveryException("No InlineFeedbackChannel is wired for kind " + kind
                    + ". Check that the vendor integration is enabled and its channel bean is registered.");
        }
        SummaryChannel.FeedbackTarget target = target(job, kind, scope);
        List<InlineFeedback> items = mapObservations(job, scope, diffNotes);
        Receipt receipt = new Receipt(recorded, items, scope);

        List<InlineFeedback> unconfirmed = new ArrayList<>();
        List<InlineFeedback> unrequested = new ArrayList<>();
        for (InlineFeedback item : receipt.open()) {
            if (receipt.safeToCreate(item)) {
                unrequested.add(item);
            } else if (item.deliveryKey() != null) {
                unconfirmed.add(item);
            }
        }
        if (!unconfirmed.isEmpty()) {
            receipt.acknowledge(lookup(channel, target, unconfirmed, scope));
        }

        boolean suppressed = false;
        List<String> suppressedKeys = List.of();
        PackageFence fence = new PackageFence(receipt, recordAttempt);
        if (!unrequested.isEmpty()) {
            try {
                InlineFeedbackChannel.InlineResult result =
                        channel.postImmutablePackage(target, unrequested, scope.readback(), fence);
                receipt.report(result.signals(), unrequested);
                suppressed = result.suppressed();
                suppressedKeys = result.suppressedDeliveryKeys();
            } catch (OutboundEgressSuppressedException e) {
                suppressed = true;
                suppressedKeys = keys(unrequested);
            } catch (RuntimeException e) {
                // A request may have left only for notes the fence recorded; they stay unconfirmed in the receipt.
                log.warn("Inline package delivery stopped: kind={}, jobId={}", kind, job.getId(), e);
            }
        }
        // A note with no text left carries nothing to post, so it never holds the package open.
        return new DiffNoteResult(
                receipt.signals(),
                receipt.open().isEmpty(),
                receipt.anyUnconfirmed(),
                fence.leaseLost,
                suppressed,
                suppressedKeys);
    }

    /**
     * The notes an earlier attempt may have posted without its result being recorded, looked up on the provider
     * without writing. Complete only when every note is acknowledged; unconfirmed while a note whose request may have
     * left is still not found, since it may yet arrive. The notes it did find are kept either way.
     */
    InlineLookup findUnacknowledged(
            AgentJob job, InlinePackageScope scope, List<DiffNote> diffNotes, List<DeliveredSignal> recorded) {
        List<InlineFeedback> items = mapObservations(job, scope, diffNotes);
        Receipt receipt = new Receipt(recorded, items, scope);
        List<InlineFeedback> unconfirmed = receipt.open().stream()
                .filter(item -> item.deliveryKey() != null && !receipt.safeToCreate(item))
                .toList();
        List<DeliveredSignal> found = List.of();
        IntegrationKind kind = job.getIntegrationKind();
        InlineFeedbackChannel channel = kind == null ? null : channels.get(kind);
        if (!unconfirmed.isEmpty() && kind != null && channel != null) {
            found = receipt.acknowledge(lookup(channel, target(job, kind, scope), unconfirmed, scope));
        }
        return new InlineLookup(found, receipt.open().isEmpty(), receipt.anyUnconfirmed());
    }

    /** Where the package lands, anchored to the commit it was reviewed at. */
    private SummaryChannel.FeedbackTarget target(AgentJob job, IntegrationKind kind, InlinePackageScope scope) {
        SummaryChannel.FeedbackTarget pinned =
                commentPoster.buildTarget(job, kind, job.getWorkspace().getId());
        String revision = scope.reviewedRevision();
        return revision == null
                ? pinned
                : new SummaryChannel.FeedbackTarget(pinned.ref(), pinned.subjectExternalId(), revision);
    }

    private static @Nullable List<DeliveredSignal> lookup(
            InlineFeedbackChannel channel,
            SummaryChannel.FeedbackTarget target,
            List<InlineFeedback> items,
            InlinePackageScope scope) {
        try {
            return channel.findPosted(target, items, scope.readback());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<InlineFeedback> mapObservations(AgentJob job, InlinePackageScope scope, List<DiffNote> diffNotes) {
        List<InlineFeedback> observations = new ArrayList<>(diffNotes.size());
        for (int index = 0; index < diffNotes.size(); index++) {
            DiffNote note = diffNotes.get(index);
            // A sealed package stores the exact provider text; only historical text is made safe again here.
            String text = scope.sealed() ? note.body() : PullRequestCommentPoster.sanitize(note.body());
            if (text.isBlank()) {
                continue;
            }
            Integer endLine = note.endLine();
            boolean isMultiLine = endLine != null && endLine > note.startLine();
            FeedbackAnchor.DiffAnchor anchor = isMultiLine
                    ? FeedbackAnchor.DiffAnchor.range(
                            note.filePath(), note.startLine(), Objects.requireNonNull(endLine))
                    : FeedbackAnchor.DiffAnchor.singleLine(note.filePath(), note.startLine());
            observations.add(new InlineFeedback(
                    anchor,
                    scope.appendsDisclosure() ? commentFormatter.appendInlineFeedbackPrompt(text, job) : text,
                    scope.marker(),
                    scope.keyFor(note, index)));
        }
        return observations;
    }

    private static List<String> keys(List<InlineFeedback> items) {
        return items.stream()
                .map(InlineFeedback::deliveryKey)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The receipt of one package during an attempt: the recorded signal of each note, updated by readback,
     * fenced requests and their outcomes. A note without a key can be neither fenced nor read back.
     */
    private static final class Receipt {

        private final Map<String, DeliveredSignal> byKey = new LinkedHashMap<>();
        private final List<DeliveredSignal> unkeyed = new ArrayList<>();
        private final List<InlineFeedback> items;
        private final InlinePackageScope scope;

        Receipt(List<DeliveredSignal> recorded, List<InlineFeedback> items, InlinePackageScope scope) {
            for (DeliveredSignal signal : recorded) {
                if (signal.deliveryKey() == null) unkeyed.add(signal);
                else byKey.put(signal.deliveryKey(), signal);
            }
            this.items = items;
            this.scope = scope;
        }

        /** Every note not yet acknowledged, once per key. */
        List<InlineFeedback> open() {
            List<InlineFeedback> open = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (InlineFeedback item : items) {
                String key = item.deliveryKey();
                if (key == null) {
                    open.add(item);
                } else if (seen.add(key) && !isAcknowledged(key)) {
                    open.add(item);
                }
            }
            return open;
        }

        /** Proof that no copy can exist from the prior attempt, or that no inline write began. */
        boolean safeToCreate(InlineFeedback item) {
            String key = item.deliveryKey();
            if (key == null) return false;
            DeliveredSignal signal = byKey.get(key);
            return signal == null ? scope.unrecordedNotesUnsent() : Boolean.FALSE.equals(signal.writeMayHaveStarted());
        }

        boolean anyUnconfirmed() {
            return open().stream().anyMatch(item -> !safeToCreate(item));
        }

        /** Takes the verified copies a lookup found; anything else it reports changes nothing. */
        List<DeliveredSignal> acknowledge(@Nullable List<DeliveredSignal> found) {
            if (found == null) return List.of();
            List<DeliveredSignal> taken = new ArrayList<>();
            for (DeliveredSignal signal : found) {
                if (signal.acknowledged() && signal.deliveryKey() != null && !isAcknowledged(signal.deliveryKey())) {
                    byKey.put(signal.deliveryKey(), signal);
                    taken.add(signal);
                }
            }
            return taken;
        }

        /**
         * Takes a channel's outcome for the notes this attempt gave it: the channel made or withheld those requests
         * itself, so it alone may prove creation was refused. A known copy is never replaced by a failure.
         */
        void report(List<DeliveredSignal> outcomes, List<InlineFeedback> given) {
            Set<String> givenKeys = new HashSet<>(keys(given));
            for (DeliveredSignal signal : outcomes) {
                String key = signal.deliveryKey();
                if (key != null && givenKeys.contains(key) && (signal.acknowledged() || !isAcknowledged(key))) {
                    byKey.put(key, signal);
                }
            }
        }

        /**
         * The receipt to store before a request: every note of the package accounted for, the ones it carries as
         * unconfirmed. A note with no receipt yet keeps what the frozen dispatch proves about it: never requested
         * while no inline write began, unknown otherwise.
         */
        List<DeliveredSignal> beforeRequest(List<InlineFeedback> attempting, List<DeliveredSignal> completed) {
            Map<String, DeliveredSignal> next = new LinkedHashMap<>(byKey);
            for (InlineFeedback item : items) {
                String key = item.deliveryKey();
                if (key != null && !next.containsKey(key)) {
                    next.put(
                            key,
                            scope.unrecordedNotesUnsent()
                                    ? DeliveredSignal.notSent(key, item.anchor())
                                    : new DeliveredSignal(key, item.anchor(), Disposition.FAILED, null, null));
                }
            }
            for (DeliveredSignal signal : completed) {
                String key = signal.deliveryKey();
                DeliveredSignal known = key == null ? null : next.get(key);
                if (key != null && (signal.acknowledged() || known == null || !known.acknowledged())) {
                    next.put(key, signal);
                }
            }
            for (InlineFeedback item : attempting) {
                String key = item.deliveryKey();
                DeliveredSignal known = key == null ? null : next.get(key);
                if (key != null && (known == null || !known.acknowledged())) {
                    next.put(key, DeliveredSignal.attempted(key, item.anchor()));
                }
            }
            List<DeliveredSignal> stored = new ArrayList<>(unkeyed);
            stored.addAll(next.values());
            return stored;
        }

        void accept(List<DeliveredSignal> stored) {
            byKey.clear();
            for (DeliveredSignal signal : stored) {
                if (signal.deliveryKey() != null) byKey.put(signal.deliveryKey(), signal);
            }
        }

        List<DeliveredSignal> signals() {
            List<DeliveredSignal> all = new ArrayList<>(unkeyed);
            all.addAll(byKey.values());
            return List.copyOf(all);
        }

        private boolean isAcknowledged(String key) {
            DeliveredSignal signal = byKey.get(key);
            return signal != null && signal.acknowledged();
        }
    }

    /** Stores the receipt before each create request; a refused store refuses the request. */
    private static final class PackageFence implements InlineFeedbackChannel.WriteFence {

        private final Receipt receipt;
        private final Predicate<List<DeliveredSignal>> recordAttempt;
        private boolean leaseLost;

        PackageFence(Receipt receipt, Predicate<List<DeliveredSignal>> recordAttempt) {
            this.receipt = receipt;
            this.recordAttempt = recordAttempt;
        }

        @Override
        public boolean beforeCreate(List<InlineFeedback> attempting, List<DeliveredSignal> completed) {
            List<DeliveredSignal> stored = receipt.beforeRequest(attempting, completed);
            if (!recordAttempt.test(stored)) {
                leaseLost = true;
                return false;
            }
            receipt.accept(stored);
            return true;
        }
    }

    /**
     * @param found the copies the lookup verified
     * @param complete whether every note is acknowledged
     * @param unconfirmed whether a note whose request may have left has no known copy, so the package must keep
     *     looking for it and may never request it again
     */
    record InlineLookup(List<DeliveredSignal> found, boolean complete, boolean unconfirmed) {}

    /**
     * @param signals the receipt of every note, to persist
     * @param complete whether every note is acknowledged
     * @param unconfirmed whether a note whose request may have left has no known copy
     * @param leaseLost whether a request was refused because this attempt no longer holds the dispatch
     * @param suppressed whether egress refused a request before it left
     * @param suppressedDeliveryKeys the notes egress refused
     */
    record DiffNoteResult(
            List<DeliveredSignal> signals,
            boolean complete,
            boolean unconfirmed,
            boolean leaseLost,
            boolean suppressed,
            List<String> suppressedDeliveryKeys) {}
}
