import type { OptionCount, ParticipationCounts } from "@/api/types.gen";

/** The standard NPS cut, which the server's score also uses. Bounds are inclusive. */
export const NPS_BUCKETS = [
	{ bucket: "promoters", noun: ["promoter", "promoters"], low: 9, high: 10 },
	{ bucket: "passives", noun: ["passive", "passives"], low: 7, high: 8 },
	{ bucket: "detractors", noun: ["detractor", "detractors"], low: 0, high: 6 },
] as const;

export type NpsBucket = (typeof NPS_BUCKETS)[number]["bucket"];

export type NpsBucketCounts = Record<NpsBucket, number> & { total: number };

export function npsBuckets(counts: readonly OptionCount[]): NpsBucketCounts {
	const totals: NpsBucketCounts = { promoters: 0, passives: 0, detractors: 0, total: 0 };
	for (const { value, count } of counts) {
		const rating = Number(value);
		if (!Number.isInteger(rating)) continue;
		for (const { bucket, low, high } of NPS_BUCKETS) {
			if (rating >= low && rating <= high) {
				totals[bucket] += count;
				totals.total += count;
			}
		}
	}
	return totals;
}

const PERCENT = new Intl.NumberFormat(undefined, { style: "percent", maximumFractionDigits: 0 });

/** "40%" in the reader's locale, or `undefined` when there is nothing to divide by. */
export function percentOf(part: number, whole: number): string | undefined {
	return whole > 0 ? PERCENT.format(part / whole) : undefined;
}

/** A rate over nobody is not zero, so before anyone was invited it is a dash. */
export function completionRate(participation: ParticipationCounts): string {
	return percentOf(participation.responded, participation.invited) ?? "—";
}

/** The counts as the bars draw them: a share of the largest count, so the longest bar is full. */
export function distributionRows(counts: readonly OptionCount[]) {
	const answered = counts.reduce((sum, { count }) => sum + count, 0);
	const largest = Math.max(0, ...counts.map(({ count }) => count));
	return counts.map(({ value, count }) => ({
		value,
		count,
		percent: percentOf(count, answered) ?? PERCENT.format(0),
		width: largest > 0 ? (100 * count) / largest : 0,
	}));
}
