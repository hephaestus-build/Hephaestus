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
