package de.tum.cit.aet.hephaestus.practices.usage;

/**
 * One raw figure of one period, as the release counts it. It exists only inside the release: nothing stores or sends
 * it, and {@link UsageDisclosure} decides what of it may leave.
 *
 * @param period the period key, such as {@code 2026-04}
 * @param figure what is counted: a {@code UsageFigure} name, or {@link #DISTINCT_PEOPLE}
 * @param workspaces how many workspaces add to the figure
 * @param total the figure over all workspaces
 * @param largest the largest part that one workspace adds
 */
record UsageCount(String period, String figure, long workspaces, long total, long largest) {

    /** The developers of the period counted once each, across workspaces. A person can work in two workspaces. */
    static final String DISTINCT_PEOPLE = "DISTINCT_PEOPLE";
}
