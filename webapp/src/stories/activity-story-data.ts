import type {
	ActivityItem,
	ActivitySummary,
	MemberActivity,
	OpenWork,
	RepositoryInfo,
	UserInfo,
	WorkItem,
	WorkItemList,
} from "@/api/types.gen";

import { addMinutes, startOfDay, subDays } from "date-fns";

import { daysBefore, hoursBefore, minutesBefore, STORY_NOW } from "./story-clock";

/** No avatar address, so a story never waits on the network and shows the initials. */
function user(id: number, login: string, name: string): UserInfo {
	return { id, login, name, avatarUrl: "", htmlUrl: `https://github.com/${login}` };
}

export const ada = user(1, "ada", "Ada Lovelace");
export const bob = user(2, "bob", "Bob Brenner");
export const chen = user(3, "chen", "Chen Wei");

export const hephaestusRepo: RepositoryInfo = {
	id: 10,
	name: "Hephaestus",
	nameWithOwner: "hephaestus-build/Hephaestus",
	htmlUrl: "https://github.com/hephaestus-build/Hephaestus",
	hiddenFromContributions: false,
};

export const artemisRepo: RepositoryInfo = {
	id: 11,
	name: "Artemis",
	nameWithOwner: "hephaestus-build/Artemis",
	htmlUrl: "https://github.com/hephaestus-build/Artemis",
	hiddenFromContributions: false,
};

function pullRequest(
	id: number,
	number: number,
	title: string,
	overrides: Partial<WorkItem> = {},
): WorkItem {
	return {
		id,
		type: "PULL_REQUEST",
		number,
		title,
		state: "OPEN",
		isDraft: false,
		htmlUrl: `https://github.com/hephaestus-build/Hephaestus/pull/${number}`,
		repository: hephaestusRepo,
		author: ada,
		createdAt: daysBefore(3),
		updatedAt: hoursBefore(2),
		...overrides,
	};
}

export const reviewRequest = pullRequest(100, 2310, "Show open work on the Activity page", {
	author: bob,
	updatedAt: minutesBefore(40),
	checks: "FAILURE",
});

export const approvedPullRequest = pullRequest(
	101,
	2298,
	"Cache the workspace list between loads",
	{
		reviewDecision: "APPROVED",
	},
);

export const changesRequestedPullRequest = pullRequest(
	102,
	2301,
	"Retry webhook delivery after a gateway timeout and keep the first failure in the log",
	{ reviewDecision: "CHANGES_REQUESTED", updatedAt: daysBefore(1) },
);

export const draftPullRequest = pullRequest(103, 2315, "Workspace activity team filter", {
	isDraft: true,
	repository: artemisRepo,
	htmlUrl: "https://github.com/hephaestus-build/Artemis/pull/2315",
});

export const assignedIssue: WorkItem = {
	id: 104,
	type: "ISSUE",
	number: 1374,
	title: "Document the backup restore drill",
	state: "OPEN",
	isDraft: false,
	htmlUrl: "https://github.com/hephaestus-build/Hephaestus/issues/1374",
	repository: hephaestusRepo,
	author: chen,
	createdAt: daysBefore(20),
	updatedAt: daysBefore(2),
};

/** Merged, so it is in the timeline and never in the open work. */
export const mergedPullRequest = pullRequest(
	105,
	2294,
	"Show the review decision on open pull requests",
	{
		state: "MERGED",
	},
);

function list(content: WorkItem[], hasMore = false): WorkItemList {
	return { content, hasMore };
}

export const OPEN_WORK: OpenWork = {
	reviewRequests: list([reviewRequest]),
	pullRequests: list([changesRequestedPullRequest, approvedPullRequest, draftPullRequest]),
	issues: list([assignedIssue]),
};

export const NOTHING_OPEN: OpenWork = {
	reviewRequests: list([]),
	pullRequests: list([]),
	issues: list([]),
};

/** A merge request as GitLab numbers it, in a project rather than a repository. */
export const gitLabMergeRequest = pullRequest(
	300,
	42,
	"Split the pipeline into build and test stages",
	{
		author: bob,
		reviewDecision: "APPROVED",
		checks: "FAILURE",
		htmlUrl: "https://gitlab.example.com/aet/pipelines/-/merge_requests/42",
		repository: {
			id: 12,
			name: "pipelines",
			nameWithOwner: "aet/pipelines",
			htmlUrl: "https://gitlab.example.com/aet/pipelines",
			hiddenFromContributions: false,
		},
	},
);

