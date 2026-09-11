import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PracticeGroupReviewObservation, PracticeGroupReviewRun } from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { ReviewRunTimeline } from "./ReviewRunTimeline";

const baseObservation = {
	observationId: "00000000-0000-0000-0000-000000000102",
	feedbackId: "00000000-0000-0000-0000-000000000103",
	feedbackUsefulness: "HELPFUL",
	feedbackResolution: "ADDRESSED",
	feedbackResponseComment: "Applied in the next revision.",
	practiceSlug: "records-decisions",
	practiceName: "Record significant decisions and the reasoning",
	title: "The workspace trade-off is documented",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "GOOD",
} satisfies PracticeGroupReviewObservation;

const run = {
	reviewId: "00000000-0000-0000-0000-000000000101",
	reviewedAt: daysBefore(2),
	reviewedWork: {
		type: "scm.pull_request",
		id: 902,
		provider: "GITHUB",
		number: 902,
		title: "Split the practice catalog loader per workspace",
		repositoryName: "HephaestusTest/practice-validation",
		url: "https://github.com/HephaestusTest/practice-validation/pull/902",
	},
	observations: [baseObservation],
} satisfies PracticeGroupReviewRun;

const runs = [run];

describe("ReviewRunTimeline", () => {
	it("renders the review-run boundary and its observations", () => {
		render(<ReviewRunTimeline runs={runs} />);

		screen.getByText("#902 · Split the practice catalog loader per workspace");
		screen.getByText("The workspace trade-off is documented");
		screen.getByText("Record significant decisions and the reasoning");
		screen.getByText("Strength shown");
	});

	it("sends the whole response when one part of it changes", () => {
		const onRespond = vi.fn();
		render(
			<ReviewRunTimeline
				runs={runs}
				onToggleObservation={vi.fn()}
				openObservationId={baseObservation.observationId}
				onRespond={onRespond}
			/>,
		);

		fireEvent.click(screen.getByRole("button", { name: "Helpful" }));
		expect(onRespond).toHaveBeenCalledWith(baseObservation, {
			usefulness: undefined,
			resolution: "ADDRESSED",
			comment: "Applied in the next revision.",
		});
	});

	it("records a resolution without disturbing the usefulness already given", () => {
		const onRespond = vi.fn();
		render(
			<ReviewRunTimeline
				runs={runs}
				onToggleObservation={vi.fn()}
				openObservationId={baseObservation.observationId}
				onRespond={onRespond}
			/>,
		);

		fireEvent.click(screen.getByRole("button", { name: "Disputed" }));
		expect(onRespond).toHaveBeenCalledWith(baseObservation, {
			usefulness: "HELPFUL",
			resolution: "DISPUTED",
			comment: "Applied in the next revision.",
		});
	});

	it("keeps a dense review run compact until requested", () => {
		const denseRun: PracticeGroupReviewRun = {
			...run,
			observations: Array.from({ length: 5 }, (_, index) => ({
				...baseObservation,
				observationId: `00000000-0000-0000-0000-00000000010${index}`,
				practiceSlug: `practice-${index}`,
				practiceName: `Practice ${index + 1}`,
				title: `Observation ${index + 1}`,
			})),
		};

		render(<ReviewRunTimeline runs={[denseRun]} />);
		expect(screen.queryByText("Observation 4")).toBeNull();
		fireEvent.click(screen.getByRole("button", { name: "Show more (2)" }));
		screen.getByText("Observation 4");
		screen.getByText("Observation 5");
	});
});
