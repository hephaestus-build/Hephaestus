package de.tum.cit.aet.hephaestus.agent.handler;

import com.fasterxml.jackson.annotation.JsonAlias;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Placement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
class FeedbackDispatchStateMachine {

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(15);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(15);

    private final FeedbackDispatchRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    FeedbackDispatchStateMachine(
            FeedbackDispatchRepository repository,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
    }

    List<DeliveredSignal> deliveredSignals(FeedbackDispatch dispatch) {
        List<StoredPlacement> stored =
                objectMapper.convertValue(dispatch.getDeliveredPlacements(), new TypeReference<>() {});
        return stored.stream().map(StoredPlacement::toSignal).toList();
    }

    List<DeliveredSignal> mergeSignals(List<DeliveredSignal> persisted, List<DeliveredSignal> latest) {
        var merged = new LinkedHashMap<String, DeliveredSignal>();
        var unkeyed = new ArrayList<DeliveredSignal>();
        for (DeliveredSignal signal : persisted) {
            if (signal.deliveryKey() == null) unkeyed.add(signal);
            else merged.merge(signal.deliveryKey(), signal, FeedbackDispatchStateMachine::strongerSignal);
        }
        for (DeliveredSignal signal : latest) {
            if (signal.deliveryKey() == null) unkeyed.add(signal);
            else merged.merge(signal.deliveryKey(), signal, FeedbackDispatchStateMachine::strongerSignal);
        }
        unkeyed.addAll(merged.values());
        return List.copyOf(unkeyed);
    }

    PracticeFeedbackDispatchService.Result sent(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            List<DeliveredSignal> signals) {
        return finish(dispatch, owner, FeedbackDispatchState.SENT, externalRef, externalUrl, null, null, null, signals)
                ? PracticeFeedbackDispatchService.Result.sent(externalRef, externalUrl, signals)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result refuse(
            FeedbackDispatch dispatch, String owner, FeedbackSuppressionReason reason) {
        return refuse(dispatch, owner, reason, null, null, deliveredSignals(dispatch));
    }

    PracticeFeedbackDispatchService.Result refuse(
            FeedbackDispatch dispatch,
            String owner,
            FeedbackSuppressionReason reason,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            List<DeliveredSignal> signals) {
        return finish(
                        dispatch,
                        owner,
                        FeedbackDispatchState.SUPPRESSED,
                        externalRef,
                        externalUrl,
                        null,
                        reason,
                        null,
                        signals)
                ? PracticeFeedbackDispatchService.Result.suppressed(reason, externalRef, externalUrl, signals)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result retry(FeedbackDispatch dispatch, String owner, @Nullable String error) {
        return retryFailedAttempt(
                dispatch,
                owner,
                error,
                dispatch.getWriteStarted(),
                dispatch.getDeliveredExternalRef(),
                dispatch.getDeliveredExternalUrl(),
                deliveredSignals(dispatch));
    }

    PracticeFeedbackDispatchService.Result retry(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String error,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            boolean writeMayHaveStarted,
            List<DeliveredSignal> signals) {
        int attempt = dispatch.getAttemptCount() + 1;
        if (attempt >= PracticeFeedbackDispatchService.MAX_ATTEMPTS && !writeMayHaveStarted) {
            return finish(dispatch, owner, FeedbackDispatchState.FAILED, null, null, error, null, null, signals)
                    ? PracticeFeedbackDispatchService.Result.failed(null, null, signals)
                    : PracticeFeedbackDispatchService.Result.inProgress();
        }
        return finish(
                        dispatch,
                        owner,
                        FeedbackDispatchState.UNCERTAIN,
                        externalRef,
                        externalUrl,
                        error,
                        null,
                        Instant.now().plus(backoff(attempt)),
                        signals)
                ? PracticeFeedbackDispatchService.Result.uncertain(externalRef, externalUrl, signals)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result retryPackage(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String error,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            List<DeliveredSignal> signals) {
        int attempt = dispatch.getAttemptCount() + 1;
        if (attempt >= PracticeFeedbackDispatchService.MAX_ATTEMPTS) {
            return finish(
                            dispatch,
                            owner,
                            FeedbackDispatchState.FAILED,
                            externalRef,
                            externalUrl,
                            error,
                            null,
                            null,
                            signals)
                    ? PracticeFeedbackDispatchService.Result.failed(externalRef, externalUrl, signals)
                    : PracticeFeedbackDispatchService.Result.inProgress();
        }
        return retry(dispatch, owner, error, externalRef, externalUrl, true, signals);
    }

    /** Keeps a dispatch whose started write is unconfirmed looking for it, at {@code nextAttemptAt}. */
    PracticeFeedbackDispatchService.Result recheckAt(
            FeedbackDispatch dispatch,
            String owner,
            String error,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            List<DeliveredSignal> signals,
            Instant nextAttemptAt) {
        return finish(
                        dispatch,
                        owner,
                        FeedbackDispatchState.UNCERTAIN,
                        externalRef,
                        externalUrl,
                        error,
                        null,
                        nextAttemptAt,
                        signals)
                ? PracticeFeedbackDispatchService.Result.uncertain(externalRef, externalUrl, signals)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result retryAfterWrite(
            FeedbackDispatch dispatch, String owner, @Nullable String error) {
        return retry(dispatch, owner, error, null, null, true, deliveredSignals(dispatch));
    }

    /** What the final check before a new provider write decided, in the transaction that reserved it or did not. */
    enum ReservationStatus {
        RESERVED,
        STALE,
        UNKNOWN,
        LEASE_LOST,
        REFUSED
    }

    record Reservation(ReservationStatus status, @Nullable FeedbackSuppressionReason suppressionReason) {
        static final Reservation RESERVED = new Reservation(ReservationStatus.RESERVED, null);
        static final Reservation STALE = new Reservation(ReservationStatus.STALE, null);
        static final Reservation UNKNOWN = new Reservation(ReservationStatus.UNKNOWN, null);
        static final Reservation LEASE_LOST = new Reservation(ReservationStatus.LEASE_LOST, null);

        Reservation {
            Objects.requireNonNull(status);
            if ((status == ReservationStatus.REFUSED) != (suppressionReason != null))
                throw new IllegalArgumentException("Only a refused reservation carries a suppression reason");
        }

        static Reservation refused(FeedbackSuppressionReason reason) {
            return new Reservation(ReservationStatus.REFUSED, Objects.requireNonNull(reason));
        }

        FeedbackSuppressionReason refusal() {
            return Objects.requireNonNull(suppressionReason, "A refused reservation names its reason");
        }
    }

    /**
     * Compares the work with what was reviewed and, only when it is current, reserves the write, in one short
     * transaction whose work lock {@code lockedRevision} takes first.
     */
    Reservation reserve(
            Supplier<PracticeFeedbackDeliveryPolicy.Decision<PracticeFeedbackDeliveryPolicy.ReviewedRevision>>
                    lockedRevision,
            IntSupplier beginWrite) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            var decision = lockedRevision.get();
            if (!decision.allowed()) return Reservation.refused(decision.refusal());
            return switch (decision.target()) {
                case CHANGED -> Reservation.STALE;
                case UNKNOWN -> Reservation.UNKNOWN;
                case CURRENT -> beginWrite.getAsInt() == 1 ? Reservation.RESERVED : Reservation.LEASE_LOST;
            };
        }));
    }

