import { z } from "zod";

import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/layout/detail-drawer/detail-stack";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	type SortDirection,
} from "@/components/practice-vocabulary/practice-group-list-order";

import { RUN_TIMEFRAMES, type RunTimeframe } from "./review-run-timeframes";

const SORT_DIRECTIONS = ["asc", "desc"] as const satisfies readonly SortDirection[];

/**
 * The levels the practice profile can open over the page, outermost first: a practice group, a
 * practice over its group, and one list — every practice group — that a group opened from a row
 * stacks on, so the list is what a dismissal returns to. The schema keeps every entry of these
 * kinds and drops the rest. A practice may open with no group beneath it — one the workspace files
 * in no group, or a hand-typed URL — and its path then names no group.
 *
 * The two run levels are the other pair: every review of the reader's work, opened from the
 * header's chip, and one run over it. A run may open on its own from a shared link, and its path
 * then names no list.
 */
export const PRACTICE_PROFILE_LEVEL_KINDS = [
	"practice-group",
	"practice",
	"practice-groups",
	"review-runs",
	"review-run",
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

/** What the level with every review run is called, wherever it is named. */
export const REVIEWS_OF_YOUR_WORK = "Reviews of your work";

/**
 * Every review run on the reader's work as a level; there is one, so the id names the list rather
 * than a row.
 */
export const REVIEW_RUNS_LEVEL: DetailStackEntry<PracticeProfileDetailLevelKind> = {
	kind: "review-runs",
	id: "all",
};

/** One review run as the level over the list, addressed by its review id. */
export function reviewRunLevel(reviewId: string): DetailStackEntry<PracticeProfileDetailLevelKind> {
	return { kind: "review-run", id: reviewId };
}

/**
 * The run level's tabs: every practice this workspace runs against this kind of work, and the
 * occurrences recorded about it. The choice is the `runTab` search param.
 */
export const RUN_TABS = ["practices", "noticed"] as const;

export type RunTab = (typeof RUN_TABS)[number];

export const DEFAULT_RUN_TAB: RunTab = "practices";

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

/**
 * The selection inside an open level: the practice level's tab, the runs list's two filters, and
 * the run level's tab with the three filters over its practice table. Every one of them means
 * nothing outside the level that shows it, so every one is cleared when the level closes.
 */
export interface PracticeGroupDetailSelection {
	practiceTab?: PracticeTab;
	runKind?: string;
	runSince?: RunTimeframe;
	runTab?: RunTab;
	runGroup?: string;
	runPractice?: string;
	runWatches?: string;
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
	// A free string rather than an enum, for the reason `trace-search.ts` gives: the server derives
	// the kinds from whichever integrations are registered, and narrowing here would quietly ignore
	// a reader's filter instead of answering it.
	runKind: z.string().min(1).max(120).optional().catch(undefined),
	// No timeframe is every run, which is the default, so the param is absent rather than spelling it.
	runSince: z.enum(RUN_TIMEFRAMES).optional().catch(undefined),
	runTab: z.enum(RUN_TABS).default(DEFAULT_RUN_TAB).catch(DEFAULT_RUN_TAB),
	runGroup: z.string().min(1).max(120).optional().catch(undefined),
	runPractice: z.string().min(1).max(120).optional().catch(undefined),
	// A signal name, free for the reason `runKind` is: which occurrences a practice may watch comes
	// from the integrations the instance registered, not from anything this build can enumerate.
	runWatches: z.string().min(1).max(120).optional().catch(undefined),
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
 * The params that belong to a level and leave the URL with it: the practice level's tab, the runs
 * list's filters, and the run level's tab and filters. Going back restores the entry before the
 * level was pushed, which never held them, so a forward write must clear them or the next level
 * opened inherits a filter nobody set.
 */
export const PRACTICE_PROFILE_LEVEL_PARAMS = [
	"practiceTab",
	"runKind",
	"runSince",
	"runTab",
	"runGroup",
	"runPractice",
	"runWatches",
] as const satisfies readonly (keyof PracticeGroupDetailSelection)[];
