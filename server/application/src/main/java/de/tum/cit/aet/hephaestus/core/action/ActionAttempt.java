package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One claimed attempt of an action, as its adapter sees it.
 *
 * @param attempt the number of this attempt, from 1
 * @param epoch the lease this attempt holds; every write of the attempt names it
 * @param writeStarted an earlier attempt of the same action may have reached the provider; the adapter must read back
 *     before it writes again
 * @param fence call {@link Fence#beginWrite()} just before the provider call
 */
public record ActionAttempt(
        UUID id,
        @Nullable Long workspaceId,
        ActionKind kind,
        String idempotencyKey,
        JsonNode payload,
        int attempt,
        long epoch,
        boolean writeStarted,
        @Nullable String readbackRef,
        Fence fence,
        @Nullable PersonIdentity nativeRecipient) {

    /** Commits, under the lease, that a write starts now. */
    @FunctionalInterface
    public interface Fence {
        /**
         * Records that the provider call starts. Returns false if the lease has gone or the action was cancelled.
         */
        default boolean beginWrite() {
            return beginWrite(() -> true);
        }

        /**
         * Checks the additional database proof under the action lock after source admission. Hold any proof locks until
         * the marker commits. The predicate must not call the provider. A false result denies the write.
         */
        boolean beginWrite(BooleanSupplier additionalProof);
    }
}
