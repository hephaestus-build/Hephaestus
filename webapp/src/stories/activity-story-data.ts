import {
	addDays,
	addMinutes,
	addMonths,
	addWeeks,
	startOfDay,
	startOfMonth,
	startOfWeek,
	subDays,
} from "date-fns";

import type {
	ActivityAction,
	ActivityBucket,
	ActivityOverview,
	ActivitySummary,
	ActivityWork,
	MemberActivity,
	OpenWork,
	RepositoryInfo,
	Reviewer,
	UserInfo,
	WorkItem,
	WorkItemList,
} from "@/api/types.gen";
import type { ActivityOverviewState, DateSpan } from "@/components/activity/activity-buckets";
import { rangeStart } from "@/components/activity/activity-range";
import type { MemberActivityState } from "@/components/activity/MemberActivityTable";

import { daysBefore, hoursBefore, minutesBefore, STORY_NOW } from "./story-clock";

// The reader is Ada. Her pull requests are open or merged, never both; her open work, her tiles and
// her timeline describe the same weeks; and a workspace's totals are the sum of its members'.

/** No avatar address, so a story never waits on the network and shows the initials. */
function user(id: number, login: string, name: string): UserInfo {
	return { id, login, name, avatarUrl: "", htmlUrl: `https://github.com/${login}` };
}

export const ada = user(1, "ada", "Ada Lovelace");
export const bob = user(2, "bob", "Bob Brenner");
export const chen = user(3, "chen", "Chen Wei");
export const dana = user(4, "dana", "Dana Okafor");
export const eli = user(5, "eli", "Eli Novak");
export const fay = user(6, "fay", "Fay Iyer");
export const gus = user(7, "gus", "Gus Lindqvist");
export const elodie = user(8, "elodie", "Élodie Brière");

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

const reviewer = (person: UserInfo, state: Reviewer["state"]): Reviewer => ({
	user: person,
	state,
});

function pullRequest(
	id: number,
	number: number,
	title: string,
	overrides: Partial<WorkItem> = {},
): WorkItem {
	const repository = overrides.repository ?? hephaestusRepo;
	return {
		id,
		type: "PULL_REQUEST",
		number,
		title,
		state: "OPEN",
		isDraft: false,
		htmlUrl: `${repository.htmlUrl}/pull/${number}`,
		repository,
		author: ada,
		createdAt: daysBefore(3),
		updatedAt: hoursBefore(2),
		...overrides,
	};
}

function issue(
	id: number,
	number: number,
	title: string,
	overrides: Partial<WorkItem> = {},
): WorkItem {
	return {
		id,
		type: "ISSUE",
		number,
		title,
		state: "OPEN",
		isDraft: false,
		htmlUrl: `https://github.com/hephaestus-build/Hephaestus/issues/${number}`,
		repository: hephaestusRepo,
		author: chen,
		createdAt: daysBefore(20),
		updatedAt: daysBefore(2),
		...overrides,
	};
}

// -- Open work -------------------------------------------------------------------------------------

/** Asked of Ada, with nobody's verdict in yet and a failing check. */
export const reviewRequest = pullRequest(100, 2310, "Show open work on the Activity page", {
	author: bob,
	updatedAt: minutesBefore(40),
	checks: "FAILURE",
	reviewers: [reviewer(chen, "COMMENTED"), reviewer(ada, "REQUESTED")],
});

const secondReviewRequest = pullRequest(106, 2318, "Batch the reviewer lookup for open work", {
	author: dana,
	updatedAt: hoursBefore(5),
	reviewers: [reviewer(ada, "REQUESTED"), reviewer(eli, "REQUESTED")],
});

/** Asked of Ada, but already approved: another review would change nothing. */
export const coveredByApproval = pullRequest(107, 2290, "Cache team paths between renders", {
	author: eli,
	reviewDecision: "APPROVED",
	updatedAt: daysBefore(1),
	reviewers: [reviewer(bob, "APPROVED"), reviewer(ada, "REQUESTED")],
});

/**
 * Still listing Ada after she approved it, as GitLab does until the author asks again: her part is
 * done, and it waits with the rest.
 */
const reviewedByAda = pullRequest(114, 2322, "Show the reviewer state beside each avatar", {
	author: chen,
	updatedAt: hoursBefore(9),
	reviewers: [reviewer(ada, "APPROVED"), reviewer(bob, "REQUESTED")],
});

