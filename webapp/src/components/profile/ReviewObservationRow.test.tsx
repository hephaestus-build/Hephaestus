import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { EvidenceCitation, ObservationDetail } from "@/api/types.gen";
import { detailObservation } from "@/stories/practice-detail-story-mock-data";

import { ReviewObservationRow, type ReviewObservationRowProps } from "./ReviewObservationRow";

const observation: ObservationDetail = {
	...detailObservation,
	id: "00000000-0000-0000-0000-000000000001",
	feedbackResponse: { feedbackId: "00000000-0000-0000-0000-000000000002", usefulness: "HELPFUL" },
	practiceSlug: "explain-decisions",
	practiceName: "Explain significant decisions",
	summary: "The reason for the timeout is missing",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MINOR",
};

function renderOpen(props: Partial<ReviewObservationRowProps> = {}) {
	return render(
		<ul>
			<ReviewObservationRow observation={observation} onRespond={vi.fn()} {...props} />
		</ul>,
	);
}

const citation = (path: string): EvidenceCitation => ({
	sourceKind: "scm.pull-request.diff",
	artifactPath: "owner/repo#1",
	path,
	side: "NEW",
	startLine: 1,
	endLine: 1,
	quote: "return null;",
	quoteRedacted: false,
});

describe("ReviewObservationRow", () => {
	it("keeps a diff pair that could not fold as two blocks, each with its own key", () => {
		// A key collision is only ever reported on the console, so the diagnostic is the assertion.
		using keyWarning = vi.spyOn(console, "error").mockReturnValue(undefined);
		renderOpen({
			observation: {
				...observation,
				evidence: {
					detector: "secret-diff-scanner",
					citations: [
						{ ...citation("src/config.ts"), side: "OLD", quote: "const timeout = 30;" },
						{
							...citation("src/config.ts"),
							side: "NEW",
							quote: undefined,
							quoteRedacted: true,
						},
					],
				},
			},
		});

		expect(keyWarning).not.toHaveBeenCalled();
		expect(screen.getAllByText("config.ts")).toHaveLength(2);
		screen.getByText("const timeout = 30;");
		screen.getByText(/This looked like a credential/u);
	});

	it("shows the review's own next step over the one that was delivered", () => {
		renderOpen({
			observation: {
				...observation,
				nextStep: "Split the commit so the rename can be reverted on its own.",
			},
		});

		screen.getByText("Split the commit so the rename can be reverted on its own.");
		expect(screen.queryByText(/Land the rename on its own first/u)).toBeNull();
	});

	it("refuses a dispute made of blanks through the field rather than in silence", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({ onRespond });

		fireEvent.click(screen.getByRole("button", { name: "Disputed" }));
		const comment = screen.getByRole<HTMLInputElement>("textbox", {
			name: "What was missed?",
		});
		fireEvent.change(comment, { target: { value: "   " } });
		fireEvent.click(screen.getByRole("button", { name: "Send" }));

		expect(comment.value).toBe("");
		expect(comment.validity.valueMissing).toBe(true);
		expect(onRespond).not.toHaveBeenCalled();
	});

	it("closes a dispute's band on Skip without recording it", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({ onRespond });

		fireEvent.click(screen.getByRole("button", { name: "Disputed" }));
		fireEvent.click(screen.getByRole("button", { name: "Skip" }));

		expect(onRespond).not.toHaveBeenCalled();
		expect(screen.queryByRole("textbox")).toBeNull();
		expect(screen.getByRole("button", { name: "Disputed" }).getAttribute("aria-pressed")).toBe(
			"false",
		);
	});

	it("withdraws a recorded response, comment and all, when its button is pressed again", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({
			observation: {
				...observation,
				feedbackResponse: {
					...observation.feedbackResponse,
					feedbackId: "00000000-0000-0000-0000-000000000002",
					resolution: "ADDRESSED",
					comment: "Applied in the next revision.",
				},
			},
			onRespond,
		});

		// The response as it stands: the resolution pressed, the rating and the comment shown.
		expect(screen.getByRole("button", { name: "Addressed" }).getAttribute("aria-pressed")).toBe(
			"true",
		);
		screen.getByText("Helpful");
		screen.getByText("Applied in the next revision.");
		fireEvent.click(screen.getByRole("button", { name: "Addressed" }));

		expect(onRespond).toHaveBeenCalledExactlyOnceWith(
			{
				...observation,
				feedbackResponse: {
					...observation.feedbackResponse,
					feedbackId: "00000000-0000-0000-0000-000000000002",
					resolution: "ADDRESSED",
					comment: "Applied in the next revision.",
				},
			},
			{ comment: undefined, resolution: undefined, usefulness: "HELPFUL" },
		);
		expect(screen.queryByRole("textbox")).toBeNull();
	});
});
