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
 * The levels the practice profile can open over the page, outermost first: a practice group, a
 * practice over its group, and one list — every practice group — that a group opened from a row
 * stacks on, so the list is what a dismissal returns to. The schema keeps every entry of these
 * kinds and drops the rest. A practice may open with no group beneath it — one the workspace files
 * in no group, or a hand-typed URL — and its path then names no group.
 */
export const PRACTICE_PROFILE_LEVEL_KINDS = [
	"practice-group",
	"practice",
	"practice-groups",
] as const;

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
 * stack is read by kind rather than by depth: the group sits one deeper when the "All practice
 * groups" level is under it, and a practice may open with no group at all.
 */
export function openLevelId(
	stack: DetailStackEntry<PracticeProfileDetailLevelKind>[],
	kind: PracticeProfileDetailLevelKind,
): string | undefined {
	return stack.find((entry) => entry.kind === kind)?.id;
}

/** What the level with every practice group is called, wherever it is named. */
export const ALL_PRACTICE_GROUPS = "All practice groups";

/**
 * The "All practice groups" table as a level; there is one, so the id names the list rather than a
 * row.
 */
export const ALL_PRACTICE_GROUPS_LEVEL: DetailStackEntry<PracticeProfileDetailLevelKind> = {
	kind: "practice-groups",
	id: "all",
};

/**
 * The practice level's tabs: what the reviews found, the feedback written from it, and the
 * catalog's words on the practice. The choice is the `practiceTab` search param.
 */
export const PRACTICE_TABS = ["observations", "feedback", "about"] as const;

export type PracticeTab = (typeof PRACTICE_TABS)[number];

export const DEFAULT_PRACTICE_TAB: PracticeTab = "observations";

/** The selection inside the open practice level. */
export interface PracticeGroupDetailSelection {
	practiceTab: PracticeTab;
}

/**
 * Which feedback cards the page lists, chosen by the tab row over them; `PracticeProfilePage`
 * decides what "newest" holds. The choice is the `feedback` search param.
 */
export const FEEDBACK_TABS = ["newest", "open", "resolved", "all"] as const;

export type FeedbackTab = (typeof FEEDBACK_TABS)[number];

export const DEFAULT_FEEDBACK_TAB: FeedbackTab = "newest";

/**
 * What the page itself reads out of the URL: the table's sort, the feedback tab, and the selection
 * inside the open practice level — the tab shown. A hand-typed value a surface cannot show reads as
 * the default. Which observations are open is not here: every one arrives open and closes on its
 * own, and nothing addresses one.
 */
const practiceProfileFilterSchema = z.object({
	dir: z
		.enum(SORT_DIRECTIONS)
		.default(DEFAULT_PRACTICE_GROUP_SORT)
		.catch(DEFAULT_PRACTICE_GROUP_SORT),
	feedback: z.enum(FEEDBACK_TABS).default(DEFAULT_FEEDBACK_TAB).catch(DEFAULT_FEEDBACK_TAB),
	practiceTab: z.enum(PRACTICE_TABS).default(DEFAULT_PRACTICE_TAB).catch(DEFAULT_PRACTICE_TAB),
});

/** Each param at its default, which the route leaves out of the URL. */
export const PRACTICE_PROFILE_SEARCH_DEFAULTS = practiceProfileFilterSchema.parse({});

/** The page's own params and the detail drawer's `detail` stack: the route's whole search. */
export const practiceProfileSearchSchema = practiceProfileFilterSchema.extend(
	detailStackSchema(PRACTICE_PROFILE_LEVEL_KINDS).shape,
);

export type PracticeProfileSearch = z.infer<typeof practiceProfileSearchSchema>;

/** The params a drawer navigation keeps: the table's sort and the page's feedback tab. */
export const PRACTICE_PROFILE_SEARCH_PARAMS: (keyof PracticeProfileSearch)[] = ["dir", "feedback"];
