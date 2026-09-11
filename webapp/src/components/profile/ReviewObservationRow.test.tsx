import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { PracticeGroupReviewObservation } from "@/api/types.gen";

import { ReviewObservationRow } from "./ReviewObservationRow";

const observation: PracticeGroupReviewObservation = {
	observationId: "00000000-0000-0000-0000-000000000001",
	feedbackId: "00000000-0000-0000-0000-000000000002",
	practiceSlug: "explain-decisions",
	practiceName: "Explain significant decisions",
	title: "The reason for the timeout is missing",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MINOR",
	feedbackUsefulness: "HELPFUL",
};

describe("ReviewObservationRow", () => {
	it("submits a new dispute only after its required explanation is available", () => {
		const onRespond = vi.fn();
		render(
			<ul>
				<ReviewObservationRow
					observation={observation}
					isOpen
					onToggle={vi.fn()}
					onRespond={onRespond}
				/>
			</ul>,
		);

		fireEvent.click(screen.getByRole("button", { name: "Disputed" }));
		expect(onRespond).not.toHaveBeenCalled();

		const explanation = screen.getByRole("textbox", { name: "Why do you disagree?" });
		fireEvent.change(explanation, {
			target: { value: "The timeout is required by the provider." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save comment" }));

		expect(onRespond).toHaveBeenCalledExactlyOnceWith(observation, {
			comment: "The timeout is required by the provider.",
			resolution: "DISPUTED",
			usefulness: "HELPFUL",
		});
		expect(screen.getByRole("button", { name: "Disputed" }).getAttribute("aria-pressed")).toBe(
			"true",
		);
		screen.getByRole("textbox", { name: "Why do you disagree?" });
	});

	it("submits immediately when a dispute already has an explanation", () => {
		const onRespond = vi.fn();
		const explainedObservation = {
			...observation,
			feedbackResponseComment: "The provider requires this timeout.",
		};
		render(
			<ul>
				<ReviewObservationRow
					observation={explainedObservation}
					isOpen
					onToggle={vi.fn()}
					onRespond={onRespond}
				/>
			</ul>,
		);

		fireEvent.click(screen.getByRole("button", { name: "Disputed" }));

		expect(onRespond).toHaveBeenCalledExactlyOnceWith(explainedObservation, {
			comment: "The provider requires this timeout.",
			resolution: "DISPUTED",
			usefulness: "HELPFUL",
		});
	});
});
