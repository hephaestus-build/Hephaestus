import type {
	ReviewFeedbackCounts,
	ReviewObservationCounts,
	ReviewRunCounts,
} from "@/api/types.gen";
import { type StatusDef, statusValues } from "@/components/common/status-def";
import { ASSESSMENT_STATUS_DEFS } from "@/components/practice-vocabulary/assessment-status-defs";
import {
	DELIVERY_FAMILY_DEFS,
	DELIVERY_FAMILY_STATES,
	type DeliveryFamily,
} from "@/components/practice-vocabulary/delivery-family-defs";
import type { DeliveryState } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { MARKED_INCORRECT_DEF } from "@/components/practice-vocabulary/observation-invalidation-defs";
import { OUTCOME_DEFS, outcomeCountNoun } from "@/components/practice-vocabulary/outcome-defs";
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
	/** The count's noun for this count: "positive outcome", "negative outcomes", "delivered". */
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

/**
 * What each split is called as a legend's accessible name, wherever it is drawn — a stage's tile, a
 * practice's level — so a screen reader meets one name for one split.
 */
export const OUTCOME_LEGEND_LABELS = {
	reviews: "Reviews by outcome",
	observations: "Observations by outcome",
	/** The counts that overlap the observations' outcomes, listed apart from them. */
	checkedObservations: "Observations checked by an admin",
	feedback: "Feedback by delivery",
} as const;

const lower = (def: StatusDef) => def.label.toLowerCase();

const REVIEW_COUNT: Record<ReviewStatus, keyof ReviewRunCounts> = {
	QUEUED: "queued",
	RUNNING: "running",
	COMPLETED: "completed",
	FAILED: "failed",
	TIMED_OUT: "timedOut",
	CANCELLED: "cancelled",
};

/** A slot built with a scope, which always names the list it opens. */
type ScopedOutcomeSlot = OutcomeSlot & { target: ReviewListTarget };

export function reviewSlots(counts: ReviewRunCounts, scope: OutcomeScope): ScopedOutcomeSlot[];
export function reviewSlots(counts: ReviewRunCounts, scope?: OutcomeScope): OutcomeSlot[];
export function reviewSlots(counts: ReviewRunCounts, scope?: OutcomeScope): OutcomeSlot[] {
	return statusValues(REVIEW_STATUS_DEFS).map((status) => ({
		key: status,
		def: REVIEW_STATUS_DEFS[status],
		label: lower(REVIEW_STATUS_DEFS[status]),
		count: counts[REVIEW_COUNT[status]],
		target: scope && { list: "runs", search: { from: scope.from, to: scope.to, status: [status] } },
	}));
}

/** The statuses a review ends in, which is what a count of reviews in a range splits into. */
export const FINISHED_REVIEW_STATUSES = [
	"COMPLETED",
	"FAILED",
	"TIMED_OUT",
	"CANCELLED",
] as const satisfies readonly ReviewStatus[];

/**
 * The four results that partition observations: the two outcomes of an assessment, then the two
 * ways of not reaching one, each in its registry's count noun.
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
			label: outcomeCountNoun("POSITIVE", counts.strengths),
			count: counts.strengths,
			target: search && { list: "observations", search: { ...search, outcome: ["POSITIVE"] } },
		},
		{
			key: "problems",
			def: OUTCOME_DEFS.NEGATIVE,
			label: outcomeCountNoun("NEGATIVE", counts.problems),
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
 * Observations an admin marked incorrect. They overlap the partition — an incorrect positive outcome
 * is still a positive outcome — so this count is listed beside the mix, never added into it.
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

/** How many pieces of feedback are in one delivery family. */
export function familyCount(counts: ReviewFeedbackCounts, family: DeliveryFamily): number {
	return DELIVERY_FAMILY_STATES[family].reduce(
		(sum: number, state: DeliveryState) => sum + counts[FEEDBACK_COUNT[state]],
		0,
	);
}

/**
 * Feedback by delivery family, each opening the Feedback list filtered to exactly the states it
 * counts — and, for a practice's counts, to the feedback citing that practice.
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
				from: scope.from,
				to: scope.to,
				practiceSlug: scope.practiceSlug === undefined ? undefined : [scope.practiceSlug],
				deliveryState: [...DELIVERY_FAMILY_STATES[family]],
			},
		},
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
