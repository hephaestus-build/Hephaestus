import type {
	ActivityCounts,
	ActivitySparklineWeek,
	PublicActivity,
	UserInfo,
} from "@/api/types.gen";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";

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

/** What the Contributions total counts, said under the table's heading on every page that shows it. */
export function contributionsNote(providerType: ProviderType): string {
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	return `Contributions total ${pullRequests} opened, distinct ${pullRequests} reviewed, and issues opened in the selected period. Merges and comments are excluded from this total.`;
}
