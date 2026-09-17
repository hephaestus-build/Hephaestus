import type { Meta, StoryObj } from "@storybook/react";

import type { LeaderboardEntry } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow, expectTablesScrollInPlace } from "@/stories/reflow";

import { LeaderboardPage } from "./LeaderboardPage";

const reviewedPullRequestsPool = [
	{
		id: 201,
		number: 42,
		title: "Improve leaderboard semantics",
		state: "OPEN" as const,
		isDraft: false,
		isMerged: false,
		commentsCount: 2,
		additions: 35,
		deletions: 5,
		htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
		repository: {
			id: 1,
			name: "Hephaestus",
			nameWithOwner: "ls1intum/Hephaestus",
			htmlUrl: "https://github.com/ls1intum/Hephaestus",
			hiddenFromContributions: false,
		},
	},
	{
		id: 202,
		number: 314,
		title: "Refine profile activity feed",
		state: "OPEN" as const,
		isDraft: false,
		isMerged: false,
		commentsCount: 4,
		additions: 120,
		deletions: 15,
		htmlUrl: "https://github.com/ls1intum/Artemis/pull/314",
		repository: {
			id: 2,
			name: "Artemis",
			nameWithOwner: "ls1intum/Artemis",
			htmlUrl: "https://github.com/ls1intum/Artemis",
			hiddenFromContributions: false,
		},
	},
	{
		id: 203,
		number: 512,
		title: "Harden review aggregation",
		state: "MERGED" as const,
		isDraft: false,
		isMerged: true,
		commentsCount: 6,
		additions: 80,
		deletions: 22,
		htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/512",
		repository: {
			id: 1,
			name: "Hephaestus",
			nameWithOwner: "ls1intum/Hephaestus",
			htmlUrl: "https://github.com/ls1intum/Hephaestus",
			hiddenFromContributions: false,
		},
	},
];

const mockLeaderboardEntries: LeaderboardEntry[] = [
	{
		rank: 1,
		score: 100,
		user: {
			id: 0,
			login: "GODrums",
			avatarUrl: "https://avatars.githubusercontent.com/u/21990230?v=4",
			name: "Armin Stanitzok",
			htmlUrl: "https://github.com/GODrums",
			leaguePoints: 1250,
		},
		reviewedPullRequests: reviewedPullRequestsPool,
		numberOfReviewedPRs: 18,
		numberOfApprovals: 8,
		numberOfChangeRequests: 7,
		numberOfComments: 2,
		numberOfUnknowns: 1,
		numberOfCodeComments: 5,
		numberOfOwnReplies: 3,
		numberOfOpenPullRequests: 2,
		numberOfMergedPullRequests: 6,
		numberOfClosedPullRequests: 1,
		numberOfOpenedIssues: 4,
		numberOfClosedIssues: 2,
	},
	{
		rank: 2,
		score: 90,
		user: {
			id: 1,
			login: "FelixTJDietrich",
			avatarUrl: "https://avatars.githubusercontent.com/u/5898705?v=4",
			name: "Felix T.J. Dietrich",
			htmlUrl: "https://github.com/FelixTJDietrich",
			leaguePoints: 750,
		},
		reviewedPullRequests: reviewedPullRequestsPool.slice(0, 2),
		numberOfReviewedPRs: 8,
		numberOfApprovals: 1,
		numberOfChangeRequests: 5,
		numberOfComments: 2,
		numberOfUnknowns: 0,
		numberOfCodeComments: 21,
		numberOfOwnReplies: 5,
		numberOfOpenPullRequests: 1,
		numberOfMergedPullRequests: 3,
		numberOfClosedPullRequests: 0,
		numberOfOpenedIssues: 2,
		numberOfClosedIssues: 1,
	},
	{
		rank: 3,
		score: 50,
		user: {
			id: 2,
			login: "krusche",
			avatarUrl: "https://avatars.githubusercontent.com/u/744067?v=4",
			name: "Stephan Krusche",
			htmlUrl: "https://github.com/krusche",
			leaguePoints: 2500,
		},
		reviewedPullRequests: reviewedPullRequestsPool.slice(0, 1),
		numberOfReviewedPRs: 5,
		numberOfApprovals: 3,
		numberOfChangeRequests: 1,
		numberOfComments: 0,
		numberOfUnknowns: 0,
		numberOfCodeComments: 2,
		numberOfOwnReplies: 0,
		numberOfOpenPullRequests: 0,
		numberOfMergedPullRequests: 2,
		numberOfClosedPullRequests: 0,
		numberOfOpenedIssues: 1,
		numberOfClosedIssues: 1,
	},
	{
		rank: 4,
		score: 20,
		user: {
			id: 3,
			login: "shadcn",
			avatarUrl: "https://avatars.githubusercontent.com/u/124599?v=4",
			name: "shadcn",
			htmlUrl: "https://github.com/shadcn",
			leaguePoints: 350,
		},
		reviewedPullRequests: reviewedPullRequestsPool.slice(1, 2),
		numberOfReviewedPRs: 3,
		numberOfApprovals: 0,
		numberOfChangeRequests: 1,
		numberOfComments: 1,
		numberOfUnknowns: 1,
		numberOfCodeComments: 5,
		numberOfOwnReplies: 2,
		numberOfOpenPullRequests: 1,
		numberOfMergedPullRequests: 0,
		numberOfClosedPullRequests: 2,
		numberOfOpenedIssues: 0,
		numberOfClosedIssues: 3,
	},
];

const mockTeamOptions = [
	{ value: "Frontend", label: "Frontend" },
	{ value: "Backend", label: "Backend" },
	{ value: "DevOps", label: "DevOps" },
	{ value: "QA", label: "QA" },
	{ value: "Design", label: "Design" },
];

const meta = {
	component: LeaderboardPage,
	parameters: {
		layout: "fullscreen",
	},
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: { providerType: "GITHUB" },
} satisfies Meta<typeof LeaderboardPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	args: {
		leaderboard: mockLeaderboardEntries,
		isLoading: false,
		teamOptions: mockTeamOptions,
		selectedTeam: "all",
		selectedSort: "SCORE",
		selectedMode: "INDIVIDUAL",
	},
};

export const WithCurrentUser: Story = {
	args: {
		leaderboard: mockLeaderboardEntries,
		isLoading: false,
		teamOptions: mockTeamOptions,
		selectedTeam: "all",
		selectedSort: "SCORE",
		selectedMode: "INDIVIDUAL",
		currentUser: {
			id: 1,
			login: "FelixTJDietrich",
			name: "Felix T.J. Dietrich",
			avatarUrl: "https://avatars.githubusercontent.com/u/5898705?v=4",
			htmlUrl: "https://github.com/FelixTJDietrich",
			leaguePoints: 750,
		},
		currentUserEntry: mockLeaderboardEntries[1],
		leaguePoints: 750,
		leaderboardEnd: "Monday, May 18, 2025",
	},
};

export const Loading: Story = {
	args: {
		isLoading: true,
		teamOptions: mockTeamOptions,
		selectedTeam: "all",
		selectedSort: "SCORE",
		selectedMode: "INDIVIDUAL",
	},
};

export const EmptyLeaderboard: Story = {
	args: {
		leaderboard: [],
		isLoading: false,
		teamOptions: mockTeamOptions,
		selectedTeam: "all",
		selectedSort: "SCORE",
		selectedMode: "INDIVIDUAL",
	},
};

export const MobileReflow: Story = {
	...Default,
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPageOverflow();
		await expectTablesScrollInPlace();
	},
};
