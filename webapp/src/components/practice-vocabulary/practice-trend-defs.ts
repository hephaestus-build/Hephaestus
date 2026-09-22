import { CircleDashedIcon, CircleHelpIcon, TrendingDownIcon, TrendingUpIcon } from "lucide-react";

import type { PracticeTrend } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type TrendDirection = PracticeTrend["direction"];
export const PRACTICE_TREND_DEFS: StatusDefs<TrendDirection> = {
	IMPROVING: {
		label: "More positive recently",
		icon: TrendingUpIcon,
		badgeVariant: "success",
		description: "Recent reviewed work carried more strengths than the stretch before it.",
	},
	DECLINING: {
		label: "More difficulties recently",
		icon: TrendingDownIcon,
		badgeVariant: "destructive",
		description: "Recent reviewed work carried more problems than the stretch before it.",
	},
	UNCERTAIN: {
		label: "Direction unclear",
		icon: CircleHelpIcon,
		badgeVariant: "secondary",
		description: "The two stretches were compared and did not separate far enough to call.",
	},
	INSUFFICIENT_EVIDENCE: {
		label: "Not enough to compare yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: "There is not yet enough reviewed work on both sides to compare.",
	},
};
