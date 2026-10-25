import { describe, expect, it } from "vitest";

import { outcomeCounts, practicesLabel, type ReviewSaw } from "./review-saw";

const saw = (
	status: ReviewSaw["status"],
	results: Partial<ReviewSaw["results"]> = {},
): ReviewSaw => ({
	status,
	results: { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0, ...results },
});

describe("what a review saw", () => {
	it("counts the practices it reviewed, in digits", () => {
		expect(practicesLabel(saw("COMPLETE", { met: 5, notMet: 2, doesNotApply: 1 }))).toBe(
			"8 practices",
		);
		expect(practicesLabel(saw("PARTIAL", { met: 1 }))).toBe("1 practice");
	});

	it("breaks the practices down by outcome, in the registry's order, leaving out empty ones", () => {
		expect(
			outcomeCounts(saw("COMPLETE", { met: 5, notMet: 2, doesNotApply: 1 })).map(
				(entry) => entry.text,
			),
		).toStrictEqual(["5 met", "2 not met", "1 does not apply"]);
		expect(
			outcomeCounts(saw("COMPLETE", { couldNotDetermine: 3 })).map((entry) => entry.text),
		).toStrictEqual(["3 could not determine"]);
	});

	it("says a review with nothing recorded has no practices, yet while it runs", () => {
		expect(practicesLabel(saw("RUNNING"))).toBe("No practices yet");
		expect(practicesLabel(saw("QUEUED"))).toBe("No practices yet");
		expect(practicesLabel(saw("FAILED"))).toBe("No practices");
		expect(outcomeCounts(saw("FAILED"))).toStrictEqual([]);
	});
});