    /**
     * Records, before an inline create request leaves, the receipt of every note of the package: what the request
     * carries as unconfirmed, what is known, what was proven not created. Anything but {@link Reservation#RESERVED}
     * means nothing is sent.
     */
    Reservation recordInlineAttempt(
            FeedbackDispatch dispatch,
            String owner,
            List<DeliveredSignal> receipt,
            Supplier<PracticeFeedbackDeliveryPolicy.Decision<PracticeFeedbackDeliveryPolicy.ReviewedRevision>>
                    lockedRevision) {
        String placements = deliveredSignalsJson(receipt);
        return reserve(
                lockedRevision,
                () -> repository.beginInlineWrite(dispatch.getId(), dispatch.getWorkspaceId(), owner, placements));
    }

    /**
     * Keeps a dispatch whose started write is unconfirmed looking for it: on the retry schedule while
     * {@link PracticeFeedbackDispatchService#UNCONFIRMED_WINDOW} has not passed since the write began, then every
     * {@link PracticeFeedbackDispatchService#UNCONFIRMED_RECHECK}. A lookup that finds nothing proves nothing, so it
     * never settles the dispatch either way.
     */
    PracticeFeedbackDispatchService.Result awaitUnconfirmed(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            List<DeliveredSignal> signals) {
        String error = "An earlier provider write is not confirmed yet";
        Instant writeStartedAt = dispatch.getWriteStartedAt();
        Instant since = writeStartedAt != null ? writeStartedAt : dispatch.getCreatedAt();
        if (Instant.now().isAfter(since.plus(PracticeFeedbackDispatchService.UNCONFIRMED_WINDOW))) {
            return recheckAt(
                    dispatch,
                    owner,
                    error,
                    externalRef,
                    externalUrl,
                    signals,
                    Instant.now().plus(PracticeFeedbackDispatchService.UNCONFIRMED_RECHECK));
        }
        return retry(dispatch, owner, error, externalRef, externalUrl, true, signals);
    }

    /**
     * Settles an attempt that failed midway with the receipt it holds, never the array it loaded: a completion
     * replaces the stored array, and a note fenced in this attempt must stay unconfirmed and keep being looked for.
     */
    PracticeFeedbackDispatchService.Result retryFailedAttempt(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String error,
            boolean summaryWriteBegan,
            @Nullable String summaryRef,
            @Nullable String summaryUrl,
            List<DeliveredSignal> inlineSignals) {
        if (summaryWriteBegan && summaryRef == null) {
            return retryAfterWrite(dispatch, owner, error);
        }
        if (dispatch.inlineWriteMayHaveStarted() || inlineSignals.stream().anyMatch(DeliveredSignal::unconfirmed)) {
            return awaitUnconfirmed(dispatch, owner, summaryRef, summaryUrl, inlineSignals);
        }
        if (summaryRef != null) {
            return retryPackage(dispatch, owner, error, summaryRef, summaryUrl, inlineSignals);
        }
        return retry(dispatch, owner, error, null, null, dispatch.getWriteStarted(), inlineSignals);
    }

