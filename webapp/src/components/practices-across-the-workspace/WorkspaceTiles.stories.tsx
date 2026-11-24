import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	NOBODY_TILES,
	ONE_DEVELOPER_TILES,
	TILES_BY_WINDOW,
} from "@/stories/practices-across-the-workspace-story-data";

import { NO_TYPICAL_RANGE } from "./across-workspace-copy";
import { WorkspaceTiles } from "./WorkspaceTiles";

/**
 * The reader's figures beside the workspace's middle half: three over the chosen window, and the
 * feedback open now, which reads no window.
 */
const meta = {
	component: WorkspaceTiles,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { tiles: TILES_BY_WINDOW.DAYS_30, openFeedback: ACROSS_WORKSPACE.openFeedback },
} satisfies Meta<typeof WorkspaceTiles>;

export default meta;
type Story = StoryObj<typeof meta>;

export const LastThirtyDays: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("img", { name: "Your value: 11. Typical range here: 6 to 14." }),
		).toBeVisible();
		await expect(canvas.getByText("in the last 30 days")).toBeVisible();
		await expect(canvas.getByText("pieces open now")).toBeVisible();
	},
};

/** Another window moves the three windowed tiles, never the open feedback. */
export const AllTime: Story = {
	args: { tiles: TILES_BY_WINDOW.ALL_TIME },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("img", { name: "Your value: 64. Typical range here: 41 to 80." }),
		).toBeVisible();
		await expect(canvas.getByText("so far")).toBeVisible();
		// Open feedback has your figure only: whether another person's feedback is open follows settings an
		// admin can change for one person, so the server sends no range for it.
		await expect(canvas.getByRole("img", { name: "Your value: 3." })).toBeVisible();
	},
};

/** One developer counted: fewer than 5, so no tile has a typical range. */
export const OneDeveloper: Story = {
	args: { tiles: ONE_DEVELOPER_TILES },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("img", { name: "Your value: 4." })).toBeVisible();
	},
};

/** Nobody has a standing in the window: the windowed tiles say so, with the axis still there. */
export const NobodyYet: Story = {
	args: { tiles: NOBODY_TILES },
	play: async ({ canvas }) => {
		// The three windowed tiles, and open feedback, which never has a range.
		await expect(canvas.getAllByText(NO_TYPICAL_RANGE)).toHaveLength(4);
		await expect(canvas.getAllByRole("img", { name: "Your value: 0." })).toHaveLength(3);
	},
};

/** The window's tiles on their way: those three draw their loading shape, the open feedback stays. */
export const WindowLoading: Story = {
	args: { tiles: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Open feedback")).toBeVisible();
		await expect(canvas.queryByText("Pieces of work reviewed")).toBeNull();
	},
};

/** Another window on its way: the previous window's figures stay, drained, until the new ones arrive. */
export const Stale: Story = { args: { stale: true } };
