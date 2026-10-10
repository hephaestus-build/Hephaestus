import type { ActivityPerson } from "@/api/types.gen";

import type { PeopleSort } from "./activity-search";

const SORT_VALUES = {
	contributions: (row) => row.counts.contributions,
	"pull-requests": (row) => row.counts.pullRequestsOpened,
	reviews: (row) => row.counts.pullRequestsReviewed,
	issues: (row) => row.counts.issuesOpened,
	"active-weeks": (row) => row.counts.activeWeeks,
} as const satisfies Record<Exclude<PeopleSort, "name">, (row: ActivityPerson) => number>;

/**
 * Each person's position by the column's count, most first: one more than the number of people with
 * a higher count. Sorted the other way, the positions run backwards with the rows.
 */
export function competitionPositions(
	people: readonly ActivityPerson[],
	sort: PeopleSort,
): Map<number, number> {
	if (sort === "name") {
		return new Map();
	}
	const value = SORT_VALUES[sort];
	const sorted = people.map(value).sort((a, b) => b - a);
	const firstAt = new Map<number, number>();
	for (const [index, count] of sorted.entries()) {
		if (!firstAt.has(count)) {
			firstAt.set(count, index + 1);
		}
	}
	return new Map(people.map((row) => [row.person.id, firstAt.get(value(row)) ?? 0]));
}
