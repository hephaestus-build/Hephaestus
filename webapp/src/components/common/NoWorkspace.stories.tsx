import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { NoWorkspace } from "./NoWorkspace";

const meta = {
	component: NoWorkspace,
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Empty-state screen shown when a user has no workspace membership. It offers **Create workspace** only where the creation policy lets this person create one.",
			},
		},
	},
	tags: ["autodocs"],
	args: { creation: "allowed" },
} satisfies Meta<typeof NoWorkspace>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Anyone may create a workspace here, or the reader is an instance admin.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		// The page this fills has nothing else to name it, so its title is the page's heading.
		await expect(canvas.getByRole("heading", { level: 1, name: "No workspace" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Create workspace" })).toHaveAttribute(
			"href",
			"/workspaces/new",
		);
	},
};

/**
 * Only an instance admin may create a workspace, and the reader is not one: the page names who can help.
 */
export const InstanceAdminOnly: Story = {
	args: { creation: "instance-admin-only" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(/Ask an instance admin to create a workspace or add you\./u),
		).toBeVisible();
		await expect(canvas.queryByRole("link", { name: "Create workspace" })).toBeNull();
	},
};

/**
 * The creation policy is still loading: neither step is offered yet.
 */
export const PolicyLoading: Story = {
	args: { creation: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("You are not a member of any workspace yet.")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: "Create workspace" })).toBeNull();
	},
};
