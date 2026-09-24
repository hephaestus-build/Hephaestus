import { format } from "date-fns";

export type DateLike = Date | string | undefined | null;

/**
 * Narrow a timestamp to a `Date` the caller can format, or `undefined`. It exists for the two
 * things the type `Date | undefined` cannot say, both of which reach the screen as visible
 * nonsense:
 *
 * - An Invalid Date is still a `Date`, and `.toLocaleDateString()` on one renders the literal text
 *   "Invalid Date".
 * - A value that never passed through a generated response transformer — a hand-written fixture, a
 *   cache entry set directly — is still the ISO string its type calls a `Date`.
 *
 * Both degrade to `undefined` rather than to a fabricated `now`, leaving the caller its own
 * fallback: `asDate(value)?.toLocaleDateString() ?? "–"`.
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
 * - `formatDay`, "9 September": a day named in prose — the card's resolution, the overview's
 *   sentences, the header's chip.
 * - `formatShortDay`, "9 Sep": a day in a row of them — the card's strip of reviewed work, the
 *   timeline's date column — where the full month would widen the row past its words.
 * - `formatDayTime`, "9 September, 2:10 pm", and `formatTime`, "2:10 pm": the moment a run
 *   happened, the hour written the English way rather than on a 24-hour clock.
 */
export function formatDay(date: Date): string {
	return format(date, "d MMMM");
}

export function formatShortDay(date: Date): string {
	return format(date, "d MMM");
}

export function formatTime(date: Date): string {
	// `aaa` is date-fns' lower-case "am"/"pm"; `a` would shout it.
	return format(date, "h:mm aaa");
}

export function formatDayTime(date: Date): string {
	return `${formatDay(date)}, ${formatTime(date)}`;
}
