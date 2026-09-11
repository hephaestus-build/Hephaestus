package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Optional;

/**
 * A developer's choice in one workspace: the loosest {@link DataHandlingTier} they accept, or no AI
 * at all. Anything stricter than the ceiling also counts; absence is not consent to any tier.
 */
public enum MemberAiChoice {
    NO_AI,
    IN_HOUSE_ONLY,
    NOT_KEPT_ONLY,
    ANY_DECLARED;

    public Optional<DataHandlingTier> ceiling() {
        return switch (this) {
            case NO_AI -> Optional.empty();
            case IN_HOUSE_ONLY -> Optional.of(DataHandlingTier.IN_HOUSE);
            case NOT_KEPT_ONLY -> Optional.of(DataHandlingTier.PROVIDER_NOT_KEPT);
            case ANY_DECLARED -> Optional.of(DataHandlingTier.PROVIDER_KEPT);
        };
    }
}
