import { CircleDashedIcon, CircleHelpIcon, TrendingDownIcon, TrendingUpIcon } from "lucide-react";

import type { PracticeStanding } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";

export type TrendDirection = NonNullable<PracticeStanding["direction"]>;
/**
 * The server's default bundle: a practice's trend compares its last {@link TREND_BUNDLE} reviewed
 * pieces of work with the {@link TREND_BUNDLE} before them (`TrendProperties` `bundleSize` and
 * `minBundleSize`). A trend's own `TrendSupport` counts say it where a trend is at hand; this word
 * stands in only where they are not.
 */
export const TREND_BUNDLE = "four";

/** Both bundles together: the reviewed work a first trend needs (`2 * bundleSize`). */
export const TREND_FIRST_WORK = "eight";

/**
 * The trend in baseline words: the baseline is the reviewed work just before the latest, so it moves
 * with the work. The descriptions say it in plain words a developer reads at a glance; how the trend
 * compares is one sentence of its own (`TREND_EXPLAINED`). UNCERTAIN never claims no change, and
 * the two states without a direction say what is at hand now and promise nothing.
 */
export const PRACTICE_TREND_DEFS: StatusDefs<TrendDirection> = {
	IMPROVING: {
		label: "Increasing baseline",
		icon: TrendingUpIcon,
		badgeVariant: "success",
		description: "Your recent work meets this practice more often than before.",
	},
	DECLINING: {
		label: "Decreasing baseline",
		icon: TrendingDownIcon,
		badgeVariant: "destructive",
		description: "Your recent work meets this practice less often than before.",
	},
	UNCERTAIN: {
		label: "No clear baseline change",
		icon: CircleHelpIcon,
		badgeVariant: "secondary",
		description: "No clear direction yet between your recent work and the work before it.",
	},
	INSUFFICIENT_EVIDENCE: {
		label: "No baseline yet",
		icon: CircleDashedIcon,
		badgeVariant: "outline",
		description: `A trend needs ${TREND_FIRST_WORK} reviewed pieces of work. So far there are fewer.`,
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
