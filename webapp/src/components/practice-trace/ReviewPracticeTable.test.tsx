import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ResultList } from "@/api/types.gen";
import { daysBefore } from "@/stories/story-clock";

import { metOwnResult, notMetOwnResult, ownItemTrace, ownResults } from "./fixtures";
import { earlierReviewOf, ReviewPracticeTable } from "./ReviewPracticeTable";

const reviewId = ownItemTrace.review.id;

function table(results: ResultList[]) {
	return (
		<ReviewPracticeTable
			results={results}
			reviewId={reviewId}
			groupOf={() => undefined}
			filters={{}}
			onFiltersChange={vi.fn()}
			emptyMessage="No results."
		/>
	);
}

describe("earlierReviewOf", () => {
	it("names the review that recorded a reused result, and nothing for this review's own", () => {
		expect(ownResults.map((result) => earlierReviewOf(result, reviewId))).toStrictEqual([
			undefined,
			undefined,
			"00000000-0000-4000-8000-0000000000e1",
		]);
	});
});

describe("results marked incorrect", () => {
	it("keeps a marked result distinguishable from its sibling, then restores it", () => {
		const valid = notMetOwnResult;
		const marked: ResultList = { ...metOwnResult, markedIncorrectAt: daysBefore(1) };
		const { rerender } = render(table([marked, valid]));
		const rows = within(screen.getByRole("table", { name: "Every practice in this review" }));
		expect(rows.getAllByText("Marked incorrect")).toHaveLength(1);
		rows.getByText("Two controllers query the database directly");

		rerender(table([{ ...marked, markedIncorrectAt: undefined }, valid]));
		expect(rows.queryByText("Marked incorrect")).toBeNull();
		rows.getByText("Two controllers query the database directly");
	});
});
