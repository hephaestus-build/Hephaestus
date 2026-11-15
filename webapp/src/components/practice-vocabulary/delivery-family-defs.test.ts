import { assert, describe, expect, it } from "vitest";

import {
	feedbackSlots,
	type ReviewListTarget,
	slotsTotal,
} from "@/components/admin/practice-reviews/review-outcomes";
import { definitionFor, statusValues } from "@/components/common/status-def";

import { DELIVERY_FAMILY_DEFS, DELIVERY_FAMILY_STATES } from "./delivery-family-defs";
import {
	DELIVERY_STATE_DEFS,
	type DeliveryState,
	type KnownDeliveryState,
} from "./delivery-state-defs";

/** The delivery states a count's link filters the Feedback list to. */
function linkedStates(target: ReviewListTarget | undefined): readonly DeliveryState[] {
	assert(target?.list === "feedback", "A family's count opens the Feedback list");
	return target.search.state ?? [];
}

describe("delivery families", () => {
	it("partitions the delivery states: each is in exactly one family", () => {
		const grouped = Object.values(DELIVERY_FAMILY_STATES).flat();
		expect([...grouped].sort()).toStrictEqual([...statusValues(DELIVERY_STATE_DEFS)].sort());
	});

	// A count holds unconfirmed feedback from every destination, so its words name none of them.
	it("describes unconfirmed feedback without naming a destination", () => {
		expect(DELIVERY_FAMILY_DEFS.UNCONFIRMED.description).not.toMatch(/conversation/iu);
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
			"Ready",
			"Delivered",
			"Delivery unconfirmed",
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
			READY: 2,
			DELIVERED: 4,
			UNCONFIRMED: 8,
			WITHHELD: 16,
			REJECTED: 32,
			REPLACED: 64,
			FAILED: 128,
		} satisfies Record<KnownDeliveryState, number>;
		const slots = feedbackSlots(
			{
				awaitingApproval: weight.AWAITING_APPROVAL,
				ready: weight.READY,
				delivered: weight.DELIVERED,
				unconfirmed: weight.UNCONFIRMED,
				withheld: weight.WITHHELD,
				rejected: weight.REJECTED,
				replaced: weight.REPLACED,
				failed: weight.FAILED,
			},
			{ from: "2026-09-01", to: "2026-09-30" },
		);

		expect(slotsTotal(slots)).toBe(255);
		expect(slots.map(({ key, count }) => [key, count])).toStrictEqual(
			slots.map(({ key, target }) => [
				key,
				linkedStates(target).reduce((sum, state) => sum + definitionFor(weight, state, 0), 0),
			]),
		);
	});
});
