import { CheckCheckIcon, CircleHelpIcon, ClockIcon, LoaderIcon } from "lucide-react";

import type { FeedbackCopy, FeedbackSend, ReviewFeedbackDetail } from "@/api/types.gen";

import type { StatusDefs } from "@/components/common/status-def";
import { hasText } from "@/lib/text";

export type FeedbackSendState = FeedbackSend["state"];
export type FeedbackSendAction = FeedbackSend["action"];

// Send labels describe the provider attempt, separate from the feedback's delivery state.
export const FEEDBACK_SEND_STATE_DEFS: StatusDefs<FeedbackSendState> = {
	PENDING: {
		label: "Waiting to be sent",
		icon: ClockIcon,
		badgeVariant: "outline",
		description: "It is sent at its next attempt.",
	},
	SENDING: {
		label: "Being sent",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "A server is sending it now.",
	},
	UNCERTAIN: {
		label: "Not confirmed",
		icon: CircleHelpIcon,
		badgeVariant: "warning",
		description:
			"The provider may have posted it without confirming. It is checked before anything is posted again.",
	},
	DONE: {
		label: "Done",
		icon: CheckCheckIcon,
		badgeVariant: "success",
		description: "Every copy in this send reached its final state.",
	},
};

export const FEEDBACK_SEND_ACTION_LABELS: Record<FeedbackSendAction, string> = {
	POST: "Post the copies",
	RETRACT: "Retract the posted copies",
	EDIT: "Correct the posted copies",
	READBACK: "Read the posted copies",
};

/**
 * Where removing withdrawn feedback from the work stands, read from the copies' receipts and not from
 * the send alone: a finished send can leave a copy on the work. `retryable` means a posted copy waits
 * on a removal the provider did not acknowledge. `needsAttention` means a copy may still be on the
 * work and a retry cannot remove it: the provider cannot correct it, or its write was never confirmed.
 * Undefined when nothing on the work is being withdrawn.
 */
export type WithdrawalCorrection = "pending" | "retryable" | "needsAttention" | "complete";

export function withdrawalCorrection(
	feedback: Pick<ReviewFeedbackDetail, "withdrawn" | "destination" | "sends" | "copies">,
): WithdrawalCorrection | undefined {
	if (!feedback.withdrawn || feedback.destination !== "ON_WORK") {
		return undefined;
	}
	if (feedback.sends.some((send) => send.state !== "DONE")) {
		return "pending";
	}
	if (copiesStillOnWork(feedback.copies).length === 0) {
		return "complete";
	}
	return feedback.sends.some((send) => send.action === "RETRACT") &&
		feedback.copies.some(
			(copy) =>
				copy.state === "POSTED" && hasText(copy.externalRef) && copy.readback === "TRANSIENT",
		)
		? "retryable"
		: "needsAttention";
}

/**
 * Whether reading the work again can settle a comment whose write the provider never confirmed. It
 * mirrors the server's recheck rule over the feedback's one send. `available` means that send,
 * a post or a retraction, settled while a copy's write stayed unknown: a retraction never reads
 * it back. `checking` means a post still runs while that write is unknown; a running retraction
 * is not a check. Withdrawn feedback counts too: a comment found there is then retracted, and
 * absence releases it. Undefined otherwise.
 */
export type DeliveryCheck = "available" | "checking";

export function deliveryCheck(
	feedback: Pick<ReviewFeedbackDetail, "destination" | "sends" | "copies">,
): DeliveryCheck | undefined {
	const [send] = feedback.sends;
	if (
		feedback.destination !== "ON_WORK" ||
		send === undefined ||
		!feedback.copies.some(
			(copy) =>
				copy.state === "WRITE_STARTED" &&
				copy.writeStartedAt !== null &&
				copy.postedAt === null &&
				copy.externalRef === null &&
				copy.readback !== "ABSENT",
		)
	) {
		return undefined;
	}
	if (send.state === "DONE") {
		return "available";
	}
	return send.action === "POST" || send.action === "EDIT" ? "checking" : undefined;
}

/** Copies that are, or may be, on the work: posted, or written without a receipt proving them absent. */
export function copiesStillOnWork(copies: readonly FeedbackCopy[]): FeedbackCopy[] {
	return copies.filter(
		(copy) =>
			copy.state === "POSTED" ||
			copy.state === "WRITE_STARTED" ||
			(copy.state === "FAILED" && copy.writeStartedAt !== null && copy.readback !== "ABSENT"),
	);
}
