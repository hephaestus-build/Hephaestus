package de.tum.cit.aet.hephaestus.integration.core.spi;

/** What the checks of an item's current head say, for a practice whose judgment needs them settled. */
public enum ItemChecks {
    /** Every check of the head was read and has ended. */
    SETTLED,
    /** A check of the head still runs, or the provider has not listed all of them yet. */
    PENDING,
    /** A check waits for a maintainer to approve its workflows, as for a pull request from a fork. */
    AWAITING_APPROVAL,
    /** No check reported on the head, or the source does not read checks. */
    NONE_REPORTED
}
