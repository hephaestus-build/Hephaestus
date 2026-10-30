import type { ReasonCount, ReviewFeedbackCounts } from "@/api/types.gen";
import { type StatusDef, statusValues } from "@/components/common/status-def";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_LOCATION_DEFS } from "@/components/practice-vocabulary/feedback-location-defs";
import {
	WITHHOLDING_FAMILY_DEFS,
	WITHHOLDING_REASON_DEFS,
	type WithholdingReason,
	withholdingFamily,
} from "@/components/practice-vocabulary/withholding-defs";

import { type OutcomeScope, type ReviewListTarget, scopeSearch } from "./review-outcomes";

/**
 * The delivery states the Feedback tile counts as delivered. Feedback of which only some copies
 * posted is failed or unconfirmed, so the table counts it in its own Partly delivered row.
 */
export const DELIVERED_STATES = ["DELIVERED"] as const;

/** The delivered feedback by where it appeared, and the partly delivered feedback of the range. */
export interface DeliveredCounts {
	byLocation: readonly ReasonCount[];
	partlyDelivered: number;
}

export interface BreakdownRow {
	key: string;
	def: StatusDef;
	count: number;
	target?: ReviewListTarget;
}

function isWithholdingReason(reason: string): reason is WithholdingReason {
	return Object.hasOwn(WITHHOLDING_REASON_DEFS, reason);
}

export function deliveredByLocation(counts: DeliveredCounts, scope: OutcomeScope): BreakdownRow[] {
	const located = statusValues(FEEDBACK_LOCATION_DEFS).flatMap((location): BreakdownRow[] => {
		const count = counts.byLocation.find((entry) => entry.reason === location)?.count ?? 0;
		return count === 0
			? []
			: [
					{
						key: location,
						def: FEEDBACK_LOCATION_DEFS[location],
						count,
						target: {
							list: "feedback",
							search: {
								...scopeSearch(scope, "feedback"),
								state: [...DELIVERED_STATES],
								location,
							},
						},
					},
				];
	});
	if (counts.partlyDelivered === 0) {
		return located;
	}
	return [
		...located,
		{
			key: "PARTLY_DELIVERED",
			def: {
				...DELIVERY_STATE_DEFS.UNCONFIRMED,
				label: "Partly delivered",
				description:
					"Part of this feedback appeared on the work. Another part failed or is not confirmed yet.",
			},
			count: counts.partlyDelivered,
			target: {
				list: "feedback",
				search: { ...scopeSearch(scope, "feedback"), partlyDelivered: true },
			},
		},
	];
}

export function withheldByReason(
	reasons: readonly ReasonCount[],
	feedback: Pick<ReviewFeedbackCounts, "rejected">,
	scope: OutcomeScope,
): BreakdownRow[] {
	const range = scopeSearch(scope, "feedback");
	const families = statusValues(WITHHOLDING_FAMILY_DEFS).map((family): BreakdownRow => ({
		key: family,
		def: WITHHOLDING_FAMILY_DEFS[family],
		count: reasons
			.filter(
				(entry) => isWithholdingReason(entry.reason) && withholdingFamily(entry.reason) === family,
			)
			.reduce((sum, entry) => sum + entry.count, 0),
		target: {
			list: "feedback",
			search: { ...range, state: ["WITHHELD"], withheldFamily: [family] },
		},
	}));
	const rows: BreakdownRow[] = [
		...families,
		{
			key: "REJECTED",
			def: DELIVERY_STATE_DEFS.REJECTED,
			count: feedback.rejected,
			target: { list: "feedback", search: { ...range, state: ["REJECTED"] } },
		},
		{
			key: "UNKNOWN",
			def: { ...DELIVERY_STATE_DEFS.WITHHELD, label: "Reason not recorded" },
			// From the reasons read itself, so it never disagrees with the reason rows above it.
			count: reasons
				.filter((entry) => !isWithholdingReason(entry.reason))
				.reduce((sum, entry) => sum + entry.count, 0),
		},
	];
	return rows.filter((row) => row.count > 0);
}
