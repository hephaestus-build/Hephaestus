package de.tum.cit.aet.hephaestus.workspace.spi;

/**
 * Where a model's work goes, derived from the one fact an admin declares: systems the organisation
 * runs itself, or a provider under terms the organisation accepted. Strictest first; {@code rank}
 * is the ordering a developer's {@link MemberAiChoice} ceiling is compared against, declared so reordering
 * the constants cannot widen a ceiling. {@code UNDECLARED} sits outside every ceiling and serves only
 * members who have not chosen.
 */
public enum DataHandlingTier {
    IN_HOUSE(0),
    CLOUD(1),
    UNDECLARED(2);

    /** {@code 0} is the strictest. */
    private final int rank;

    DataHandlingTier(int rank) {
        this.rank = rank;
    }

    public boolean isWithin(DataHandlingTier ceiling) {
        return this != UNDECLARED && rank <= ceiling.rank;
    }
}
