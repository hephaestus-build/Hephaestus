import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { FeedbackResponse, OwnFeedbackRef } from "@/api/types.gen";
import { detailFeedback } from "@/stories/practice-detail-story-mock-data";

import { OwnFeedbackList } from "./OwnFeedbackList";
import { type FeedbackResponseControls, responseRequestOf } from "./reviews";

const recorded: FeedbackResponse = {
	usefulness: "HELPFUL",
	resolution: "ADDRESSED",
	comment: "Applied in the next commit.",
	createdAt: detailFeedback.deliveredAt,
};

const answered: OwnFeedbackRef = { ...detailFeedback, response: recorded };

function renderList(feedback = answered) {
	const responses = {
		write: vi.fn<FeedbackResponseControls["write"]>(),
		clear: vi.fn<FeedbackResponseControls["clear"]>(),
		responseOf: (_feedbackId: string, response: FeedbackResponse | null) =>
			responseRequestOf(response),
		pendingResponses: new Map(),
	} satisfies FeedbackResponseControls;
	render(<OwnFeedbackList feedback={[feedback]} responses={responses} />);
	return { feedback, responses };
}

describe("OwnFeedbackList", () => {
	it("refuses a disagreement made of blanks through the field rather than in silence", () => {
		const { responses } = renderList();

		fireEvent.click(screen.getByRole("button", { name: "Disagree" }));
		const comment = screen.getByRole<HTMLInputElement>("textbox", {
			name: "Why do you disagree?",
		});
		fireEvent.change(comment, { target: { value: "   " } });
		fireEvent.click(screen.getByRole("button", { name: "Send" }));

		expect(comment.value).toBe("");
		expect(comment.validity.valueMissing).toBe(true);
		expect(responses.write).not.toHaveBeenCalled();
	});

	it("writes a disagreement as a change of resolution and reason, by the feedback's id", () => {
		const { feedback, responses } = renderList();

		fireEvent.click(screen.getByRole("button", { name: "Disagree" }));
		fireEvent.change(screen.getByRole("textbox", { name: "Why do you disagree?" }), {
			target: { value: "  The value is derived, not chosen.  " },
		});
		fireEvent.click(screen.getByRole("button", { name: "Send" }));

		expect(responses.write).toHaveBeenCalledExactlyOnceWith(feedback.id, recorded, {
			resolution: "DISAGREE",
			comment: "The value is derived, not chosen.",
		});
	});

	it("closes a disagreement's band on Skip without recording it", () => {
		const { responses } = renderList();

		fireEvent.click(screen.getByRole("button", { name: "Disagree" }));
		fireEvent.click(screen.getByRole("button", { name: "Skip" }));

		expect(responses.write).not.toHaveBeenCalled();
		expect(screen.queryByRole("textbox")).toBeNull();
		expect(screen.getByRole("button", { name: "Disagree" }).getAttribute("aria-pressed")).toBe(
			"false",
		);
	});

	it("withdraws a recorded resolution, comment and all, when its button is pressed again", () => {
		const { feedback, responses } = renderList();

		expect(screen.getByRole("button", { name: "Addressed" }).getAttribute("aria-pressed")).toBe(
			"true",
		);
		screen.getByText("Helpful");
		screen.getByText("Applied in the next commit.");
		fireEvent.click(screen.getByRole("button", { name: "Addressed" }));

		expect(responses.write).toHaveBeenCalledExactlyOnceWith(feedback.id, recorded, {
			resolution: undefined,
			comment: undefined,
		});
		expect(screen.queryByRole("textbox")).toBeNull();
	});

	it("clears the whole response on Clear response", () => {
		const { feedback, responses } = renderList();

		fireEvent.click(screen.getByRole("button", { name: "Clear response" }));

		expect(responses.clear).toHaveBeenCalledExactlyOnceWith(feedback.id);
		expect(responses.write).not.toHaveBeenCalled();
	});

	it("offers no Clear response while there is nothing to clear", () => {
		renderList({ ...detailFeedback, response: null });

		expect(screen.queryByRole("button", { name: "Clear response" })).toBeNull();
	});

	it("offers no response to feedback that was not delivered", () => {
		renderList({ ...detailFeedback, state: "WITHHELD", deliveredAt: null, copies: [] });

		expect(screen.queryByRole("group")).toBeNull();
	});

	it("allows a response to the part confirmed on the work", () => {
		renderList({ ...detailFeedback, state: "UNCONFIRMED" });

		screen.getByText("Part of this feedback appeared on the work. The rest is not confirmed yet.");
		screen.getByRole("button", { name: "Disagree" });
	});
});
