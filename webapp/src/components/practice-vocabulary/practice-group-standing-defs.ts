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
			"Your work was reviewed, but nothing here could be judged — either these practices did not apply to it, or the evidence did not settle the question.",
	},
	NOT_OBSERVED: {
		shortLabel: "Not observed",
		label: "Not observed yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "No practice in this group has a current verdict for you.",
	},
};
