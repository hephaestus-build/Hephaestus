import type { PracticeGroup, PracticeGroupStanding, PracticeStanding } from "@/api/types.gen";

import { PRACTICE_GROUP_STANDING_DEFS } from "./practice-group-standing-defs";
import { statusValues } from "./status-def";

export type SortDirection = "asc" | "desc";

/**
 * The practices tables sort by standing alone; the direction is the one thing a header press
 * changes.
 */
export interface PracticeGroupSort {
	direction: SortDirection;
}

/** What needs attention first: the reading order of the registry. */
export const DEFAULT_PRACTICE_GROUP_SORT: PracticeGroupSort = { direction: "asc" };

/**
 * The sort a header press produces: the other way round, never "unsorted" — the list always has an
 * order.
 */
export function nextPracticeGroupSort(current: PracticeGroupSort): PracticeGroupSort {
	return { direction: current.direction === "asc" ? "desc" : "asc" };
}

/** Registry declaration order: what needs attention first, what no review has reached last. */
const STANDING_ORDER = statusValues(PRACTICE_GROUP_STANDING_DEFS);

/**
 * Orders a copy of `items` by standing under `sort`, needs attention first when ascending. Ties
 * fall through to `tieBreak` in both directions, so a header press reverses the standings and
 * nothing else.
 */
export function sortByStanding<T>(
	items: readonly T[],
	sort: PracticeGroupSort,
	standingOf: (item: T) => PracticeStanding["standing"],
	tieBreak: (left: T, right: T) => number,
): T[] {
	const sign = sort.direction === "asc" ? 1 : -1;
	const rank = (item: T) => STANDING_ORDER.indexOf(standingOf(item));
	return [...items].sort(
		(left, right) => sign * (rank(left) - rank(right)) || tieBreak(left, right),
	);
}

/**
 * The groups by standing; a group the standings did not report reads as not observed, and ties keep
 * the catalog's display order, then the name.
 */
export function sortPracticeGroups(
	groups: PracticeGroup[],
	standings: Record<string, PracticeGroupStanding | undefined>,
	sort: PracticeGroupSort,
): PracticeGroup[] {
	return sortByStanding(
		groups,
		sort,
		(group) => standings[group.slug]?.standing ?? "NOT_OBSERVED",
		(left, right) => left.displayOrder - right.displayOrder || left.name.localeCompare(right.name),
	);
}
