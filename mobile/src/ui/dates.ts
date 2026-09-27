import { formatDistanceStrict } from "date-fns";

// Hermes has no Intl.RelativeTimeFormat, which date-fns does not need.
const calendarDate = new Intl.DateTimeFormat(undefined, {
	day: "numeric",
	month: "short",
	year: "numeric",
});

const MINUTE = 60_000;
const WEEK = 7 * 24 * 60 * MINUTE;

/** "just now", "3 hours ago", "2 days ago", then a calendar date past a week. */
export function formatRelative(date: Date, now: number = Date.now()): string {
	const elapsed = now - date.getTime();
	if (elapsed < MINUTE) {
		return "just now";
	}
	if (elapsed < WEEK) {
		return formatDistanceStrict(date, now, { addSuffix: true, roundingMethod: "round" });
	}
	return calendarDate.format(date);
}

export function formatDate(date: Date): string {
	return calendarDate.format(date);
}
