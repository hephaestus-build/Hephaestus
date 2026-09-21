import type { ObservationDetail } from "@/api/types.gen";

export type ClaimCurrentness = ObservationDetail["claimCurrentness"];

/**
 * The one line a developer's surface prints over an observation the current review rules did not
 * produce. A note rather than a badge: the observation is still shown in full, and the sentence
 * says what to make of it. `CURRENT` has no entry because a surface says nothing for it.
 */
export const CLAIM_CURRENTNESS_NOTES: Record<Exclude<ClaimCurrentness, "CURRENT">, string> = {
	STALE: "Reviewed under earlier rules for this practice.",
	UNVERIFIABLE: "The rules this was reviewed under can no longer be verified.",
};
