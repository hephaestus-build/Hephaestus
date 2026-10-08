import { z } from "zod";

import type { ActivityRange } from "@/components/activity/activity-range";
import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/layout/detail-drawer/detail-stack";
import { REVIEW_FILTER_MAX_LENGTH } from "@/components/practice-trace/trace-format";
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
 *
 * The other pair is the reviews of the reader's work and one review over it; a review opened from
 * a shared link has no list beneath it.
 */
export const PRACTICE_PROFILE_LEVEL_KINDS = [
	"practice-group",
	"practice",
	"practice-groups",
	"reviews",
	"review",
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

export const REVIEWS_OF_YOUR_WORK = "Reviews of your work";

/** How many reviews a page of that list holds, and so how many rows its skeleton draws. */
export const PROFILE_REVIEWS_PAGE_SIZE = 10;

export const REVIEWS_LEVEL: DetailStackEntry<PracticeProfileDetailLevelKind> = {
	kind: "reviews",
	id: "all",
};

export function reviewLevel(reviewId: string): DetailStackEntry<PracticeProfileDetailLevelKind> {
	return { kind: "review", id: reviewId };
}

/**
 * One review's own tab and filters, cleared when another review takes its place: a filter chosen
 * for one review could hide every practice of the next. The reviews list's filters stay.
 */
export const REVIEW_SELECTION_CLEARED = {
	reviewTab: undefined,
	reviewGroup: undefined,
	reviewPractice: undefined,
	reviewWatches: undefined,
} satisfies PracticeGroupDetailSelection;

/** The review level's tabs, from the `reviewTab` search param. */
export const REVIEW_TABS = ["practices", "noticed"] as const;

export type ReviewTab = (typeof REVIEW_TABS)[number];

export const DEFAULT_REVIEW_TAB: ReviewTab = "practices";

/** How far back the reviews list reaches; absent is every review. */
export const REVIEW_TIMEFRAMES = ["7d", "30d", "90d"] as const satisfies readonly ActivityRange[];

export type ReviewTimeframe = (typeof REVIEW_TIMEFRAMES)[number];

/** What the level with every practice group is called, wherever it is named. */
export const ALL_PRACTICE_GROUPS = "All practice groups";

/** Shared by the Practice profile and Practices across the workspace: it is one state. */
export const NO_PRACTICE_GROUPS = {
	title: "No practices set up yet",
	description:
		"Practice groups appear here once a workspace admin sets up the practices this workspace reviews.",
};

/**
 * The "All practice groups" table as a level; there is one, so the id names the list rather than a
 * row.
 */
export const ALL_PRACTICE_GROUPS_LEVEL: DetailStackEntry<PracticeProfileDetailLevelKind> = {
	kind: "practice-groups",
	id: "all",
};

/**
 * The practice level's tabs: what the reviews found, the feedback written from it, and the practice
 * guide. The catalog's words on the practice open the level above them. The choice is the
 * `practiceTab` search param; the level shows the guide only for a practice that has one.
 */
export const PRACTICE_TABS = ["observations", "feedback", "guide"] as const;

export type PracticeTab = (typeof PRACTICE_TABS)[number];

export const DEFAULT_PRACTICE_TAB: PracticeTab = "observations";

/** The selection inside an open level; each is cleared when its level closes. */
export interface PracticeGroupDetailSelection {
	practiceTab?: PracticeTab;
	reviewKind?: string;
	reviewSince?: ReviewTimeframe;
	reviewTab?: ReviewTab;
	reviewGroup?: string;
	reviewPractice?: string;
	reviewWatches?: string;
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
 * inside the open levels — the practice level's tab, and the review levels' filters and tab. A
 * hand-typed value a surface cannot show reads as the default. Which observations are open is not
 * here: every one arrives open and closes on its own, and nothing addresses one.
 */
const practiceProfileFilterSchema = z.object({
	dir: z
		.enum(SORT_DIRECTIONS)
		.default(DEFAULT_PRACTICE_GROUP_SORT)
		.catch(DEFAULT_PRACTICE_GROUP_SORT),
	feedback: z.enum(FEEDBACK_TABS).default(DEFAULT_FEEDBACK_TAB).catch(DEFAULT_FEEDBACK_TAB),
	practiceTab: z.enum(PRACTICE_TABS).default(DEFAULT_PRACTICE_TAB).catch(DEFAULT_PRACTICE_TAB),
	// A free string, for the reason `TraceKindFilter` gives.
	reviewKind: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	reviewSince: z.enum(REVIEW_TIMEFRAMES).optional().catch(undefined),
	reviewTab: z.enum(REVIEW_TABS).default(DEFAULT_REVIEW_TAB).catch(DEFAULT_REVIEW_TAB),
	reviewGroup: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	reviewPractice: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	// A signal name, free for the reason `reviewKind` is.
	reviewWatches: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
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

/**
 * The params that belong to a level and leave the URL with it. Going back restores the entry before
 * the level was pushed, which never held them, so a forward write must clear them or the next level
 * opened inherits a filter nobody set.
 */
export const PRACTICE_PROFILE_LEVEL_PARAMS = [
	"practiceTab",
	"reviewKind",
	"reviewSince",
	"reviewTab",
	"reviewGroup",
	"reviewPractice",
	"reviewWatches",
] as const satisfies readonly (keyof PracticeGroupDetailSelection)[];
