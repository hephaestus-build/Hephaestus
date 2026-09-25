package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * Where a model's work goes, derived from the one fact an admin declares: systems the organisation
 * runs itself, or a provider under terms the organisation accepted. Strictest first; the enum order
 * is the ordering a developer's {@link MemberAiChoice} ceiling is compared against. {@code UNDECLARED}
 * sits outside every ceiling and serves only members who have not chosen.
 */
public enum DataHandlingTier {
    IN_HOUSE,
    CLOUD,
    UNDECLARED;

    public boolean isWithin(DataHandlingTier ceiling) {
        return this != UNDECLARED && ordinal() <= ceiling.ordinal();
    }
}
