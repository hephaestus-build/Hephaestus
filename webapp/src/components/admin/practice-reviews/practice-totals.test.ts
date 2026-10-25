import { describe, expect, it } from "vitest";

import { practiceReviewOverview } from "./fixtures";
import { feedbackComposed, practiceTotals, practiceTotalsNotes } from "./practice-totals";
import { resultSlots, overviewFeedbackSlots, slotsTotal } from "./review-outcomes";

function tileFigures() {
	const results = resultSlots(practiceReviewOverview.results);
	const count = (key: string) => results.find((slot) => slot.key === key)?.count;
	return {
		results: slotsTotal(results),
		notMet: count("notMet"),
		doesNotApply: count("DOES_NOT_APPLY"),
		couldNotDetermine: count("COULD_NOT_DETERMINE"),
		markedIncorrect: practiceReviewOverview.resultsMarkedIncorrect,
		feedbackDelivered: overviewFeedbackSlots(practiceReviewOverview.feedback).find(
			(slot) => slot.key === "DELIVERED",
		)?.count,
	};
}

describe("practiceTotals", () => {
	it("pins the totals row to the figures of the tiles", () => {
		const totals = practiceTotals(practiceReviewOverview, practiceReviewOverview.practices);
		expect({
			results: totals.results,
			notMet: totals.notMet,
			doesNotApply: totals.doesNotApply,
			couldNotDetermine: totals.couldNotDetermine,
			markedIncorrect: totals.markedIncorrect,
			feedbackDelivered: totals.feedbackDelivered,
		}).toStrictEqual(tileFigures());
		const rows = practiceReviewOverview.practices;
		expect(rows.reduce((sum, row) => sum + slotsTotal(resultSlots(row.results)), 0)).toBe(
			totals.results,
		);
		expect(rows.reduce((sum, row) => sum + row.resultsMarkedIncorrect, 0)).toBe(
			totals.markedIncorrect,
		);
	});

	it("counts the feedback composed once, leaving out the pieces replaced by newer ones", () => {
		expect(feedbackComposed(practiceReviewOverview.feedback)).toBe(24);
		const totals = practiceTotals(practiceReviewOverview, practiceReviewOverview.practices);
		expect([totals.feedbackDelivered, totals.feedbackComposed]).toStrictEqual([14, 24]);
		expect(
			practiceReviewOverview.practices.map((row) => feedbackComposed(row.feedback)),
		).toStrictEqual([12, 8, 2, 3, 0]);
	});

	it("says why the delivered rows add up to more than the pieces delivered", () => {
		const totals = practiceTotals(practiceReviewOverview, practiceReviewOverview.practices);
		expect([totals.feedbackDeliveredInRows, totals.feedbackDelivered]).toStrictEqual([16, 14]);
		expect(practiceTotalsNotes(totals)).toStrictEqual([
			"Feedback delivered adds up to 16 across the practices, but 14 pieces were delivered. A piece about several practices counts in the row of each, and a piece about no practice counts in none.",
		]);
	});

	it("counts the practices and results the filters hide, and keeps them in the totals", () => {
		const shown = practiceReviewOverview.practices.filter(
			(row) => row.practiceSlug !== "thin-controllers",
		);
		const totals = practiceTotals(practiceReviewOverview, shown);
		expect(totals.hidden).toStrictEqual({ practices: 1, results: 33 });
		expect(totals.results).toBe(tileFigures().results);
		expect(practiceTotalsNotes(totals)[0]).toBe(
			"The totals include 1 practice that the filters hide, with 33 observations.",
		);
	});

	it("says nothing when the rows add up to the totals", () => {
		const practices = practiceReviewOverview.practices.map((row) => ({
			...row,
			feedback: { ...row.feedback, delivered: 0 },
		}));
		const overview = {
			...practiceReviewOverview,
			practices,
			feedback: { ...practiceReviewOverview.feedback, delivered: 0 },
		};
		expect(practiceTotalsNotes(practiceTotals(overview, practices))).toStrictEqual([]);
	});
});
