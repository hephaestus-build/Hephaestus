import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarProvider } from "./sidebar";

const meta = {
	component: SidebarMenuButton,
	parameters: { layout: "centered" },
	args: { variant: "outline", children: "Workspace overview" },
	decorators: [
		(Story) => (
			<SidebarProvider className="min-h-0 w-64">
				<SidebarMenu>
					<SidebarMenuItem>
						<Story />
					</SidebarMenuItem>
				</SidebarMenu>
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof SidebarMenuButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Outline: Story = {
	play: async ({ canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "Workspace overview" });
		await expect(getComputedStyle(button).boxShadow).toContain("0px 0px 0px 1px");
		await expect(getComputedStyle(button).boxShadow).toContain(
			getComputedStyle(button).getPropertyValue("--sidebar-border").trim(),
		);
		await userEvent.tab();
		await expect(button).toHaveFocus();
		await expect(getComputedStyle(button).boxShadow).toContain("0px 0px 0px 2px");
		await expect(getComputedStyle(button).boxShadow).toContain(
			getComputedStyle(button).getPropertyValue("--sidebar-ring").trim(),
		);
	},
};
