import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { NavWorkspace } from "./NavWorkspace";
import { withSidebarFrame } from "./sidebar-story-frame";

const meta = {
	component: NavWorkspace,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { workspaceSlug: "aet", practicesEnabled: true },
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavWorkspace>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Workspace" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Teams" })).toHaveAttribute(
			"href",
			"/w/aet/teams",
		);
	},
};
export const PracticesOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("link", { name: "Practices across the workspace" }),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("link", { name: "Workspace activity" })).toBeVisible();
	},
};
