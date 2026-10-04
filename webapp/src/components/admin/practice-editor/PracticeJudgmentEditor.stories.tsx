import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { mockDescriptionJudgment, mockStartingJudgment } from "@/mocks/fixtures/practice";
import { expectNoOverflowingElement } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { judgmentProblems } from "./practice-judgment";
import { PracticeJudgmentEditor } from "./PracticeJudgmentEditor";

const meta = {
	component: PracticeJudgmentEditor,
	args: {
		value: mockDescriptionJudgment,
		starting: mockStartingJudgment,
		saved: mockDescriptionJudgment,
		onChange: fn(),
	},
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	render: (args) => (
		<Stateful initial={args.value}>
			{(value, setValue) => (
				<PracticeJudgmentEditor
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
} satisfies Meta<typeof PracticeJudgmentEditor>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A written judgment: each rule reads as the sentence it decides by. */
export const Written: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("When “Says what changed” is no: Not met, Major")).toBeVisible();
		await expect(canvas.getByText("In every other case: Met")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Restore the starting questions" }),
		).toBeVisible();
	},
};

/** The starting questions offer nothing to restore. */
export const Starting: Story = {
	args: { value: mockStartingJudgment, saved: undefined },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: "Restore the starting questions" }),
		).toBeNull();
	},
};

/** A new question takes its key from its title, and a rule can then ask about it. */
export const AddsAQuestion: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Add question" }));
		const title = canvas.getAllByLabelText("Title").at(-1);
		if (title === undefined) {
			throw new Error("The new question has a title field");
		}
		await userEvent.type(title, "Has tests");
		await expect(args.onChange).toHaveBeenCalled();
		// Each rule but the last can now ask about it.
		await expect(canvas.getAllByLabelText("Has tests")).toHaveLength(2);
	},
};

/** A refused save lists what to fix, worded as the server words it. */
export const WithProblems: Story = {
	args: {
		value: { ...mockDescriptionJudgment, rules: mockDescriptionJudgment.rules.slice(0, 2) },
		problems: judgmentProblems({
			...mockDescriptionJudgment,
			rules: mockDescriptionJudgment.rules.slice(0, 2),
		}),
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"The last rule must have no conditions, so every combination of answers has an outcome.",
			),
		).toBeVisible();
	},
};

export const Disabled: Story = {
	args: { disabled: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Add rule" })).toBeDisabled();
	},
};

export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
