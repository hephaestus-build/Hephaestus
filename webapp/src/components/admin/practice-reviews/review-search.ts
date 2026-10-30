import { z } from "zod";

import type {
	ListReviewFeedbackData,
	ListReviewProblemsData,
	ListReviewResultsData,
	ListReviewsData,
} from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { REVIEW_FILTER_MAX_LENGTH } from "@/components/practice-trace/trace-format";
import { CURRENTNESS_VALUES } from "@/components/practice-vocabulary/claim-currentness";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import {
	reasonsInFamilies,
	WITHHOLDING_FAMILY_DEFS,
} from "@/components/practice-vocabulary/withholding-defs";
import { dayAfterInstant, dayStartInstant, fromDayParam } from "@/lib/date-range-search";
import { ITEM_KIND_VALUES } from "@/lib/item-kinds";
import { multiValue, narrowToEnum, nonEmpty } from "@/lib/search-params";
import { hasText } from "@/lib/text";

import { QUEUE_STATES } from "./review-queue";
import { type ReviewScope, type ScopeSource, readScope, TIME_ORDERS } from "./review-scope";
import { positiveIdParam, uuidParam } from "./review-selection";

/**
 * Read by the query and by the skeleton that stands in for the results, so a skeleton cannot draw a
 * different number of rows than the first page it replaces.
 */
export const REVIEW_PAGE_SIZE = 25;

/**
 * How often a queued or running review is re-asked for, on every screen that watches one. Applied
 * through TanStack Query's `refetchInterval`, which stops on its own at a terminal status.
 */
export const ACTIVE_REVIEW_POLL_MS = 5000;

export const PRACTICE_REVIEW_READS: ReadonlySet<string> = new Set([
	"getReviewOverview",
	"listReviews",
	"getReview",
	"listReviewResults",
	"getReviewResult",
	"listResultMarks",
	"listReviewProblems",
	"getReviewProblem",
	"listReviewFeedback",
	"getReviewFeedback",
	"listFeedbackDecisions",
	"listReviewFeedbackChecks",
	"getReviewLineage",
	"getReviewQueue",
	"getReviewTiming",
	"countReviewFeedbackByLocation",
	"countReviewFeedbackByReason",
	"listReviewItems",
	"getItemTrace",
]);

/** The results list's orderings: the shared time order, then "Most actionable first", which is its own. */
export const RESULT_SORTS = [...TIME_ORDERS, "ACTIONABILITY"] as const;

export type ResultSort = (typeof RESULT_SORTS)[number];

const day = z.iso.date().optional().catch(undefined);
const flag = z.boolean().optional().catch(undefined);
/**
 * Every allowlist below is a status registry's own key set, so a URL filter and the dropdown that
 * offers it cannot come apart.
 *
 * <p>A parser must not admit a value the control it feeds has no way to display or clear: a
 * hand-typed value the toolbar cannot offer would apply an invisible filter that the next tick of the
 * facet then discards without saying so — a facet emits only the options it was given.
 */
const enumValues = <T extends string>(allowed: readonly T[]) =>
	multiValue.transform((values): T[] | undefined => narrowToEnum(values, allowed));

const scope = {
	developerId: positiveIdParam,
	reviewId: uuidParam,
	resultId: uuidParam,
	problemId: uuidParam,
	feedbackId: uuidParam,
	selectedPractice: z.string().min(1).max(REVIEW_FILTER_MAX_LENGTH).optional().catch(undefined),
	itemKind: z.enum(ITEM_KIND_VALUES).optional().catch(undefined),
	itemId: positiveIdParam,
	// Only `true`: the record above was picked with a crosshair, not filtered by (`review-selection.ts`).
	selected: z.literal(true).optional().catch(undefined),
	from: day,
	to: day,
	groupSlug: multiValue,
	practiceSlug: multiValue,
	// The time order every list shares. The results list widens it with an ordering of its own.
	order: z.enum(TIME_ORDERS).optional().catch(undefined),
};

export function parseScopeSearch(search: unknown): ReviewScope {
	const parsed = z.object(scope).safeParse(search);
	return parsed.success ? readScope(parsed.data) : {};
}

function canonicalDateRange<T extends { from?: string; to?: string }>(search: T): T {
	if (!hasText(search.from) || (hasText(search.to) && search.to < search.from)) {
		return { ...search, to: undefined };
	}
	return search;
}

export const feedbackSearchSchema = z
	.object({
		...scope,
		state: enumValues(statusValues(DELIVERY_STATE_DEFS)),
		destination: enumValues(statusValues(FEEDBACK_DESTINATION_DEFS)),
		withheldFamily: enumValues(statusValues(WITHHOLDING_FAMILY_DEFS)),
		location: z.enum(statusValues(FEEDBACK_LOCATION_DEFS)).optional().catch(undefined),
		withdrawn: flag,
		disagreed: flag,
		partlyDelivered: flag,
	})
	.transform(canonicalDateRange);

export const resultsSearchSchema = z
	.object({
		...scope,
		outcome: enumValues(statusValues(OUTCOME_DEFS)),
		severity: enumValues(statusValues(SEVERITY_DEFS)),
		currentness: z.enum(CURRENTNESS_VALUES).optional().catch(undefined),
		markedIncorrect: flag,
		accepted: flag,
		disagreed: flag,
		// The endpoint's `sort`: the shared time order, or this list's own "Most actionable first". A
		// link to a sibling tab carries only a time order (`carriedScope`).
		order: z.enum(RESULT_SORTS).optional().catch(undefined),
	})
	.transform(canonicalDateRange);

