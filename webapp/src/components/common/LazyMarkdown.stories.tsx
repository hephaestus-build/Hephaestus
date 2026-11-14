import type { Meta, StoryObj } from "@storybook/react-vite";
import { lazy } from "react";
import { expect, waitFor } from "storybook/test";

import { pending } from "@/test/async";

import { LazyMarkdown } from "./UntrustedMarkdown";

const SOURCE = "**Name** the branch after the issue.";

/** A renderer whose code never arrives, so the boundary stays in its loading state. */
const NeverLoads = lazy(async () => pending<{ default: () => null }>());

/** A renderer whose code is gone, as after a deploy removed the chunk an open tab asks for. */
const Gone = lazy(async () => {
	throw new TypeError("Failed to fetch dynamically imported module: /assets/renderer.js");
});

const meta = {
	component: LazyMarkdown,
	tags: ["autodocs"],
	args: { source: SOURCE, children: <NeverLoads /> },
} satisfies Meta<typeof LazyMarkdown>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing for the first second, so a fast load does not flash a placeholder, then a skeleton. */
export const Loading: Story = {
	play: async ({ canvasElement }) => {
		await expect(canvasElement.querySelector("[aria-busy]")).toBeNull();
		await waitFor(
			async () => expect(canvasElement.querySelector("[aria-busy='true']")).not.toBeNull(),
			{
				timeout: 2000,
			},
		);
	},
};

/** The renderer cannot load: the source stays readable as plain text. */
export const Unavailable: Story = {
	args: { children: <Gone /> },
	play: async ({ canvas }) => {
		await expect(await canvas.findByText(SOURCE)).toBeVisible();
	},
};
