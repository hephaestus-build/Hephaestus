/**
 * The middle value, or the mean of the two middle values; `null` for no values, which a report
 * shows as missing rather than as a zero measurement.
 */
export function median(values: readonly number[]): number | null {
	if (values.length === 0) {
		return null;
	}
	const sorted = values.toSorted((left, right) => left - right);
	const middle = Math.floor(sorted.length / 2);
	const upper = sorted[middle] ?? 0;
	return sorted.length % 2 === 0 ? ((sorted[middle - 1] ?? 0) + upper) / 2 : upper;
}

/** The nearest-rank value `fraction` of the way through the sorted values; `null` for no values. */
export function percentile(values: readonly number[], fraction: number): number | null {
	const sorted = values.toSorted((left, right) => left - right);
	return sorted[Math.ceil(sorted.length * fraction) - 1] ?? null;
}
