import { addMinutes, startOfDay, subDays } from "date-fns";

import type {
	ActivityAction,
	ActivityPeople,
	ActivityPerson,
	ActivityRepository,
	ActivityRepositoryCounts,
	ActivityTeam,
	ActivityWork,
	OpenWork,
	PublicActivity,
	RepositoryInfo,
	Reviewer,
	UserInfo,
	WorkItem,
	WorkItemList,
} from "@/api/types.gen";
import type { ActivityOverviewState, DateSpan } from "@/components/activity/activity-buckets";
import { ACTIVITY_KINDS, type ActivityKind } from "@/components/activity/activity-kind-defs";
import { publicPeopleRows } from "@/components/activity/activity-people-rows";
import {
	type ActivityPreset,
	DEFAULT_ACTIVITY_PRESET,
} from "@/components/activity/activity-period";
import { ACTIVITY_RANGE_DEFS } from "@/components/activity/activity-range";
import {
	type ActivityOverview,
	type ActivityTally,
	weekStarts,
} from "@/components/activity/activity-tally";
import type {
	ActivityPeopleState,
	PeopleTableState,
} from "@/components/activity/ActivityPeopleTable";

import { daysBefore, hoursBefore, minutesBefore, STORY_NOW } from "./story-clock";

// The reader is Ada. Her pull requests are open or merged, never both; and her open work, her tiles
// and her timeline describe the same weeks.

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

/**
 * Ada's teams as the server names them: each team's own name, as its provider shows it, with no
 * parent team or organization before it.
 */
const paymentsTeam = { id: 21, name: "payments" };
const billingTeam = { id: 22, name: "Billing Reliability" };
const developerExperienceTeam = { id: 23, name: "Developer Experience and Tooling" };

const teamReviewRequest = pullRequest(115, 2327, "Retire the old sync scheduler", {
	author: eli,
	updatedAt: hoursBefore(4),
	requestedTeams: [developerExperienceTeam],
	reviewers: [reviewer(gus, "COMMENTED")],
});

