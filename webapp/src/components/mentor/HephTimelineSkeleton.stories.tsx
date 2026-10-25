import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { HephTimelineSkeleton } from "./HephTimelineSkeleton";

const meta = {
	component: HephTimelineSkeleton,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
} satisfies Meta<typeof HephTimelineSkeleton>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Past the one second it waits for. */
const SHOWN = { timeout: 3000 };

/**
 * Nothing for the first second, so a read from the cache shows no flash. Then stand-in turns,
 * hidden from a screen reader, which hears that the conversation loads.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Loading the conversation…")).toBeNull();
		await expect(
			await canvas.findByText("Loading the conversation…", undefined, SHOWN),
		).toBeVisible();
	},
};

/** A thread loading in its panel: fewer stand-in turns, and the screen reader hears it is the thread. */
export const Thread: Story = {
	args: { rows: 2, label: "Loading the thread…" },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Loading the thread…", undefined, SHOWN)).toBeVisible();
		await expect(canvas.queryByText("Loading the conversation…")).toBeNull();
	},
};

/** In a 320 px window every stand-in line stays inside the text column beside its avatar. */
export const Reflow320: Story = {
	parameters: { viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas, canvasElement }) => {
		await canvas.findByText("Loading the conversation…", undefined, SHOWN);
		const columns = [...canvasElement.querySelectorAll<HTMLElement>("[data-slot=message-content]")];
		await expect(columns).toHaveLength(4);
		for (const column of columns) {
			const { right } = column.getBoundingClientRect();
			for (const line of column.children) {
				await expect(line.getBoundingClientRect().right).toBeLessThanOrEqual(right + 1);
			}
		}
	},
};
