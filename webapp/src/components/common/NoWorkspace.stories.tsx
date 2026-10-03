import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { NoWorkspace } from "./NoWorkspace";

const meta = {
	component: NoWorkspace,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component: "Empty-state screen shown when a user has no workspace membership.",
			},
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof NoWorkspace>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Default presentation.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		// The page this fills has nothing else to name it, so its title is the page's heading.
		await expect(canvas.getByRole("heading", { level: 1, name: "No workspace" })).toBeVisible();
	},
};
