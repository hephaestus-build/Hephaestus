import type { Meta, StoryObj } from "@storybook/react";

import { withProvider } from "@/stories/decorators";
import { STORY_NOW } from "@/stories/story-clock";

import { ActivityBadges } from "./ActivityBadges";
import type { ReviewedPullRequest } from "./ReviewsPopover";

const basePullRequests: ReviewedPullRequest[] = [
	{
		id: 1,
		number: 42,
		title: "Improve docs",
		htmlUrl: "https://github.com/example/repo/pull/42",
		additions: 10,
		deletions: 2,
		commentsCount: 0,
		isDraft: false,
		isMerged: false,
		createdAt: new Date(STORY_NOW),
		state: "OPEN",
		labels: [],
		repository: {
			id: 100,
			name: "repo",
			nameWithOwner: "example/repo",
			htmlUrl: "https://github.com/example/repo",
			hiddenFromContributions: false,
		},
	},
	{
		id: 2,
		number: 7,
		title: "Fix tests",
		htmlUrl: "https://github.com/example/repo/pull/7",
		additions: 5,
		deletions: 1,
		commentsCount: 0,
		isDraft: false,
		isMerged: false,
		createdAt: new Date(STORY_NOW),
		state: "OPEN",
		labels: [],
		repository: {
			id: 101,
			name: "repo",
			nameWithOwner: "example/repo",
			htmlUrl: "https://github.com/example/repo",
			hiddenFromContributions: false,
		},
	},
];

/**
 * Activity badges summarize review work by showing reviewed pull requests, approvals, change requests, comments, and code comments at a glance.
 */
const meta = {
	component: ActivityBadges,
	tags: ["autodocs"],
	args: {
		providerType: "GITHUB",
		reviewedPullRequests: basePullRequests,
		approvals: 3,
		changeRequests: 1,
		comments: 5,
		codeComments: 2,
		isLoading: false,
	},
} satisfies Meta<typeof ActivityBadges>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Balanced mix of approvals, change requests, comments, and code comments.
 */
export const Default: Story = {};

/**
 * Highlights reviewed pull requests without other activity types.
 */
export const ReviewsOnly: Story = {
	args: {
		reviewedPullRequests: basePullRequests,
		approvals: 0,
		changeRequests: 0,
		comments: 0,
		codeComments: 0,
		highlightReviews: true,
	},
};

/**
 * Minimal activity with a single change request and no reviewed pull requests.
 */
export const Minimal: Story = {
	args: {
		reviewedPullRequests: [],
		approvals: 0,
		changeRequests: 1,
		comments: 0,
		codeComments: 0,
	},
};

/**
 * Skeleton placeholders shown while review stats are still loading.
 */
export const Loading: Story = {
	args: {
		isLoading: true,
		reviewedPullRequests: [],
		approvals: 0,
		changeRequests: 0,
		comments: 0,
		codeComments: 0,
	},
};

// --- Alternate provider variant ---

/**
 * Alternate provider with merge request icon and provider-native colors.
 */
export const MergeRequestProvider: Story = {
	decorators: [withProvider("GITLAB")],
	args: {
		providerType: "GITLAB",
	},
};

/**
 * Other activity without score-earning reviews.
 */
export const OtherActivity: Story = {
	args: {
		reviewedPullRequests: [],
		approvals: 0,
		changeRequests: 0,
		comments: 0,
		codeComments: 0,
		ownReplies: 2,
		openPullRequests: 1,
		mergedPullRequests: 3,
		closedPullRequests: 1,
		openedIssues: 2,
		closedIssues: 1,
	},
};

/**
 * Realistic mixed state with reviewed PRs first and authored activity after it.
 */
export const MixedActivity: Story = {
	args: {
		reviewedPullRequests: basePullRequests,
		approvals: 2,
		changeRequests: 1,
		comments: 3,
		codeComments: 4,
		ownReplies: 2,
		openPullRequests: 1,
		mergedPullRequests: 2,
		closedPullRequests: 1,
		openedIssues: 1,
		closedIssues: 1,
	},
};
