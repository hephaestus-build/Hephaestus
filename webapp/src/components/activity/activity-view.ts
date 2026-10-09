import { ACTIVITY_KIND_DEFS } from "./activity-kind-defs";

import type { ActivityPeople, ActivitySummary, UserInfo } from "@/api/types.gen";

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

export function overviewFromPeople(data: ActivityPeople, login?: string): ActivityOverview {
	const people = data.people.filter((row) => login === undefined || row.person.login === login);
	const zero = {
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
	const total = { ...zero };
	const weeks = new Map<number, typeof zero>();
	for (const person of people) {
		for (const { summaryField } of Object.values(ACTIVITY_KIND_DEFS)) {
			total[summaryField] += person.breakdown[summaryField];
		}
		for (const week of person.weeks) {
			const counts = weeks.get(week.start.getTime()) ?? { ...zero };
			for (const { summaryField } of Object.values(ACTIVITY_KIND_DEFS)) {
				counts[summaryField] += week.breakdown[summaryField];
			}
			weeks.set(week.start.getTime(), counts);
		}
	}
	return {
		summary: total,
		bucket: "WEEK",
		buckets: [...weeks]
			.sort(([a], [b]) => a - b)
			.map(([start, summary]) => ({ start: new Date(start), summary })),
	};
}
