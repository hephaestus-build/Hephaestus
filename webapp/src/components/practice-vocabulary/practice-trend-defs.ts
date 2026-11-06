import { CircleDashedIcon, CircleHelpIcon, TrendingDownIcon, TrendingUpIcon } from "lucide-react";

import type { PracticeStanding } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type TrendDirection = NonNullable<PracticeStanding["direction"]>;
/** The direction compares recent reviewed work with the work before it. */
export const PRACTICE_TREND_DEFS: StatusDefs<TrendDirection> = {
	IMPROVING: {
		label: "Met more often",
		icon: TrendingUpIcon,
		badgeVariant: "success",
		description: "Your recent work meets this practice more often than before.",
	},
	DECLINING: {
		label: "Met less often",
		icon: TrendingDownIcon,
		badgeVariant: "destructive",
		description: "Your recent work meets this practice less often than before.",
	},
	UNCERTAIN: {
		label: "No clear direction",
		icon: CircleHelpIcon,
		badgeVariant: "secondary",
		description: "No clear direction yet between your recent work and the work before it.",
	},
	INSUFFICIENT_EVIDENCE: {
		label: "Not enough to compare",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "A trend needs more reviewed work. So far there is too little.",
	},
};

/**
 * A group's trend in the same plain words, without a bundle size: the server pools the trends of
 * the group's practices that can be compared (`GroupTrendAggregator`), each over its own work.
 */
export const GROUP_TREND_DESCRIPTIONS: Record<TrendDirection, string> = {
	IMPROVING: "Your recent work meets this group's practices more often than before.",
	DECLINING: "Your recent work meets this group's practices less often than before.",
	UNCERTAIN:
		"No clear direction yet between your recent work in this group and the work before it.",
	INSUFFICIENT_EVIDENCE:
		"A trend needs more reviewed work in this group. So far there is too little.",
};
