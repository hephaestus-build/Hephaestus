import type {
	ActivityCounts,
	ActivityPeople,
	ActivityPersonDetail,
	UserInfo,
} from "@/api/types.gen";

export interface ActivitySummary {
	pullRequestsOpened: number;
	pullRequestsMerged: number;
	pullRequestsClosed: number;
	approvals: number;
	changeRequests: number;
	commentReviews: number;
	comments: number;
	codeComments: number;
	issuesOpened: number;
	issuesClosed: number;
	pullRequestsReviewed?: number;
	totalComments?: number;
}
export interface ActivityBucket {
	start: Date;
	summary: ActivitySummary;
}
export interface ActivityOverview {
	summary: ActivitySummary;
	bucket: "DAY" | "WEEK" | "MONTH";
	buckets: ActivityBucket[];
}
export interface MemberActivity {
	user: UserInfo;
	summary: ActivitySummary;
}

export function summaryFromCounts(counts: ActivityCounts): ActivitySummary {
	return {
		pullRequestsOpened: counts.pullRequestsOpened,
		pullRequestsMerged: counts.pullRequestsMerged,
		issuesOpened: counts.issuesOpened,
		pullRequestsReviewed: counts.pullRequestsReviewed,
		totalComments: counts.comments,
		pullRequestsClosed: 0,
		approvals: 0,
		changeRequests: 0,
		commentReviews: 0,
		comments: 0,
		codeComments: 0,
		issuesClosed: 0,
	};
}
export function overviewFromPerson(data: ActivityPersonDetail): ActivityOverview {
	const summary = (
		counts: ActivityCounts,
		breakdown: ActivityPersonDetail["breakdown"],
	): ActivitySummary => ({
		...summaryFromCounts(counts),
		...breakdown,
		comments: breakdown.discussionComments,
		pullRequestsReviewed: undefined,
		totalComments: undefined,
	});
	return {
		summary: summary(data.counts, data.breakdown),
		bucket: "WEEK",
		buckets: data.weeks.map((week) => ({
			start: week.start,
			summary: summary(week.counts, week.breakdown),
		})),
	};
}
export function overviewFromPeople(data: ActivityPeople): ActivityOverview {
	const totals: ActivityCounts = {
		contributions: 0,
		pullRequestsOpened: 0,
		pullRequestsMerged: 0,
		pullRequestsReviewed: 0,
		peopleHelped: 0,
		issuesOpened: 0,
		comments: 0,
		activeWeeks: 0,
	};
	for (const person of data.people) {
		totals.pullRequestsOpened += person.counts.pullRequestsOpened;
		totals.pullRequestsMerged += person.counts.pullRequestsMerged;
		totals.pullRequestsReviewed += person.counts.pullRequestsReviewed;
		totals.issuesOpened += person.counts.issuesOpened;
		totals.comments += person.counts.comments;
	}
	// The list carries contribution sparklines, not per-type weekly counts.
	return { summary: summaryFromCounts(totals), bucket: "WEEK", buckets: [] };
}