/** Asked of Ada, but a reviewer already asked for changes: the author moves first. */
const coveredByChanges = pullRequest(108, 2296, "Drop the legacy leaderboard read model", {
	author: fay,
	updatedAt: daysBefore(2),
	reviewers: [reviewer(chen, "CHANGES_REQUESTED"), reviewer(ada, "REQUESTED")],
});

export const changesRequestedPullRequest = pullRequest(
	102,
	2301,
	"Retry webhook delivery after a gateway timeout and keep the first failure in the log",
	{
		reviewDecision: "CHANGES_REQUESTED",
		updatedAt: daysBefore(1),
		reviewers: [reviewer(bob, "CHANGES_REQUESTED"), reviewer(chen, "APPROVED")],
	},
);

const failingChecksPullRequest = pullRequest(
	109,
	2312,
	"Pin the Playwright image for the browser suite",
	{
		checks: "FAILURE",
		updatedAt: hoursBefore(3),
		reviewers: [reviewer(dana, "REQUESTED")],
	},
);

/** Approved by more reviewers than the row shows faces for. */
export const approvedPullRequest = pullRequest(
	101,
	2298,
	"Cache the workspace list between loads",
	{
		reviewDecision: "APPROVED",
		reviewers: [
			reviewer(bob, "APPROVED"),
			reviewer(chen, "APPROVED"),
			reviewer(dana, "COMMENTED"),
			reviewer(fay, "APPROVED"),
			reviewer(gus, "APPROVED"),
			reviewer(eli, "REQUESTED"),
		],
	},
);

const waitingPullRequest = pullRequest(
	110,
	2320,
	"Describe the activity buckets in the admin docs",
	{
		updatedAt: hoursBefore(20),
		reviewers: [reviewer(bob, "REQUESTED"), reviewer(chen, "REQUESTED")],
	},
);

export const draftPullRequest = pullRequest(103, 2315, "Workspace activity team filter", {
	isDraft: true,
	repository: artemisRepo,
});

export const assignedIssue = issue(104, 1374, "Document the backup restore drill");

const secondAssignedIssue = issue(111, 1381, "Explain what counts as a review in the user docs", {
	author: bob,
	updatedAt: daysBefore(6),
});

/** Merged, so it is in the timeline and never in the open work. */
export const mergedPullRequest = pullRequest(
	105,
	2294,
	"Show the review decision on open pull requests",
	{
		state: "MERGED",
	},
);

/** A title as long as a real one gets when someone pastes a stack trace into it. */
export const LONG_TITLE = `Reconcile GitLab approvals with reviews after a force push rewrites the merge request's history, keep the earliest approval when the same reviewer approves twice, ${"and keep counting every comment on code that survives the rewrite ".repeat(3).trim()}, without double counting the ones the provider re-sends on the next sync`;

export const longTitlePullRequest = pullRequest(112, 2325, LONG_TITLE, {
	author: bob,
	reviewers: [reviewer(ada, "REQUESTED")],
});

function list(content: WorkItem[], hasMore = false): WorkItemList {
	return { content, hasMore };
}

export const OPEN_WORK: OpenWork = {
	reviewRequests: list([
		reviewRequest,
		secondReviewRequest,
		coveredByApproval,
		coveredByChanges,
		reviewedByAda,
	]),
	pullRequests: list([
		failingChecksPullRequest,
		changesRequestedPullRequest,
		approvedPullRequest,
		waitingPullRequest,
		draftPullRequest,
	]),
	issues: list([assignedIssue, secondAssignedIssue]),
};

export const NOTHING_OPEN: OpenWork = {
	reviewRequests: list([]),
	pullRequests: list([]),
	issues: list([]),
};

/** Nothing asks anything of Ada: her pull requests wait on reviewers, the one request is covered. */
export const ONLY_WAITING: OpenWork = {
	reviewRequests: list([coveredByApproval]),
	pullRequests: list([waitingPullRequest, draftPullRequest]),
	issues: list([]),
};

/** More review requests than the server lists at once. */
export const MANY_REVIEW_REQUESTS: OpenWork = {
	...NOTHING_OPEN,
	reviewRequests: list(
		Array.from({ length: 8 }, (_, index) =>
			pullRequest(
				200 + index,
				2400 + index,
				`Follow-up ${index + 1}: tidy the activity read model`,
				{
					author: bob,
					reviewers: [reviewer(ada, "REQUESTED")],
				},
			),
		),
		true,
	),
};

// -- GitLab ----------------------------------------------------------------------------------------

const pipelinesProject: RepositoryInfo = {
	id: 12,
	name: "pipelines",
	nameWithOwner: "aet/pipelines",
	htmlUrl: "https://gitlab.example.com/aet/pipelines",
	hiddenFromContributions: false,
};

