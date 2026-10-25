import { describe, expect, it } from "vitest";

import { type ReviewSaw, reviewLead, reviewReach } from "./review-saw";

describe("recorded outcome totals", () => {
	it("counts sibling outcomes as practices", () => {
		expect(
			reviewReach({
				status: "PARTIAL",
				results: { met: 2, notMet: 1, doesNotApply: 0, couldNotDetermine: 0 },
				notMetPractices: [],
			}),
		).toBe("two practices met");
	});
});

function nothingNotMet(status: ReviewSaw["status"], couldNotDetermine = 0): ReviewSaw {
	return {
		status,
		results: { met: 2, notMet: 0, doesNotApply: 0, couldNotDetermine },
		notMetPractices: [],
	};
}

describe("the lead of a review with nothing not met", () => {
	// A partial review may have run to the end, so its lead names the scope and never a stop.
	it("scopes a partial review's clean lead to what it reached", () => {
		expect(reviewLead(nothingNotMet("PARTIAL"))).toBe("Nothing to improve in what it reached");
		expect(reviewLead(nothingNotMet("COMPLETE"))).toBe("Nothing to improve");
	});

	it.each(["PARTIAL", "COMPLETE"] as const)(
		"says a %s review could not determine some practices",
		(status) => {
			expect(reviewLead(nothingNotMet(status, 1))).toBe("Some practices could not be determined");
		},
	);

	it.each(["FAILED", "CANCELLED"] as const)(
		"says a %s review stopped before it finished",
		(status) => {
			expect(reviewLead(nothingNotMet(status))).toBe("Stopped before it finished");
		},
	);
});
