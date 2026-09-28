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
 * - `formatShortDay`, "9 Sep": a day in a row of them, where the full month would widen the row
 *   past its words.
 * - `formatWeekdayDay`, "Monday, 9 September": a day at the head of the rows that fall on it, where
 *   the weekday is what a reader places their own week by, with the year only when it is not
 *   `today`'s.
 * - `formatDayRange`, "3–9 September 2026": the days from one to another, both included, saying
 *   the month and the year once where the two share them.
 * - `formatDayTime`, "9 September, 2:10 pm", and `formatTime`, "2:10 pm": the moment a run
 *   happened, the hour written the English way rather than on a 24-hour clock.
 *
 * The overloaded ones take the timestamp as it reaches the caller and narrow it through
 * {@link asDate} themselves: date-fns throws on an Invalid Date, and a wire timestamp that never
 * parsed would otherwise take the whole render down. A value that is not a moment formats to
 * `undefined`, which a caller that has already narrowed its own `Date` never sees.
 */
export function formatDay(date: Date): string;
export function formatDay(date: DateLike): string | undefined;
export function formatDay(date: DateLike): string | undefined {
	return formatAs(date, "d MMMM");
}

export function formatWeekdayDay(date: Date, today: Date): string {
	return format(date, isSameYear(date, today) ? "EEEE, d MMMM" : "EEEE, d MMMM yyyy");
}

export function formatDayRange(from: Date, to: Date): string {
	if (isSameDay(from, to)) {
		return format(to, "d MMMM yyyy");
	}
	if (isSameMonth(from, to)) {
		return `${format(from, "d")}–${format(to, "d MMMM yyyy")}`;
	}
	if (isSameYear(from, to)) {
		return `${format(from, "d MMMM")} – ${format(to, "d MMMM yyyy")}`;
	}
	return `${format(from, "d MMMM yyyy")} – ${format(to, "d MMMM yyyy")}`;
}

export function formatShortDay(date: Date): string;
export function formatShortDay(date: DateLike): string | undefined;
export function formatShortDay(date: DateLike): string | undefined {
	return formatAs(date, "d MMM");
}

export function formatTime(date: Date): string;
export function formatTime(date: DateLike): string | undefined;
export function formatTime(date: DateLike): string | undefined {
	// `aaa` is date-fns' lower-case "am"/"pm"; `a` would shout it.
	return formatAs(date, "h:mm aaa");
}

export function formatDayTime(date: Date): string;
export function formatDayTime(date: DateLike): string | undefined;
export function formatDayTime(date: DateLike): string | undefined {
	const at = asDate(date);
	return at && `${formatDay(at)}, ${formatTime(at)}`;
}

function formatAs(date: DateLike, pattern: string): string | undefined {
	const at = asDate(date);
	return at && format(at, pattern);
}

/** The browser's IANA time zone, whose midnights start a summary's days, weeks and months. */
export function browserTimeZone(): string {
	return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
