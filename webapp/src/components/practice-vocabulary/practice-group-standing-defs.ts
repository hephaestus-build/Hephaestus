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
	/** The standing as the title over a count of practices: "Practices going well". */
	practicesTitle: string;
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
		description: "Recent reviews here mostly found problems.",
		predicate: { one: "needs attention", many: "need attention" },
		practicesTitle: "Practices needing attention",
	},
	MIXED: {
		shortLabel: "Mixed",
		label: "Mixed feedback",
		icon: CircleMinusIcon,
		badgeVariant: "warning",
		description: "Recent reviews found both strengths and problems here.",
		predicate: { one: "shows mixed feedback", many: "show mixed feedback" },
		practicesTitle: "Practices with mixed feedback",
	},
	STRENGTH: {
		shortLabel: "Going well",
		label: "Going well",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "Recent reviews here were almost entirely positive.",
		predicate: { one: "is going well", many: "are going well" },
		practicesTitle: "Practices going well",
	},
	// Both unsettled standings have one name, No standing yet: a developer reads them as one. The
	// sentence keeps why, and the icon tells them apart.
	NO_OPPORTUNITY: {
		shortLabel: "No standing yet",
		label: "No standing yet",
		icon: CircleSlashIcon,
		badgeVariant: "outline",
		description:
			"Your work was reviewed, but nothing here could be judged. Either these practices did not apply to it, or the evidence did not settle the question.",
		predicate: { one: "has no standing yet", many: "have no standing yet" },
		practicesTitle: "Practices with no standing yet",
	},
	NOT_OBSERVED: {
		shortLabel: "No standing yet",
		label: "No standing yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "No review has looked at a practice in this group in your work yet.",
		predicate: { one: "has no standing yet", many: "have no standing yet" },
		practicesTitle: "Practices with no standing yet",
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
				"Your work was reviewed, but this practice could not be judged on it. Either it did not apply, or the evidence did not settle the question.",
		},
		NOT_OBSERVED: {
			...PRACTICE_GROUP_STANDING_DEFS.NOT_OBSERVED,
			description: "No review has looked at this practice in your work yet.",
		},
	};

/** The registry whose sentences are worded for whose standing it is. */
export const standingDefs = (scope: StandingScope) =>
	scope === "practice" ? PRACTICE_STANDING_DEFS : PRACTICE_GROUP_STANDING_DEFS;
