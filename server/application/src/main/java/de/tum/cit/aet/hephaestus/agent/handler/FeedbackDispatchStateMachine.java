package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatch;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
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
        var unkeyed = new java.util.ArrayList<DeliveredSignal>();
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
            FeedbackDispatch dispatch, String owner, @Nullable String externalRef, List<DeliveredSignal> signals) {
        return finish(dispatch, owner, FeedbackDispatchState.SENT, externalRef, null, null, null, signals)
                ? PracticeFeedbackDispatchService.Result.sent(externalRef, signals)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result refuse(
            FeedbackDispatch dispatch, String owner, FeedbackSuppressionReason reason) {
        return refuse(dispatch, owner, reason, null, deliveredSignals(dispatch));
    }

    PracticeFeedbackDispatchService.Result refuse(
            FeedbackDispatch dispatch,
            String owner,
            FeedbackSuppressionReason reason,
            @Nullable String externalRef,
            List<DeliveredSignal> signals) {
        return finish(dispatch, owner, FeedbackDispatchState.SUPPRESSED, externalRef, null, reason, null, signals)
                ? PracticeFeedbackDispatchService.Result.suppressed(reason, externalRef, signals)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result retry(FeedbackDispatch dispatch, String owner, @Nullable String error) {
        return retry(dispatch, owner, error, null, dispatch.getWriteStarted(), deliveredSignals(dispatch));
    }

    PracticeFeedbackDispatchService.Result retry(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String error,
            @Nullable String externalRef,
            boolean writeMayHaveStarted,
            List<DeliveredSignal> signals) {
        int attempt = dispatch.getAttemptCount() + 1;
        if (attempt >= PracticeFeedbackDispatchService.MAX_ATTEMPTS && !writeMayHaveStarted) {
            return finish(dispatch, owner, FeedbackDispatchState.FAILED, null, error, null, null, signals)
                    ? PracticeFeedbackDispatchService.Result.failed(null, signals)
                    : PracticeFeedbackDispatchService.Result.inProgress();
        }
        return finish(
                        dispatch,
                        owner,
                        FeedbackDispatchState.UNCERTAIN,
                        externalRef,
                        error,
                        null,
                        Instant.now().plus(backoff(attempt)),
                        signals)
                ? PracticeFeedbackDispatchService.Result.uncertain(externalRef)
                : PracticeFeedbackDispatchService.Result.inProgress();
    }

    PracticeFeedbackDispatchService.Result retryPackage(
            FeedbackDispatch dispatch,
            String owner,
            @Nullable String error,
            @Nullable String externalRef,
            List<DeliveredSignal> signals) {
        int attempt = dispatch.getAttemptCount() + 1;
        if (attempt >= PracticeFeedbackDispatchService.MAX_ATTEMPTS) {
            return finish(dispatch, owner, FeedbackDispatchState.FAILED, externalRef, error, null, null, signals)
                    ? PracticeFeedbackDispatchService.Result.failed(externalRef, signals)
                    : PracticeFeedbackDispatchService.Result.inProgress();
        }
        return retry(dispatch, owner, error, externalRef, true, signals);
    }

    PracticeFeedbackDispatchService.Result retryAfterWrite(
            FeedbackDispatch dispatch, String owner, @Nullable String error) {
        return retry(dispatch, owner, error, null, true, deliveredSignals(dispatch));
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

    private static DeliveredSignal strongerSignal(DeliveredSignal persisted, DeliveredSignal latest) {
        if (persisted.disposition() != Disposition.FAILED && latest.disposition() == Disposition.FAILED) {
            return persisted;
        }
        if (latest.externalRef() == null && persisted.externalRef() != null) return persisted;
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

    private record StoredPlacement(
            @com.fasterxml.jackson.annotation.JsonAlias("recurrenceKey") @Nullable
            String deliveryKey,

            String path,
            int startLine,
            @Nullable Integer endLine,
            Disposition disposition,
            @Nullable String externalRef,
            @Nullable String threadExternalRef) {
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
                    signal.threadExternalRef());
        }

        private DeliveredSignal toSignal() {
            FeedbackAnchor.DiffAnchor anchor = endLine == null
                    ? FeedbackAnchor.DiffAnchor.singleLine(path, startLine)
                    : FeedbackAnchor.DiffAnchor.range(path, startLine, endLine);
            return new DeliveredSignal(deliveryKey, anchor, disposition, externalRef, threadExternalRef);
        }
    }
}
