import type { Meta, StoryObj } from "@storybook/react";
import { expect, userEvent, within } from "storybook/test";

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

/** One press reaches the Practice profile from any page; the chevron beside it discloses the workspace view. */
export const Default: Story = {
	play: async ({ canvas }) => {
		// The Practice profile is the workspace home, so it leads; Activity follows it.
		const links = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(links).toEqual(["Practice profile", "Activity", "Workspace activity", "Teams"]);
		await expect(
			canvas.getByRole("button", { name: "Pages under Practice profile" }),
		).toHaveAttribute("aria-expanded", "false");
	},
};

/** Opened, the workspace's view of the same groups sits under the profile. */
export const PracticePagesOpen: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Pages under Practice profile" }));
		const pages = within(canvas.getByRole("list", { name: "Practice profile" }));
		await expect(pages.getByRole("link", { name: "Across the workspace" })).toHaveAttribute(
			"href",
			"/w/aet/practices-across-the-workspace",
		);
	},
};

/** Folded to icons, nothing can unfold, so each practice page is one icon and one press. */
export const PracticePagesFolded: Story = {
	decorators: [
		(Story) => (
			<SidebarProvider defaultOpen={false} className="min-h-0">
				<Story />
			</SidebarProvider>
		),
	],
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Practice profile" })).toHaveAttribute(
			"href",
			"/w/aet/practice-profile",
		);
		await expect(canvas.getByRole("link", { name: "Across the workspace" })).toHaveAttribute(
			"href",
			"/w/aet/practices-across-the-workspace",
		);
	},
};

/** On the workspace view, folded to icons: its own icon is the one active entry, not the profile's. */
export const AcrossTheWorkspaceActiveFolded: Story = {
	parameters: { router: { initialUrl: "/w/aet/practices-across-the-workspace" } },
	decorators: [
		(Story) => (
			<SidebarProvider defaultOpen={false} className="min-h-0">
				<Story />
			</SidebarProvider>
		),
	],
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Across the workspace" })).toHaveAttribute(
			"data-active",
		);
		await expect(canvas.getByRole("link", { name: "Practice profile" })).not.toHaveAttribute(
			"data-active",
		);
	},
};

/** On the workspace view with the sidebar open, the view's sub entry is active and its parent is not. */
export const AcrossTheWorkspaceActive: Story = {
	parameters: { router: { initialUrl: "/w/aet/practices-across-the-workspace" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Across the workspace" })).toHaveAttribute(
			"data-active",
		);
		await expect(canvas.getByRole("link", { name: "Practice profile" })).not.toHaveAttribute(
			"data-active",
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
