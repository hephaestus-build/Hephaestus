import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	threeWay,
	TOTAL_ONLY,
	WITHHELD,
} from "@/stories/practices-across-the-workspace-story-data";

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
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. The You marker is on Mixed feedback.",
		);
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.queryByText(/^You:/u)).toBeNull();
	},
};

/** A reader with no current standing is in no count: no part carries the marker, and no word says it. */
export const ReaderNotCounted: Story = {
	args: { readerCounted: false },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(canvas.queryByText(/^You:/u)).toBeNull();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers with a current standing in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet.",
		);
	},
};

/**
 * A part holds too few: one neutral bar with the total and a short label, so the row says the
 * group counts but not how. The reader is not marked.
 */
export const TotalOnly: Story = {
	args: { split: TOTAL_ONLY },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers with a current standing in this workspace. The split is held back so no one can be singled out.",
		);
		await expect(canvas.getByText("Split held back")).toBeVisible();
		await expect(canvas.getByText("28 developers")).toBeVisible();
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(canvas.queryByText(/None yet/u)).toBeNull();
	},
};

/**
 * Held back, the total too, as when too few developers have a standing at all: an empty track where
 * the bar would be and one short reason, nothing more. The reader is not marked.
 */
export const Withheld: Story = {
	args: { split: WITHHELD, yourStanding: "NOT_OBSERVED" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.getByText("Held back so no one can be singled out.")).toBeVisible();
		await expect(canvas.queryByText(/developers with a/u)).toBeNull();
		await expect(canvas.queryByText(/You/u)).toBeNull();
		await expect(canvas.queryByText(/None yet/u)).toBeNull();
	},
};

/** The server held the total back: the bar names its reference group without a count. */
export const TotalHeldBack: Story = {
	args: { developersWithAStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/^Developers with a current standing in this workspace: /u,
		);
	},
};

/** A reader with nothing to report is counted in none yet: the marker sits over that part. */
export const ReaderInNoneYet: Story = {
	args: { yourStanding: "NO_OPPORTUNITY" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/, 8 none yet\. The You marker is on none yet\.$/u,
		);
	},
};
