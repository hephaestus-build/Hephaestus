import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	GATED_WORKSPACE,
} from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceTiles } from "./WorkspaceTiles";

const meta = {
	component: WorkspaceTiles,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { overview: ACROSS_WORKSPACE },
} satisfies Meta<typeof WorkspaceTiles>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Three to a row: the fourth tile, open feedback, starts the second. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText(/^Typical range:/u)).toHaveLength(4);
		await expect(canvas.getByText("11 to 21")).toBeVisible();
		await expect(canvas.getAllByText("of your 18 practices")).toHaveLength(2);
		await expect(canvas.getByText("pieces open now")).toBeVisible();
		await expect(
			canvas.getByRole("img", {
				name: "You: 3. Typical range here: 1 to 4.",
			}),
		).toBeVisible();
		// Where a figure comes from sits behind an info icon, so every tile keeps one height.
		await expect(
			canvas.getByRole("button", {
				name: "About Open feedback: Feedback your Practice profile shows open right now, whatever the range.",
			}),
		).toBeVisible();
		// Every tile explains itself the same way, so all four keep one height.
		await expect(canvas.getAllByRole("button", { name: /^About /u })).toHaveLength(4);
	},
};

/** Too few developers observed: the reader's own figures stand, the workspace's say why they do not. */
export const NeedsMoreData: Story = {
	args: { overview: GATED_WORKSPACE },
	play: async ({ canvas }) => {
		await expect(
			canvas.getAllByText("Needs more data before the workspace shows here."),
		).toHaveLength(4);
		// Each tile keeps its axis, with only the reader's own pin on it.
		await expect(canvas.getAllByRole("img", { name: /^You: \d+\.$/u })).toHaveLength(4);
	},
};

/** Nobody here has feedback open: one sentence in place of a band and pin at nought. */
export const NoOpenFeedbackHere: Story = {
	args: {
		overview: { ...ACROSS_WORKSPACE, openFeedback: { yours: 0, middleLow: 0, middleHigh: 0 } },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Most developers here have no open feedback.")).toBeVisible();
		await expect(canvas.getAllByText(/^Typical range:/u)).toHaveLength(3);
	},
};

export const Loading: Story = {
	args: { overview: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Loading the figures")).toHaveClass("sr-only");
	},
};
