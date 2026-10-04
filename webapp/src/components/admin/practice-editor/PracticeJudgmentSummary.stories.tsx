import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { mockDescriptionJudgment, mockStartingJudgment } from "@/mocks/fixtures/practice";

import { PracticeJudgmentSummary } from "./PracticeJudgmentSummary";

const meta = {
	component: PracticeJudgmentSummary,
	args: { judgment: mockDescriptionJudgment },
	parameters: { layout: "padded" },
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeJudgmentSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Written: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("When “Says what changed” is no")).toBeVisible();
		await expect(canvas.getByText("In every other case")).toBeVisible();
	},
};

export const Starting: Story = { args: { judgment: mockStartingJudgment } };

/** A practice nobody reviews automatically asks nothing, and says so. */
export const NoQuestions: Story = {
	args: { judgment: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/This practice asks no questions/u)).toBeVisible();
	},
};
