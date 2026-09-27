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
		// Activity is the workspace home, so it leads; the practice pages follow it.
		const links = canvas.getAllByRole("link").map((link) => link.textContent);
		await expect(links).toEqual([
			"Activity",
			"Practice profile",
			"Workspace activity",
			"Review activity",
			"Teams",
		]);
	},
};

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		// Activity is always on; a workspace that does not review practices has no practice pages to
		// show, so those entries are gone rather than leading to a page that could only explain itself.
		await expect(await canvas.findByRole("link", { name: "Activity" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Workspace activity" })).toBeVisible();
		for (const gated of ["Practice profile", "Review activity"]) {
			await expect(canvas.queryByRole("link", { name: gated })).toBeNull();
		}
	},
};
