import type { Meta, StoryObj } from "@storybook/react";

import { SidebarProvider } from "@/components/ui/sidebar";

import { NavFooter } from "./NavFooter";

const meta = {
	component: NavFooter,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<SidebarProvider className="min-h-0 w-[16rem] border border-border rounded-lg p-2 bg-sidebar">
				<Story />
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof NavFooter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WorkspacePreferences: Story = { args: { workspaceSlug: "engineering" } };
