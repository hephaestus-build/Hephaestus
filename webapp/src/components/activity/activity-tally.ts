import type {
	ActivityBreakdown,
	ActivityCounts,
	ActivityPersonDetail,
	ActivityRepositoryCounts,
} from "@/api/types.gen";

import type { ActivityKind } from "./activity-kind-defs";

/**
 * How often each kind of activity happened, and the counts that no sum of kinds gives: each pull
 * request reviewed once, and Contributions.
 */
export type ActivityTally = Record<ActivityKind, number> &
	Pick<ActivityCounts, "contributions" | "pullRequestsReviewed" | "comments">;

export interface ActivityWeek {
	/** Monday 00:00 UTC, where the server starts a week. */
	start: Date;
	tally: ActivityTally;
}

/** One person's activity in a period: in total, every week of it, and by repository. */
export interface ActivityOverview {
	tally: ActivityTally;
	weeks: ActivityWeek[];
	repositories: ActivityRepositoryCounts[];
}

export function tallyOf(counts: ActivityCounts, breakdown: ActivityBreakdown): ActivityTally {
	return {
		PULL_REQUEST_OPENED: counts.pullRequestsOpened,
		PULL_REQUEST_MERGED: counts.pullRequestsMerged,
		PULL_REQUEST_CLOSED: breakdown.pullRequestsClosed,
		REVIEW_APPROVED: breakdown.approvals,
		REVIEW_CHANGES_REQUESTED: breakdown.changeRequests,
		REVIEW_COMMENTED: breakdown.commentReviews,
		COMMENTED: breakdown.discussionComments,
		CODE_COMMENTED: breakdown.codeComments,
		ISSUE_OPENED: counts.issuesOpened,
		ISSUE_CLOSED: breakdown.issuesClosed,
		contributions: counts.contributions,
		pullRequestsReviewed: counts.pullRequestsReviewed,
		comments: counts.comments,
	};
}

const EMPTY_TALLY = tallyOf(
	{
		activeWeeks: 0,
		comments: 0,
		contributions: 0,
		issuesOpened: 0,
		peopleHelped: 0,
		pullRequestsMerged: 0,
		pullRequestsOpened: 0,
		pullRequestsReviewed: 0,
	},
	{
		approvals: 0,
		changeRequests: 0,
		codeComments: 0,
		commentReviews: 0,
		discussionComments: 0,
		issuesClosed: 0,
		pullRequestsClosed: 0,
	},
);

export function overviewOf(detail: ActivityPersonDetail): ActivityOverview {
	const counted = new Map(
		detail.weeks.map((week) => [week.start.getTime(), tallyOf(week.counts, week.breakdown)]),
	);
	return {
		tally: tallyOf(detail.counts, detail.breakdown),
		weeks: weekStarts(detail.from, detail.to).map((start) => ({
			start,
			tally: counted.get(start.getTime()) ?? EMPTY_TALLY,
		})),
		repositories: detail.repositories,
	};
}

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * Every week the span touches, from the one that holds `from` to the one before `to`. The server
 * lists only weeks with activity, so a quiet week must still get its place on a chart.
 */
export function weekStarts(from: Date, to: Date): Date[] {
	const daysSinceMonday = (from.getUTCDay() + 6) % 7;
	let start = Date.UTC(
		from.getUTCFullYear(),
		from.getUTCMonth(),
		from.getUTCDate() - daysSinceMonday,
	);
	const starts: Date[] = [];
	while (start < to.getTime()) {
		starts.push(new Date(start));
		start += WEEK_MS;
	}
	return starts;
}
