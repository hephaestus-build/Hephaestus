import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

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

export const Default: Story = {
	play: async ({ canvas }) => {
		// The Practice profile is the workspace home, so it leads; Activity follows it.
		const links = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(links).toEqual([
			"Practice profile",
			"Activity",
			"Workspace activity",
			"Review activity",
			"Teams",
		]);
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
