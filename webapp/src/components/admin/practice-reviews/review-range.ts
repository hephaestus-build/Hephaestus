import { startOfDay, subDays } from "date-fns";

import type { FilterOption } from "@/components/common/FilterToggle";

/** How far back a summary looks, always ending now. */
export const REVIEW_RANGES = ["7d", "30d", "90d", "1y"] as const;

export type ReviewRange = (typeof REVIEW_RANGES)[number];

interface ReviewRangeDef {
	days: number;
	/** "Last 7 days": the range as a control or a caption names it. */
	label: string;
	/** "7 days": the toggle row's chip, where width is the constraint. */
	shortLabel: string;
	/** "the last 7 days": the range inside a sentence, after "in". */
	inSentence: string;
	/** "the previous 7 days": the period of the same length before, which a figure is set against. */
	previous: string;
}

export const REVIEW_RANGE_DEFS = {
	"7d": {
		days: 7,
		label: "Last 7 days",
		shortLabel: "7 days",
		inSentence: "the last 7 days",
		previous: "the previous 7 days",
	},
	"30d": {
		days: 30,
		label: "Last 30 days",
		shortLabel: "30 days",
		inSentence: "the last 30 days",
		previous: "the previous 30 days",
	},
	"90d": {
		days: 90,
		label: "Last 90 days",
		shortLabel: "90 days",
		inSentence: "the last 90 days",
		previous: "the previous 90 days",
	},
	"1y": {
		days: 365,
		label: "Last 12 months",
		shortLabel: "12 months",
		inSentence: "the last 12 months",
		previous: "the previous 12 months",
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
export function reviewRangeStart(nowMs: number, range: ReviewRange): Date {
	return subDays(startOfDay(nowMs), REVIEW_RANGE_DEFS[range].days - 1);
}
