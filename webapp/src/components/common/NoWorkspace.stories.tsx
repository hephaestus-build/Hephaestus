import type { Meta, StoryObj } from "@storybook/react";

import { NoWorkspace } from "./NoWorkspace";

const meta: Meta<typeof NoWorkspace> = {
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
};

export default meta;
type Story = StoryObj<typeof NoWorkspace>;

/**
 * Default presentation.
 */
export const Default: Story = {};
