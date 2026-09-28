import type { StatusDef, StatusDefs } from "@/components/common/status-def";

import { DELIVERY_STATE_DEFS, type DeliveryState } from "./delivery-outcome-defs";

/**
 * The delivery families a count of feedback uses in place of the stored delivery states. What each
 * holds, and why, is defined in `docs/contributor/practice-feedback-language.md` (delivery family);
 * this registry is its code, and every family opens its list filtered to exactly
 * {@link DELIVERY_FAMILY_STATES}.
 */
export type DeliveryFamily =
	| "AWAITING_APPROVAL"
	| "PREPARED"
	| "DELIVERED"
	| "UNCONFIRMED"
	| "WITHHELD"
	| "FAILED";

/** The stored states each family stands for. Every state is in exactly one family. */
export const DELIVERY_FAMILY_STATES = {
	AWAITING_APPROVAL: ["AWAITING_APPROVAL"],
	PREPARED: ["PREPARED"],
	DELIVERED: ["DELIVERED", "PARTIALLY_DELIVERED"],
	UNCONFIRMED: ["UNCONFIRMED"],
	WITHHELD: ["SUPPRESSED", "DISCARDED", "SUPERSEDED"],
	FAILED: ["FAILED", "PARTIALLY_FAILED"],
} as const satisfies Record<DeliveryFamily, readonly DeliveryState[]>;

/**
 * Each family wears the words, icon and tone of its first state, so a count and the badge on the
 * rows it opens read alike; only the description widens to every state the family holds.
 */
function familyDef(family: DeliveryFamily, description: string): StatusDef {
	return { ...DELIVERY_STATE_DEFS[DELIVERY_FAMILY_STATES[family][0]], description };
}

export const DELIVERY_FAMILY_DEFS: StatusDefs<DeliveryFamily> = {
	AWAITING_APPROVAL: familyDef(
		"AWAITING_APPROVAL",
		"An authorized reviewer must approve or reject it before anything is sent.",
	),
	PREPARED: familyDef(
		"PREPARED",
		"Composed and waiting for the moment that delivers it, which differs by channel.",
	),
	DELIVERED: familyDef(
		"DELIVERED",
		"It reached the developer where it was placed, in full or in part.",
	),
	UNCONFIRMED: familyDef(
		"UNCONFIRMED",
		"Raised in a conversation with no record that it was shown, so it counts as neither delivered nor withheld.",
	),
	WITHHELD: familyDef(
		"WITHHELD",
		"Deliberately not sent: a delivery check held it back, a reviewer rejected it, or newer feedback replaced it.",
	),
	FAILED: familyDef("FAILED", "Sending was attempted and did not succeed, in full or in part."),
};
