import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { EvidenceCitation, ObservationDetail } from "@/api/types.gen";
import { detailObservation } from "@/stories/practice-detail-story-mock-data";

import { ReviewObservationRow, type ReviewObservationRowProps } from "./ReviewObservationRow";

const observation: ObservationDetail = {
	...detailObservation,
	id: "00000000-0000-0000-0000-000000000001",
	feedbackId: "00000000-0000-0000-0000-000000000002",
	practiceSlug: "explain-decisions",
	practiceName: "Explain significant decisions",
	summary: "The reason for the timeout is missing",
	assessmentStatus: "ASSESSED",
	presence: "PRESENT",
	assessment: "BAD",
	severity: "MINOR",
	feedbackUsefulness: "HELPFUL",
};

/** The row as a review run's card mounts it: under the work's own head, so it links no work. */
function renderOpen(props: Partial<ReviewObservationRowProps> = {}) {
	return render(
		<ul>
			<ReviewObservationRow
				observation={observation}
				showWorkLink={false}
				onRespond={vi.fn()}
				{...props}
			/>
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
	it("arrives open with every quote shown and the capturing scanner named once", () => {
		renderOpen({
			observation: {
				...observation,
				evidence: {
					detector: "secret-diff-scanner",
					citations: [citation("src/first.ts"), citation("src/second.ts")],
				},
			},
		});

		expect(
			screen
				.getByRole("button", { name: /The reason for the timeout/u })
				.getAttribute("aria-expanded"),
		).toBe("true");
		screen.getByText("first.ts");
		screen.getByText("second.ts");
		expect(screen.getAllByText("secret-diff-scanner")).toHaveLength(1);
	});

	it("shows what the feed carries, each block under its label, and no label over nothing", () => {
		const { rerender } = renderOpen();

		screen.getByText("Why it was noted");
		screen.getByText(/renames the loader's package and changes its caching/u);
		screen.getByText("Evidence");
		screen.getByText("Next step");
		screen.getByText(/Land the rename on its own first/u);
		screen.getByText("Your response");
		// Inside the work's own card, the row does not link the work again.
		expect(screen.queryByRole("link")).toBeNull();

		rerender(
			<ul>
				<ReviewObservationRow
					observation={{
						...observation,
						evidenceRationale: undefined,
						deliveredFeedback: undefined,
						evidence: undefined,
					}}
					showWorkLink={false}
					onRespond={vi.fn()}
				/>
			</ul>,
		);
		expect(screen.queryByText("Why it was noted")).toBeNull();
		expect(screen.queryByText("Evidence")).toBeNull();
		expect(screen.queryByText("Next step")).toBeNull();
		screen.getByText("Your response");
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

	it("opens a row whose only body is the warrant behind it, under its own label", () => {
		renderOpen({
			observation: {
				...observation,
				feedbackId: undefined,
				evidenceRationale: undefined,
				deliveredFeedback: undefined,
				evidence: {
					citations: [],
					undecidability: {
						openQuestion: "whether the rename was asked for",
						wouldSettleIt: "the review thread the description points at",
					},
				},
			},
		});

		expect(
			screen
				.getByRole("button", { name: /The reason for the timeout/u })
				.getAttribute("aria-expanded"),
		).toBe("true");
		screen.getByText("What was checked");
		screen.getByText("Open question:");
		screen.getByText("whether the rename was asked for");
		expect(screen.queryByText("Evidence")).toBeNull();
	});

	it("links the reviewed work where the row stands outside its card", () => {
		renderOpen({ showWorkLink: true });
		expect(
			screen.getByRole("link", { name: /Open the pull or merge request/u }).getAttribute("href"),
		).toBe(observation.artifactUrl);
	});

	it("says where an observation came from and under which rules, and hides neither", () => {
		renderOpen({ observation: { ...observation, origin: "BACKFILL", claimCurrentness: "STALE" } });

		screen.getByText("Backfilled");
		screen.getByText("Reviewed under earlier rules for this practice.");
		screen.getByText("Why it was noted");
	});

	it("closes on a press", () => {
		renderOpen();
		const row = screen.getByRole("button", { name: /The reason for the timeout/u });
		fireEvent.click(row);
		expect(row.getAttribute("aria-expanded")).toBe("false");
		expect(screen.queryByText("Why it was noted")).toBeNull();
	});

	it("is not a control when the feed carries nothing more than its head line", () => {
		renderOpen({
			observation: {
				...observation,
				feedbackId: undefined,
				evidenceRationale: undefined,
				deliveredFeedback: undefined,
				evidence: undefined,
			},
		});
		screen.getByText("The reason for the timeout is missing");
		// The outcome chip is the one control left: the button a keyboard reaches its sentence by.
		expect(screen.getAllByRole("button")).toHaveLength(1);
		screen.getByRole("button", { name: "Needs improvement" });
	});

	it("names the outcome without ranking it, and keeps the comment band shut until a response is chosen", () => {
		renderOpen();

		screen.getByText("Needs improvement");
		expect(screen.queryByText("Minor")).toBeNull();
		screen.getByRole("button", { name: "Addressed" });
		screen.getByRole("button", { name: "Not applicable" });
		expect(screen.queryByRole("textbox")).toBeNull();
	});

	it("records a response without a comment through Skip, with the usefulness intact", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({ onRespond });

		fireEvent.click(screen.getByRole("button", { name: "Addressed" }));
		expect(screen.getByRole("button", { name: "Addressed" }).getAttribute("aria-pressed")).toBe(
			"true",
		);
		screen.getByRole("textbox", { name: "Anything to add?" });
		expect(onRespond).not.toHaveBeenCalled();

		fireEvent.click(screen.getByRole("button", { name: "Skip" }));
		expect(onRespond).toHaveBeenCalledExactlyOnceWith(observation, {
			comment: undefined,
			resolution: "ADDRESSED",
			usefulness: "HELPFUL",
		});
		expect(screen.queryByRole("textbox")).toBeNull();
	});

	it("holds a dispute until its sentence is sent", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({ onRespond });

		fireEvent.click(screen.getByRole("button", { name: "Disputed" }));
		expect(onRespond).not.toHaveBeenCalled();
		const comment = screen.getByRole("textbox", { name: "What was missed?" });
		expect(comment.hasAttribute("required")).toBe(true);

		fireEvent.change(comment, { target: { value: "The timeout is required by the provider." } });
		fireEvent.click(screen.getByRole("button", { name: "Send" }));

		expect(onRespond).toHaveBeenCalledExactlyOnceWith(observation, {
			comment: "The timeout is required by the provider.",
			resolution: "DISPUTED",
			usefulness: "HELPFUL",
		});
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

	it("sends a comment with the chosen response", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({ onRespond });

		fireEvent.click(screen.getByRole("button", { name: "Not applicable" }));
		fireEvent.change(screen.getByRole("textbox", { name: "Anything to add?" }), {
			target: { value: "This branch never reaches the network." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Send" }));

		expect(onRespond).toHaveBeenCalledExactlyOnceWith(observation, {
			comment: "This branch never reaches the network.",
			resolution: "NOT_APPLICABLE",
			usefulness: "HELPFUL",
		});
	});

	it("withdraws a recorded response, comment and all, when its button is pressed again", () => {
		const onRespond = vi.fn<NonNullable<ReviewObservationRowProps["onRespond"]>>();
		renderOpen({
			observation: {
				...observation,
				feedbackResolution: "ADDRESSED",
				feedbackResponseComment: "Applied in the next revision.",
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
				feedbackResolution: "ADDRESSED",
				feedbackResponseComment: "Applied in the next revision.",
			},
			{ comment: undefined, resolution: undefined, usefulness: "HELPFUL" },
		);
		expect(screen.queryByRole("textbox")).toBeNull();
	});
});
