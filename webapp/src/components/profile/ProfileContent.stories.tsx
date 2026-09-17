import type { Meta, StoryObj } from "@storybook/react";
import { endOfISOWeek, formatISO, startOfISOWeek } from "date-fns";
import { expect, fn } from "storybook/test";

import { STORY_NOW } from "@/stories/story-clock";

import { authoredPullRequests, filledMonitor, reviewActivity } from "./fixtures";
import { ProfileContent, ZERO_ACTIVITY_STATS } from "./ProfileContent";

const now = new Date(STORY_NOW);
const defaultAfter = formatISO(startOfISOWeek(now));
const defaultBefore = formatISO(endOfISOWeek(now));

const emptyMonitor = {
	activityStats: ZERO_ACTIVITY_STATS,
	reviewActivity: [],
	authoredPullRequests: [],
	repositories: [],
	totalReviewActivityCount: 0,
	totalAuthoredPullRequestCount: 0,
};

const meta = {
	component: ProfileContent,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		isLoading: false,
		username: "johndoe",
		currUserIsDashboardUser: true,
		workspaceSlug: "aet",
		afterDate: defaultAfter,
		beforeDate: defaultBefore,
		activityMonitorFilters: { repositoryIds: [], limit: 5 },
		activityMonitorData: filledMonitor,
		onActivityMonitorFiltersChange: fn(),
		onTimeframeChange: fn(),
	},
} satisfies Meta<typeof ProfileContent>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const RepositoryFiltered: Story = {
	args: {
		activityMonitorFilters: { repositoryIds: [1], limit: 5 },
		activityMonitorData: {
			...filledMonitor,
			reviewActivity: reviewActivity.slice(0, 1),
			authoredPullRequests: authoredPullRequests.slice(0, 1),
			totalReviewActivityCount: 1,
			totalAuthoredPullRequestCount: 1,
		},
	},
};

export const Loading: Story = {
	args: { isLoading: true, activityMonitorData: undefined },
};

export const CompletelyEmpty: Story = {
	args: { activityMonitorData: emptyMonitor },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("No review activity that counts yet. Try a wider timeframe."),
		).toBeVisible();
		await expect(canvas.getByText("Pull Requests you create will appear here.")).toBeVisible();
	},
};

/** The empty sentences name the developer when the reader is someone else. */
export const EmptyForAnotherDeveloper: Story = {
	args: {
		activityMonitorData: emptyMonitor,
		currUserIsDashboardUser: false,
		username: "janedoe",
		displayName: "Jane Doe",
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Jane Doe has no review activity that counts in this timeframe."),
		).toBeVisible();
		await expect(canvas.getByText("Jane Doe doesn't have any open pull requests.")).toBeVisible();
	},
};
