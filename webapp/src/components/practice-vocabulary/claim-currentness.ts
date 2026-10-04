import { CircleHelp, ClockAlert } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";

export type Currentness = ReviewObservation["claimCurrentness"];

const STALE_NOTE = "The practice or the reviewed work changed after this observation.";

/** Shared wording distinguishes an unverifiable observation from a changed practice or reviewed work. */
export const NON_CURRENT = {
	STALE: {
		badge: "Historical observation",
		badgeVariant: "warning",
		Icon: ClockAlert,
		title: "This observation is no longer current",
		description: `${STALE_NOTE} The earlier result remains in the record, but it does not describe the current state.`,
		note: STALE_NOTE,
	},
	UNVERIFIABLE: {
		badge: "Unverified observation",
		badgeVariant: "outline",
		Icon: CircleHelp,
		title: "We cannot verify this observation",
		description:
			"The record of what this observation was based on is missing, or it comes from an earlier version of the review. It cannot show whether the practice’s current standard is met.",
		note: "We cannot verify what this observation was based on.",
	},
} as const satisfies Record<
	Exclude<Currentness, "CURRENT">,
	{
		badge: string;
		badgeVariant: "warning" | "outline";
		Icon: typeof ClockAlert;
		title: string;
		description: string;
		note: string;
	}
>;

export function claimCurrentnessNote(currentness: Currentness): string | undefined {
	return currentness === "CURRENT" ? undefined : NON_CURRENT[currentness].note;
}
