import {
	CircleAlertIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleMinusIcon,
	CircleSlashIcon,
} from "lucide-react";

import type { PracticeGroupStanding } from "@/api/types.gen";

import type { StatusDef } from "@/components/common/status-def";

export type PracticeGroupStandingValue = PracticeGroupStanding["standing"];

/** Whose standing or trend a surface shows: one practice's, or a group's over its practices. */
export type StandingScope = "practice" | "group";

/**
 * A standing a review has settled; the other two say why none could be formed, and carry no trend.
 */
export function isSettledStanding(standing: PracticeGroupStandingValue): boolean {
	return standing !== "NOT_OBSERVED" && standing !== "NO_OPPORTUNITY";
}

export interface PracticeGroupStandingDef extends StatusDef {
	shortLabel: string;
}
export const PRACTICE_GROUP_STANDING_DEFS: Record<
	PracticeGroupStandingValue,
	PracticeGroupStandingDef
> = {
	DEVELOPING: {
		shortLabel: "Needs attention",
		label: "Needs attention",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "Recent reviews here were mostly problems.",
	},
	MIXED: {
		shortLabel: "Mixed",
		label: "Mixed feedback",
		icon: CircleMinusIcon,
		badgeVariant: "warning",
		description: "Recent reviews found both strengths and problems here.",
	},
	STRENGTH: {
		shortLabel: "Going well",
		label: "Going well",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Recent reviews here were almost entirely positive.",
	},
	NO_OPPORTUNITY: {
		shortLabel: "Nothing to report",
		label: "Nothing to report yet",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"Your work was reviewed, but nothing here could be judged: either these practices did not apply to it, or the evidence did not settle the question.",
	},
	NOT_OBSERVED: {
		shortLabel: "Not observed",
		label: "Not observed yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "No practice in this group has a current verdict for you.",
	},
};

/**
 * The same standings read for one practice. The sentences are the group's, except where the
 * group's names its practices: a practice no review has settled is one practice with no verdict,
 * not a group with none.
 */
export const PRACTICE_STANDING_DEFS: Record<PracticeGroupStandingValue, PracticeGroupStandingDef> =
	{
		...PRACTICE_GROUP_STANDING_DEFS,
		NOT_OBSERVED: {
			...PRACTICE_GROUP_STANDING_DEFS.NOT_OBSERVED,
			description: "This practice has no current verdict for you yet.",
		},
	};

/** The registry whose sentences are worded for whose standing it is. */
export const standingDefs = (scope: StandingScope) =>
	scope === "practice" ? PRACTICE_STANDING_DEFS : PRACTICE_GROUP_STANDING_DEFS;
