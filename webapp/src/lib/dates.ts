import { format, isSameDay, isSameMonth, isSameYear } from "date-fns";

export type DateLike = Date | string | undefined | null;

/**
 * Narrow a timestamp to a `Date` the caller can format, or `undefined`. It exists for the two things
 * the type `Date | undefined` cannot say, both of which reach the screen as visible nonsense:
 *
 * - An Invalid Date is still a `Date`, and `.toLocaleDateString()` on one renders the literal text
 *   "Invalid Date".
 * - A value that never passed through a generated response transformer — a hand-written fixture, a
 *   cache entry set directly — is still the ISO string its type calls a `Date`.
 *
 * Both degrade to `undefined` rather than to a fabricated `now`, leaving the caller its own fallback:
 * `asDate(value)?.toLocaleDateString() ?? "–"`.
 */
export function asDate(value: DateLike): Date | undefined {
	if (value == null) {
		return undefined;
	}
	const date = value instanceof Date ? value : new Date(value);
	return Number.isNaN(date.getTime()) ? undefined : date;
}

/**
 * A generated view as it looks *on the wire*: every `Date` in it, however deeply nested, is a
 * string. Type MSW fixtures with this — they are serialised JSON, which has no date type.
 */
export type Wire<T> = T extends Date
	? string
	: T extends readonly (infer Element)[]
		? Wire<Element>[]
		: T extends object
			? { [Key in keyof T]: Wire<T[Key]> }
			: T;

/**
 * The day and the time of day as the practice surfaces write them, in one home so a card, a
 * header and a timeline cannot each spell September their own way. date-fns rather than `Intl`,
 * whose en-GB short month is "Sept".
 *
 * - `formatDay`, "9 September": a day named in prose.
 * - `formatDate`, "9 September 2026": a day that may lie in another year, such as when something
 *   was created.
 * - `formatShortDay`, "9 Sep": a day in a row of them, where the full month would widen the row
 *   past its words.
 * - `formatDayTime`, "9 September, 2:10 pm", and `formatTime`, "2:10 pm": the moment a run
 *   happened, the hour written the English way rather than on a 24-hour clock; `formatDayTime`
 *   adds the year when it is not `today`'s.
 * - `formatWeekdayDay`, "Monday, 9 September": a day heading a list of what happened on it, with
 *   the year only when it is not `today`'s.
 * - `formatDayRange`, "3–9 September 2026": the days from one to another, both included, saying
 *   the month and the year once where the two share them.
 */
export function formatDay(date: Date): string {
	return format(date, "d MMMM");
}

export function formatDate(date: Date): string {
	return format(date, "d MMMM yyyy");
}

/**
 * A wire calendar date (`format: date`), written as `formatDate` writes a day. The generated response
 * transformer parses one as UTC midnight, so its day is read in UTC; the local day is the one before
 * anywhere west of UTC.
 */
const UTC_DATE_FIELDS = {
	day: "numeric",
	month: "long",
	year: "numeric",
	timeZone: "UTC",
} as const;
const UTC_DATE_FORMAT = new Intl.DateTimeFormat("en-GB", UTC_DATE_FIELDS);
const UTC_SHORT_DAY_FORMAT = new Intl.DateTimeFormat("en-GB", {
	day: "numeric",
	month: "short",
	timeZone: "UTC",
});

export function formatCalendarDate(date: Date): string {
	return date.toLocaleDateString("en-GB", UTC_DATE_FIELDS);
}

/** Calendar days of an actual UTC interval, without projecting its instants into local midnight. */
export function formatUtcDayRange(from: Date, to: Date): string {
	return UTC_DATE_FORMAT.formatRange(from, to);
}

export function formatUtcShortDay(date: Date): string {
	return UTC_SHORT_DAY_FORMAT.format(date);
}

export function formatWeekdayDay(date: Date, today: Date): string {
	return format(date, isSameYear(date, today) ? "EEEE, d MMMM" : "EEEE, d MMMM yyyy");
}

export function formatDayRange(from: Date, to: Date): string {
	if (isSameDay(from, to)) {
		return formatDate(to);
	}
	if (isSameMonth(from, to)) {
		return `${format(from, "d")}–${formatDate(to)}`;
	}
	if (isSameYear(from, to)) {
		return `${formatDay(from)} – ${formatDate(to)}`;
	}
	return `${formatDate(from)} – ${formatDate(to)}`;
}

export function formatShortDay(date: Date): string {
	return format(date, "d MMM");
}

export function formatTime(date: Date): string {
	// `aaa` is date-fns' lower-case "am"/"pm"; `a` would shout it.
	return format(date, "h:mm aaa");
}

export function formatDayTime(date: Date, today: Date): string {
	return `${isSameYear(date, today) ? formatDay(date) : formatDate(date)}, ${formatTime(date)}`;
}

/**
 * Every IANA time zone this browser knows, with UTC first. Chromium and Node list no "UTC", and a
 * choice whose default is UTC must be able to return to it.
 */
export const TIME_ZONES: readonly string[] = [
	"UTC",
	...Intl.supportedValuesOf("timeZone").filter((zone) => zone !== "UTC"),
];

/** The browser's IANA time zone, whose midnights start a summary's days, weeks and months. */
export function browserTimeZone(): string {
	return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

const MINUTE = 60;
const HOUR = 60 * MINUTE;

/**
 * A duration as a reader says it, in one format for every trace, sheet and card: "0 s", "42 s",
 * "6 min", "1 h 20 min", "2 d 3 h". Under an hour one unit is enough; above it, the next smaller
 * unit keeps the figure from rounding away most of an hour. The figure is rounded to the units it
 * is said in before the units are chosen, so it never says "60 min" or "1 h 60 min".
 */
export function durationText(seconds: number): string {
	const whole = Math.max(0, Math.round(seconds));
	if (whole < MINUTE) {
		return `${whole} s`;
	}
	const minutes = Math.round(whole / MINUTE);
	if (minutes < 60) {
		return `${minutes} min`;
	}
	if (minutes < 24 * 60) {
		const hours = Math.floor(minutes / 60);
		const rest = minutes % 60;
		return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
	}
	const totalHours = Math.round(whole / HOUR);
	const days = Math.floor(totalHours / 24);
	const hours = totalHours % 24;
	return hours === 0 ? `${days} d` : `${days} d ${hours} h`;
}

/** The time between two moments in {@link durationText}'s words, whichever comes first. */
export function durationBetween(from: Date, to: Date): string {
	return durationText(Math.abs(to.getTime() - from.getTime()) / 1000);
}
