import { render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ObservationDetail, PracticeTraceEntry, TracedSignal } from "@/api/types.gen";
import { detailObservation } from "@/stories/practice-detail-story-mock-data";
import { groups } from "@/stories/practice-profile-story-mock-data";
import { daysBefore } from "@/stories/story-clock";

import { artifactTrace } from "./fixtures";
import { ReviewRunPracticeTable, runPractices } from "./ReviewRunPracticeTable";

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

describe("the practices one review decided", () => {
	const ready = "11111111-1111-1111-1111-111111111111";
	const push = "44444444-4444-4444-4444-444444444444";
	const base = artifactTrace.practices[0];
	if (base === undefined) {
		throw new Error("The trace fixture lists no practice.");
	}
	const { reviewId: _reviewId, ...unattributed } = base;
	const signals: TracedSignal[] = [
		...artifactTrace.signals,
		{
			id: "sig-push",
			signal: "scm.pull_request.synchronized",
			displayName: "New commits pushed",
			revision: "c0ffee12",
			occurredAt: daysBefore(1),
			discoveredVia: "EVENT",
			state: "TRIGGERED",
			reviewId: push,
		},
	];
	const entries: PracticeTraceEntry[] = [
		{ ...base, practiceSlug: "asked", reviewId: push, occasionedById: "sig-push" },
		// Reused from the Ready review: the answer names that review, the occurrence is this one's.
		{ ...base, practiceSlug: "reused", reviewId: ready, occasionedById: "sig-push" },
		{
			...unattributed,
			practiceSlug: "turned-off",
			outcome: "TURNED_OFF",
			occasionedById: "sig-push",
		},
		{ ...base, practiceSlug: "earlier", reviewId: ready, occasionedById: "sig-ready" },
	];

	it("keeps a practice the review left to an earlier answer, and nothing it did not decide", () => {
		expect(runPractices(entries, signals, push).map((entry) => entry.practiceSlug)).toStrictEqual([
			"asked",
			"reused",
		]);
	});
});

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
				outcome: "MET",
				severity: undefined,
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
