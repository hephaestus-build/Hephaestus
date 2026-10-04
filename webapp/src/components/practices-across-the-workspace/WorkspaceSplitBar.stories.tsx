import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { threeWay, WITHHELD } from "@/stories/practices-across-the-workspace-story-data";

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
		split: threeWay([6, 7, 7]),
		yourStanding: "MIXED",
		window: "DAYS_30",
		readerCounted: true,
		developersWithAStanding: 28,
		minimumOthers: 3,
	},
} satisfies Meta<typeof WorkspaceSplitBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers observed in this workspace in the last 30 days: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. You: Mixed feedback.",
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

/** Beside a standing badge, as in a level's head, the bar leaves the reader's word out. */
export const BesideABadge: Story = {
	args: { readerCounted: false, showYourWord: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("You:")).toBeNull();
	},
};

/**
 * Held back: an empty track where the bar would be, one short reason and the reader's word. The
 * total is said once above the table, never on the row.
 */
export const Withheld: Story = {
	args: { split: WITHHELD, yourStanding: "NOT_OBSERVED" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.getByText("Held back so no one can be singled out.")).toBeVisible();
		await expect(canvas.queryByText(/developers observed/u)).toBeNull();
		await expect(canvas.getByText("None yet (Not observed yet)")).toBeVisible();
	},
};

/**
 * A reader with nothing to report is counted in none yet: the marker sits over that part, and the
 * text names it in the legend's words with the profile's reason after them.
 */
export const ReaderInNoneYet: Story = {
	args: { yourStanding: "NO_OPPORTUNITY" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.queryByText("You:")).toBeNull();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/, 8 none yet\. You: None yet \(Nothing to report yet\)\.$/u,
		);
	},
};
