package de.tum.cit.aet.hephaestus.integration.core.handler;

import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The durable's workspace on the dispatch thread; installation deliveries have no workspace. */
public record IntegrationDispatchScope(@Nullable Long workspaceId) {

    private static final ThreadLocal<@Nullable IntegrationDispatchScope> CURRENT = new ThreadLocal<>();

    /** Empty outside dispatch; a present scope with a null id denotes an installation delivery. */
    public static Optional<IntegrationDispatchScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public <T extends @Nullable Object> T call(Supplier<T> work) {
        IntegrationDispatchScope previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
