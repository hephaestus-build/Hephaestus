import { z } from "zod";

import { startOfDay, subDays } from "date-fns";
import type { FilterOption } from "@/components/common/FilterToggle";
import { statusValues } from "@/components/common/status-def";
import {
	type DetailStackEntry,
	detailStackSchema,
} from "@/components/layout/detail-drawer/detail-stack";
import { REVIEW_FILTER_MAX_LENGTH } from "@/components/practice-trace/trace-format";
import { type Outcome, OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	DEFAULT_PRACTICE_GROUP_SORT,
	type SortDirection,
} from "@/components/practice-vocabulary/practice-group-list-order";
import {
	OWN_REVIEW_STATUS_DEFS,
	type ReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";
import { multiValue, narrowToEnum, nonEmpty } from "@/lib/search-params";

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

/** Clear review-specific tabs, filters, and focus on a review switch so old filters cannot hide the next review. */
export const REVIEW_SELECTION_CLEARED = {
	reviewGroup: undefined,
	reviewPractice: undefined,
	reviewOutcome: undefined,
} satisfies PracticeGroupDetailSelection;

/** The orders the reviews list offers besides its default, newest first. */
export const REVIEW_ORDERS = ["OLDEST"] as const;

export type ReviewOrder = (typeof REVIEW_ORDERS)[number];

/** How far back the reviews list reaches; absent is every review. */
export const REVIEW_TIMEFRAMES = ["7d", "30d", "90d"] as const;

export const REVIEW_TIMEFRAME_DEFS = {
	"7d": { days: 7, label: "Last 7 days" },
	"30d": { days: 30, label: "Last 30 days" },
	"90d": { days: 90, label: "Last 90 days" },
} as const;

export function reviewRangeStart(now: number, range: ReviewTimeframe): Date {
	return subDays(startOfDay(now), REVIEW_TIMEFRAME_DEFS[range].days - 1);
}

export type ReviewTimeframe = (typeof REVIEW_TIMEFRAMES)[number];

/** The timeframe's choice that narrows nothing; it never reaches the address, where every one is the default. */
export const ALL_TIME = "all";

/**
 * "Last 7 days | Last 30 days | Last 90 days | All time": one segmented timeframe for Reviews of
 * your work and a practice's Observations tab.
 */
export const REVIEW_TIMEFRAME_OPTIONS: readonly FilterOption<ReviewTimeframe | typeof ALL_TIME>[] =
	[
		...REVIEW_TIMEFRAMES.map((value) => ({ value, label: REVIEW_TIMEFRAME_DEFS[value].label })),
		{ value: ALL_TIME, label: "All time" },
	];

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

export const PRACTICE_TABS = ["feedback", "results", "guide"] as const;

export type PracticeTab = (typeof PRACTICE_TABS)[number];

export const DEFAULT_PRACTICE_TAB: PracticeTab = "feedback";

/** The selection inside an open level; each is cleared when its level closes. */
export interface PracticeGroupDetailSelection {
	practiceTab?: PracticeTab;
	/**
	 * The observation a link asked for, read on its own so it shows whatever review pages have
	 * loaded. It opens alone; see `observation-filter.ts`.
	 */
	observation?: string;
	/** The Observations tab's text filter: words, work (such as "#15") and problems. */
	observationQuery?: string;
	/** The Observations tab's outcome filter. */
	observationOutcome?: Outcome[];
	/** How far back the Observations tab reaches; absent is every observation. */
	observationSince?: ReviewTimeframe;
	/** The feedback card a link asked for on the Feedback tab, by its feedback id: shown and marked. */
	feedbackCard?: string;
	/** The work an observation link narrowed the Observations tab to, by its id. */
	observationWork?: string;
	/** The reviews list's kinds of work. */
	reviewKind?: string[];
	reviewSince?: ReviewTimeframe;
	/** The reviews list's statuses. */
	reviewStatus?: ReviewStatus[];
	/** The reviews list's order: absent is newest first. */
	reviewOrder?: ReviewOrder;
	/** The work the reviews list is narrowed to, by its id: a row's Track sets it. */
	reviewItem?: string;
	reviewGroup?: string;
	reviewPractice?: string;
	/** The outcomes a review's table is narrowed to. */
	reviewOutcome?: Outcome[];
}

/**
 * Which feedback cards the page lists, chosen by the tab row over them. The choice is the
 * `feedback` search param; an older link's `newest`, a tab that is gone, reads as the default.
 */
export const FEEDBACK_TABS = ["open", "resolved", "all"] as const;

export type FeedbackTab = (typeof FEEDBACK_TABS)[number];

export const DEFAULT_FEEDBACK_TAB: FeedbackTab = "open";

/**
 * A work's id in the address: a positive 64-bit number, kept as text. The router reads a numeric
 * value from the address as a number, so it is coerced back.
 */
const itemIdSchema = z.coerce.string().regex(/^[1-9]\d{0,18}$/u);

/**
 * The statuses Reviews of your work can hold: a review is listed only once it records something
 * about the reader, so a waiting review is never in it, and a link that asks for one asks for none.
 */
export const LISTED_REVIEW_STATUSES: readonly ReviewStatus[] = statusValues(
	OWN_REVIEW_STATUS_DEFS,
).filter((status) => status !== "QUEUED");

const practiceProfileFilterSchema = z.object({
	dir: z
		.enum(SORT_DIRECTIONS)
		.default(DEFAULT_PRACTICE_GROUP_SORT)
		.catch(DEFAULT_PRACTICE_GROUP_SORT),
	feedback: z.enum(FEEDBACK_TABS).default(DEFAULT_FEEDBACK_TAB).catch(DEFAULT_FEEDBACK_TAB),
	practiceTab: z.enum(PRACTICE_TABS).default(DEFAULT_PRACTICE_TAB).catch(DEFAULT_PRACTICE_TAB),
	observation: z.uuid().optional().catch(undefined),
	observationQuery: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	observationOutcome: multiValue.transform((values) =>
		narrowToEnum(values, statusValues(OUTCOME_DEFS)),
	),
	observationSince: z.enum(REVIEW_TIMEFRAMES).optional().catch(undefined),
	feedbackCard: z.uuid().optional().catch(undefined),
	observationWork: itemIdSchema.optional().catch(undefined),
	// Free strings, for the reason `TraceKindFilter` gives; a link from before named one kind.
	reviewKind: multiValue.transform((values) =>
		nonEmpty(
			(values ?? []).filter((kind) => kind.length > 0 && kind.length <= REVIEW_FILTER_MAX_LENGTH),
		),
	),
	reviewSince: z.enum(REVIEW_TIMEFRAMES).optional().catch(undefined),
	reviewStatus: multiValue.transform((values) => narrowToEnum(values, LISTED_REVIEW_STATUSES)),
	reviewOrder: z.enum(REVIEW_ORDERS).optional().catch(undefined),
	reviewItem: itemIdSchema.optional().catch(undefined),
	reviewGroup: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	reviewPractice: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	reviewOutcome: multiValue.transform((values) => narrowToEnum(values, statusValues(OUTCOME_DEFS))),
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
	"observation",
	"observationQuery",
	"observationOutcome",
	"observationSince",
	"feedbackCard",
	"observationWork",
	"reviewKind",
	"reviewSince",
	"reviewStatus",
	"reviewOrder",
	"reviewItem",
	"reviewGroup",
	"reviewPractice",
	"reviewOutcome",
] as const satisfies readonly (keyof PracticeGroupDetailSelection)[];
