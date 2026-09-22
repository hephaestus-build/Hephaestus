package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Optional;

/**
 * A developer's choice: the loosest {@link DataHandlingTier} they accept, or no AI at all. In-house
 * only, in-house or a provider (the cloud), or nothing. Absence is not consent to any tier.
 */
public enum MemberAiChoice {
    NO_AI,
    IN_HOUSE_ONLY,
    CLOUD;

    public Optional<DataHandlingTier> ceiling() {
        return switch (this) {
            case NO_AI -> Optional.empty();
            case IN_HOUSE_ONLY -> Optional.of(DataHandlingTier.IN_HOUSE);
            case CLOUD -> Optional.of(DataHandlingTier.CLOUD);
        };
    }
}