    void fail(FeedbackDispatch dispatch, String error) {
        transactionTemplate.executeWithoutResult(
                status -> repository.fail(dispatch.getId(), dispatch.getWorkspaceId(), bounded(error)));
    }

    private boolean finish(
            FeedbackDispatch dispatch,
            String owner,
            FeedbackDispatchState state,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            @Nullable String error,
            @Nullable FeedbackSuppressionReason suppressionReason,
            @Nullable Instant nextAttemptAt,
            List<DeliveredSignal> deliveredSignals) {
        Integer affected = transactionTemplate.execute(status -> repository.finish(new FeedbackDispatchCompletion(
                dispatch.getId(),
                dispatch.getWorkspaceId(),
                owner,
                state.name(),
                externalRef,
                externalUrl,
                bounded(error),
                suppressionReason == null ? null : suppressionReason.name(),
                deliveredSignalsJson(deliveredSignals),
                nextAttemptAt == null ? Instant.now() : nextAttemptAt)));
        if (affected == null || affected != 1) return false;
        meterRegistry
                .counter(
                        AgentMetrics.PRACTICE_FEEDBACK_DISPATCH,
                        "destination",
                        dispatch.getDestination().name(),
                        "state",
                        state.name())
                .increment();
        return true;
    }

    private String deliveredSignalsJson(List<DeliveredSignal> signals) {
        return objectMapper
                .valueToTree(signals.stream().map(StoredPlacement::from).toList())
                .toString();
    }

    /**
     * A durable handle outranks any later failure, and an unconfirmed write is never relabelled unsent: only the
     * attempt that made the request can prove no copy was created, and that attempt reports it itself.
     */
    private static DeliveredSignal strongerSignal(DeliveredSignal persisted, DeliveredSignal latest) {
        if (!latest.acknowledged()) {
            if (persisted.acknowledged()) return persisted;
            return persisted.unconfirmed() && !latest.unconfirmed() ? persisted : latest;
        }
        if (Objects.equals(latest.externalRef(), persisted.externalRef())
                && ((latest.externalUrl() == null && persisted.externalUrl() != null)
                        || (latest.placement() == null && persisted.placement() != null))) {
            return new DeliveredSignal(
                    latest.deliveryKey(),
                    latest.anchor(),
                    latest.disposition(),
                    latest.externalRef(),
                    latest.threadExternalRef(),
                    latest.externalUrl() != null ? latest.externalUrl() : persisted.externalUrl(),
                    latest.writeMayHaveStarted(),
                    latest.placement() != null ? latest.placement() : persisted.placement());
        }
        return latest;
    }

    private static Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 10);
        Duration candidate = BASE_BACKOFF.multipliedBy(multiplier);
        double jitter = ThreadLocalRandom.current().nextDouble(0.75, 1.25);
        Duration jittered = Duration.ofMillis((long) (candidate.toMillis() * jitter));
        return jittered.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : jittered;
    }

    private static @Nullable String bounded(@Nullable String value) {
        if (value == null || value.length() <= 512) return value;
        return value.substring(0, 512);
    }

    /**
     * One element of the {@code delivered_placements} array. {@code writeMayHaveStarted} is absent on elements
     * written before it was recorded, which reads as unknown, never as unsent; {@code placement} is absent on
     * elements written before it was recorded, and stays absent rather than being guessed.
     */
    private record StoredPlacement(
            @JsonAlias("recurrenceKey") @Nullable String deliveryKey,

            String path,
            int startLine,
            @Nullable Integer endLine,
            Disposition disposition,
            @Nullable String externalRef,
            @Nullable String externalUrl,
            @Nullable String threadExternalRef,
            @Nullable Boolean writeMayHaveStarted,
            @Nullable Placement placement) {
        private static StoredPlacement from(DeliveredSignal signal) {
            FeedbackAnchor.DiffAnchor anchor = (FeedbackAnchor.DiffAnchor) signal.anchor();
            Integer rangeStart = anchor.startLine();
            return new StoredPlacement(
                    signal.deliveryKey(),
                    anchor.filePath(),
                    rangeStart == null ? anchor.newLineNumber() : rangeStart,
                    rangeStart == null ? null : anchor.newLineNumber(),
                    signal.disposition(),
                    signal.externalRef(),
                    signal.externalUrl(),
                    signal.threadExternalRef(),
                    signal.writeMayHaveStarted(),
                    signal.placement());
        }

        private DeliveredSignal toSignal() {
            FeedbackAnchor.DiffAnchor anchor = endLine == null
                    ? FeedbackAnchor.DiffAnchor.singleLine(path, startLine)
                    : FeedbackAnchor.DiffAnchor.range(path, startLine, endLine);
            return new DeliveredSignal(
                    deliveryKey,
                    anchor,
                    disposition,
                    externalRef,
                    threadExternalRef,
                    externalUrl,
                    writeMayHaveStarted,
                    placement);
        }
    }
}
