import type { OptionCount, ParticipationCounts } from "@/api/types.gen";

export interface NpsBuckets {
	promoters: number;
	passives: number;
	detractors: number;
	total: number;
}

/** 9–10 promote, 7–8 are passive, 0–6 detract — the standard cut the server's score also uses. */
export function npsBuckets(counts: readonly OptionCount[]): NpsBuckets {
	const buckets: NpsBuckets = { promoters: 0, passives: 0, detractors: 0, total: 0 };
	for (const { value, count } of counts) {
		const rating = Number(value);
		if (!Number.isInteger(rating) || rating < 0 || rating > 10) continue;
		if (rating >= 9) buckets.promoters += count;
		else if (rating >= 7) buckets.passives += count;
		else buckets.detractors += count;
		buckets.total += count;
	}
	return buckets;
}

/** Whole-number percent of `part` in `whole`, or `undefined` when there is nothing to divide by. */
export function percentOf(part: number, whole: number): number | undefined {
	return whole > 0 ? Math.round((100 * part) / whole) : undefined;
}

/** "40%" when someone was invited, "—" before anyone was — a rate over nobody is not zero. */
export function completionRate(participation: ParticipationCounts): string {
	const percent = percentOf(participation.responded, participation.invited);
	return percent === undefined ? "—" : `${percent}%`;
}

/** The counts as the bars draw them: a share of the largest count, so the longest bar is full. */
export function distributionRows(counts: readonly OptionCount[]) {
	const answered = counts.reduce((sum, { count }) => sum + count, 0);
	const largest = Math.max(0, ...counts.map(({ count }) => count));
	return counts.map(({ value, count }) => ({
		value,
		count,
		percent: percentOf(count, answered) ?? 0,
		width: largest > 0 ? (100 * count) / largest : 0,
	}));
}
