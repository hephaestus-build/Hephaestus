import type { ReviewFeedbackCounts, ReviewOverview, ReviewPracticeCounts } from "@/api/types.gen";

import { familyCount, resultSlots, slotsTotal } from "./review-outcomes";

export interface PracticeTotals {
	results: number;
	notMet: number;
	doesNotApply: number;
	couldNotDetermine: number;
	markedIncorrect: number;
	/** Pieces of feedback delivered, each once, as the Feedback tile counts them. */
	feedbackDelivered: number;
	/** What Feedback delivered is out of: every piece composed, each once (`feedbackComposed`). */
	feedbackComposed: number;
	/** What the Feedback delivered column adds up to over every practice, hidden or shown. */
	feedbackDeliveredInRows: number;
	hidden: { practices: number; results: number };
}

/**
 * The feedback composed, what the Feedback delivered column is "of": every piece in any state, sent,
 * still waiting or withheld, but not a piece replaced by a newer one, since the newer one stands for
 * it and would count the same feedback twice. So "6 of 9" reads as the share of the feedback written
 * that reached the developer, and a practice whose feedback is mostly withheld or waiting shows it.
 */
export function feedbackComposed(counts: ReviewFeedbackCounts): number {
	return (
		counts.awaitingApproval +
		counts.ready +
		counts.delivered +
		counts.unconfirmed +
		counts.withheld +
		counts.rejected +
		counts.failed
	);
}

const resultsOf = (counts: ReviewPracticeCounts) => slotsTotal(resultSlots(counts.results));

/** The totals of the range, with the rows on screen to tell how much the filters hide. */
export function practiceTotals(
	overview: ReviewOverview,
	shown: readonly ReviewPracticeCounts[],
): PracticeTotals {
	const shownSlugs = new Set(shown.map((counts) => counts.practiceSlug));
	const hiddenRows = overview.practices.filter((counts) => !shownSlugs.has(counts.practiceSlug));
	return {
		results: slotsTotal(resultSlots(overview.results)),
		notMet: overview.results.notMet,
		doesNotApply: overview.results.doesNotApply,
		couldNotDetermine: overview.results.couldNotDetermine,
		markedIncorrect: overview.resultsMarkedIncorrect,
		feedbackDelivered: familyCount(overview.feedback, "DELIVERED"),
		feedbackComposed: feedbackComposed(overview.feedback),
		feedbackDeliveredInRows: overview.practices.reduce(
			(sum, counts) => sum + familyCount(counts.feedback, "DELIVERED"),
			0,
		),
		hidden: {
			practices: hiddenRows.length,
			results: hiddenRows.reduce((sum, counts) => sum + resultsOf(counts), 0),
		},
	};
}

const plural = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`;

/**
 * Where the rows and the totals row differ, and why, one sentence each: nothing when the rows add up
 * to the totals.
 */
export function practiceTotalsNotes(totals: PracticeTotals): string[] {
	const notes: string[] = [];
	if (totals.hidden.practices > 0) {
		notes.push(
			`The totals include ${plural(totals.hidden.practices, "practice", "practices")} that the filters hide, with ${plural(totals.hidden.results, "observation", "observations")}.`,
		);
	}
	if (totals.feedbackDeliveredInRows !== totals.feedbackDelivered) {
		notes.push(
			`Feedback delivered adds up to ${totals.feedbackDeliveredInRows} across the practices, but ${plural(totals.feedbackDelivered, "piece was", "pieces were")} delivered. A piece about several practices counts in the row of each, and a piece about no practice counts in none.`,
		);
	}
	return notes;
}