/** More open pull requests than a list shows before it asks. */
export const MANY_OPEN_PULL_REQUESTS: OpenWork = {
	...NOTHING_OPEN,
	pullRequests: list(
		Array.from({ length: 8 }, (_, index) =>
			pullRequest(
				200 + index,
				2400 + index,
				`Follow-up ${index + 1}: tidy the activity read model`,
			),
		),
		true,
	),
};

export const SUMMARY: ActivitySummary = {
	pullRequestsOpened: 4,
	pullRequestsMerged: 3,
	pullRequestsClosed: 1,
	approvals: 4,
	changeRequests: 1,
	commentReviews: 2,
	comments: 9,
	codeComments: 12,
	issuesOpened: 2,
	issuesClosed: 0,
};

/** Ada's, Bob's and Chen's in `MEMBERS` together. */
export const WORKSPACE_SUMMARY: ActivitySummary = {
	pullRequestsOpened: 9,
	pullRequestsMerged: 7,
	pullRequestsClosed: 1,
	approvals: 10,
	changeRequests: 3,
	commentReviews: 3,
	comments: 20,
	codeComments: 27,
	issuesOpened: 2,
	issuesClosed: 1,
};

export const QUIET_SUMMARY: ActivitySummary = {
	pullRequestsOpened: 0,
	pullRequestsMerged: 0,
	pullRequestsClosed: 0,
	approvals: 0,
	changeRequests: 0,
	commentReviews: 0,
	comments: 0,
	codeComments: 0,
	issuesOpened: 0,
	issuesClosed: 0,
};

function item(
	id: string,
	kind: ActivityItem["kind"],
	occurredAt: Date,
	work?: WorkItem,
	actor = ada,
	htmlUrl = work?.htmlUrl,
): ActivityItem {
	return { id, kind, occurredAt, actor, work, htmlUrl };
}

/** A review has an address of its own on the provider, beside the pull request's. */
export const APPROVAL_URL =
	"https://github.com/hephaestus-build/Hephaestus/pull/2310#pullrequestreview-9001";

/**
 * A minute past local midnight `daysAgo` days before the story's clock, so an entry falls on the day
 * it is meant to whatever time of day the story runs.
 */
const onDay = (daysAgo: number, minutes = 0): Date =>
	addMinutes(subDays(startOfDay(STORY_NOW), daysAgo), 1 + minutes);

export const TIMELINE: ActivityItem[] = [
	item("e1", "REVIEW_APPROVED", onDay(0, 1), reviewRequest, ada, APPROVAL_URL),
	item("e2", "PULL_REQUEST_MERGED", onDay(0), mergedPullRequest),
	item("e3", "CODE_COMMENTED", onDay(1), changesRequestedPullRequest),
	item("e4", "PULL_REQUEST_OPENED", onDay(2), draftPullRequest),
	item("e5", "ISSUE_OPENED", onDay(4), assignedIssue),
	item("e6", "PULL_REQUEST_CLOSED", onDay(5)),
];

/** The same activity by several people, for Workspace activity. */
export const WORKSPACE_TIMELINE: ActivityItem[] = [
	item("w1", "REVIEW_CHANGES_REQUESTED", onDay(0, 2), changesRequestedPullRequest, bob),
	item("w2", "PULL_REQUEST_OPENED", onDay(0, 1), reviewRequest, bob),
	item("w3", "COMMENTED", onDay(0), assignedIssue, bob),
	item("w4", "PULL_REQUEST_MERGED", onDay(1), mergedPullRequest, ada),
];

/** By name, as the server lists them; Bob is the most active, so an order by count would differ. */
export const MEMBERS: MemberActivity[] = [
	{ user: ada, summary: SUMMARY },
	{
		user: bob,
		summary: {
			pullRequestsOpened: 5,
			pullRequestsMerged: 4,
			pullRequestsClosed: 0,
			approvals: 6,
			changeRequests: 2,
			commentReviews: 1,
			comments: 11,
			codeComments: 15,
			issuesOpened: 0,
			issuesClosed: 1,
		},
	},
	{ user: chen, summary: QUIET_SUMMARY },
];
