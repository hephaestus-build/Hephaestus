import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { ACROSS_WORKSPACE } from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

const [acting, , failure] = ACROSS_WORKSPACE.groups;
if (acting === undefined || failure === undefined) {
	throw new Error("The fixture carries a split and a collapsed group");
}

/**
 * The split in developers, needs attention first. The text alternative names the reference group
 * and every count, so the colours carry nothing a screen reader misses.
 */
const meta = {
	component: WorkspaceSplitBar,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { group: acting, window: "TERM", readerCounted: true, observedDevelopers: 24 },
} satisfies Meta<typeof WorkspaceSplitBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"24 developers observed in this workspace this term: 5 Needs attention, 7 Mixed feedback, 7 Going well, 5 none yet. You: Mixed feedback.",
		);
		await expect(canvas.getByText("You")).toBeVisible();
	},
};

/** A reader outside the counts gets no marker, and the text says why. */
export const ReaderNotCounted: Story = {
	args: { readerCounted: false, window: "DAYS_30" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/in the last 30 days: .* You: Mixed feedback, not counted in the split\.$/u,
		);
	},
};

/** Collapsed: has a standing against none yet, and never a marker. */
export const Collapsed: Story = {
	args: { group: failure },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(
			canvas.getByText("19 have a standing · 5 none yet · split held back"),
		).toBeVisible();
	},
};
