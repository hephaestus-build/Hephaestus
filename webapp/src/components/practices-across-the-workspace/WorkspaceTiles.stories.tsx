import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import {
	ACROSS_WORKSPACE,
	ACROSS_WORKSPACE_TILES,
	NOBODY_TILES,
} from "@/stories/practices-across-the-workspace-story-data";

import { WorkspaceTiles } from "./WorkspaceTiles";

const meta = {
	component: WorkspaceTiles,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: { tiles: ACROSS_WORKSPACE_TILES, openFeedback: ACROSS_WORKSPACE.openFeedback },
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
				name: "Your value: 3. Typical range here: 1 to 4.",
			}),
		).toBeVisible();
	},
};

/** Nobody is counted: the reader's own figures stand, and each tile says why it has no range. */
export const NobodyCounted: Story = {
	args: { tiles: NOBODY_TILES, openFeedback: { yours: 0 } },
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("No developer is counted here yet.")).toHaveLength(4);
		// Each tile keeps its axis, with only the reader's own pin on it.
		await expect(canvas.getAllByRole("img", { name: /^Your value: \d+\.$/u })).toHaveLength(4);
	},
};

/** Nobody here has feedback open: one sentence in place of a band and pin at nought. */
export const NoOpenFeedbackHere: Story = {
	args: { openFeedback: { yours: 0, middle: { low: 0, high: 0 } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Most developers here have no open feedback.")).toBeVisible();
		await expect(canvas.getAllByText(/^Typical range:/u)).toHaveLength(3);
	},
};

export const Loading: Story = {
	args: { tiles: undefined, openFeedback: undefined },
	play: async ({ canvas }) => {
		// Every tile is its skeleton; the page's section carries the busy state.
		await expect(canvas.queryByText("Pieces of work reviewed")).toBeNull();
		await expect(canvas.queryByText("Open feedback")).toBeNull();
	},
};

/** The open feedback is in before the window's tiles: it shows, the other three keep their shape. */
export const WindowLoading: Story = {
	args: { tiles: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Open feedback")).toBeVisible();
		await expect(canvas.queryByText("Pieces of work reviewed")).toBeNull();
	},
};

/**
 * Another window on its way: its three tiles are drained of color, and open feedback, which reads
 * no window, is not. A visual state only, so the snapshot is the test.
 */
export const Stale: Story = { args: { stale: true } };
