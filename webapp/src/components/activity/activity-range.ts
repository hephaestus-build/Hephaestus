import { startOfDay, subDays } from "date-fns";

import type { FilterOption } from "@/components/common/FilterToggle";

/** How far back a summary looks, always ending now. */
export const ACTIVITY_RANGES = ["7d", "30d", "90d", "1y"] as const;

export type ActivityRange = (typeof ACTIVITY_RANGES)[number];

export const DEFAULT_ACTIVITY_RANGE: ActivityRange = "7d";

interface ActivityRangeDef {
	days: number;
	/** "Last 7 days": the range as a control or a caption names it. */
	label: string;
	/** "7 days": the toggle row's chip, where width is the constraint. */
	shortLabel: string;
}

export const ACTIVITY_RANGE_DEFS = {
	"7d": { days: 7, label: "Last 7 days", shortLabel: "7 days" },
	"30d": { days: 30, label: "Last 30 days", shortLabel: "30 days" },
	"90d": { days: 90, label: "Last 90 days", shortLabel: "90 days" },
	"1y": {
		days: 365,
		label: "Last 12 months",
		shortLabel: "12 months",
	},
} as const satisfies Record<ActivityRange, ActivityRangeDef>;

export const ACTIVITY_RANGE_OPTIONS: readonly FilterOption<ActivityRange>[] = ACTIVITY_RANGES.map(
	(value) => ({
		value,
		label: ACTIVITY_RANGE_DEFS[value].label,
		shortLabel: ACTIVITY_RANGE_DEFS[value].shortLabel,
	}),
);

/**
 * The start of the range: local midnight `days - 1` days ago, so the range is today and the days
 * before it. Flooring to the day keeps the query key stable while the clock ticks.
 */
export function rangeStart(nowMs: number, range: ActivityRange): Date {
	return subDays(startOfDay(nowMs), ACTIVITY_RANGE_DEFS[range].days - 1);
}
