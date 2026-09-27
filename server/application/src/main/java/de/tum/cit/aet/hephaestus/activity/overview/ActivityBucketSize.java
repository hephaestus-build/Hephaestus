package de.tum.cit.aet.hephaestus.activity.overview;

/** How long one time bucket of a range is; a longer range gets longer buckets. */
public enum ActivityBucketSize {
    DAY,
    /** From Monday. */
    WEEK,
    MONTH
}
