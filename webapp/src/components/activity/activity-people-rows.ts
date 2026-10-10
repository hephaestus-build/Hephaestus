import type {
	ActivityCounts,
	ActivitySparklineWeek,
	PublicActivity,
	UserInfo,
} from "@/api/types.gen";

/** The figures a people row shows, which the public page counts too. */
export type PersonCounts = Pick<
	ActivityCounts,
	| "contributions"
	| "pullRequestsOpened"
	| "pullRequestsMerged"
	| "pullRequestsReviewed"
	| "peopleHelped"
	| "issuesOpened"
	| "activeWeeks"
>;

/** What the people table reads of a person, whichever page lists them. */
export interface PeopleRow {
	/** `id` tells people apart within one response: a member's id, or a public login. */
	person: Pick<UserInfo, "login" | "name" | "avatarUrl" | "htmlUrl"> & { id: number | string };
	counts: PersonCounts;
	weeks: readonly ActivitySparklineWeek[];
}

/** The people of one period, in the shape both activity pages hand the table. */
export interface PeopleRows {
	people: readonly PeopleRow[];
	from: Date;
	to: Date;
	/** The people whose first contribution is in the period, by `person.id`. */
	highlights: { firstContributors: readonly (number | string)[] };
}

/** The public page's people: a public login stands for a person, as no id leaves the server. */
export function publicPeopleRows({ people, from, to, highlights }: PublicActivity): PeopleRows {
	return {
		people: people.map(({ login, name, avatarUrl, profileUrl, counts, weeks }) => ({
			person: { id: login, login, name, avatarUrl, htmlUrl: profileUrl },
			counts,
			weeks,
		})),
		from,
		to,
		highlights: { firstContributors: highlights.firstContributors },
	};
}
