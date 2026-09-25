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
	/** The standing as the predicate of "n practices", so a count of them reads as one sentence. */
	predicate: { one: string; many: string };
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
		predicate: { one: "needs attention", many: "need attention" },
	},
	MIXED: {
		shortLabel: "Mixed",
		label: "Mixed feedback",
		icon: CircleMinusIcon,
		badgeVariant: "warning",
		description: "Recent reviews found both strengths and problems here.",
		predicate: { one: "shows mixed feedback", many: "show mixed feedback" },
	},
	STRENGTH: {
		shortLabel: "Going well",
		label: "Going well",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Recent reviews here were almost entirely positive.",
		predicate: { one: "is going well", many: "are going well" },
	},
	NO_OPPORTUNITY: {
		shortLabel: "Nothing to report",
		label: "Nothing to report yet",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"Your work was reviewed, but nothing here could be judged: either these practices did not apply to it, or the evidence did not settle the question.",
		predicate: { one: "has nothing to report", many: "have nothing to report" },
	},
	NOT_OBSERVED: {
		shortLabel: "Not observed",
		label: "Not observed yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "No practice in this group has been observed in your work yet.",
		predicate: { one: "is not observed yet", many: "are not observed yet" },
	},
};

/**
 * The same standings read for one practice. The sentences are the group's, except where the
 * group's speak of its practices: a practice is one practice, not a group of them.
 */
export const PRACTICE_STANDING_DEFS: Record<PracticeGroupStandingValue, PracticeGroupStandingDef> =
	{
		...PRACTICE_GROUP_STANDING_DEFS,
		NO_OPPORTUNITY: {
			...PRACTICE_GROUP_STANDING_DEFS.NO_OPPORTUNITY,
			description:
				"Your work was reviewed, but this practice could not be judged on it: either it did not apply, or the evidence did not settle the question.",
		},
		NOT_OBSERVED: {
			...PRACTICE_GROUP_STANDING_DEFS.NOT_OBSERVED,
			description: "No review has observed this practice in your work yet.",
		},
	};

/** The registry whose sentences are worded for whose standing it is. */
export const standingDefs = (scope: StandingScope) =>
	scope === "practice" ? PRACTICE_STANDING_DEFS : PRACTICE_GROUP_STANDING_DEFS;
