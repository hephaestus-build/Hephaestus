import { describe, expect, it } from "vitest";

import type { ReviewProgress, ReviewStage } from "@/api/types.gen";

import {
	OWN_REVIEW_STATUS_DEFS,
	REVIEW_CONTINUING_DEF,
	reviewProgressWords,
	shownReviewStatusDef,
} from "./review-status-defs";

function progress(
	practicesEnded: number,
	practicesTotal: number,
	stage: ReviewStage,
): ReviewProgress {
	return { practicesEnded, practicesTotal, continuing: stage === "CONTINUING", stage };
}

describe("review progress", () => {
	it("counts the practices that are finished, and says nothing before the first one", () => {
		expect(reviewProgressWords(progress(76, 200, "CONTINUING"))).toBe(
			"76 of 200 practices finished",
		);
		expect(reviewProgressWords(progress(1, 1, "CHECKING"))).toBe("1 of 1 practice finished");
		expect(reviewProgressWords(progress(0, 200, "CHECKING"))).toBeUndefined();
		expect(reviewProgressWords(null)).toBeUndefined();
	});

	it("counts nothing once the review has ended", () => {
		for (const stage of ["READY", "PARTLY_CHECKED", "WITHHELD", "FAILED", "STOPPED"] as const) {
			expect(reviewProgressWords(progress(3, 3, stage)), stage).toBeUndefined();
		}
	});

	it("shows a waiting review that moved on as continuing, and any other by its status", () => {
		const moved = progress(76, 200, "CONTINUING");
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
