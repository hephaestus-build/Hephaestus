import type { ReviewFeedbackCounts, ReviewResultCounts, ReviewStatusCounts } from "@/api/types.gen";
import { type StatusDef, statusValues } from "@/components/common/status-def";
import {
	DELIVERY_FAMILY_DEFS,
	DELIVERY_FAMILY_STATES,
	type DeliveryFamily,
} from "@/components/practice-vocabulary/delivery-family-defs";
import {
	DELIVERY_STATE_DEFS,
	type DeliveryState,
} from "@/components/practice-vocabulary/delivery-state-defs";
import { OUTCOME_DEFS, outcomeCountNoun } from "@/components/practice-vocabulary/outcome-defs";
import { RESULT_MARK_DEFS } from "@/components/practice-vocabulary/result-mark-defs";
import {
	REVIEW_STATUS_DEFS,
	type ReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";
import { toDayParam } from "@/lib/date-range-search";

import type { FeedbackSearch, ProblemsSearch, ResultsSearch, ReviewsSearch } from "./review-search";

/** A list tab, filtered: where a count opens. */
export type ReviewListTarget =
	| { list: "reviews"; search: Partial<ReviewsSearch> }
	| { list: "results"; search: Partial<ResultsSearch> }
	| { list: "problems"; search: Partial<ProblemsSearch> }
	| { list: "feedback"; search: Partial<FeedbackSearch> };

/**
 * One count, in the words, icon and colour of the registry that owns its value, and the list
 * filtered to exactly the rows it counts: the server counts by the same predicate the list filters on.
 */
export interface OutcomeSlot {
	key: string;
	def: StatusDef;
	label: string;
	count: number;
	target?: ReviewListTarget;
}

/**
 * The days a count covers, as the lists' `from`/`to` params, and the practice it is about. A count
 * built without one names no list, and reads as words only.
 */
export interface OutcomeScope {
	from?: string;
	to?: string;
	practiceSlug?: string;
	/** The developer feedback is addressed to. Only feedback counts carry it. */
	developerId?: string;
}

/**
 * What each split is called as a legend's accessible name, wherever it is drawn — a stage's tile, a
 * practice's level — so a screen reader meets one name for one split.
 */
export const OUTCOME_LEGEND_LABELS = {
	reviews: "Reviews by outcome",
	results: "Observations by outcome",
	checkedResults: "Observations checked by an admin",
	/** The reviews of the range without an outcome yet, listed apart from the outcomes. */
	openReviews: "Reviews not finished yet",
	feedback: "Feedback by delivery",
} as const;

const lower = (def: StatusDef) => def.label.toLowerCase();

const REVIEW_COUNT: Record<ReviewStatus, keyof ReviewStatusCounts> = {
	QUEUED: "queued",
	RUNNING: "running",
	COMPLETE: "complete",
	PARTIAL: "partial",
	FAILED: "failed",
	CANCELLED: "cancelled",
};

/** A slot built with a scope, which always names the list it opens. */
type ScopedOutcomeSlot = OutcomeSlot & { target: ReviewListTarget };

export function reviewSlots(counts: ReviewStatusCounts, scope: OutcomeScope): ScopedOutcomeSlot[];
export function reviewSlots(counts: ReviewStatusCounts, scope?: OutcomeScope): OutcomeSlot[];
export function reviewSlots(counts: ReviewStatusCounts, scope?: OutcomeScope): OutcomeSlot[] {
	return statusValues(REVIEW_STATUS_DEFS).map((status) => ({
		key: status,
		def: REVIEW_STATUS_DEFS[status],
		label: lower(REVIEW_STATUS_DEFS[status]),
		count: counts[REVIEW_COUNT[status]],
		target: scope && {
			list: "reviews",
			search: { ...scopeSearch(scope, "reviews"), status: [status] },
		},
	}));
}

// Cancelled reviews ended without finishing; exclude them from finished-review and failure-share counts.
export const FINISHED_REVIEW_STATUSES = [
	"COMPLETE",
	"PARTIAL",
	"FAILED",
] as const satisfies readonly ReviewStatus[];

/** The statuses a review ends in, which is what a count of reviews in a range splits into. */
export const ENDED_REVIEW_STATUSES = [
	...FINISHED_REVIEW_STATUSES,
	"CANCELLED",
] as const satisfies readonly ReviewStatus[];

/** The reviews that finished, by `FINISHED_REVIEW_STATUSES`. */
export function finishedCount(counts: ReviewStatusCounts): number {
	return counts.complete + counts.partial + counts.failed;
}

export function resultSlots(counts: ReviewResultCounts, scope?: OutcomeScope): OutcomeSlot[] {
	const search = scope && scopeSearch(scope, "results");
	return [
		{
			key: "met",
			def: OUTCOME_DEFS.MET,
			label: outcomeCountNoun("MET", counts.met),
			count: counts.met,
			target: search && { list: "results", search: { ...search, outcome: ["MET"] } },
		},
		{
			key: "notMet",
			def: OUTCOME_DEFS.NOT_MET,
			label: outcomeCountNoun("NOT_MET", counts.notMet),
			count: counts.notMet,
			target: search && { list: "results", search: { ...search, outcome: ["NOT_MET"] } },
		},
		...(["DOES_NOT_APPLY", "COULD_NOT_DETERMINE"] as const).map((status) => ({
			key: status,
			def: OUTCOME_DEFS[status],
			label: lower(OUTCOME_DEFS[status]),
			count: status === "DOES_NOT_APPLY" ? counts.doesNotApply : counts.couldNotDetermine,
			target: search && {
				list: "results" as const,
				search: { ...search, outcome: [status] },
			},
		})),
	];
}

export function markedIncorrectSlot(count: number, scope?: OutcomeScope): OutcomeSlot {
	return {
		key: "markedIncorrect",
		def: RESULT_MARK_DEFS.INCORRECT,
		label: lower(RESULT_MARK_DEFS.INCORRECT),
		count,
		target: scope && {
			list: "results",
			search: { ...scopeSearch(scope, "results"), markedIncorrect: true },
		},
	};
}

const FEEDBACK_COUNT: Record<DeliveryState, keyof ReviewFeedbackCounts> = {
	AWAITING_APPROVAL: "awaitingApproval",
	READY: "ready",
	DELIVERED: "delivered",
	UNCONFIRMED: "unconfirmed",
	WITHHELD: "withheld",
	REJECTED: "rejected",
	REPLACED: "replaced",
	FAILED: "failed",
};

/** How many pieces of feedback are in one delivery family. */
export function familyCount(counts: ReviewFeedbackCounts, family: DeliveryFamily): number {
	return DELIVERY_FAMILY_STATES[family].reduce(
		(sum: number, state: DeliveryState) => sum + counts[FEEDBACK_COUNT[state]],
		0,
	);
}

/**
 * Feedback by delivery family, each opening the Feedback list filtered to exactly the states it
 * counts — and, for a practice's or a developer's counts, to the feedback citing that practice or
 * addressed to that developer.
 */
export function feedbackSlots(counts: ReviewFeedbackCounts, scope?: OutcomeScope): OutcomeSlot[] {
	return statusValues(DELIVERY_FAMILY_DEFS).map((family) => ({
		key: family,
		def: DELIVERY_FAMILY_DEFS[family],
		label: lower(DELIVERY_FAMILY_DEFS[family]),
		count: familyCount(counts, family),
		target: scope && {
			list: "feedback",
			search: {
				...scopeSearch(scope, "feedback"),
				state: [...DELIVERY_FAMILY_STATES[family]],
			},
		},
	}));
}

export function overviewFeedbackSlots(
	counts: ReviewFeedbackCounts,
	scope?: OutcomeScope,
): OutcomeSlot[] {
	const search = (state: DeliveryState[]): ReviewListTarget | undefined =>
		scope && {
			list: "feedback",
			search: { ...scopeSearch(scope, "feedback"), state },
		};
	const slots = feedbackSlots(counts, scope).map((slot): OutcomeSlot => {
		if (slot.key !== "WITHHELD") {
			return slot;
		}
		return {
			...slot,
			def: { ...slot.def, badgeVariant: "outline" },
			count: counts.withheld + counts.rejected,
			target: search(["WITHHELD", "REJECTED"]),
		};
	});
	return [
		...slots,
		{
			key: "REPLACED",
			def: DELIVERY_STATE_DEFS.REPLACED,
			label: lower(DELIVERY_STATE_DEFS.REPLACED),
			count: counts.replaced,
			target: search(["REPLACED"]),
		},
	];
}

export function slotsTotal(slots: readonly OutcomeSlot[]): number {
	return slots.reduce((sum, slot) => sum + slot.count, 0);
}

/** The days from `from` through the day of `nowMs`, as the lists' day params. */
export function rangeScope(from: Date, nowMs: number): OutcomeScope {
	return { from: toDayParam(from), to: toDayParam(new Date(nowMs)) };
}

export function scopeSearch(
	scope: OutcomeScope,
	list: ReviewListTarget["list"],
): Pick<FeedbackSearch, "from" | "to" | "practiceSlug" | "developerId"> {
	return {
		from: scope.from,
		to: scope.to,
		practiceSlug: scope.practiceSlug === undefined ? undefined : [scope.practiceSlug],
		developerId: list === "feedback" ? scope.developerId : undefined,
	};
}
