import type { ReviewResultCounts } from "@/api/types.gen";
import type { BadgeVariant } from "@/components/common/status-def";

import { PRACTICE_MARKER_THRESHOLDS } from "./review-attention-thresholds";

export type AttentionTone = Extract<BadgeVariant, "secondary" | "destructive">;

export function shareOf(count: number, whole: number): number {
	return whole > 0 ? count / whole : 0;
}

/** "5%", "0.4%": whole from one up, one decimal below, so a small loss never reads as none. */
export function percentText(share: number): string {
	const percent = share * 100;
	if (percent === 0) {
		return "0%";
	}
	return percent >= 1
		? `${Math.round(percent)}%`
		: `${Math.max(0.1, Math.round(percent * 10) / 10)}%`;
}

export type PracticeMarker = "NEVER_APPLIES" | "OFTEN_UNDECIDED";

export interface PracticeMarkerDef {
	label: string;
	reason: (count: number, whole: number) => string;
}

export const PRACTICE_MARKER_DEFS: Record<PracticeMarker, PracticeMarkerDef> = {
	NEVER_APPLIES: {
		label: "Mostly does not apply",
		reason: (count, whole) =>
			`Does not apply in ${count} of ${whole} observations. Consider turning it off for this workspace.`,
	},
	OFTEN_UNDECIDED: {
		label: "Often could not determine",
		reason: (count, whole) =>
			`Could not determine in ${count} of ${whole} observations. The review may lack the evidence to decide.`,
	},
};

export function practiceMarker(
	counts: ReviewResultCounts,
): { marker: PracticeMarker; count: number; whole: number } | undefined {
	const whole = counts.met + counts.notMet + counts.doesNotApply + counts.couldNotDetermine;
	if (whole < PRACTICE_MARKER_THRESHOLDS.minimumResults) {
		return undefined;
	}
	if (shareOf(counts.doesNotApply, whole) >= PRACTICE_MARKER_THRESHOLDS.neverAppliesShare) {
		return { marker: "NEVER_APPLIES", count: counts.doesNotApply, whole };
	}
	if (shareOf(counts.couldNotDetermine, whole) >= PRACTICE_MARKER_THRESHOLDS.oftenUndecidedShare) {
		return { marker: "OFTEN_UNDECIDED", count: counts.couldNotDetermine, whole };
	}
	return undefined;
}

const MARKER_RANK: Record<PracticeMarker, number> = { NEVER_APPLIES: 0, OFTEN_UNDECIDED: 1 };
const UNMARKED_RANK = 2;

export function needsALookFirst<
	T extends { results: ReviewResultCounts; resultsMarkedIncorrect: number },
>(rows: readonly T[]): T[] {
	const rank = (row: T) => {
		const found = practiceMarker(row.results);
		return found === undefined ? UNMARKED_RANK : MARKER_RANK[found.marker];
	};
	return rows
		.map((row, index) => ({ row, index, rank: rank(row) }))
		.sort(
			(a, b) =>
				a.rank - b.rank ||
				b.row.resultsMarkedIncorrect - a.row.resultsMarkedIncorrect ||
				b.row.results.couldNotDetermine - a.row.results.couldNotDetermine ||
				a.index - b.index,
		)
		.map(({ row }) => row);
}
