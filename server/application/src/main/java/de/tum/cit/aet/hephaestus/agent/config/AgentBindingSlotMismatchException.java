package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * A model cannot fill the tier row it was assigned to. The client names tiers from its own label
 * registry, so the response carries {@link #declaredTier()} and the detail stays label-free; an
 * undeclared model is not declared as any tier and carries none.
 */
public class AgentBindingSlotMismatchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final @Nullable DataHandlingTier declaredTier;

    private AgentBindingSlotMismatchException(String message, @Nullable DataHandlingTier declaredTier) {
        super(message);
        this.declaredTier = declaredTier;
    }

    public static AgentBindingSlotMismatchException declaredAs(DataHandlingTier declaredTier) {
        return new AgentBindingSlotMismatchException(
                "This model is declared as a different tier; assign it to that row.", declaredTier);
    }

    public static AgentBindingSlotMismatchException undeclared() {
        return new AgentBindingSlotMismatchException(
                "This model's data handling isn't declared yet. "
                        + "Declare it first, or assign it to Members who haven't chosen.",
                null);
    }

    public @Nullable DataHandlingTier declaredTier() {
        return declaredTier;
    }
}
