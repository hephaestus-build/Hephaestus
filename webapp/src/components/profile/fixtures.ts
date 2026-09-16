import type {
	ProfileActivityMonitor,
	ProfileActivityStats,
	ProfileReviewActivity,
	PullRequestInfo,
	RepositoryInfo,
} from "@/api/types.gen";

import { daysBefore } from "@/stories/story-clock";

// One developer's activity monitor, shared by the profile stories and by the card stories that
// render one of its rows, so a title or a count reads the same in the section as on the card.

const hephaestus: RepositoryInfo = {
	id: 1,
	name: "Hephaestus",
	nameWithOwner: "ls1intum/Hephaestus",
	htmlUrl: "https://github.com/ls1intum/Hephaestus",
	hiddenFromContributions: false,
};

const artemis: RepositoryInfo = {
	id: 2,
	name: "Artemis",
	nameWithOwner: "ls1intum/Artemis",
	htmlUrl: "https://github.com/ls1intum/Artemis",
	hiddenFromContributions: false,
};

const athena: RepositoryInfo = {
	id: 3,
	name: "Athena",
	nameWithOwner: "ls1intum/Athena",
	htmlUrl: "https://github.com/ls1intum/Athena",
	hiddenFromContributions: false,
};

export const repositories: RepositoryInfo[] = [hephaestus, artemis, athena];

export const approvedReview: ProfileReviewActivity = {
	id: 1,
	state: "APPROVED",
	submittedAt: daysBefore(2),
	htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
	pullRequest: {
		id: 101,
		title: "Add new feature to dashboard",
		number: 42,
		state: "OPEN",
		isDraft: false,
		isMerged: false,
		htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
		repository: hephaestus,
	},
	score: 80,
	isDismissed: false,
	codeComments: 3,
};

export const changesRequestedReview: ProfileReviewActivity = {
	id: 2,
	state: "CHANGES_REQUESTED",
	submittedAt: daysBefore(5),
	htmlUrl: "https://github.com/ls1intum/Artemis/pull/123",
	pullRequest: {
		id: 102,
		title: "Fix authentication bugs",
		number: 123,
		state: "OPEN",
		isDraft: false,
		isMerged: false,
		htmlUrl: "https://github.com/ls1intum/Artemis/pull/123",
		repository: artemis,
	},
	score: 65,
	isDismissed: false,
	codeComments: 2,
};

export const commentedReview: ProfileReviewActivity = {
	id: 3,
	state: "COMMENTED",
	submittedAt: daysBefore(7),
	htmlUrl: "https://github.com/ls1intum/Athena/pull/15",
	pullRequest: {
		id: 103,
		title: "Update documentation",
		number: 15,
		state: "OPEN",
		isDraft: false,
		isMerged: false,
		htmlUrl: "https://github.com/ls1intum/Athena/pull/15",
		repository: athena,
	},
	score: 50,
	isDismissed: false,
	codeComments: 0,
};

export const reviewActivity: ProfileReviewActivity[] = [
	approvedReview,
	changesRequestedReview,
	commentedReview,
];

export const openPullRequest: PullRequestInfo = {
	id: 101,
	number: 42,
	title: "Add new analytics dashboard",
	state: "OPEN",
	isDraft: false,
	isMerged: false,
	commentsCount: 5,
	additions: 250,
	deletions: 30,
	htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/42",
	createdAt: daysBefore(3),
	repository: hephaestus,
	labels: [
		{ id: 1, name: "enhancement", color: "0E8A16" },
		{ id: 2, name: "frontend", color: "FBCA04" },
	],
};

export const draftPullRequest: PullRequestInfo = {
	id: 102,
	number: 87,
	title: "WIP: Refactor authentication module",
	state: "OPEN",
	isDraft: true,
	isMerged: false,
	commentsCount: 0,
	additions: 320,
	deletions: 280,
	htmlUrl: "https://github.com/ls1intum/Artemis/pull/87",
	createdAt: daysBefore(1),
	repository: artemis,
	labels: [
		{ id: 3, name: "refactoring", color: "D93F0B" },
		{ id: 4, name: "security", color: "5319E7" },
	],
};

export const authoredPullRequests: PullRequestInfo[] = [openPullRequest, draftPullRequest];

/** Not in the monitor, which lists open work: the two closed shapes a pull request card can take. */
export const mergedPullRequest: PullRequestInfo = {
	id: 103,
	number: 103,
	title: "Fix critical security vulnerability",
	state: "MERGED",
	isDraft: false,
	isMerged: true,
	commentsCount: 2,
	additions: 25,
	deletions: 5,
	htmlUrl: "https://github.com/ls1intum/Athena/pull/103",
	createdAt: daysBefore(9),
	repository: athena,
	labels: [
		{ id: 5, name: "bug", color: "B60205" },
		{ id: 6, name: "priority", color: "C2E0C6" },
	],
};

export const closedPullRequest: PullRequestInfo = {
	id: 104,
	number: 75,
	title: "Add experimental feature (closed without merge)",
	state: "CLOSED",
	isDraft: false,
	isMerged: false,
	commentsCount: 4,
	additions: 450,
	deletions: 0,
	htmlUrl: "https://github.com/ls1intum/Hephaestus/pull/75",
	createdAt: daysBefore(14),
	repository: hephaestus,
	labels: [
		{ id: 7, name: "wontfix", color: "000000" },
		{ id: 8, name: "experimental", color: "C5DEF5" },
	],
};

export const activityStats: ProfileActivityStats = {
	score: 195,
	numberOfReviewedPRs: 3,
	numberOfApprovals: 1,
	numberOfChangeRequests: 1,
	numberOfComments: 1,
	numberOfCodeComments: 5,
	numberOfUnknowns: 0,
	numberOfOwnReplies: 2,
	numberOfOpenPullRequests: 2,
	numberOfMergedPullRequests: 1,
	numberOfClosedPullRequests: 0,
	numberOfOpenedIssues: 1,
	numberOfClosedIssues: 1,
};

export const filledMonitor: ProfileActivityMonitor = {
	activityStats,
	reviewActivity,
	authoredPullRequests,
	repositories,
	totalReviewActivityCount: 8,
	totalAuthoredPullRequestCount: 6,
};
