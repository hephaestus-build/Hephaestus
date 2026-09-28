import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	decideFeedbackProposalMutation,
	getPracticeReviewFeedbackOptions,
} from "@/api/@tanstack/react-query.gen";
import type { GetPracticeReviewFeedbackResponse } from "@/api/types.gen";
import type { ProposalRejectionReason } from "@/components/admin/practice-reviews/proposal-rejection-vocabulary";
import type { ApprovalQueue } from "@/components/admin/practice-reviews/ProposalDecision";
import { PRACTICE_REVIEW_READS } from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { isDeliveryInProgress } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

import { awaitingApprovalOptions } from "./use-feedback-awaiting-approval";

/** How often feedback still on its way out is re-read, until it lands or stops. */
const DELIVERY_POLL_MS = 2000;

interface FeedbackController {
	feedback: PanelState<{ feedback: GetPracticeReviewFeedbackResponse }>;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: ProposalRejectionReason, note?: string) => void;
}

interface FeedbackControllerOptions {
	/**
	 * Called once this feedback no longer awaits a decision — this reader's was recorded, or a refused
	 * one found it decided by someone else — and only after the reads it moved have been read again.
	 * With the closure of the render the reader decided in, so a queue moves on to the neighbour it
	 * showed, not to whatever the refreshed queue holds next.
	 */
	onDecided?: () => void;
}

/** One piece of feedback as its level reads it, and the approval decision it may be waiting on. */
export function useFeedbackController(
	workspaceSlug: string,
	feedbackId: string,
	{ onDecided }: FeedbackControllerOptions = {},
): FeedbackController {
	const queryClient = useQueryClient();
	const feedbackOptions = getPracticeReviewFeedbackOptions({ path: { workspaceSlug, feedbackId } });
	const feedbackQuery = useQuery({
		...feedbackOptions,
		refetchInterval: ({ state: { data } }) =>
			data !== undefined && isDeliveryInProgress(data) ? DELIVERY_POLL_MS : false,
	});
	const decision = useMutation({
		...decideFeedbackProposalMutation(),
		// Settled, not succeeded: a decision refused because someone else decided first still means
		// the lists and counts have moved. Returned, so the decision stays pending until they are read
		// again: the buttons cannot be pressed twice on the record just decided, and whatever moves on
		// after it reads the queue as it is now.
		onSettled: async () =>
			invalidateWorkspaceReads(queryClient, workspaceSlug, PRACTICE_REVIEW_READS),
		onSuccess: (_, variables) => {
			toast.success(
				variables.body.decision === "APPROVED"
					? "Feedback approved. Delivery is being checked."
					: "Feedback rejected",
			);
		},
		onError: (error) => {
			toast.error("Couldn't decide on this feedback", { description: problemDetailOf(error) });
		},
	});

	const decided = {
		onSuccess: onDecided,
		// A refusal is read by what it left behind, not by its status: "already decided" and "no
		// longer eligible" took the feedback out of approval, while "sending is paused" left it waiting
		// — and the re-read above has already landed by the time this runs.
		onError: () => {
			const state = queryClient.getQueryData(feedbackOptions.queryKey)?.deliveryState;
			if (state !== undefined && state !== "AWAITING_APPROVAL") {
				onDecided?.();
			}
		},
	};
	return {
		feedback: panelState(feedbackQuery, (feedback) => ({ status: "ready" as const, feedback })),
		isDeciding: decision.isPending,
		onApprove: () =>
			decision.mutate(
				{ path: { workspaceSlug, feedbackId }, body: { decision: "APPROVED" } },
				decided,
			),
		onReject: (rejectionReason, rejectionNote) =>
			decision.mutate(
				{
					path: { workspaceSlug, feedbackId },
					body: { decision: "REJECTED", rejectionReason, rejectionNote },
				},
				decided,
			),
	};
}

/**
 * The most feedback awaiting approval a queue reads at once: the server's largest page. Beyond it the
 * queue is read again as decisions shrink it.
 */
const APPROVAL_QUEUE_SIZE = 100;

/**
 * Where `feedbackId` sits among all feedback awaiting approval, oldest first — the order the
 * overview offers it in. Undefined while the queue loads, when it fails, and when this feedback is
 * not in it: a decided one, or one past the first page.
 */
export function useApprovalQueue(
	workspaceSlug: string,
	feedbackId: string,
	enabled: boolean,
): ApprovalQueue | undefined {
	const query = useQuery({
		...awaitingApprovalOptions(workspaceSlug, APPROVAL_QUEUE_SIZE),
		enabled,
	});
	const ids = (query.data?.content ?? []).map((item) => item.id);
	const index = ids.indexOf(feedbackId);
	if (!enabled || index === -1) {
		return undefined;
	}
	return {
		position: index + 1,
		total: query.data?.page?.totalElements ?? ids.length,
		previous: ids[index - 1],
		next: ids[index + 1],
	};
}

/**
 * What to take after deciding on `feedbackId` when the queue showed nothing after it: the oldest
 * other proposal in the queue read again, which the decision has shrunk — one the reader skipped
 * past, one beyond the page that was read, or any at all when the queue had not loaded yet.
 * Undefined when nothing else awaits.
 */
export function useNextInApprovalQueue(workspaceSlug: string, feedbackId: string) {
	const queryClient = useQueryClient();
	return async () => {
		// The decision invalidated the queue, so this is a fresh read: the one the decision already
		// waited for when the queue was on screen, or a new one when it was not.
		const page = await queryClient.query(
			awaitingApprovalOptions(workspaceSlug, APPROVAL_QUEUE_SIZE),
		);
		return page.content?.find((item) => item.id !== feedbackId)?.id;
	};
}
