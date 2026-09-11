package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * How a model handles the work it receives, derived from the two facts an admin declares. Strictest
 * first; the enum order is the ordering a developer's {@link MemberAiChoice} ceiling is compared
 * against. {@code UNDECLARED} sits outside every ceiling and serves only members who have not chosen.
 */
public enum DataHandlingTier {
    IN_HOUSE,
    PROVIDER_NOT_KEPT,
    PROVIDER_KEPT,
    UNDECLARED;

    public boolean isWithin(DataHandlingTier ceiling) {
        return this != UNDECLARED && ordinal() <= ceiling.ordinal();
    }
}
