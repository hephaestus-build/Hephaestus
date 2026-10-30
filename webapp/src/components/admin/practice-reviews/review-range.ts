import { startOfDay, subDays } from "date-fns";

import type { FilterOption } from "@/components/common/FilterToggle";

/** Every review since the workspace's first: no first day, and no period before to compare with. */
export const ALL_TIME = "all";

/**
 * How far back the practice review overview looks, always ending now. Practice reviews offer today:
 * an admin checks the day's reviews as they arrive. In place of 12 months, they offer all time: a
 * course is shorter than a year, so all of it is the longer view an admin wants.
 */
export const REVIEW_RANGES = ["today", "7d", "30d", "90d", ALL_TIME] as const;

export type ReviewRange = (typeof REVIEW_RANGES)[number];

/** A range with a first day, which `reviewRangeStart` finds: every range but all time. */
export type DatedReviewRange = Exclude<ReviewRange, typeof ALL_TIME>;

interface ReviewRangeDef {
	/** How many days back, today included; all time has none, and `reviewRangeStart` never takes it. */
	days: number;
	/** "Last 7 days": the range as a control or a caption names it. */
	label: string;
	/** "7 days": the toggle row's chip, where width is the constraint. */
	shortLabel: string;
	/** "in the last 7 days", "today": the range inside a sentence. */
	during: string;
	/** "the previous 7 days": the period of the same length before, which a figure is set against. */
	previous: string;
}

export const REVIEW_RANGE_DEFS = {
	today: {
		days: 1,
		label: "Today",
		shortLabel: "Today",
		during: "today",
		previous: "yesterday",
	},
	"7d": {
		days: 7,
		label: "Last 7 days",
		shortLabel: "7 days",
		during: "in the last 7 days",
		previous: "the previous 7 days",
	},
	"30d": {
		days: 30,
		label: "Last 30 days",
		shortLabel: "30 days",
		during: "in the last 30 days",
		previous: "the previous 30 days",
	},
	"90d": {
		days: 90,
		label: "Last 90 days",
		shortLabel: "90 days",
		during: "in the last 90 days",
		previous: "the previous 90 days",
	},
	[ALL_TIME]: {
		days: 0,
		label: "All time",
		shortLabel: "All time",
		during: "over all time",
		previous: "no earlier period",
	},
} as const satisfies Record<ReviewRange, ReviewRangeDef>;

export const REVIEW_RANGE_OPTIONS: readonly FilterOption<ReviewRange>[] = REVIEW_RANGES.map(
	(value) => ({
		value,
		label: REVIEW_RANGE_DEFS[value].label,
		shortLabel: REVIEW_RANGE_DEFS[value].shortLabel,
	}),
);

/**
 * The start of the range: local midnight `days - 1` days ago, so the range is today and the days
 * before it. Flooring to the day keeps the query key stable while the clock ticks.
 */
export function reviewRangeStart(nowMs: number, range: DatedReviewRange): Date {
	return subDays(startOfDay(nowMs), REVIEW_RANGE_DEFS[range].days - 1);
}
