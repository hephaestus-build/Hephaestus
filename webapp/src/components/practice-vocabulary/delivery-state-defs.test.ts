import { describe, expect, it } from "vitest";

import {
	DELIVERY_STATE_DEFS,
	deliveryOutcome,
	isDeliveryInProgress,
	withdrawalMark,
} from "./delivery-state-defs";
import { FEEDBACK_DECISION_DEFS } from "./feedback-decision-defs";

describe("deliveryOutcome for unconfirmed feedback", () => {
	it.each([
		["UNCONFIRMED", "Part of this feedback appeared on the work. The rest is not confirmed yet."],
		["FAILED", "Part of this feedback appeared on the work. Another part failed to post."],
	] as const)("describes confirmed partial exposure when %s", (state, description) => {
		const facts = {
			destination: "ON_WORK",
			state,
			stopReason: null,
			withdrawn: false,
			deliveredAt: new Date("2026-10-14T10:00:00Z"),
		} as const;
		expect(deliveryOutcome(facts)).toMatchObject({ label: "Partly delivered", description });
		expect(deliveryOutcome({ ...facts, withdrawn: true }).description).toBe(
			DELIVERY_STATE_DEFS[state].description,
		);
	});
	it("names no conversation for a write on the work", () => {
		const outcome = deliveryOutcome({
			destination: "ON_WORK",
			state: "UNCONFIRMED",
			stopReason: "DELIVERY_UNCONFIRMED",
			withdrawn: false,
			deliveredAt: null,
		});
		expect(outcome).toBe(DELIVERY_STATE_DEFS.UNCONFIRMED);
		expect(outcome.description).not.toMatch(/conversation/iu);
	});

	// The label stays the state's, so a filter by state still matches what the row says.
	it("keeps the conversation's own words for a conversation", () => {
		expect(
			deliveryOutcome({
				destination: "CONVERSATION",
				state: "UNCONFIRMED",
				stopReason: "DELIVERY_UNCONFIRMED",
				withdrawn: false,
				deliveredAt: null,
			}),
		).toMatchObject({
			label: DELIVERY_STATE_DEFS.UNCONFIRMED.label,
			description: "The conversation linked this feedback, but its appearance was not recorded.",
		});
	});
});

const DESTINATIONS = ["CONVERSATION", "PRACTICE_PAGE", "ON_WORK"] as const;

describe("deliveryOutcome for withdrawn feedback", () => {
	// A withdrawal keeps the stored READY, but nothing delivers the piece while it stands.
	it.each(DESTINATIONS)("promises no delivery for ready feedback withdrawn %s", (destination) => {
		const facts = {
			destination,
			state: "READY",
			stopReason: null,
			withdrawn: true,
			deliveredAt: null,
		} as const;
		const outcome = deliveryOutcome(facts);
		expect(outcome).toMatchObject({
			label: "Ready, then withdrawn",
			icon: FEEDBACK_DECISION_DEFS.WITHDRAW.icon,
			badgeVariant: FEEDBACK_DECISION_DEFS.WITHDRAW.badgeVariant,
		});
		// The raw Ready filter still matches what the row says.
		expect(outcome.label.startsWith(DELIVERY_STATE_DEFS.READY.label)).toBe(true);
		expect(outcome.description).not.toMatch(/next chat|cleared to send|delivered when/iu);
		// The outcome already names the withdrawal, so no second flag repeats it.
		expect(withdrawalMark(facts)).toBeUndefined();
	});

	it.each([
		["CONVERSATION", "Ready for conversation"],
		["PRACTICE_PAGE", "Ready for their practice pages"],
		["ON_WORK", "Ready to send"],
	] as const)(
		"keeps active readiness for %s, as the server reports it after a restore",
		(destination, label) => {
			const facts = {
				destination,
				state: "READY",
				stopReason: null,
				withdrawn: false,
				deliveredAt: null,
			} as const;
			expect(deliveryOutcome(facts).label).toBe(label);
			expect(withdrawalMark(facts)).toBeUndefined();
		},
	);

	// Withdrawal never rewrites a delivery receipt: the recorded outcome stays, flagged.
	it.each([
		["ON_WORK", DELIVERY_STATE_DEFS.DELIVERED.label],
		["PRACTICE_PAGE", DELIVERY_STATE_DEFS.DELIVERED.label],
		["CONVERSATION", "Delivered in conversation"],
	] as const)(
		"keeps the receipt of feedback delivered %s, then withdrawn",
		(destination, label) => {
			const facts = {
				destination,
				state: "DELIVERED",
				stopReason: null,
				withdrawn: true,
				deliveredAt: null,
			} as const;
			expect(deliveryOutcome(facts).label).toBe(label);
			expect(withdrawalMark(facts)).toBe(FEEDBACK_DECISION_DEFS.WITHDRAW);
		},
	);

	it("flags an unconfirmed piece withdrawn without hiding that it may have appeared", () => {
		const facts = {
			destination: "ON_WORK",
			state: "UNCONFIRMED",
			stopReason: "DELIVERY_UNCONFIRMED",
			withdrawn: true,
			deliveredAt: null,
		} as const;
		expect(deliveryOutcome(facts)).toBe(DELIVERY_STATE_DEFS.UNCONFIRMED);
		expect(withdrawalMark(facts)).toBe(FEEDBACK_DECISION_DEFS.WITHDRAW);
	});
});

describe("isDeliveryInProgress", () => {
	it("follows ready feedback on the work while it is still to be sent", () => {
		expect(
			isDeliveryInProgress({
				destination: "ON_WORK",
				state: "READY",
				stopReason: null,
				withdrawn: false,
				deliveredAt: null,
			}),
		).toBe(true);
	});

	// Its correction, if any, keeps its own poll; withdrawal is not a reason to wait for a send.
	it("does not follow ready feedback that was withdrawn", () => {
		expect(
			isDeliveryInProgress({
				destination: "ON_WORK",
				state: "READY",
				stopReason: null,
				withdrawn: true,
				deliveredAt: null,
			}),
		).toBe(false);
	});
});
