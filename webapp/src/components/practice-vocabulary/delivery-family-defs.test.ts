import { assert, describe, expect, it } from "vitest";

import {
	feedbackSlots,
	type ReviewListTarget,
	slotsTotal,
} from "@/components/admin/practice-reviews/review-outcomes";
import { statusValues } from "@/components/common/status-def";

import { DELIVERY_FAMILY_DEFS, DELIVERY_FAMILY_STATES } from "./delivery-family-defs";
import { DELIVERY_STATE_DEFS, type DeliveryState } from "./delivery-outcome-defs";

/** The delivery states a count's link filters the Feedback list to. */
function linkedStates(target: ReviewListTarget | undefined): readonly DeliveryState[] {
	assert(target?.list === "feedback", "A family's count opens the Feedback list");
	return target.search.deliveryState ?? [];
}

describe("delivery families", () => {
	it("partitions the delivery states: each is in exactly one family", () => {
		const grouped = Object.values(DELIVERY_FAMILY_STATES).flat();
		expect([...grouped].sort()).toStrictEqual([...statusValues(DELIVERY_STATE_DEFS)].sort());
	});

	it("gives every family its own icon", () => {
		const icons = statusValues(DELIVERY_FAMILY_DEFS).map(
			(family) => DELIVERY_FAMILY_DEFS[family].icon,
		);
		expect(new Set(icons).size).toBe(icons.length);
	});

	it("names each family in its first state's words, so a count reads like the rows it opens", () => {
		expect(
			statusValues(DELIVERY_FAMILY_DEFS).map((family) => DELIVERY_FAMILY_DEFS[family].label),
		).toStrictEqual([
			"Awaiting approval",
			"Prepared",
			"Delivered",
			"Unconfirmed",
			"Withheld",
			"Failed to deliver",
		]);
	});

	/**
	 * Each state counts a different power of two, so a family's count names exactly the states it
	 * added up — and its link must filter to exactly those.
	 */
	it("counts every state once, and opens each family on exactly the states it counted", () => {
		const weight = {
			AWAITING_APPROVAL: 1,
			PREPARED: 2,
			DELIVERED: 4,
			PARTIALLY_DELIVERED: 8,
			UNCONFIRMED: 16,
			SUPPRESSED: 32,
			DISCARDED: 64,
			SUPERSEDED: 128,
			FAILED: 256,
			PARTIALLY_FAILED: 512,
		} satisfies Record<DeliveryState, number>;
		const slots = feedbackSlots(
			{
				awaitingApproval: weight.AWAITING_APPROVAL,
				prepared: weight.PREPARED,
				delivered: weight.DELIVERED,
				partiallyDelivered: weight.PARTIALLY_DELIVERED,
				unconfirmed: weight.UNCONFIRMED,
				suppressed: weight.SUPPRESSED,
				discarded: weight.DISCARDED,
				superseded: weight.SUPERSEDED,
				failed: weight.FAILED,
				partiallyFailed: weight.PARTIALLY_FAILED,
			},
			{ from: "2026-09-01", to: "2026-09-30" },
		);

		expect(slotsTotal(slots)).toBe(1023);
		expect(slots.map(({ key, count }) => [key, count])).toStrictEqual(
			slots.map(({ key, target }) => [
				key,
				linkedStates(target).reduce((sum, state) => sum + weight[state], 0),
			]),
		);
	});
});
