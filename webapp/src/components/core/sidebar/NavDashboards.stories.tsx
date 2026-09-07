import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { SidebarProvider } from "@/components/ui/sidebar";

import { NavDashboards } from "./NavDashboards";

const meta = {
	component: NavDashboards,
	parameters: {
		layout: "centered",
	},
	tags: ["autodocs"],
	args: {
		username: "johnDoe",
		workspaceSlug: "aet",
		achievementsEnabled: true,
		leaderboardEnabled: true,
		practicesEnabled: true,
	},
	decorators: [
		(Story) => (
			<SidebarProvider className="min-h-0 w-[16rem] border border-border rounded-lg p-2 bg-sidebar">
				<Story />
			</SidebarProvider>
		),
	],
} satisfies Meta<typeof NavDashboards>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const DifferentUser: Story = {
	args: {
		username: "janeDoe",
		workspaceSlug: "aet",
	},
};

export const AllFeaturesDisabled: Story = {
	args: {
		achievementsEnabled: false,
		leaderboardEnabled: false,
		practicesEnabled: false,
	},
	play: async ({ canvas }) => {
		// Profile and Teams are not workspace capabilities, so they stay whatever else is off.
		await expect(await canvas.findByRole("link", { name: "Profile" })).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Teams" })).toBeVisible();
		for (const gated of ["Achievements", "Leaderboard", "Review activity"])
			await expect(canvas.queryByRole("link", { name: gated })).toBeNull();
	},
};

export const PracticeReviewsOff: Story = {
	args: { practicesEnabled: false },
	play: async ({ canvas }) => {
		// A workspace that does not review practices has no review activity to show, so the entry is
		// gone rather than leading to a page that could only explain itself.
		await expect(await canvas.findByRole("link", { name: "Leaderboard" })).toBeVisible();
		await expect(canvas.queryByRole("link", { name: "Review activity" })).toBeNull();
	},
};
