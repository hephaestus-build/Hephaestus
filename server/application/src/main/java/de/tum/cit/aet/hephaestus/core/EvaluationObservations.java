package de.tum.cit.aet.hephaestus.core;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The evaluation collector accepts only identifiers, digests and closed sets of labels. */
public interface EvaluationObservations {

    void record(Event event);

    void changed();

    Call beginCall(CallKind kind, String id);

    enum CallKind {
        PROVIDER_EVENT,
        OUTBOX,
        MODEL
    }

    interface Call extends AutoCloseable {
        @Override
        void close();
    }

    sealed interface Event permits Run, RecordRead, ModelCall, ActionQueued, DeliveryAttempt, Silence {}

    enum RunState {
        STARTED,
        ENDED,
        REQUEUED
    }

    record Run(
            long workspaceId,
            UUID runId,
            int epoch,
            RunState state,
            @Nullable Enum<?> reason) implements Event {}

    record RecordRead(long workspaceId, UUID recordId) implements Event {}

    record ModelCall(
            @Nullable Long workspaceId,
            @Nullable UUID runId,
            UUID sourceId,
            @Nullable Long modelId,
            String requestSha256)
            implements Event {

        public ModelCall {
            if (!requestSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Expected a SHA-256 digest in hex");
        }
    }

    record Authority(@Nullable Long workspaceId, @Nullable Long subjectAccountId) {}

    record ActionQueued(UUID actionId, Enum<?> kind, Authority authority) implements Event {}

    record DeliveryAttempt(
            UUID actionId,
            @Nullable Long workspaceId,
            int attempt,
            @Nullable String providerObjectId) implements Event {

        public DeliveryAttempt {
            // Provider references have several forms, including URLs and serialized receipts. Only IDs leave.
            if (providerObjectId != null
                    && !providerObjectId.matches(
                            "[0-9]+(?:[.][0-9]+)?|[CGDUW][0-9A-Z]{8,}|(?:IC|PRR|PRRC)_[A-Za-z0-9_-]+")) {
                providerObjectId = null;
            }
        }
    }

    record Silence(@Nullable Long workspaceId, UUID subjectId, Enum<?> reason) implements Event {}
}
