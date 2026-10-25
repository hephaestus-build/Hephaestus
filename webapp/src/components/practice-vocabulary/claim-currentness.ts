import type { ResultList } from "@/api/types.gen";

export type Currentness = ResultList["currentness"];

export const UNVERIFIED_HISTORY_NOTE =
	"Unverified history from an earlier review. It does not show whether the current standard is met.";

const STALE_NOTE = "The practice or the reviewed work changed after this observation.";

export const NON_CURRENT = {
	STALE: {
		badge: "Historical observation",
		badgeVariant: "warning",
		description: `${STALE_NOTE} It stays in the record, but it does not describe the current state.`,
		note: STALE_NOTE,
	},
	UNVERIFIABLE: {
		badge: "Unverified observation",
		badgeVariant: "outline",
		description:
			"The record of what this observation was based on is missing, or it comes from an earlier version of the review. It cannot show whether the practice’s current standard is met.",
		note: "We cannot verify what this observation was based on.",
	},
} as const satisfies Record<
	Exclude<Currentness, "CURRENT">,
	{
		badge: string;
		badgeVariant: "warning" | "outline";
		description: string;
		note: string;
	}
>;

export function claimCurrentnessNote(currentness: Currentness): string | undefined {
	return currentness === "CURRENT" ? undefined : NON_CURRENT[currentness].note;
}

export const CURRENTNESS_VALUES = [
	"CURRENT",
	"STALE",
	"UNVERIFIABLE",
] as const satisfies readonly Currentness[];
