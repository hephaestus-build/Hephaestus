import {
	BotMessageSquareIcon,
	CircleAlertIcon,
	CircleCheckIcon,
	ClipboardCheckIcon,
	ClockIcon,
	EyeOffIcon,
	HistoryIcon,
	HourglassIcon,
	MessageSquareDashedIcon,
	UserRoundIcon,
	XCircleIcon,
} from "lucide-react";
import type { ReviewFeedback } from "@/api/types.gen";
import type { StatusDef, StatusDefs } from "@/components/common/status-def";

export type DeliveryState = ReviewFeedback["deliveryState"];

export type DeliveryFacts = Pick<ReviewFeedback, "channel" | "deliveryState" | "suppressionReason">;

/**
 * **What happened** to a piece of feedback. The second delivery axis; `delivery-place-defs` is the
 * first, and a cell shows one or the other, never a value from each.
 *
 * <p>Keyed by the stored state, because that is what the list endpoint filters on. What a *row*
 * renders is {@link deliveryOutcome}, which says more: `DELIVERED` and `PREPARED` each mean
 * different things depending on the lane the unit went down, so both are refined per lane below.
 */
export const DELIVERY_STATE_DEFS: StatusDefs<DeliveryState> = {
	AWAITING_APPROVAL: {
		label: "Awaiting approval",
		icon: ClipboardCheckIcon,
		badgeVariant: "secondary",
		description: "An authorized reviewer must approve or reject it before anything is sent.",
	},
	DELIVERED: {
		label: "Delivered",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "It reached the developer where it was placed.",
	},
	PREPARED: {
		// The bare state, which is what the Outcome facet can offer. Both lanes that use it refine the
		// words below: what a prepared unit is waiting *for* differs, and naming one lane's moment here
		// mislabelled the other's rows.
		label: "Prepared",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"Composed, and waiting for the moment that delivers it — which differs by channel.",
	},
	PARTIALLY_DELIVERED: {
		label: "Partially delivered",
		icon: CircleAlertIcon,
		badgeVariant: "warning",
		description:
			"Some approved comments reached the provider. The remaining comments are retrying or were withheld for the reason shown.",
	},
	PARTIALLY_FAILED: {
		label: "Partially delivered · retries exhausted",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description:
			"Some approved comments reached the provider, but the remaining comments could not be delivered after bounded retries.",
	},
	SUPERSEDED: {
		label: "Replaced by newer",
		icon: HistoryIcon,
		badgeVariant: "outline",
		description: "A later piece of feedback about the same work took its place.",
	},
	SUPPRESSED: {
		label: "Withheld",
		icon: EyeOffIcon,
		badgeVariant: "warning",
		description: "Composed, then deliberately not sent. The reason says who decided and why.",
	},
	FAILED: {
		label: "Failed to deliver",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "Sending was attempted and did not succeed.",
	},
	DISCARDED: {
		label: "Rejected",
		icon: XCircleIcon,
		badgeVariant: "outline",
		description: "An authorized reviewer decided this proposal should not be sent.",
	},
};

/**
 * The rows where the stored state under-describes what happened, on the conversation lane.
 *
 * <p>Each label **must begin with the label of the state it refines**. The Outcome facet can only
 * offer the stored states, so a badge whose words share no stem with any option leaves a reader
 * unable to find the filter for the row in front of them.
 */
const IN_CHAT_OVERRIDES = {
	PREPARED: {
		label: "Prepared for conversation",
		icon: MessageSquareDashedIcon,
		badgeVariant: "secondary",
		description: "Waiting for the developer's next chat with the mentor, which is what sends it.",
	},
	RAISED: {
		label: "Delivered in conversation",
		icon: BotMessageSquareIcon,
		badgeVariant: "success",
		description: "The mentor raised it the next time the developer was talking to it.",
	},
	EXPIRED: {
		label: "Withheld, never raised",
		icon: HourglassIcon,
		badgeVariant: "warning",
		description: "It sat in the conversation queue until it aged out, and was never said.",
	},
} as const satisfies Record<string, StatusDef>;

/**
 * The same, on the in-app lane. A prepared unit here is not waiting on the mentor: nothing sends
 * it, and the developer opening their own practice pages is the delivery. Saying "waiting for their
 * next chat" about one of these rows would describe an event that will never happen to it.
 */
const IN_APP_OVERRIDES = {
	PREPARED: {
		label: "Prepared for their practice pages",
		icon: UserRoundIcon,
		badgeVariant: "secondary",
		description: "Waiting on the developer's own practice pages; opening it is what delivers it.",
	},
} as const satisfies Record<string, StatusDef>;

const IN_CONTEXT_OVERRIDES = {
	PREPARED: {
		label: "Prepared · approved, sending",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"The decision is recorded. Provider delivery is still in progress and may be retried safely.",
	},
} as const satisfies Record<string, StatusDef>;

/** What a row should say became of this feedback. */
export function deliveryOutcome(feedback: DeliveryFacts): StatusDef {
	const { channel, deliveryState, suppressionReason } = feedback;
	if (channel === "IN_CHAT") {
		if (deliveryState === "PREPARED") {
			return IN_CHAT_OVERRIDES.PREPARED;
		}
		if (deliveryState === "DELIVERED") {
			return IN_CHAT_OVERRIDES.RAISED;
		}
		if (suppressionReason === "CONVERSATION_EXPIRED") {
			return IN_CHAT_OVERRIDES.EXPIRED;
		}
	}
	if (channel === "IN_APP" && deliveryState === "PREPARED") {
		return IN_APP_OVERRIDES.PREPARED;
	}
	if (channel === "IN_CONTEXT" && deliveryState === "PREPARED") {
		return IN_CONTEXT_OVERRIDES.PREPARED;
	}
	return DELIVERY_STATE_DEFS[deliveryState];
}

export function isWithheld(feedback: DeliveryFacts): boolean {
	return (
		feedback.deliveryState === "SUPPRESSED" ||
		(feedback.deliveryState === "PARTIALLY_DELIVERED" && feedback.suppressionReason !== undefined)
	);
}
