import { definitionFor, type StatusDef, type StatusDefs } from "@/components/common/status-def";

import { DELIVERY_STATE_DEFS, type DeliveryState } from "./delivery-state-defs";

/**
 * The delivery families a count of feedback uses in place of the stored delivery states. What each
 * holds, and why, is defined in `docs/contributor/practice-feedback-language.md` (delivery family);
 * this registry is its code, and every family opens its list filtered to exactly
 * {@link DELIVERY_FAMILY_STATES}.
 */
export type DeliveryFamily =
	| "AWAITING_APPROVAL"
	| "READY"
	| "DELIVERED"
	| "UNCONFIRMED"
	| "WITHHELD"
	| "FAILED";

/** The stored states each family stands for. Every state is in exactly one family. */
export const DELIVERY_FAMILY_STATES = {
	AWAITING_APPROVAL: ["AWAITING_APPROVAL"],
	READY: ["READY"],
	DELIVERED: ["DELIVERED"],
	UNCONFIRMED: ["UNCONFIRMED"],
	WITHHELD: ["WITHHELD", "REJECTED", "REPLACED"],
	FAILED: ["FAILED"],
} as const satisfies Record<DeliveryFamily, readonly DeliveryState[]>;

/**
 * Each family wears the words, icon and tone of its first state, so a count and the badge on the
 * rows it opens read alike; only the description widens to every state the family holds.
 */
function familyDef(family: DeliveryFamily, description: string): StatusDef {
	return { ...definitionFor(DELIVERY_STATE_DEFS, DELIVERY_FAMILY_STATES[family][0]), description };
}

export const DELIVERY_FAMILY_DEFS: StatusDefs<DeliveryFamily> = {
	AWAITING_APPROVAL: familyDef(
		"AWAITING_APPROVAL",
		"A workspace admin must approve or reject it before anything is sent.",
	),
	READY: familyDef(
		"READY",
		"Composed and waiting for the moment that delivers it, which differs by destination.",
	),
	DELIVERED: familyDef(
		"DELIVERED",
		"It reached the developer at its destination. Its copy counts say whether every copy landed.",
	),
	UNCONFIRMED: familyDef(
		"UNCONFIRMED",
		"It may have appeared, but that was never confirmed, so it counts as neither delivered nor withheld.",
	),
	WITHHELD: familyDef(
		"WITHHELD",
		"Deliberately not sent: a delivery check held it back, a workspace admin rejected it, or newer feedback replaced it.",
	),
	FAILED: familyDef("FAILED", "Sending was attempted and did not succeed."),
};
