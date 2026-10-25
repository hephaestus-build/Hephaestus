import { ArchiveIcon, BadgeCheckIcon, HistoryIcon } from "lucide-react";

import type { PracticeStanding } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

/** What a standing was read from, strongest first. */
export type StandingBasis = NonNullable<PracticeStanding["basis"]>;

/** The two bases a standing reads only off results recorded before the current review records. */
export type EarlierBasis = Exclude<StandingBasis, "CURRENT_REVIEWS">;

const EARLIER_ASSESSMENTS =
	"This standing includes earlier assessments. They do not show whether the current criteria are met.";

/** The words beside a standing read only off earlier reviews, with no tooltip behind them. */
export const EARLIER_REVIEWS_LABEL = "Based on earlier reviews, the criteria might have changed";

/**
 * The one home of what an earlier-reviews standing means, for the Practice profile and Practices
 * across the workspace alike. A standing from current reviews needs no words: it is the default.
 */
export const STANDING_BASIS_DEFS: StatusDefs<StandingBasis> = {
	CURRENT_REVIEWS: {
		label: "Current reviews",
		icon: BadgeCheckIcon,
		badgeVariant: "outline",
		description: "Read from reviews of the work against the practice as it is now.",
	},
	EARLIER_REVIEWS: {
		label: EARLIER_REVIEWS_LABEL,
		icon: HistoryIcon,
		badgeVariant: "outline",
		description: `${EARLIER_ASSESSMENTS} No trend or feedback quotes are shown for those assessments.`,
	},
	EARLIER_REVIEWS_CRITERIA_UNKNOWN: {
		// The same words: a mark cannot tell a changed criterion from a lost one, and either way the
		// standing may not meet the criteria as they are now. Only the icon differs.
		label: EARLIER_REVIEWS_LABEL,
		icon: ArchiveIcon,
		badgeVariant: "outline",
		description: `${EARLIER_ASSESSMENTS} The original criterion version is unavailable. No trend or feedback quotes are shown for those assessments.`,
	},
};

export function isEarlierBasis(basis: StandingBasis | null | undefined): basis is EarlierBasis {
	return basis === "EARLIER_REVIEWS" || basis === "EARLIER_REVIEWS_CRITERIA_UNKNOWN";
}

/** Once above the bars of Practices across the workspace while a counted standing rests on earlier reviews. */
export const EARLIER_REVIEWS_COUNTED = {
	title: "Some standings here are based on earlier reviews",
	description:
		"Those assessments do not show whether the current criteria are met. Your Practice profile marks yours.",
} as const;
