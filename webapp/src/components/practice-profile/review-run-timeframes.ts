import { startOfDay, subDays } from "date-fns";

/**
 * How far back the list of reviews reaches. The list is paged, so "all time" is the default: a
 * window chosen for the reader would hide runs they can see by pressing once, which is the kind of
 * silent narrowing a reader has no way to notice.
 */
export const RUN_TIMEFRAMES = ["7d", "30d", "90d"] as const;

export type RunTimeframe = (typeof RUN_TIMEFRAMES)[number];

export const RUN_TIMEFRAME_LABELS: Record<RunTimeframe, string> = {
	"7d": "Last 7 days",
	"30d": "Last 30 days",
	"90d": "Last 90 days",
};

/** What the choice for no window is called, wherever it is named. */
export const ALL_TIME_LABEL = "All time";

const DAYS_BACK: Record<RunTimeframe, number> = { "7d": 7, "30d": 30, "90d": 90 };

/**
 * The moment a timeframe reaches back to, counted in whole days from midnight of the reader's own
 * day rather than from the instant they happen to be reading.
 *
 * Whole days on purpose: "Last 7 days" then means the same thing all day, so the query key it
 * becomes is stable and the list does not refetch on every tick of the shared clock.
 */
export function timeframeSince(timeframe: RunTimeframe | undefined, now: number): Date | undefined {
	if (timeframe === undefined) {
		return undefined;
	}
	return subDays(startOfDay(now), DAYS_BACK[timeframe]);
}
