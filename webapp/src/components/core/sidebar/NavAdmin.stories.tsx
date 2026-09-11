import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { SidebarProvider } from "@/components/ui/sidebar";

import { NavAdmin } from "./NavAdmin";

const meta = {
	component: NavAdmin,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "aet",
		integrationKinds: ["GITHUB", "SLACK", "OUTLINE"],
	},
	decorators: [
		(Story) => (
			<SidebarProvider className="min-h-0 w-[16rem] border border-border rounded-lg p-2 bg-sidebar">
				<Story />
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof NavAdmin>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const GitLabWorkspace: Story = {
	args: {
		scmProviderType: "GITLAB",
		integrationKinds: ["GITLAB", "SLACK", "OUTLINE"],
	},
};

export const ExpandedNavigation: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Practices" }));
		await userEvent.click(canvas.getByRole("button", { name: "Integrations" }));

		canvas.getByRole("link", { name: "Practice setup" });
		canvas.getByRole("link", { name: "Practice reviews" });
		canvas.getByRole("link", { name: "Overview" });
		canvas.getByRole("link", { name: "GitHub" });
	},
};

export const OptionalIntegrationsUnavailable: Story = {
	args: {
		integrationKinds: ["GITHUB"],
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Integrations" }));

		canvas.getByRole("link", { name: "Overview" });
		canvas.getByRole("link", { name: "GitHub" });
		await expect(canvas.queryByRole("link", { name: "Slack" })).not.toBeInTheDocument();
		await expect(canvas.queryByRole("link", { name: "Outline" })).not.toBeInTheDocument();
	},
};
