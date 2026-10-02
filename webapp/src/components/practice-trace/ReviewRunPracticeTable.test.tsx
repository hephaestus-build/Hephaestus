import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ObservationDetail } from "@/api/types.gen";
import { detailObservation } from "@/stories/practice-detail-story-mock-data";
import { groups } from "@/stories/practice-profile-story-mock-data";
import { daysBefore } from "@/stories/story-clock";

import { artifactTrace } from "./fixtures";
import { ReviewRunPracticeTable } from "./ReviewRunPracticeTable";

const valid: ObservationDetail = {
	...detailObservation,
	id: "00000000-0000-0000-0000-0000000001aa",
	practiceSlug: "thin-controllers",
	summary: "The merge request description never says why",
};

function table(observations: ObservationDetail[]) {
	return (
		<ReviewRunPracticeTable
			entries={artifactTrace.practices}
			signals={artifactTrace.signals}
			observationsByPractice={{ "thin-controllers": observations }}
			groups={groups}
			filters={{}}
			onFiltersChange={vi.fn()}
			onOpenPractice={vi.fn()}
			onShowOccurrence={vi.fn()}
			emptyMessage="No practices were reviewed."
		/>
	);
}

describe("review history corrections", () => {
	it.each([
		{
			summary: "The title and description explain the purpose",
			visible: "The title and description explain the purpose",
		},
		{ summary: "", visible: valid.summary },
	])(
		"keeps a corrected observation with summary $summary distinguishable from its valid sibling, then restores it",
		({ summary, visible }) => {
			const corrected: ObservationDetail = {
				...valid,
				id: detailObservation.id,
				presence: "PRESENT",
				assessment: "GOOD",
				summary,
				invalidatedAt: daysBefore(1),
				invalidationReason: "The review imported the linked issue's reason.",
			};
			const { rerender } = render(table([corrected, valid]));
			const history = within(screen.getByRole("table", { name: "Every practice on this work" }));
			expect(history.getAllByText("Marked incorrect")).toHaveLength(1);
			history.getByText("Reason: The review imported the linked issue's reason.");
			history.getByText(valid.summary);
			history.getByText(visible);

			rerender(
				table([{ ...corrected, invalidatedAt: undefined, invalidationReason: undefined }, valid]),
			);
			expect(history.queryByText("Marked incorrect")).toBeNull();
			expect(history.queryByText(/^Reason:/u)).toBeNull();
			history.getByText(valid.summary);
			history.getByText(visible);
		},
	);
});