/** The same work as a GitLab group would hold it: merge requests in a project, numbered `!`. */
function onGitLab(item: WorkItem): WorkItem {
	const path = item.type === "PULL_REQUEST" ? "merge_requests" : "issues";
	return {
		...item,
		repository: pipelinesProject,
		htmlUrl: `${pipelinesProject.htmlUrl}/-/${path}/${item.number}`,
	};
}

/** A merge request as GitLab numbers it, in a project rather than a repository. */
export const gitLabMergeRequest = onGitLab(
	pullRequest(300, 42, "Split the pipeline into build and test stages", {
		author: bob,
		checks: "FAILURE",
		reviewers: [reviewer(ada, "REQUESTED"), reviewer(chen, "APPROVED")],
	}),
);

export const GITLAB_OPEN_WORK: OpenWork = {
	reviewRequests: list(OPEN_WORK.reviewRequests.content.map(onGitLab)),
	pullRequests: list(OPEN_WORK.pullRequests.content.map(onGitLab)),
	issues: list(OPEN_WORK.issues.content.map(onGitLab)),
};

// -- Summaries and buckets -------------------------------------------------------------------------

const ZERO: ActivitySummary = {
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

const FIELDS = Object.keys(ZERO).filter((key): key is keyof ActivitySummary =>
	Object.hasOwn(ZERO, key),
);

export function summaryOf(counts: Partial<ActivitySummary>): ActivitySummary {
	return { ...ZERO, ...counts };
}

export function sumSummaries(summaries: readonly ActivitySummary[]): ActivitySummary {
	const total = { ...ZERO };
	for (const summary of summaries) {
		for (const field of FIELDS) {
			total[field] += summary[field];
		}
	}
	return total;
}

/**
 * Ada's month, as skewed as a reviewer's month is: one issue, three merged, 18 reviews and 142
 * comments. Kinds of such different sizes are why no two share an axis.
 */
export const SUMMARY = summaryOf({
	pullRequestsOpened: 4,
	pullRequestsMerged: 3,
	pullRequestsClosed: 1,
	approvals: 11,
	changeRequests: 3,
	commentReviews: 4,
	comments: 60,
	codeComments: 82,
	issuesOpened: 1,
});

export const QUIET_SUMMARY = ZERO;

type StoryRange = "7d" | "30d" | "90d" | "1y";

/** Where the server starts each bucket of a range that ends now, in the browser's time zone. */
function bucketStarts(range: StoryRange): { bucket: ActivityOverview["bucket"]; starts: Date[] } {
	const from = rangeStart(STORY_NOW, range);
	const now = new Date(STORY_NOW);
	const starts: Date[] = [];
	if (range === "7d" || range === "30d") {
		for (let day = startOfDay(from); day <= now; day = addDays(day, 1)) {
			starts.push(day);
		}
		return { bucket: "DAY", starts };
	}
	if (range === "90d") {
		for (let week = startOfWeek(from, { weekStartsOn: 1 }); week <= now; week = addWeeks(week, 1)) {
			starts.push(week);
		}
		return { bucket: "WEEK", starts };
	}
	for (let month = startOfMonth(from); month <= now; month = addMonths(month, 1)) {
		starts.push(month);
	}
	return { bucket: "MONTH", starts };
}

/** A fixed, uneven rhythm — quiet weekends, a busy stretch — so the bars look like somebody's. */
const RHYTHM = [
	3, 5, 2, 6, 4, 0, 1, 7, 2, 5, 3, 8, 0, 0, 4, 6, 1, 3, 9, 2, 5, 0, 1, 4, 7, 3, 2, 6, 0, 5,
];

/**
 * Spreads each count over the buckets by the rhythm, shifted per kind so no two kinds peak together,
 * and makes the buckets add up to the summary exactly, as the server's do.
 */
function spread(summary: ActivitySummary, count: number): ActivitySummary[] {
	const buckets = Array.from({ length: count }, () => ({ ...ZERO }));
	for (const [shift, field] of FIELDS.entries()) {
		const weights = buckets.map((_, index) => RHYTHM[(index + shift * 3) % RHYTHM.length] ?? 1);
		const totalWeight = Math.max(
			1,
			weights.reduce((sum, weight) => sum + weight, 0),
		);
		let left = summary[field];
		for (const [index, weight] of weights.entries()) {
			const share = Math.floor((summary[field] * weight) / totalWeight);
			const bucket = buckets[index];
			if (bucket) {
				bucket[field] += share;
			}
			left -= share;
		}
		// The remainder goes to the heaviest buckets, one each, so a small count still lands somewhere.
		const heaviest = weights
			.map((weight, index) => ({ weight, index }))
			.sort((a, b) => b.weight - a.weight);
		for (let index = 0; left > 0; index = (index + 1) % heaviest.length) {
			const bucket = buckets[heaviest[index]?.index ?? 0];
			if (bucket) {
				bucket[field] += 1;
			}
			left -= 1;
		}
	}
	return buckets;
}

/** An overview of `summary` over `range`, dense and adding up, as `GET /activity/summary` returns it. */
export function overviewOf(summary: ActivitySummary, range: StoryRange): ActivityOverview {
	const { bucket, starts } = bucketStarts(range);
	const summaries = spread(summary, starts.length);
	const buckets: ActivityBucket[] = starts.map((start, index) => ({
		start,
		summary: summaries[index] ?? ZERO,
	}));
	return { summary, bucket, buckets };
}

/** The span a range's overview was read for, ending at the story's clock. */
export function spanOf(range: StoryRange): DateSpan {
	return { from: rangeStart(STORY_NOW, range), to: new Date(STORY_NOW) };
}

/** An overview as the page receives it once it is in and current. */
export function readyOverview(
	overview: ActivityOverview,
	range: StoryRange = "30d",
): ActivityOverviewState {
	return { status: "ready", overview, span: spanOf(range), stale: false };
}

export function readyMembers(members: MemberActivity[]): MemberActivityState {
	return { status: "ready", members, stale: false };
}

export const OVERVIEW = overviewOf(SUMMARY, "30d");

export const WEEK_OVERVIEW = overviewOf(
	summaryOf({ pullRequestsMerged: 1, approvals: 3, comments: 9, codeComments: 14 }),
	"7d",
);

export const QUIET_OVERVIEW = overviewOf(QUIET_SUMMARY, "30d");

/** Twelve months of the same habits, one bar per month. */
export const YEAR_OVERVIEW = overviewOf(
	summaryOf({
		pullRequestsOpened: 48,
		pullRequestsMerged: 41,
		pullRequestsClosed: 5,
		approvals: 132,
		changeRequests: 29,
		commentReviews: 51,
		comments: 704,
		codeComments: 988,
		issuesOpened: 12,
		issuesClosed: 7,
	}),
	"1y",
);

// -- Members ---------------------------------------------------------------------------------------

/** A small team, by name as the server lists them; Bob is the most active, so a count order would differ. */
export const MEMBERS: MemberActivity[] = [
	{ user: ada, summary: SUMMARY },
	{
		user: bob,
		summary: summaryOf({
			pullRequestsOpened: 5,
			pullRequestsMerged: 4,
			approvals: 16,
			changeRequests: 2,
			commentReviews: 1,
			comments: 31,
			codeComments: 45,
			issuesClosed: 1,
		}),
	},
	{ user: chen, summary: QUIET_SUMMARY },
	{ user: dana, summary: summaryOf({ comments: 3 }) },
	{ user: elodie, summary: QUIET_SUMMARY },
];

export const WORKSPACE_SUMMARY = sumSummaries(MEMBERS.map((member) => member.summary));

export const WORKSPACE_OVERVIEW = overviewOf(WORKSPACE_SUMMARY, "30d");

const FIRST_NAMES = [
	"Amara",
	"Bruno",
	"Carla",
	"Dmitri",
	"Esme",
	"Farid",
	"Greta",
	"Hugo",
	"Ines",
	"Jonas",
	"Kaito",
	"Lena",
	"Mateo",
	"Nadia",
	"Omar",
	"Priya",
	"Quentin",
	"Rosa",
	"Sven",
	"Tara",
	"Umar",
	"Vera",
	"Wim",
	"Xenia",
	"Yusuf",
	"Zoe",
];

const LAST_NAMES = [
	"Achebe",
	"Berg",
	"Castro",
	"Dubois",
	"Eriksen",
	"Fischer",
	"Gallo",
	"Horvat",
	"Ito",
	"Jansen",
];

/**
 * A workspace of 250: most members did nothing in the range, some only commented or only
 * reviewed, a few did a bit of everything — the shape a large course or company workspace has.
 */
const byName = new Intl.Collator("en", { sensitivity: "base" });

export const LARGE_ROSTER: MemberActivity[] = Array.from({ length: 250 }, (_, index) => {
	const first = FIRST_NAMES[index % FIRST_NAMES.length] ?? "Sam";
	const last = LAST_NAMES[Math.floor(index / FIRST_NAMES.length) % LAST_NAMES.length] ?? "Lee";
	const person = user(1000 + index, `${first}-${last}-${index}`.toLowerCase(), `${first} ${last}`);
	const seed = RHYTHM[index % RHYTHM.length] ?? 0;
	let summary = QUIET_SUMMARY;
	if (index % 10 === 3) {
		summary = summaryOf({ comments: seed + 1 });
	} else if (index % 10 === 7) {
		summary = summaryOf({ approvals: seed + 2 });
	} else if (index % 17 === 0) {
		summary = summaryOf({
			pullRequestsOpened: 1 + (seed % 3),
			pullRequestsMerged: seed % 3,
			approvals: seed,
			comments: seed * 4,
			codeComments: seed * 6,
			issuesOpened: seed % 2,
		});
	}
	return { user: person, summary };
	// By name, as the server lists members.
}).sort((a, b) => byName.compare(a.user.name, b.user.name));

export const LARGE_WORKSPACE_OVERVIEW = overviewOf(
	sumSummaries(LARGE_ROSTER.map((member) => member.summary)),
	"30d",
);

// -- Timeline --------------------------------------------------------------------------------------

/**
 * A minute past local midnight `daysAgo` days before the story's clock, so a row falls on the day
 * it is meant to whatever time of day the story runs.
 */
const onDay = (daysAgo: number, minutes = 0): Date =>
	addMinutes(subDays(startOfDay(STORY_NOW), daysAgo), 1 + minutes);

const action = (kind: ActivityAction["kind"], count = 1): ActivityAction => ({ kind, count });

function work(
	item: WorkItem | undefined,
	lastOccurredAt: Date,
	actions: ActivityAction[],
	people: UserInfo[] = [ada],
	eventId = "1",
): ActivityWork {
	return {
		id: item ? `work:${item.id}` : `event:${eventId}`,
		work: item,
		actions,
		lastOccurredAt,
		people,
	};
}

const chensPullRequest = pullRequest(113, 2287, "Group the timeline by the work it happened on", {
	author: chen,
	state: "MERGED",
});

export const WORK_LOG: ActivityWork[] = [
	work(reviewRequest, onDay(0, 90), [action("REVIEW_COMMENTED"), action("CODE_COMMENTED", 4)]),
	work(mergedPullRequest, onDay(0, 30), [action("PULL_REQUEST_MERGED"), action("COMMENTED", 2)]),
	work(changesRequestedPullRequest, onDay(1, 600), [
		action("PULL_REQUEST_OPENED"),
		action("COMMENTED", 3),
	]),
	work(chensPullRequest, onDay(2, 840), [
		action("REVIEW_CHANGES_REQUESTED"),
		action("REVIEW_APPROVED"),
		action("CODE_COMMENTED", 40),
	]),
	work(assignedIssue, onDay(4, 60), [action("ISSUE_OPENED"), action("COMMENTED")]),
	work(undefined, onDay(5), [action("PULL_REQUEST_CLOSED")], [ada], "5f0c"),
];

/** Several people's work on the same pull requests, for Workspace activity. */
export const WORKSPACE_WORK_LOG: ActivityWork[] = [
	work(
		changesRequestedPullRequest,
		onDay(0, 120),
		[action("REVIEW_CHANGES_REQUESTED"), action("REVIEW_APPROVED"), action("CODE_COMMENTED", 9)],
		[bob, chen],
	),
	work(reviewRequest, onDay(0, 60), [action("PULL_REQUEST_OPENED"), action("COMMENTED", 3)], [bob]),
	work(
		chensPullRequest,
		onDay(1, 300),
		[action("PULL_REQUEST_MERGED"), action("REVIEW_APPROVED", 2), action("CODE_COMMENTED", 40)],
		[ada, bob, chen, dana, eli, fay],
	),
	work(mergedPullRequest, onDay(1), [action("PULL_REQUEST_MERGED")], [ada]),
	work(assignedIssue, onDay(3), [action("COMMENTED", 2)], [bob, dana]),
];

/** A row whose title runs to hundreds of characters. */
export const LONG_WORK_LOG: ActivityWork[] = [
	work(longTitlePullRequest, onDay(0, 10), [
		action("REVIEW_COMMENTED"),
		action("CODE_COMMENTED", 1000),
	]),
	...WORK_LOG.slice(1, 3),
];

export const GITLAB_WORK_LOG: ActivityWork[] = WORK_LOG.map((item) => ({
	...item,
	work: item.work && onGitLab(item.work),
}));
