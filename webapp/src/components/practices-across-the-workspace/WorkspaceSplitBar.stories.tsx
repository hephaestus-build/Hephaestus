import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { collapsed, threeWay, WITHHELD } from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

/**
 * One bar for a practice group and for a practice. The text alternative names the reference group
 * and every count, and each part carries its count and icon, so the colours carry nothing a screen
 * reader or a colour blind reader misses.
 */
const meta = {
	component: WorkspaceSplitBar,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-md">
				<Story />
			</div>
		),
	],
	args: {
		split: threeWay([5, 7, 7]),
		yourStanding: "MIXED",
		window: "TERM",
		readerCounted: true,
		observedDevelopers: 24,
		minimumOthers: 5,
	},
} satisfies Meta<typeof WorkspaceSplitBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"24 developers observed in this workspace this term: 5 Needs attention, 7 Mixed feedback, 7 Going well, 5 none yet. You: Mixed feedback.",
		);
		await expect(canvas.getByText("You")).toBeVisible();
		// The marker carries the word, so no caption repeats it.
		await expect(canvas.queryByText("You:")).toBeNull();
	},
};

/** A reader outside the counts gets no marker; their word stands under the bar. */
export const ReaderNotCounted: Story = {
	args: { readerCounted: false, window: "DAYS_30" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(canvas.getByText("You:")).toBeVisible();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/in the last 30 days: .* You: Mixed feedback, not counted in the split\.$/u,
		);
	},
};

/** Collapsed: has a standing against none yet, the marker on the reader's part and the word under it. */
export const Collapsed: Story = {
	args: { split: collapsed(19, 5), yourStanding: "DEVELOPING" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.getByText("19 have a standing, 5 none yet; split held back")).toBeVisible();
		await expect(canvas.getByText("Needs attention")).toBeVisible();
	},
};

/** Held back with its total: has a standing or none yet holds too few, so only the total shows. */
export const CollapsedTotalOnly: Story = {
	args: { split: WITHHELD, yourStanding: "STRENGTH" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(
			canvas.getByText("Split held back: 24 developers observed this term."),
		).toBeVisible();
		await expect(canvas.getByText("Going well")).toBeVisible();
	},
};

/** Withheld: too few others observed for even the total, so the reader's word is all there is. */
export const Withheld: Story = {
	args: { split: WITHHELD, yourStanding: "NOT_OBSERVED", observedDevelopers: 5 },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Too few developers observed to compare yet.")).toBeVisible();
		await expect(canvas.queryByText(/developers observed this term/u)).toBeNull();
		await expect(canvas.getByText("Not observed yet")).toBeVisible();
	},
};
