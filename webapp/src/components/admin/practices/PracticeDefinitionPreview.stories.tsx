import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, userEvent } from "storybook/test";

import type { CuratedPracticeDefinition } from "@/api/types.gen";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { realPracticeDefinition } from "@/mocks/fixtures/practice-catalog";
import { expectNoOverflowingElement } from "@/stories/reflow";
import { precedes } from "@/test/dom";

import { PracticeDefinitionPreview } from "./PracticeDefinitionPreview";

const definition: CuratedPracticeDefinition = {
	name: "Describe what changed and why",
	artifactKind: "scm.pull_request",
	bindings: [mockPullRequestBinding],
	criteria: "Confirm the pull request explains both the change and its motivation.",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	precomputeScript: "export default { hasDescription: pullRequest.body.length > 0 };",
	whyItMatters: "Reviewers need intent to assess whether the change solves the right problem.",
	whatGoodLooksLike: "A concise summary, motivation, and verification steps.",
	groupSlug: "review-ready-work",
};

const meta = {
	component: PracticeDefinitionPreview,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	// A real bundled practice, not invented copy: the layout claims below are about its size.
	args: { definition: realPracticeDefinition, options: mockPracticeDefinitionOptions },
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeDefinitionPreview>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Complete: Story = {
	args: { definition },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Pull request details")).not.toBeInTheDocument();
		await expect(canvas.queryByText(/hasDescription/u)).not.toBeInTheDocument();
		await userEvent.click(canvas.getByRole("button", { name: "Review scope and evidence" }));
		await expect(canvas.getByText("Pull request details")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "What it measures first" }));
		await expect(canvas.getByText(/hasDescription/u)).toBeVisible();
	},
};

export const WithoutOptionalGuidance: Story = {
	args: {
		definition: {
			...definition,
			whyItMatters: undefined,
			whatGoodLooksLike: undefined,
			precomputeScript: undefined,
		},
	},
};

export const ScopedReviewer: Story = {
	args: {
		definition: {
			...definition,
			bindings: [
				{
					...mockPullRequestBinding,
					subject: "REVIEWER",
					appliesWhen: {
						absentSays: "the change has no Swift code",
						anyOf: [{ changedPathMatches: ["**/*.swift"] }],
					},
				},
			],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Review scope and evidence" }));
		await expect(canvas.getByText("reviewer")).toBeVisible();
		await expect(canvas.getByText("Changed path matches **/*.swift")).toBeVisible();
	},
};

export const LongContentInDarkMode: Story = {
	args: {
		definition: {
			...definition,
			criteria:
				"Confirm that the pull request explains the behavior change, the operational constraints that shaped it, the alternatives considered, and the evidence used to verify the result. Stay silent when the change is generated automatically and no meaningful author decision exists.",
			whatGoodLooksLike:
				"The description connects the implementation to the user-visible outcome, names the rejected alternatives without reproducing the entire design discussion, and gives reviewers concrete verification steps.",
		},
	},
	globals: { theme: "dark" },
};

/**
 * Every bundled practice writes `criteria` as markdown, and the editor promises it is supported.
 * Rendered as one paragraph it reached the reader as literal `##` and `-` characters.
 */
export const CriteriaIsMarkdown: Story = {
	args: {
		definition: {
			...definition,
			criteria: [
				"## The standard",
				"Judge whether the change is packaged at a size a reviewer can read *end to end*.",
				"",
				"## Signals",
				"- One self-contained concern",
				"- A `diff` a reviewer can hold in their head",
			].join("\n"),
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "How it decides" }));
		const standard = await canvas.findByRole("heading", { name: "The standard", level: 4 });
		await expect(standard).toBeVisible();
		await expect(canvas.getByRole("list")).toBeVisible();
		await expect(canvas.getAllByRole("listitem")).toHaveLength(2);
		await expect(canvas.getByText("end to end").tagName).toBe("EM");
		await expect(canvas.queryByText(/^## /u)).not.toBeInTheDocument();
	},
};

/**
 * The decision being made is "do we want this habit", so the rationale leads. `criteria` answers a
 * different question — how the model judges — in text addressed to the model, so it sits behind a
 * disclosure with the precompute script rather than above the reason to adopt.
 */
export const RationaleLeadsTheRuleFollows: Story = {
	play: async ({ canvas }) => {
		const rationale = canvas.getByText(realPracticeDefinition.whyItMatters);
		await expect(rationale).toBeVisible();
		// Collapsed, so none of the rule is in the accessible tree until it is asked for.
		await expect(
			canvas.queryByRole("heading", { name: "The standard", level: 4 }),
		).not.toBeInTheDocument();
		const rule = canvas.getByRole("button", { name: "How it decides" });
		await expect(precedes(rationale, rule)).toBe(true);
	},
};

/** A practice may carry only one of the two guidance fields; nothing above may collapse without it. */
export const RationaleOnly: Story = {
	args: { definition: { ...realPracticeDefinition, whatGoodLooksLike: undefined } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(realPracticeDefinition.whyItMatters)).toBeVisible();
		await expect(
			canvas.queryByRole("heading", { name: "What good looks like" }),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "How it decides" })).toBeVisible();
	},
};

/** An artifact kind the options do not describe: the evidence summary must still render. */
export const UnknownWorkType: Story = {
	args: { options: { ...mockPracticeDefinitionOptions, workTypes: [] } },
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Review scope and evidence" }));
		await expect(await canvas.findByRole("button", { name: "How it decides" })).toBeVisible();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