export const twoTeamsReviewRequest = pullRequest(116, 2329, "Charge retries through one queue", {
	author: fay,
	updatedAt: daysBefore(1),
	requestedTeams: [paymentsTeam, billingTeam],
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
	teamReviewRequests: list([teamReviewRequest, twoTeamsReviewRequest]),
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
	teamReviewRequests: list([]),
	pullRequests: list([]),
	issues: list([]),
};

/** Nothing asks anything of Ada: her pull requests wait on reviewers, the one request is covered. */
export const ONLY_WAITING: OpenWork = {
	reviewRequests: list([coveredByApproval]),
	teamReviewRequests: list([]),
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
	teamReviewRequests: list([]),
	pullRequests: list(OPEN_WORK.pullRequests.content.map(onGitLab)),
	issues: list(OPEN_WORK.issues.content.map(onGitLab)),
};

// -- Repositories and teams ----------------------------------------------------------------------

export const REPOSITORIES: ActivityRepository[] = [hephaestusRepo, artemisRepo].map(
	({ id, name, nameWithOwner }) => ({ id, name, key: nameWithOwner }),
);

export const TEAMS: ActivityTeam[] = [
	{ id: 31, key: "platform", name: "Platform" },
	{ id: 32, key: "payments", name: "Payments", parentId: 31 },
	{ id: 33, key: "web", name: "Web" },
];

// -- Tallies and weeks ----------------------------------------------------------------------------

const KIND_ZERO: Record<ActivityKind, number> = {
	PULL_REQUEST_OPENED: 0,
	PULL_REQUEST_MERGED: 0,
	PULL_REQUEST_CLOSED: 0,
	REVIEW_APPROVED: 0,
	REVIEW_CHANGES_REQUESTED: 0,
	REVIEW_COMMENTED: 0,
	COMMENTED: 0,
	CODE_COMMENTED: 0,
	ISSUE_OPENED: 0,
	ISSUE_CLOSED: 0,
};

/** The fields a story spreads over weeks; the totals the server derives follow from them. */
type StoryCounts = Partial<Record<ActivityKind, number>> & { pullRequestsReviewed?: number };

/** A tally whose Contributions and comments add up the way the server's do. */
export function tallyOf(counts: StoryCounts): ActivityTally {
	const kinds = { ...KIND_ZERO, ...counts };
	const pullRequestsReviewed = counts.pullRequestsReviewed ?? 0;
	return {
		...kinds,
		pullRequestsReviewed,
		contributions: kinds.PULL_REQUEST_OPENED + pullRequestsReviewed + kinds.ISSUE_OPENED,
		comments: kinds.COMMENTED + kinds.CODE_COMMENTED,
	};
}

const SPREAD_FIELDS = [...ACTIVITY_KINDS, "pullRequestsReviewed"] as const;

/**
 * Ada's month, as skewed as a reviewer's month is: one issue, three merged, 12 pull requests reviewed
 * and 142 comments. Kinds of such different sizes are why no two share an axis.
 */
export const TALLY = tallyOf({
	PULL_REQUEST_OPENED: 4,
	PULL_REQUEST_MERGED: 3,
	PULL_REQUEST_CLOSED: 1,
	REVIEW_APPROVED: 11,
	REVIEW_CHANGES_REQUESTED: 3,
	REVIEW_COMMENTED: 4,
	COMMENTED: 60,
	CODE_COMMENTED: 82,
	ISSUE_OPENED: 1,
	pullRequestsReviewed: 12,
});

/**
 * Ada's month before this one: fewer merged, more pull requests reviewed, fewer comments — so each
 * tile says a different thing about the change.
 */
export const PREVIOUS_TALLY = tallyOf({
	PULL_REQUEST_OPENED: 3,
	PULL_REQUEST_MERGED: 1,
	REVIEW_APPROVED: 14,
	REVIEW_CHANGES_REQUESTED: 3,
	REVIEW_COMMENTED: 4,
	COMMENTED: 50,
	CODE_COMMENTED: 70,
	ISSUE_OPENED: 1,
	pullRequestsReviewed: 15,
});

type StoryPreset = Exclude<ActivityPreset, "all">;

/** The span a preset counts, ending at the story's clock, as the server reads it. */
export function spanOf(preset: StoryPreset): DateSpan {
	return {
		from: subDays(STORY_NOW, ACTIVITY_RANGE_DEFS[preset].days),
		to: new Date(STORY_NOW),
	};
}

/** A fixed, uneven rhythm — quiet weeks, a busy stretch — so the bars look like somebody's. */
const RHYTHM = [
	3, 5, 2, 6, 4, 0, 1, 7, 2, 5, 3, 8, 0, 0, 4, 6, 1, 3, 9, 2, 5, 0, 1, 4, 7, 3, 2, 6, 0, 5,
];

/**
 * Spreads each count over the weeks by the rhythm, shifted per field so no two peak together, and
 * makes the weeks add up to the total exactly, as the server's do.
 */
function spread(total: ActivityTally, count: number): ActivityTally[] {
	const weeks = Array.from({ length: count }, (): StoryCounts => ({}));
	for (const [shift, field] of SPREAD_FIELDS.entries()) {
		const weights = weeks.map((_, index) => RHYTHM[(index + shift * 3) % RHYTHM.length] ?? 1);
		const totalWeight = Math.max(
			1,
			weights.reduce((sum, weight) => sum + weight, 0),
		);
		const shares = weights.map((weight) => Math.floor((total[field] * weight) / totalWeight));
		let left = total[field] - shares.reduce((sum, share) => sum + share, 0);
		// The remainder goes to the heaviest weeks, one each, so a small count still lands somewhere.
		const heaviest = weights
			.map((weight, index) => ({ weight, index }))
			.sort((a, b) => b.weight - a.weight);
		for (let index = 0; left > 0; index = (index + 1) % heaviest.length) {
			const at = heaviest[index]?.index ?? 0;
			shares[at] = (shares[at] ?? 0) + 1;
			left -= 1;
		}
		for (const [index, week] of weeks.entries()) {
			week[field] = shares[index] ?? 0;
		}
	}
	return weeks.map(tallyOf);
}

const REPOSITORY_SPLIT = [0.7, 0.3];

/** A person's counts in the two story repositories, most in Hephaestus. */
function repositoriesOf(total: ActivityTally): ActivityRepositoryCounts[] {
	return REPOSITORIES.slice(0, 2).map((repository, index) => {
		const share = (value: number) => Math.round(value * (REPOSITORY_SPLIT[index] ?? 0));
		return {
			repository,
			counts: {
				contributions: share(total.contributions),
				pullRequestsOpened: share(total.PULL_REQUEST_OPENED),
				pullRequestsMerged: share(total.PULL_REQUEST_MERGED),
				pullRequestsReviewed: share(total.pullRequestsReviewed),
				peopleHelped: share(total.pullRequestsReviewed),
				issuesOpened: share(total.ISSUE_OPENED),
				comments: share(total.comments),
				activeWeeks: 1,
			},
			breakdown: {
				approvals: share(total.REVIEW_APPROVED),
				changeRequests: share(total.REVIEW_CHANGES_REQUESTED),
				commentReviews: share(total.REVIEW_COMMENTED),
				discussionComments: share(total.COMMENTED),
				codeComments: share(total.CODE_COMMENTED),
				issuesClosed: share(total.ISSUE_CLOSED),
				pullRequestsClosed: share(total.PULL_REQUEST_CLOSED),
			},
		};
	});
}

/** An overview of `total` over a preset, one tally per week and adding up, for a presentational chart. */
export function overviewOf(total: ActivityTally, preset: StoryPreset): ActivityOverview {
	const span = spanOf(preset);
	const starts = weekStarts(span.from, span.to);
	const weeks = spread(total, starts.length);
	return {
		tally: total,
		weeks: starts.map((start, index) => ({ start, tally: weeks[index] ?? tallyOf({}) })),
		repositories: repositoriesOf(total),
	};
}

/**
 * An overview as the page receives it once it is in and current — with the period before it, when
 * the story sets a figure against one.
 */
export function readyOverview(
	overview: ActivityOverview,
	preset: StoryPreset = DEFAULT_ACTIVITY_PRESET,
	previous?: ActivityTally,
): ActivityOverviewState {
	return {
		status: "ready",
		overview,
		span: spanOf(preset),
		stale: false,
		previous: previous && { tally: previous, name: ACTIVITY_RANGE_DEFS[preset].previous },
	};
}

/**
 * A quiet month drawn week by week by hand, so its figures are known: one merge three weeks ago and
 * two last week, one approval, and nothing else.
 */
export const SPARSE_OVERVIEW: ActivityOverview = (() => {
	const span = spanOf("30d");
	const starts = weekStarts(span.from, span.to);
	const weeks = starts.map((start, index) => {
		const weeksAgo = starts.length - 1 - index;
		return {
			start,
			tally: tallyOf({
				PULL_REQUEST_MERGED: { 3: 1, 1: 2 }[weeksAgo] ?? 0,
				REVIEW_APPROVED: weeksAgo === 1 ? 1 : 0,
				pullRequestsReviewed: weeksAgo === 1 ? 1 : 0,
			}),
		};
	});
	return {
		tally: tallyOf({ PULL_REQUEST_MERGED: 3, REVIEW_APPROVED: 1, pullRequestsReviewed: 1 }),
		weeks,
		repositories: [],
	};
})();

export const OVERVIEW = overviewOf(TALLY, DEFAULT_ACTIVITY_PRESET);

/** The same activity over a month, for a component story that counts weeks one by one. */
export const MONTH_OVERVIEW = overviewOf(TALLY, "30d");

export const QUIET_OVERVIEW = overviewOf(tallyOf({}), "30d");

/** A year of activity, one tally per week. */
export const YEAR_OVERVIEW = overviewOf(
	tallyOf({
		PULL_REQUEST_OPENED: 48,
		PULL_REQUEST_MERGED: 41,
		PULL_REQUEST_CLOSED: 5,
		REVIEW_APPROVED: 132,
		REVIEW_CHANGES_REQUESTED: 29,
		REVIEW_COMMENTED: 51,
		COMMENTED: 704,
		CODE_COMMENTED: 988,
		ISSUE_OPENED: 12,
		ISSUE_CLOSED: 7,
		pullRequestsReviewed: 160,
	}),
	"1y",
);

// -- People ----------------------------------------------------------------------------------------

/** One row of the people table: its counts, and its weeks in the period, spread by the rhythm. */
function personOf(
	who: UserInfo,
	counts: StoryCounts & { peopleHelped?: number },
	options: { kind?: ActivityPerson["kind"]; firstContributionAt?: Date } = {},
): ActivityPerson {
	const total = tallyOf(counts);
	const span = spanOf(DEFAULT_ACTIVITY_PRESET);
	const starts = weekStarts(span.from, span.to);
	const weeks = spread(total, starts.length);
	return {
		person: who,
		kind: options.kind ?? "PERSON",
		firstContributionAt: options.firstContributionAt,
		counts: {
			contributions: total.contributions,
			pullRequestsOpened: total.PULL_REQUEST_OPENED,
			pullRequestsMerged: total.PULL_REQUEST_MERGED,
			pullRequestsReviewed: total.pullRequestsReviewed,
			peopleHelped: counts.peopleHelped ?? 0,
			issuesOpened: total.ISSUE_OPENED,
			comments: total.comments,
			activeWeeks: weeks.filter((week) => week.contributions > 0).length,
		},
		weeks: starts
			.map((start, index) => ({ start, contributions: weeks[index]?.contributions ?? 0 }))
			.filter((week) => week.contributions > 0),
	};
}

/**
 * A small team in name order. Bob and Dana tie on Contributions, so they share a position, and Ada,
 * the most active, comes first only once the table sorts by a count.
 */
export const PEOPLE: ActivityPerson[] = [
	personOf(ada, { ...TALLY, peopleHelped: 9 }),
	personOf(bob, {
		PULL_REQUEST_OPENED: 5,
		PULL_REQUEST_MERGED: 4,
		REVIEW_APPROVED: 9,
		pullRequestsReviewed: 7,
		ISSUE_OPENED: 1,
		peopleHelped: 4,
	}),
	personOf(chen, { pullRequestsReviewed: 2, REVIEW_APPROVED: 2, peopleHelped: 2 }),
	personOf(dana, {
		PULL_REQUEST_OPENED: 6,
		PULL_REQUEST_MERGED: 5,
		pullRequestsReviewed: 6,
		REVIEW_APPROVED: 6,
		ISSUE_OPENED: 1,
		peopleHelped: 3,
	}),
	personOf(
		elodie,
		{ PULL_REQUEST_OPENED: 1, PULL_REQUEST_MERGED: 1 },
		{ firstContributionAt: daysBefore(6) },
	),
];

export const AUTOMATION: ActivityPerson[] = [
	personOf(
		user(90, "dependabot[bot]", "dependabot[bot]"),
		{ PULL_REQUEST_OPENED: 14 },
		{
			kind: "BOT",
		},
	),
	personOf(
		user(91, "release-robot", "Release Robot"),
		{ PULL_REQUEST_OPENED: 4 },
		{
			kind: "AUTOMATION",
		},
	),
];

/**
 * The response the people table reads, for `people` in the last 30 days. Élodie's first contribution
 * is in it, unless `firstContributors` says whose is.
 */
export function peopleOf(
	people: ActivityPerson[],
	options: { automation?: ActivityPerson[]; firstContributors?: readonly number[] } = {},
): ActivityPeople {
	const span = spanOf(DEFAULT_ACTIVITY_PRESET);
	return {
		...span,
		people,
		automation: options.automation ?? [],
		coverage: { since: daysBefore(400), completeRepositories: 2, totalRepositories: 2 },
		highlights: {
			firstContributors: [...(options.firstContributors ?? [elodie.id])].filter((id) =>
				people.some(({ person }) => person.id === id),
			),
			mostPeopleHelped: [],
		},
		repositories: REPOSITORIES,
		teams: TEAMS,
	};
}

export function readyPeople(people: ActivityPeople): ActivityPeopleState {
	return { status: "ready", people, stale: false };
}

/**
 * What the public page reads for `people`: the same figures, named by login, since no id leaves
 * the server, and only the public repositories.
 */
export function publicActivityOf(
	people: ActivityPerson[],
	overrides: Partial<PublicActivity> = {},
): PublicActivity {
	const everyone = peopleOf(people);
	return {
		workspaceName: "Hephaestus",
		providerType: "GITHUB",
		allowSearchEngines: false,
		from: everyone.from,
		to: everyone.to,
		coverage: everyone.coverage,
		repositories: REPOSITORIES.map(({ key, name }) => ({ key, name })),
		highlights: {
			firstContributors: everyone.highlights.firstContributors.flatMap((id) =>
				people.filter(({ person }) => person.id === id).map(({ person }) => person.login),
			),
			mostPeopleHelped: [],
		},
		people: people.map(({ person, counts, firstContributionAt, weeks }) => ({
			login: person.login,
			name: person.name,
			avatarUrl: person.avatarUrl,
			profileUrl: person.htmlUrl,
			counts,
			firstContributionAt,
			weeks,
		})),
		...overrides,
	};
}

export function readyPublicPeople(activity: PublicActivity): PeopleTableState {
	return { status: "ready", people: publicPeopleRows(activity), stale: false };
}

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
 * A workspace of 250 contributors: many with one or two pieces of work, some who only review, a
 * few who do a bit of everything — the shape a large course or open-source workspace has.
 */
export const LARGE_PEOPLE: ActivityPerson[] = Array.from({ length: 250 }, (_, index) => {
	const first = FIRST_NAMES[index % FIRST_NAMES.length] ?? "Sam";
	const last = LAST_NAMES[Math.floor(index / FIRST_NAMES.length) % LAST_NAMES.length] ?? "Lee";
	const who = user(1000 + index, `${first}-${last}-${index}`.toLowerCase(), `${first} ${last}`);
	const seed = RHYTHM[index % RHYTHM.length] ?? 0;
	if (index % 10 === 7) {
		return personOf(who, {
			pullRequestsReviewed: seed + 1,
			REVIEW_APPROVED: seed + 2,
			peopleHelped: 1,
		});
	}
	if (index % 17 === 0) {
		return personOf(who, {
			PULL_REQUEST_OPENED: 1 + (seed % 3),
			PULL_REQUEST_MERGED: seed % 3,
			pullRequestsReviewed: seed,
			ISSUE_OPENED: seed % 2,
			peopleHelped: Math.min(seed, 4),
		});
	}
	return personOf(who, { ISSUE_OPENED: 1 + (index % 2) });
});

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
