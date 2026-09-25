import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/layout/detail-drawer/detail-stack";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	type SortDirection,
} from "@/components/practice-vocabulary/practice-group-list-order";

const SORT_DIRECTIONS = ["asc", "desc"] as const satisfies readonly SortDirection[];

/**
 * The table's URL state: the direction of its one sort. A hand-typed value the table cannot show
 * is dropped rather than applied; an absent one means the default, which is also what the route
 * writes back when a header press lands on it.
 */
const practiceGroupListSearchSchema = z.object({
	dir: z.enum(SORT_DIRECTIONS).optional().catch(undefined),
});

export function parsePracticeGroupListSort(search: { dir?: SortDirection }): SortDirection {
	return search.dir ?? DEFAULT_PRACTICE_GROUP_SORT;
}

/**
 * The levels the practice profile can open over the page, outermost first: a practice group, a
 * practice over its group, and one list — every practice — that a group opened from a row stacks
 * on, so the list is what a dismissal returns to. The schema keeps every entry of these kinds and
 * drops the rest, so a hand-typed URL with a practice and no group beneath it still opens the
 * practice; its path then names no group.
 */
export const PRACTICE_PROFILE_LEVEL_KINDS = ["practice-group", "practice", "practices"] as const;

export type PracticeProfileDetailLevelKind = (typeof PRACTICE_PROFILE_LEVEL_KINDS)[number];

/** The read-only detail level for one practice group's standing. */
export function practiceGroupLevel(
	groupSlug: string,
): DetailStackEntry<PracticeProfileDetailLevelKind> {
	return { kind: "practice-group", id: groupSlug };
}

/** The read-only detail level for one practice, stacked on its group's level. */
export function practiceLevel(
	practiceSlug: string,
): DetailStackEntry<PracticeProfileDetailLevelKind> {
	return { kind: "practice", id: practiceSlug };
}

/**
 * The id of the open level of one kind, or `undefined` while no level of that kind is open. The
 * stack is read by kind rather than by depth, so a route may stack its own levels between these.
 */
export function openLevelId(
	stack: DetailStackEntry<PracticeProfileDetailLevelKind>[],
	kind: PracticeProfileDetailLevelKind,
): string | undefined {
	return stack.find((entry) => entry.kind === kind)?.id;
}

/**
 * The "All practice groups" table as a level; there is one, so the id names the list rather than a row.
 */
export function allPracticesLevel(): DetailStackEntry<PracticeProfileDetailLevelKind> {
	return { kind: "practices", id: "all" };
}

/**
 * The practice level's tabs: what the reviews found, the feedback written from it, and the
 * catalog's words on the practice. The choice is the `practiceTab` search param; "observations"
 * is the URL's silence.
 */
export const PRACTICE_TABS = ["observations", "feedback", "about"] as const;

export type PracticeTab = (typeof PRACTICE_TABS)[number];

export const DEFAULT_PRACTICE_TAB: PracticeTab = "observations";

/** The selection inside the open practice level, as it is written: `undefined` clears a param. */
export interface PracticeGroupDetailSelection {
	practiceTab?: PracticeTab;
}

/**
 * Which feedback cards the page lists, chosen by the tab row over them; `PracticeProfilePage`
 * decides what "newest" holds. The choice is the `feedback` search param; "newest" is the URL's
 * silence.
 */
export const FEEDBACK_TABS = ["newest", "open", "resolved", "all"] as const;

export type FeedbackTab = (typeof FEEDBACK_TABS)[number];

export const DEFAULT_FEEDBACK_TAB: FeedbackTab = "newest";

/**
 * What the page itself reads out of the URL: the table's sort, the feedback tab, and the selection
 * inside the open practice level — the tab shown. A hand-typed value a surface cannot show is
 * dropped rather than applied. Which observations are open is not here: every one arrives open and
 * closes on its own, and nothing addresses one.
 */
const practiceProfileFilterSchema = practiceGroupListSearchSchema.extend({
	feedback: z.enum(FEEDBACK_TABS).optional().catch(undefined),
	practiceTab: z.enum(PRACTICE_TABS).optional().catch(undefined),
});

/** The page's own params and the detail drawer's `detail` stack: the route's whole search. */
export const practiceProfileSearchSchema = practiceProfileFilterSchema.extend(
	detailStackSchema(PRACTICE_PROFILE_LEVEL_KINDS).shape,
);

export type PracticeProfileSearch = z.infer<typeof practiceProfileSearchSchema>;

/** The params a drawer navigation keeps: the table's sort and the page's feedback tab. */
export const PRACTICE_PROFILE_SEARCH_PARAMS: (keyof PracticeProfileSearch)[] = ["dir", "feedback"];
