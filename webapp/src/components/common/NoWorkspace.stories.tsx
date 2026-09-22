import type { Meta, StoryObj } from "@storybook/react";

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
export const Default: Story = {};
