import { CircleHelp, ClockAlert } from "lucide-react";

import type { ReviewObservation } from "@/api/types.gen";

export type Currentness = ReviewObservation["claimCurrentness"];

const STALE_NOTE = "The practice or the reviewed work changed after this observation.";

/** Shared wording distinguishes an unknown review basis from a changed practice or reviewed work. */
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
		badge: "Review basis unverified",
		badgeVariant: "outline",
		Icon: CircleHelp,
		title: "This observation’s review basis cannot be verified",
		description:
			"The recorded review basis is missing or uses an earlier assessment protocol. This observation cannot establish whether the current practice standard is met.",
		note: "The review basis for this observation cannot be verified.",
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
