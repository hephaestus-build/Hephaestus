import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { NOBODY, SMALL_PARTS, threeWay } from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceSplitBar } from "./WorkspaceSplitBar";

/**
 * One bar for a practice group and for a practice. The text alternative names every count, and
 * each part carries its count and icon, so color carries nothing a reader can miss.
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
		await expect(canvas.getByText("28 developers")).toBeVisible();
	},
};

/** The parts and the You marker carry color meaning, so dark mode is asserted, not assumed. */
export const Dark: Story = { globals: { theme: "dark" } };

/** Parts of one and two draw like any other part. A part of nobody draws nothing; the text alternative still names it. */
export const SmallParts: Story = {
	args: { split: SMALL_PARTS, yourStanding: "STRENGTH" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img")).toHaveAccessibleName(
			"28 developers with a current standing in this workspace: 1 Needs attention, 0 Mixed feedback, 2 Going well, 25 none yet. The You marker is on Going well.",
		);
		await expect(canvas.getByTitle("Needs attention: 1")).toBeVisible();
		await expect(canvas.queryByTitle("Mixed feedback: 0")).toBeNull();
		await expect(canvas.getByText("You")).toBeVisible();
	},
};

/** Nobody has a standing yet: an empty track that says so, and no marker. */
export const NobodyYet: Story = {
	args: { split: NOBODY, yourStanding: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img")).toBeNull();
		await expect(canvas.getByText("No developer has a standing yet.")).toBeVisible();
		await expect(canvas.queryByText("You")).toBeNull();
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
