import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { mockPullRequestWorkType } from "@/mocks/fixtures/practice";
import { Stateful } from "@/stories/stateful";
import { expectNoOverflowingElement } from "@/test/reflow";

import { PracticeMentoringSupportEditor } from "./PracticeMentoringSupportEditor";

const meta = {
	component: PracticeMentoringSupportEditor,
	args: {
		value: mockPullRequestWorkType.recommendedPolicy,
		recommended: mockPullRequestWorkType.recommendedPolicy,
		supportedAutomatedReviewModes: mockPullRequestWorkType.supportedAutomatedReviewModes,
		onChange: fn(),
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<PracticeMentoringSupportEditor
					{...args}
					value={value}
					onChange={(next) => {
						args.onChange(next);
						setValue(next);
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeMentoringSupportEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AiSupported: Story = {};

export const HumanReviewReasonIsNotALimitation: Story = {
	args: {
		value: {
			...mockPullRequestWorkType.recommendedPolicy,
			automatedReview: {
				mode: "LANGUAGE_MODEL",
				evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
			},
			insufficiencyReason: {
				code: "MENTOR_CONVERSATION_NOT_OBSERVED",
				description: "The trade-off was agreed in a conversation no automated review can read.",
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(/Why is human review needed/u)).toHaveValue(
			"The trade-off was agreed in a conversation no automated review can read.",
		);
		await expect(
			canvas.getByDisplayValue(
				"Repository evidence does not establish behavior in a deployed runtime.",
			),
		).toBeVisible();
	},
};

/** With no review to constrain, there is nothing for a limitation to be a limitation of. */
export const GuidanceOnly: Story = {
	args: {
		value: {
			...mockPullRequestWorkType.recommendedPolicy,
			automatedReview: { mode: "NONE", evidenceSufficiency: "NONE" },
			knownLimitations: [],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: "Add limitation" })).toBeNull();
	},
};

/** The choice is disabled rather than silently reinterpreted as one the instance supports. */
export const AiReviewUnavailable: Story = {
	args: { supportedAutomatedReviewModes: [] },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/no AI review available on this instance/u)).toBeVisible();
	},
};

export const Invalid: Story = {
	args: {
		value: {
			...mockPullRequestWorkType.recommendedPolicy,
			automatedReview: {
				mode: "LANGUAGE_MODEL",
				evidenceSufficiency: "DECLARED_EVIDENCE_INSUFFICIENT",
			},
			insufficiencyReason: { code: "LIMITATION_00000000", description: "" },
			knownLimitations: [],
		},
		error: "Explain at least one limitation that requires additional context.",
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Explain at least one limitation that requires additional context."),
		).toBeVisible();
		await expect(
			canvas.getByText("Say what a person can see here that the connected work cannot show."),
		).toBeVisible();
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
