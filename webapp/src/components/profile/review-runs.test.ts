import { describe, expect, it } from "vitest";

import { isEmptyFeedbackResponse } from "./review-runs";

describe("isEmptyFeedbackResponse", () => {
	it("treats an answer with nothing left in it as a withdrawal", () => {
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

	it("keeps an answer that still carries any one part", () => {
		expect(isEmptyFeedbackResponse({ usefulness: "HELPFUL" })).toBe(false);
		expect(isEmptyFeedbackResponse({ resolution: "NOT_APPLICABLE" })).toBe(false);
		expect(isEmptyFeedbackResponse({ comment: "Handled in the follow-up." })).toBe(false);
	});
});
