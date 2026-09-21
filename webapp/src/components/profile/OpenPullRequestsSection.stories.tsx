import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { authoredPullRequests } from "./fixtures";
import { OpenPullRequestsSection } from "./OpenPullRequestsSection";

const meta = {
	component: OpenPullRequestsSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		pullRequests: authoredPullRequests,
		isLoading: false,
		emptyMessage: "Pull Requests you create will appear here.",
		canViewAll: true,
		onViewAll: fn(),
	},
} satisfies Meta<typeof OpenPullRequestsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("heading", { name: "Open pull requests" })).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "View all pull requests" }));
		await expect(args.onViewAll).toHaveBeenCalledOnce();
	},
};

export const GitLab: Story = {
	args: { providerType: "GITLAB" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Open merge requests" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "View all merge requests" })).toBeVisible();
	},
};

export const Loading: Story = {
	args: { isLoading: true, canViewAll: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("heading", { name: "Open pull requests" }).closest("[aria-busy]"),
		).toHaveAttribute("aria-busy", "true");
		// The fixture rows are still in `args`: loading takes precedence over data already given.
		await expect(canvas.queryByText("Add new analytics dashboard")).toBeNull();
	},
};

export const Empty: Story = {
	args: { pullRequests: [], canViewAll: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Pull Requests you create will appear here.")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "View all pull requests" })).toBeNull();
	},
};
