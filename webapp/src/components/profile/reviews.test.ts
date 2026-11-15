import { describe, expect, it } from "vitest";

import { isEmptyFeedbackResponse, responseRequestOf, resultSummary } from "./reviews";

describe("isEmptyFeedbackResponse", () => {
	it("treats a response with nothing left in it as cleared", () => {
		expect(isEmptyFeedbackResponse({})).toBe(true);
		expect(
			isEmptyFeedbackResponse({
				usefulness: undefined,
				resolution: undefined,
				comment: undefined,
			}),
		).toBe(true);
	});

	it("does not mistake a blank comment for something worth storing", () => {
		expect(isEmptyFeedbackResponse({ comment: "   " })).toBe(true);
	});

	it("keeps a response that still carries a dimension or a comment", () => {
		expect(isEmptyFeedbackResponse({ usefulness: "HELPFUL" })).toBe(false);
		expect(isEmptyFeedbackResponse({ resolution: "NOT_APPLICABLE" })).toBe(false);
		expect(isEmptyFeedbackResponse({ comment: "Handled in the follow-up." })).toBe(false);
	});
});

describe("responseRequestOf", () => {
	it("writes a recorded response again with both dimensions and the comment", () => {
		expect(
			responseRequestOf({
				usefulness: "HELPFUL",
				resolution: "DISAGREE",
				comment: "The rename was its own pull request.",
				createdAt: new Date("2026-09-09T14:10:00Z"),
			}),
		).toStrictEqual({
			usefulness: "HELPFUL",
			resolution: "DISAGREE",
			comment: "The rename was its own pull request.",
		});
	});

	it("reads a cleared response as the empty one", () => {
		const cleared = responseRequestOf({});
		expect(isEmptyFeedbackResponse(cleared)).toBe(true);
		expect(isEmptyFeedbackResponse(responseRequestOf(undefined))).toBe(true);
	});
});

describe("resultSummary", () => {
	it("names the practice when a met result has no summary", () => {
		expect(resultSummary({ practiceName: "Product language" })).toBe("Product language");
		expect(resultSummary({ summary: "  ", practiceName: "Product language" })).toBe(
			"Product language",
		);
	});

	it("keeps the summary when there is one", () => {
		expect(
			resultSummary({ summary: " Each commit carries one concern ", practiceName: "Commits" }),
		).toBe("Each commit carries one concern");
	});
});
