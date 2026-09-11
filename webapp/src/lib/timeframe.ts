import {
	addDays,
	addWeeks,
	endOfMonth,
	formatISO,
	getISODay,
	isEqual,
	parseISO,
	setHours,
	setMilliseconds,
	setMinutes,
	setSeconds,
	startOfDay,
	startOfMonth,
	subMonths,
	subWeeks,
} from "date-fns";

export type TimeframePreset =
	| "all-activity"
	| "this-week"
	| "last-week"
	| "this-month"
	| "last-month"
	| "custom";

export interface LeaderboardSchedule {
	/** 1 = Monday, 7 = Sunday (ISO weekday) */
	day: number;
	hour: number;
	minute: number;
}

export const DEFAULT_SCHEDULE: LeaderboardSchedule = {
	day: 1, // Monday
	hour: 9,
	minute: 0,
};

/**
 * Set a date to a specific ISO weekday and time.
 * Day is 1-7 where 1 = Monday (ISO standard).
 */
function setToScheduledTime(date: Date, dayOfWeek: number, hour: number, minute: number): Date {
	const currentISODay = getISODay(date);
	const diff = dayOfWeek - currentISODay;
	const adjustedDate = addDays(date, diff);
	return setMilliseconds(setSeconds(setMinutes(setHours(adjustedDate, hour), minute), 0), 0);
}

/**
 * Calculate the start of the current leaderboard week based on schedule.
 */
export function getLeaderboardWeekStart(
	now: Date,
	schedule: LeaderboardSchedule = DEFAULT_SCHEDULE,
): Date {
	const currentISODay = getISODay(now);
	const currentTime = now.getHours() * 60 + now.getMinutes() + now.getSeconds() / 60;
	const scheduledTime = schedule.hour * 60 + schedule.minute;

	// If we're past the scheduled day OR on the scheduled day past the scheduled time
	const pastSchedule =
		currentISODay > schedule.day ||
		(currentISODay === schedule.day && currentTime >= scheduledTime);

	if (pastSchedule) {
		return setToScheduledTime(now, schedule.day, schedule.hour, schedule.minute);
	}
	// Haven't reached the schedule yet, use last week's scheduled day
	return setToScheduledTime(subWeeks(now, 1), schedule.day, schedule.hour, schedule.minute);
}

/**
 * Calculate the start of last leaderboard week.
 */
function getLastLeaderboardWeekStart(
	now: Date,
	schedule: LeaderboardSchedule = DEFAULT_SCHEDULE,
): Date {
	const thisWeekStart = getLeaderboardWeekStart(now, schedule);
	return subWeeks(thisWeekStart, 1);
}

/**
 * Get the end of a leaderboard week (start + 1 week).
 */
export function getLeaderboardWeekEnd(weekStart: Date): Date {
	return addWeeks(weekStart, 1);
}

/**
 * Calculate date range for a given preset.
 * Returns { after, before } where before is undefined for open-ended ranges.
 */
export function getDateRangeForPreset(
	instant: Date,
	preset: TimeframePreset,
	schedule: LeaderboardSchedule = DEFAULT_SCHEDULE,
	customRange?: { from: Date; to?: Date },
): { after: Date; before: Date | undefined } {
	// Copied before truncating: the caller's instant is shared with everything else it renders.
	const now = new Date(instant);
	now.setSeconds(0, 0);

	switch (preset) {
		case "all-activity":
			return {
				after: new Date(0),
				before: undefined,
			};

		case "this-week": {
			const weekStart = getLeaderboardWeekStart(now, schedule);
			return {
				after: weekStart,
				before: undefined, // Open-ended to show activity "so far"
			};
		}

		case "last-week": {
			const lastWeekStart = getLastLeaderboardWeekStart(now, schedule);
			const lastWeekEnd = getLeaderboardWeekEnd(lastWeekStart);
			return {
				after: lastWeekStart,
				before: lastWeekEnd, // Bounded - it's a completed week
			};
		}

		case "this-month": {
			return {
				after: startOfMonth(now),
				before: undefined, // Open-ended
			};
		}

		case "last-month": {
			return {
				after: startOfMonth(subMonths(now, 1)),
				before: startOfMonth(now), // Bounded - completed month
			};
		}

		case "custom": {
			if (!customRange?.from) {
				// Fallback to last 7 days
				return {
					after: addDays(now, -7),
					before: now,
				};
			}
			if (customRange.to) {
				return {
					after: startOfDay(customRange.from),
					before: addDays(startOfDay(customRange.to), 1), // Exclusive end
				};
			}
			// Only start date provided - open-ended
			return {
				after: startOfDay(customRange.from),
				before: undefined,
			};
		}
	}
}

/**
 * Format a date range to ISO strings for API/URL consumption.
 */
export function formatDateRangeForApi(range: { after: Date; before: Date | undefined }): {
	after: string;
	before: string | undefined;
} {
	return {
		after: formatISO(range.after),
		before: range.before ? formatISO(range.before) : undefined,
	};
}

/**
 * Simple label for dropdown items - clean and scannable.
 * Used inside SelectItem components.
 */
export function formatDropdownLabel(preset: TimeframePreset): string {
	switch (preset) {
		case "all-activity":
			return "All time";
		case "this-week":
			return "This week";
		case "last-week":
			return "Last week";
		case "this-month":
			return "This month";
		case "last-month":
			return "Last month";
		case "custom":
			return "Custom range";
	}
}

/**
 * Try to detect which preset matches a given date range.
 */
export function detectPresetFromDates(
	now: Date,
	afterStr?: string,
	beforeStr?: string,
	schedule: LeaderboardSchedule = DEFAULT_SCHEDULE,
	enableAllActivity = false,
): TimeframePreset {
	if (!afterStr) {
		return beforeStr ? "custom" : enableAllActivity ? "all-activity" : "this-week";
	}

	const after = parseISO(afterStr);
	const before = beforeStr ? parseISO(beforeStr) : undefined;

	// All time is open-ended; a bounded epoch range is still a custom selection.
	if (enableAllActivity && !before && isEqual(after, new Date(0))) {
		return "all-activity";
	}

	const thisWeekStart = getLeaderboardWeekStart(now, schedule);
	if (isEqual(after, thisWeekStart) && !before) {
		return "this-week";
	}

	const thisWeekEnd = getLeaderboardWeekEnd(thisWeekStart);
	if (isEqual(after, thisWeekStart) && before && isEqual(before, thisWeekEnd)) {
		return "this-week";
	}

	const lastWeekStart = getLastLeaderboardWeekStart(now, schedule);
	const lastWeekEnd = getLeaderboardWeekEnd(lastWeekStart);
	if (isEqual(after, lastWeekStart) && before && isEqual(before, lastWeekEnd)) {
		return "last-week";
	}

	const thisMonthStart = startOfMonth(now);
	if (isEqual(after, thisMonthStart) && !before) {
		return "this-month";
	}

	const nextMonthStart = startOfMonth(addDays(endOfMonth(now), 1));
	if (isEqual(after, thisMonthStart) && before && isEqual(before, nextMonthStart)) {
		return "this-month";
	}

	const lastMonthStart = startOfMonth(subMonths(now, 1));
	if (isEqual(after, lastMonthStart) && before && isEqual(before, thisMonthStart)) {
		return "last-month";
	}

	return "custom";
}
