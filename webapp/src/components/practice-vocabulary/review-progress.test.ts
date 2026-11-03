import { describe, expect, it } from "vitest";

import {
	OWN_REVIEW_STATUS_DEFS,
	REVIEW_CONTINUING_DEF,
	reviewProgressWords,
	shownReviewStatusDef,
} from "./review-status-defs";

describe("review progress", () => {
	it("counts the practices that are finished, and says nothing before the first one", () => {
		expect(reviewProgressWords({ practicesEnded: 76, practicesTotal: 200, continuing: true })).toBe(
			"76 of 200 practices finished",
		);
		expect(reviewProgressWords({ practicesEnded: 1, practicesTotal: 1, continuing: false })).toBe(
			"1 of 1 practice finished",
		);
		expect(
			reviewProgressWords({ practicesEnded: 0, practicesTotal: 200, continuing: false }),
		).toBeUndefined();
		expect(reviewProgressWords(null)).toBeUndefined();
	});

	it("shows a waiting review that moved on as continuing, and any other by its status", () => {
		const moved = { practicesEnded: 76, practicesTotal: 200, continuing: true };
		expect(shownReviewStatusDef(OWN_REVIEW_STATUS_DEFS, "QUEUED", moved)).toBe(
			REVIEW_CONTINUING_DEF,
		);
		expect(
			shownReviewStatusDef(OWN_REVIEW_STATUS_DEFS, "QUEUED", { ...moved, continuing: false }),
		).toBe(OWN_REVIEW_STATUS_DEFS.QUEUED);
		expect(shownReviewStatusDef(OWN_REVIEW_STATUS_DEFS, "RUNNING", null)).toBe(
			OWN_REVIEW_STATUS_DEFS.RUNNING,
		);
	});
});
