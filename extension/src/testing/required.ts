/**
 * The value, or a thrown error naming what was missing. Tests narrow with it instead of branching,
 * so a missing fixture fails loudly at the line that needed it.
 */
export function required<T>(value: T | null | undefined, what = "value"): T {
	if (value === undefined || value === null) {
		throw new Error(`Missing ${what}`);
	}
	return value;
}