export const problemsSearchSchema = z
	.object({
		...scope,
		severity: enumValues(statusValues(SEVERITY_DEFS)),
		currentness: z.enum(CURRENTNESS_VALUES).optional().catch(undefined),
		markedIncorrect: flag,
		accepted: flag,
		disagreed: flag,
	})
	.transform(canonicalDateRange);

export const reviewsSearchSchema = z
	.object({
		...scope,
		status: enumValues(statusValues(REVIEW_STATUS_DEFS)),
		kind: z.enum(["ITEM", "SCAN"]).optional().catch(undefined),
		// Set by a figure of the queue card; the list shows it as a pill to remove.
		queueState: z.enum(QUEUE_STATES).optional().catch(undefined),
	})
	.transform(canonicalDateRange);

/** The overview applies none of the scope, but keeps it for the next tab. */
export const overviewSearchSchema = z
	.object({
		...scope,
		// The Practices table's own filters, apart from the scope the overview does not apply.
		practicesGroup: multiValue,
		showTurnedOff: z.literal(true).optional().catch(undefined),
		// The default, "Needs a look first", is left out of the URL.
		practicesSort: z.literal("BUSIEST").optional().catch(undefined),
		// Kept as the list before had it; a link to a list carries only a time order (`carriedScope`).
		order: z.enum(RESULT_SORTS).optional().catch(undefined),
	})
	.transform(canonicalDateRange);

export type FeedbackSearch = z.infer<typeof feedbackSearchSchema>;
export type ResultsSearch = z.infer<typeof resultsSearchSchema>;
export type ProblemsSearch = z.infer<typeof problemsSearchSchema>;
export type ReviewsSearch = z.infer<typeof reviewsSearchSchema>;

/**
 * A picked pair of days becomes the half-open instant window the API takes: midnight on `from`, and
 * midnight on the day *after* `to`, so the day the reader picked last is included whole.
 */
export function dateWindowQuery(search: { from?: string; to?: string }) {
	const from = fromDayParam(search.from);
	const to = fromDayParam(search.to);
	return {
		from: from ? dayStartInstant(from) : undefined,
		to: to ? dayAfterInstant(to) : undefined,
	};
}

const numericId = (id: string | undefined) => (id === undefined ? undefined : Number(id));

function oneValue(values: string[] | undefined): string | undefined {
	return values?.length === 1 ? values[0] : undefined;
}

export function onePracticeQuery(search: {
	practiceSlug?: string[];
	selectedPractice?: string;
}): string | undefined {
	return hasText(search.selectedPractice) ? search.selectedPractice : oneValue(search.practiceSlug);
}

function scopeQuery(search: Partial<ScopeSource>) {
	return {
		...dateWindowQuery(search),
		reviewId: search.reviewId,
		practiceSlug: onePracticeQuery(search),
		groupSlug: oneValue(search.groupSlug),
	};
}

export function reviewsQuery(
	search: Partial<ReviewsSearch>,
	size: number,
): NonNullable<ListReviewsData["query"]> {
	return {
		...scopeQuery(search),
		size,
		status: nonEmpty(search.status ?? []),
		kind: search.kind,
		queueState: search.queueState,
		itemId: search.itemId,
		developerId: numericId(search.developerId),
		sort: search.order,
	};
}

export function feedbackQuery(
	search: Partial<FeedbackSearch>,
	size: number,
): NonNullable<ListReviewFeedbackData["query"]> {
	return {
		...scopeQuery(search),
		size,
		itemId: search.itemId,
		itemKind: search.itemKind,
		developerId: search.developerId,
		state: search.state,
		destination: search.destination,
		stopReason:
			search.withheldFamily !== undefined && search.withheldFamily.length > 0
				? reasonsInFamilies(search.withheldFamily)
				: undefined,
		location: search.location,
		withdrawn: search.withdrawn,
		disagreed: search.disagreed,
		partlyDelivered: search.partlyDelivered,
		sort: search.order,
	};
}

export function resultsQuery(
	search: Partial<ResultsSearch>,
	size: number,
): NonNullable<ListReviewResultsData["query"]> {
	return {
		...scopeQuery(search),
		size,
		itemId: search.itemId,
		itemKind: search.itemKind,
		developerId: numericId(search.developerId),
		outcome: search.outcome,
		severity: search.severity,
		currentness: search.currentness,
		markedIncorrect: search.markedIncorrect,
		accepted: search.accepted,
		disagreed: search.disagreed,
		sort: search.order,
	};
}

export function problemsQuery(
	search: Partial<ProblemsSearch>,
	size: number,
): NonNullable<ListReviewProblemsData["query"]> {
	return {
		...scopeQuery(search),
		openOnly: false,
		size,
		resultId: search.resultId,
		itemId: search.itemId,
		itemKind: search.itemKind,
		developerId: numericId(search.developerId),
		severity: search.severity,
		currentness: search.currentness,
		markedIncorrect: search.markedIncorrect,
		accepted: search.accepted,
		disagreed: search.disagreed,
		sort: search.order,
	};
}
