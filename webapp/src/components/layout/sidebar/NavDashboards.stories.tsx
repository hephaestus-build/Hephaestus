import type { Meta, StoryObj } from "@storybook/react";
import { expect, screen, userEvent, within } from "storybook/test";

import { SidebarProvider } from "@/components/ui/sidebar";

import { NavDashboards } from "./NavDashboards";
import { withSidebarFrame } from "./sidebar-story-frame";

const meta = {
	component: NavDashboards,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		workspaceSlug: "aet",
		practicesEnabled: true,
	},
	decorators: [withSidebarFrame],
} satisfies Meta<typeof NavDashboards>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The practice pages fold under Practice profile, closed until the reader opens them. */
export const Default: Story = {
	play: async ({ canvas }) => {
		// The Practice profile is the workspace home, so it leads; Activity follows it.
		await expect(canvas.getByRole("button", { name: "Practice profile" })).toHaveAttribute(
			"aria-expanded",
			"false",
		);
		const links = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(links).toEqual(["Activity", "Workspace activity", "Teams"]);
	},
};

/** Opened, the reader's own practices lead and the workspace's view of the same groups follows. */
export const PracticePagesOpen: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Practice profile" }));
		const pages = within(canvas.getByRole("list", { name: "Practice profile" }));
		await expect(pages.getAllByRole("link").map((link) => link.textContent)).toEqual([
			"Your profile",
			"Across the workspace",
		]);
	},
};

/**
 * The sidebar folded to icons: nothing can unfold, and the profile links nowhere else, so the
 * section's icon offers both pages as a menu beside it.
 */
export const PracticePagesFolded: Story = {
	decorators: [
		(Story) => (
			<SidebarProvider defaultOpen={false} className="min-h-0">
				<Story />
			</SidebarProvider>
		),
	],
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Practice profile" }));
		const menu = within(await screen.findByRole("menu"));
		await expect(menu.getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
			"Your profile",
			"Across the workspace",
		]);
		await expect(menu.getByRole("menuitem", { name: "Across the workspace" })).toHaveAttribute(
			"href",
			"/w/aet/practices-across-the-workspace",
		);
	},
};

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		// Activity is always on, and leads — it is the home of a workspace that does not review
		// practices. The practice pages are gone rather than leading to a page that could only explain
		// itself.
		const links = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(links).toEqual(["Activity", "Workspace activity", "Teams"]);
	},
};
