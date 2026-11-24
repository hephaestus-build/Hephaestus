import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { split, totalOnly } from "@/stories/practices-across-the-workspace-story-data";

import { LevelSplit, WorkspaceSplitBar } from "./WorkspaceSplitBar";

/**
 * One bar for a practice group and for a practice, each developer counted once. The text
 * alternative names every count, and each part carries its count and icon, so colour carries
 * nothing a reader can miss.
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
	args: { split: split(6, 7, 7, 8), yourStanding: "MIXED" },
} satisfies Meta<typeof WorkspaceSplitBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet. You are in the Mixed feedback part.",
		);
		await expect(canvas.getByText("You")).toBeVisible();
		await expect(canvas.getByText("28 developers")).toBeVisible();
		// A range, not a point: the bracket is as wide as the part it stands over.
		const bracket = canvasElement.querySelector<HTMLElement>('[data-slot="you-bracket"]');
		const part = canvas.getByTitle("Mixed feedback: 7");
		await expect(bracket).not.toBeNull();
		await expect(
			Math.abs((bracket?.getBoundingClientRect().width ?? 0) - part.getBoundingClientRect().width),
		).toBeLessThan(1);
		// The bracket's corners stand clear of the part: they end above it, not on it.
		const bar = part.children[1];
		await expect(bracket?.getBoundingClientRect().bottom ?? Infinity).toBeLessThan(
			bar?.getBoundingClientRect().top ?? 0,
		);
	},
};

/** The parts and the You bracket carry colour meaning, so dark mode is drawn, not assumed. */
export const Dark: Story = { globals: { theme: "dark" } };

/**
 * A part would hold 1 to 4, or everyone sits in one part: the server sends the total only, and the
 * bar is one dotted track that says why without saying which.
 */
export const SpreadHidden: Story = {
	args: { split: totalOnly(28), yourStanding: "STRENGTH" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers in this workspace. Their split stays hidden to protect small counts.",
		);
		await expect(
			canvas.getByText("Their split stays hidden to protect small counts."),
		).toBeVisible();
		await expect(canvas.getByText("28 developers")).toBeVisible();
		// No part to stand over, so no bracket: the reader's own standing is on their Practice profile.
		await expect(canvas.queryByText("You")).toBeNull();
	},
};

/** Fewer than 5 developers in total: the server sends no cell and no total, so the track stays empty. */
export const TooFewToSplit: Story = {
	args: { split: totalOnly(null), yourStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(
			canvas.getByText("Fewer than 5 developers have a standing here, so the split stays hidden."),
		).toBeVisible();
	},
};

/** The reader is not counted: the bar marks nobody. */
export const ReaderNotCounted: Story = {
	args: { yourStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers in this workspace: 6 Needs attention, 7 Mixed feedback, 7 Going well, 8 none yet.",
		);
	},
};

/** Nobody has a standing yet: an empty track that says so, and no bracket. */
export const NobodyYet: Story = {
	args: { split: split(0, 0, 0, 0), yourStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.getByText("No developer has a standing yet.")).toBeVisible();
	},
};

/** A reader with nothing to report is counted in none yet: the bracket spans that part. */
export const ReaderInNoneYet: Story = {
	args: { yourStanding: "NO_OPPORTUNITY" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			/, 8 none yet\. You are in the part with no standing yet\.$/u,
		);
	},
};

/**
 * A level's summary while the split loads. The bar's shape stands in with the heights of the loaded
 * bar, and says nothing, so no count is claimed before it is known.
 */
export const Loading: Story = {
	render: () => <LevelSplit state={{ status: "loading" }} />,
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.queryByText("You")).toBeNull();
		await expect(canvasElement.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(
			0,
		);
	},
};
