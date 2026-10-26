import {
	BotMessageSquareIcon,
	CircleAlertIcon,
	CircleCheckIcon,
	CircleHelpIcon,
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

import { FEEDBACK_DECISION_DEFS } from "./feedback-decision-defs";

export type DeliveryState = ReviewFeedback["state"];

export type DeliveryFacts = Pick<
	ReviewFeedback,
	"destination" | "state" | "stopReason" | "withdrawn" | "deliveredAt"
>;

type PartialFacts = Pick<ReviewFeedback, "destination" | "state" | "deliveredAt">;

/** Part of the feedback is on the work, while another copy failed or is not confirmed yet. */
export function isPartialDelivery(feedback: PartialFacts): boolean {
	return (
		feedback.destination === "ON_WORK" &&
		(feedback.state === "UNCONFIRMED" || feedback.state === "FAILED") &&
		feedback.deliveredAt !== null
	);
}

export function partialDeliveryDescription(feedback: PartialFacts): string {
	return feedback.state === "FAILED"
		? "Part of this feedback appeared on the work. Another part failed to post."
		: "Part of this feedback appeared on the work. The rest is not confirmed yet.";
}

export const DELIVERY_STATE_DEFS: StatusDefs<DeliveryState> = {
	AWAITING_APPROVAL: {
		label: "Awaiting approval",
		icon: ClipboardCheckIcon,
		badgeVariant: "secondary",
		description: "A workspace admin must approve or reject it before anything is sent.",
	},
	READY: {
		label: "Ready",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description:
			"Composed, and waiting for the moment that delivers it. That moment differs by destination.",
	},
	DELIVERED: {
		label: "Delivered",
		icon: CircleCheckIcon,
		badgeVariant: "success",
		description: "It reached the developer at its destination.",
	},
	UNCONFIRMED: {
		label: "Delivery unconfirmed",
		icon: CircleHelpIcon,
		badgeVariant: "outline",
		description:
			"It may have appeared, but that was never confirmed, so it does not count as delivered.",
	},
	WITHHELD: {
		label: "Withheld",
		icon: EyeOffIcon,
		badgeVariant: "warning",
		description: "Composed, then deliberately not sent. The reason says who decided and why.",
	},
	REJECTED: {
		label: "Rejected",
		icon: XCircleIcon,
		badgeVariant: "outline",
		description: "A workspace admin decided this feedback should not be sent.",
	},
	REPLACED: {
		label: "Replaced by newer",
		icon: HistoryIcon,
		badgeVariant: "outline",
		description: "A later piece of feedback about the same work took its place.",
	},
	FAILED: {
		label: "Failed to deliver",
		icon: CircleAlertIcon,
		badgeVariant: "destructive",
		description: "Sending was attempted and did not succeed.",
	},
};

// Refined delivery labels must begin with the base state label so filters still match what rows say.
const CONVERSATION_OVERRIDES = {
	READY: {
		label: "Ready for conversation",
		icon: MessageSquareDashedIcon,
		badgeVariant: "secondary",
		description:
			"Waiting for the developer’s next question in their conversation, which is what delivers it.",
	},
	DELIVERED: {
		label: "Delivered in conversation",
		icon: BotMessageSquareIcon,
		badgeVariant: "success",
		description: "Hephaestus showed it in a reply in the developer’s conversation.",
	},
	UNCONFIRMED: {
		...DELIVERY_STATE_DEFS.UNCONFIRMED,
		description: "The conversation linked this feedback, but its appearance was not recorded.",
	},
	EXPIRED: {
		label: "Withheld, never raised",
		icon: HourglassIcon,
		badgeVariant: "warning",
		description: "It stayed ready for conversation until it expired. Hephaestus never raised it.",
	},
} as const satisfies Record<string, StatusDef>;

// Practice-page feedback is delivered when its developer opens the page; conversation feedback waits for a turn.
const PRACTICE_PAGE_READY: StatusDef = {
	label: "Ready for their practice pages",
	icon: UserRoundIcon,
	badgeVariant: "secondary",
	description:
		"Waiting on the developer’s own practice pages. The feedback is delivered when the developer opens it.",
};

const ON_WORK_READY: StatusDef = {
	label: "Ready to send",
	icon: ClockIcon,
	badgeVariant: "secondary",
	description: "It is cleared to send. Provider delivery is in progress and may be retried safely.",
};

// A withdrawal keeps the stored state, so feedback withdrawn while ready still reads READY. No
// destination delivers it while the withdrawal stands, so it must not read as waiting to be sent.
const WITHDRAWN_READY: StatusDef = {
	...FEEDBACK_DECISION_DEFS.WITHDRAW,
	label: "Ready, then withdrawn",
	description:
		"It was withdrawn before delivery. It is not sent or shown while the withdrawal stands.",
};

export function deliveryOutcome(feedback: DeliveryFacts): StatusDef {
	const { destination, state, stopReason } = feedback;
	if (isPartialDelivery(feedback) && !feedback.withdrawn) {
		return {
			...DELIVERY_STATE_DEFS[state],
			label: "Partly delivered",
			description: partialDeliveryDescription(feedback),
		};
	}
	if (feedback.withdrawn && state === "READY") {
		return WITHDRAWN_READY;
	}
	if (destination === "CONVERSATION") {
		if (state === "READY") {
			return CONVERSATION_OVERRIDES.READY;
		}
		if (state === "DELIVERED") {
			return CONVERSATION_OVERRIDES.DELIVERED;
		}
		if (state === "UNCONFIRMED") {
			return CONVERSATION_OVERRIDES.UNCONFIRMED;
		}
		if (stopReason === "CONVERSATION_EXPIRED") {
			return CONVERSATION_OVERRIDES.EXPIRED;
		}
	}
	if (state === "READY") {
		return destination === "PRACTICE_PAGE" ? PRACTICE_PAGE_READY : ON_WORK_READY;
	}
	return DELIVERY_STATE_DEFS[state];
}

/**
 * The withdrawal flag shown beside the delivery outcome. Feedback withdrawn after it was delivered,
 * or after it stopped, keeps its recorded outcome and gains this flag. Feedback withdrawn while
 * ready gets none, because its outcome already names the withdrawal.
 */
export function withdrawalMark(feedback: DeliveryFacts): StatusDef | undefined {
	return feedback.withdrawn && deliveryOutcome(feedback) !== WITHDRAWN_READY
		? FEEDBACK_DECISION_DEFS.WITHDRAW
		: undefined;
}

// A withdrawn piece is not sent; a correction it still needs is polled by its own owner.
export function isDeliveryInProgress(feedback: DeliveryFacts): boolean {
	return feedback.state === "READY" && feedback.destination === "ON_WORK" && !feedback.withdrawn;
}
