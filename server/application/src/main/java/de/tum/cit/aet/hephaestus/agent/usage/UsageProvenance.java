package de.tum.cit.aet.hephaestus.agent.usage;

/** Which observation source supplied a ledger row's token buckets. */
public enum UsageProvenance {
    /** Every bucket came from the runner's own report, whose prompt and output totals covered the proxy's. */
    RUNNER,

    /**
     * Every bucket came from the proxy's per-call accumulation: it covered the runner's totals, or the two were
     * incomparable and the row is unverifiable.
     */
    PROXY,

    /** Rows recorded before one source was selected whole: a per-bucket maximum of both. No longer written. */
    MERGED,

    /** Neither source had tokens. The row is UNPRICED: this is an admission, not a $0. */
    NONE,
}
