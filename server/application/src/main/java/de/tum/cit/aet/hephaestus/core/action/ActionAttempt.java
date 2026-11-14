package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import java.util.UUID;
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
         * Records that the provider call starts. It refuses when the lease is gone or the action was cancelled; the
         * adapter then must not call the provider, and returns {@link ActionResult.Abandoned}.
         */
        boolean beginWrite();
    }
}
