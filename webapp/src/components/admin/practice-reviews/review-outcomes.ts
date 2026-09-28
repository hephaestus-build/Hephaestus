import type {
	ReviewFeedbackCounts,
	ReviewObservationCounts,
	ReviewRunCounts,
} from "@/api/types.gen";
import { type StatusDef, statusValues } from "@/components/common/status-def";
import { ASSESSMENT_STATUS_DEFS } from "@/components/practice-vocabulary/assessment-status-defs";
import {
	DELIVERY_STATE_DEFS,
	type DeliveryState,
} from "@/components/practice-vocabulary/delivery-outcome-defs";
import { MARKED_INCORRECT_DEF } from "@/components/practice-vocabulary/observation-invalidation-defs";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import {
	REVIEW_STATUS_DEFS,
	type ReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";
import { toDayParam } from "@/lib/date-range-search";

import type { FeedbackSearch, ObservationsSearch, RunsSearch } from "./review-search";

/** A list tab, filtered: where a count opens. */
export type ReviewListTarget =
	| { list: "runs"; search: Partial<RunsSearch> }
	| { list: "observations"; search: Partial<ObservationsSearch> }
	| { list: "feedback"; search: Partial<FeedbackSearch> };

/**
 * One count, in the words, icon and colour of the registry that owns its value, and the list
 * filtered to exactly the rows it counts: the server counts by the same predicate the list filters on.
 */
export interface OutcomeSlot {
	key: string;
	def: StatusDef;
	/** The count's noun for this count: "strength", "strengths", "delivered". */
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
}

const lower = (def: StatusDef) => def.label.toLowerCase();

const REVIEW_COUNT: Record<ReviewStatus, keyof ReviewRunCounts> = {
	QUEUED: "queued",
	RUNNING: "running",
	COMPLETED: "completed",
	FAILED: "failed",
	TIMED_OUT: "timedOut",
	CANCELLED: "cancelled",
};

export function reviewSlots(counts: ReviewRunCounts, scope?: OutcomeScope): OutcomeSlot[] {
	return statusValues(REVIEW_STATUS_DEFS).map((status) => ({
		key: status,
		def: REVIEW_STATUS_DEFS[status],
		label: lower(REVIEW_STATUS_DEFS[status]),
		count: counts[REVIEW_COUNT[status]],
		target: scope && { list: "runs", search: { from: scope.from, to: scope.to, status: [status] } },
	}));
}

/**
 * The four results that partition observations: the two outcomes of an assessment, then the two
 * ways of not reaching one. The outcomes are nominalised because a count needs a noun.
 */
export function observationSlots(
	counts: ReviewObservationCounts,
	scope?: OutcomeScope,
): OutcomeSlot[] {
	const search = scope && observationsSearch(scope);
	return [
		{
			key: "strengths",
			def: OUTCOME_DEFS.POSITIVE,
			label: counts.strengths === 1 ? "strength" : "strengths",
			count: counts.strengths,
			target: search && { list: "observations", search: { ...search, outcome: ["POSITIVE"] } },
		},
		{
			key: "problems",
			def: OUTCOME_DEFS.NEGATIVE,
			label: counts.problems === 1 ? "improvement" : "improvements",
			count: counts.problems,
			target: search && { list: "observations", search: { ...search, outcome: ["NEGATIVE"] } },
		},
		...(["NOT_APPLICABLE", "UNDETERMINED"] as const).map((status) => ({
			key: status,
			def: ASSESSMENT_STATUS_DEFS[status],
			label: lower(ASSESSMENT_STATUS_DEFS[status]),
			count: status === "NOT_APPLICABLE" ? counts.notApplicable : counts.undetermined,
			target: search && {
				list: "observations" as const,
				search: { ...search, assessmentStatus: [status] },
			},
		})),
	];
}

/**
 * Observations an admin marked incorrect. They overlap the partition — an incorrect strength is
 * still a strength — so this count is drawn beside the mix, never inside it.
 */
export function markedIncorrectSlot(count: number, scope?: OutcomeScope): OutcomeSlot {
	return {
		key: "markedIncorrect",
		def: MARKED_INCORRECT_DEF,
		label: lower(MARKED_INCORRECT_DEF),
		count,
		target: scope && {
			list: "observations",
			search: { ...observationsSearch(scope), invalidated: true },
		},
	};
}

const FEEDBACK_COUNT: Record<DeliveryState, keyof ReviewFeedbackCounts> = {
	AWAITING_APPROVAL: "awaitingApproval",
	DELIVERED: "delivered",
	PREPARED: "prepared",
	PARTIALLY_DELIVERED: "partiallyDelivered",
	PARTIALLY_FAILED: "partiallyFailed",
	SUPERSEDED: "superseded",
	SUPPRESSED: "suppressed",
	FAILED: "failed",
	DISCARDED: "discarded",
	UNCONFIRMED: "unconfirmed",
};

/** Feedback by delivery state. Feedback has no practice filter, so a practice's counts open nothing. */
export function feedbackSlots(counts: ReviewFeedbackCounts, scope?: OutcomeScope): OutcomeSlot[] {
	return statusValues(DELIVERY_STATE_DEFS).map((state) => ({
		key: state,
		def: DELIVERY_STATE_DEFS[state],
		label: lower(DELIVERY_STATE_DEFS[state]),
		count: counts[FEEDBACK_COUNT[state]],
		target:
			scope !== undefined && scope.practiceSlug === undefined
				? { list: "feedback", search: { from: scope.from, to: scope.to, deliveryState: [state] } }
				: undefined,
	}));
}

export function slotsTotal(slots: readonly OutcomeSlot[]): number {
	return slots.reduce((sum, slot) => sum + slot.count, 0);
}

/** The days from `from` through the day of `nowMs`, as the lists' day params. */
export function rangeScope(from: Date, nowMs: number): OutcomeScope {
	return { from: toDayParam(from), to: toDayParam(new Date(nowMs)) };
}

function observationsSearch(scope: OutcomeScope): Partial<ObservationsSearch> {
	return {
		from: scope.from,
		to: scope.to,
		practiceSlug: scope.practiceSlug === undefined ? undefined : [scope.practiceSlug],
	};
}
