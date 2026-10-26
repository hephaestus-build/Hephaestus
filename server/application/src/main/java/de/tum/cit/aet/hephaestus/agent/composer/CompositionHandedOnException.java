package de.tum.cit.aet.hephaestus.agent.composer;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Composition stopped for a passing cause before the retry limit: the destinations it left open are not withheld, and
 * the attempt is handed on so that another composes them.
 */
public final class CompositionHandedOnException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final CompositionStop stop;

    public CompositionHandedOnException(CompositionStop stop, @Nullable Throwable cause) {
        super("Composition is handed on: " + stop.tag(), cause);
        this.stop = stop;
    }

    public CompositionStop stop() {
        return stop;
    }
}
