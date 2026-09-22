import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";

import type { PracticeAutomatedReviewPolicy } from "@/api/types.gen";
import { mockPullRequestWorkType } from "@/mocks/fixtures/practice";
import { renderWithRouter } from "@/test/router-harness";

import {
	PracticeMentoringSupportEditor,
	practicePolicyError,
} from "./PracticeMentoringSupportEditor";

const recommended = mockPullRequestWorkType.recommendedPolicy;

/**
 * The editor is controlled, so what it emits is only observable through the state it writes back.
 * These `output` probes publish that state as named readings an assertion can query by label.
 */
function Controlled({ initial = recommended }: { initial?: PracticeAutomatedReviewPolicy }) {
	const [value, setValue] = useState(initial);
	return (
		<>
			<output aria-label="review rule">
				{value.automatedReview.mode}:{value.automatedReview.evidenceSufficiency}
			</output>
			<output aria-label="insufficiency reason">
				{value.insufficiencyReason ? "set" : "none"}
			</output>
			<output aria-label="limitation count">{value.knownLimitations.length}</output>
			<PracticeMentoringSupportEditor
				value={value}
				recommended={recommended}
				supportedAutomatedReviewModes={mockPullRequestWorkType.supportedAutomatedReviewModes}
				onChange={setValue}
			/>
		</>
	);
}

describe("PracticeMentoringSupportEditor", () => {
	it("maps the human-facing support choices to conservative review rules", async () => {
		const user = userEvent.setup();
		await renderWithRouter(<Controlled />, "/admin/practices/new");

		await user.click(screen.getByRole("radio", { name: /Human review needed/u }));
		expect(screen.getByLabelText("review rule").textContent).toBe(
			"LANGUAGE_MODEL:DECLARED_EVIDENCE_INSUFFICIENT",
		);

		await user.click(screen.getByRole("radio", { name: /Guidance only/u }));
		expect(screen.getByLabelText("review rule").textContent).toBe("NONE:NONE");
	});

	it("does not leave an empty human-review reason after returning to AI support", async () => {
		const user = userEvent.setup();
		await renderWithRouter(
			<Controlled initial={{ ...recommended, knownLimitations: [] }} />,
			"/admin/practices/new",
		);

		await user.click(screen.getByRole("radio", { name: /Human review needed/u }));
		screen.getByRole("textbox", { name: /Why is human review needed/u });
		expect(screen.getByLabelText("insufficiency reason").textContent).toBe("set");
		// The reason is not a limitation, so asking for a human adds nothing to that list.
		expect(screen.getByLabelText("limitation count").textContent).toBe("0");

		await user.click(screen.getByRole("radio", { name: /AI-supported mentoring/u }));
		expect(screen.queryByRole("textbox", { name: /Why is human review needed/u })).toBeNull();
		expect(screen.getByLabelText("insufficiency reason").textContent).toBe("none");
	});

	it("restores the limitations an author wrote after a detour through guidance only", async () => {
		const user = userEvent.setup();
		await renderWithRouter(
			<Controlled
				initial={{
					...recommended,
					knownLimitations: [
						{ code: "CUSTOM_LIMITATION", description: "Keep this edited limitation." },
					],
				}}
			/>,
			"/admin/practices/new",
		);

		await user.click(screen.getByRole("radio", { name: /Guidance only/u }));
		await user.click(screen.getByRole("radio", { name: /AI-supported mentoring/u }));

		screen.getByDisplayValue("Keep this edited limitation.");
	});

	it("derives a stable limitation code from the text so retyping it is not a rule change", async () => {
		const user = userEvent.setup();
		function CodeProbe() {
			const [value, setValue] = useState<PracticeAutomatedReviewPolicy>({
				...recommended,
				knownLimitations: [{ code: "LIMITATION_79DBDE7E", description: "" }],
			});
			return (
				<>
					<output aria-label="limitation code">{value.knownLimitations[0]?.code}</output>
					<PracticeMentoringSupportEditor
						value={value}
						recommended={recommended}
						supportedAutomatedReviewModes={mockPullRequestWorkType.supportedAutomatedReviewModes}
						onChange={setValue}
					/>
				</>
			);
		}
		await renderWithRouter(<CodeProbe />, "/admin/practices/new");

		await user.type(screen.getByLabelText(/Description/u), "Runtime behavior not observed");

		// A pure function of the text, so retyping the same limitation cannot look like a review-rule
		// change to the policy digest.
		expect(screen.getByLabelText("limitation code").textContent).toBe(
			"RUNTIME_BEHAVIOR_NOT_OBSERVED",
		);
	});

	it("still allows human review when AI support is unavailable", async () => {
		await renderWithRouter(
			<PracticeMentoringSupportEditor
				value={recommended}
				recommended={recommended}
				supportedAutomatedReviewModes={[]}
				onChange={vi.fn()}
			/>,
			"/admin/practices/new",
		);

		// `aria-disabled`, not `data-disabled`: Base UI renders a radio as a `<span role="radio">`, so
		// there is no native `disabled`, and `data-disabled` is only the CSS hook. A regression that
		// keeps the styling and drops the semantics leaves that one alone.
		expect(
			screen.getByRole("radio", { name: /AI-supported mentoring/u }).getAttribute("aria-disabled"),
		).toBe("true");
		expect(
			screen.getByRole("radio", { name: /Human review needed/u }).getAttribute("aria-disabled"),
		).not.toBe("true");
	});

	it("preserves focus while editing limitations", async () => {
		const user = userEvent.setup();
		await renderWithRouter(<Controlled />, "/admin/practices/new");

		await user.click(screen.getByRole("button", { name: "Add limitation" }));
		const added = screen.getByRole("textbox", { name: "Description for limitation 2" });
		await waitFor(() => expect(document.activeElement).toBe(added));
		await user.type(added, "A stable limitation");
		await user.click(screen.getByRole("button", { name: "Remove limitation 2" }));
		await waitFor(() =>
			expect(document.activeElement).toBe(
				screen.getByRole("textbox", { name: "Description for limitation 1" }),
			),
		);
	});

	it("rejects a review frame the server would refuse", () => {
		expect(
			practicePolicyError({
				...recommended,
				automatedReview: {
					mode: "LANGUAGE_MODEL",
					evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
				},
				knownLimitations: [],
			}),
		).toBe("Explain at least one limitation that requires additional context.");
		expect(
			practicePolicyError({ ...recommended, knownLimitations: [{ code: "X", description: "a" }] }),
		).toBe("Limitation identifiers must use 3–64 uppercase letters, numbers, and underscores.");
		// Evidence belongs to the occasion that reads it, not to the practice-wide frame.
		expect(practicePolicyError(recommended)).toBeUndefined();
	});
});
