import type { ReviewRef, ReviewResultCounts } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { type Outcome, OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";

/** What a review saw of the reader's work: its status and how its practices came out. */
export interface ReviewSaw {
	status: ReviewRef["status"];
	results: ReviewResultCounts;
}

const COUNT_KEYS = {
	MET: "met",
	NOT_MET: "notMet",
	DOES_NOT_APPLY: "doesNotApply",
	COULD_NOT_DETERMINE: "couldNotDetermine",
} as const satisfies Record<Outcome, keyof ReviewResultCounts>;

/** How many practices the review recorded something about for the reader. */
export function practicesReviewed(saw: ReviewSaw): number {
	const { met, notMet, doesNotApply, couldNotDetermine } = saw.results;
	return met + notMet + doesNotApply + couldNotDetermine;
}

/** "8 practices": the lead of the cell, in digits like the counts after it. */
export function practicesLabel(saw: ReviewSaw): string {
	const n = practicesReviewed(saw);
	if (n === 0) {
		return saw.status === "QUEUED" || saw.status === "RUNNING"
			? "No practices yet"
			: "No practices";
	}
	return `${n} ${n === 1 ? "practice" : "practices"}`;
}

/**
 * Each outcome the review recorded, in the registry's order, with its count: "5 met", "2 not
 * met". An outcome with none is left out.
 */
export function outcomeCounts(saw: ReviewSaw): { outcome: Outcome; n: number; text: string }[] {
	return statusValues(OUTCOME_DEFS)
		.map((outcome) => {
			const n = saw.results[COUNT_KEYS[outcome]];
			return { outcome, n, text: `${n} ${OUTCOME_DEFS[outcome].label.toLowerCase()}` };
		})
		.filter((entry) => entry.n > 0);
}
