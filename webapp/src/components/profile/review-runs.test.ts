import { describe, expect, it } from "vitest";
import type { PracticeGroupReviewObservation } from "@/api/types.gen";
import { feedbackResponseOf, isEmptyFeedbackResponse } from "./review-runs";

const observation = (
	overrides: Partial<PracticeGroupReviewObservation> = {},
): PracticeGroupReviewObservation => ({
	observationId: "00000000-0000-0000-0000-000000000001",
	practiceSlug: "records-decisions",
	practiceName: "Record significant decisions",
	title: "The workspace trade-off is documented",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "GOOD",
	...overrides,
});

describe("feedbackResponseOf", () => {
	it("reads back every part of a response the server already holds", () => {
		expect(
			feedbackResponseOf(
				observation({
					feedbackUsefulness: "HELPFUL",
					feedbackResolution: "ADDRESSED",
					feedbackResponseComment: "Split into two commits.",
				}),
			),
		).toStrictEqual({
			usefulness: "HELPFUL",
			resolution: "ADDRESSED",
			comment: "Split into two commits.",
		});
	});

	it("carries the parts that are missing as undefined rather than dropping them", () => {
		expect(feedbackResponseOf(observation({ feedbackUsefulness: "UNHELPFUL" }))).toStrictEqual({
			usefulness: "UNHELPFUL",
			resolution: undefined,
			comment: undefined,
		});
	});
});

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
