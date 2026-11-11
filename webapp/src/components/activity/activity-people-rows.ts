import type {
	ActivityCounts,
	ActivitySparklineWeek,
	PublicActivity,
	UserInfo,
} from "@/api/types.gen";
import { ITEM_KIND, itemKindNoun } from "@/lib/item-kinds";
import type { ProviderType } from "@/lib/provider/provider-terms";
import { hasText } from "@/lib/text";

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

/** The identity activity presents, without account or workspace fields. */
export type ActivityPersonIdentity = Pick<UserInfo, "login" | "name" | "avatarUrl" | "htmlUrl">;

/** A missing display name leaves the provider login as the identity. */
export function activityPersonName(
	person: ActivityPersonIdentity | undefined,
	login: string,
): string {
	return person !== undefined && hasText(person.name) ? person.name : login;
}

/** What the people table reads of a person, whichever page lists them. */
export interface PeopleRow {
	/** `id` tells people apart within one response: a member's id, or a public login. */
	person: ActivityPersonIdentity & { id: number | string };
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
	/** Contributors in the period that the public page counts but does not name. */
	unlisted?: number;
}

/** The public page's people: a public login stands for a person, as no id leaves the server. */
export function publicPeopleRows({
	people,
	from,
	to,
	highlights,
	unlistedContributors,
}: PublicActivity): PeopleRows {
	return {
		people: people.map(({ login, name, avatarUrl, profileUrl, counts, weeks }) => ({
			person: { id: login, login, name, avatarUrl, htmlUrl: profileUrl },
			counts,
			weeks,
		})),
		from,
		to,
		highlights: { firstContributors: highlights.firstContributors },
		unlisted: unlistedContributors,
	};
}

/** What the Contributions total counts, said under the table's heading on every page that shows it. */
export function contributionsNote(providerType: ProviderType): string {
	const pullRequests = itemKindNoun(ITEM_KIND.pullRequest, 2, providerType);
	return `Contributions total ${pullRequests} opened, distinct ${pullRequests} reviewed, and issues opened in the selected period. Merges and comments are excluded from this total.`;
}
