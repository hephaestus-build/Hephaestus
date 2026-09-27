const DATE_TIME = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const TIME = new Intl.DateTimeFormat(undefined, { timeStyle: "short" });

/** An ISO timestamp as the reader's local date and time; `undefined` for a missing or bad one. */
export function formatDateTime(iso: string | undefined): string | undefined {
	if (iso === undefined) {
		return undefined;
	}
	const time = Date.parse(iso);
	return Number.isNaN(time) ? undefined : DATE_TIME.format(time);
}

export function formatTime(iso: string): string {
	const time = Date.parse(iso);
	return Number.isNaN(time) ? iso : TIME.format(time);
}

const RELATIVE = new Intl.RelativeTimeFormat(undefined, { numeric: "auto", style: "short" });

/** Each unit, and how many of it make the next. */
const RELATIVE_UNITS: readonly (readonly [Intl.RelativeTimeFormatUnit, number])[] = [
	["minute", 60],
	["hour", 24],
	["day", 7],
	["week", 4.35],
	["month", 12],
	["year", Number.POSITIVE_INFINITY],
];

/**
 * How long before `now` an ISO timestamp was, as the reader says it: "5 min. ago", "yesterday". Both
 * come from data, never the clock, so a view renders the same thing for the same answer.
 */
export function formatRelative(iso: string, now: string): string | undefined {
	const then = Date.parse(iso);
	const reference = Date.parse(now);
	if (Number.isNaN(then) || Number.isNaN(reference)) {
		return undefined;
	}
	let value = (then - reference) / 60_000;
	for (const [unit, perNext] of RELATIVE_UNITS) {
		if (Math.abs(value) < perNext) {
			return RELATIVE.format(Math.round(value), unit);
		}
		value /= perNext;
	}
	return undefined;
}
