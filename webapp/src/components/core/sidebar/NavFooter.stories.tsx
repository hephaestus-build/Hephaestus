import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { Sidebar, SidebarFooter, SidebarProvider } from "@/components/ui/sidebar";

import { NavFooter } from "./NavFooter";

/**
 * The footer is where the member's own AI choice lives, one click from any page. In the collapsed
 * rail only the icons remain. No two footer entries may share a glyph, and Collapsed asserts it.
 */
const meta = {
	component: NavFooter,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	decorators: [
		// A story decorator would render inside this one, nesting a second provider under an open
		// sidebar, so the switch to the collapsed rail has to happen here.
		(Story, { parameters }) =>
			parameters.sidebarCollapsed === true ? (
				<SidebarProvider defaultOpen={false}>
					<Sidebar collapsible="icon">
						<SidebarFooter>
							<Story />
						</SidebarFooter>
					</Sidebar>
				</SidebarProvider>
			) : (
				<SidebarProvider className="min-h-0 w-[16rem] border border-border rounded-lg p-2 bg-sidebar">
					<Story />
				</SidebarProvider>
			),
	],
} satisfies Meta<typeof NavFooter>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const WithWorkspace: Story = {
	args: { workspaceSlug: "engineering" },
	play: async ({ canvas }) => {
		// `returnTo` is whatever URL the global router decorator happens to sit on, so only the
		// path before it is a literal.
		await expect(canvas.getByRole("link", { name: "Your AI choice" })).toHaveAttribute(
			"href",
			expect.stringMatching(/^\/w\/engineering\/onboarding\?returnTo=/),
		);
	},
};

export const Collapsed: Story = {
	args: { isAppAdmin: true, workspaceSlug: "engineering" },
	parameters: { layout: "fullscreen", sidebarCollapsed: true },
	play: async ({ canvas }) => {
		const aiChoice = canvas.getByRole("link", { name: "Your AI choice" });
		const instanceAdmin = canvas.getByRole("link", { name: "Instance admin" });
		// Lucide names each glyph on its svg; the rail shows nothing else, so the two must differ.
		await expect(aiChoice.querySelector("svg")).toHaveClass("lucide-sliders-horizontal");
		await expect(instanceAdmin.querySelector("svg")).toHaveClass("lucide-shield-check");
	},
};
