import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import {
	decideFeedbackProposalMutation,
	getPracticeReviewFeedbackOptions,
} from "@/api/@tanstack/react-query.gen";
import type { GetPracticeReviewFeedbackResponse } from "@/api/types.gen";
import type { ProposalRejectionReason } from "@/components/admin/practice-reviews/proposal-rejection-vocabulary";
import { PRACTICE_REVIEW_READS } from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { isDeliveryInProgress } from "@/components/practice-vocabulary/delivery-outcome-defs";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

/** How often feedback still on its way out is re-read, until it lands or stops. */
const DELIVERY_POLL_MS = 2000;

interface FeedbackController {
	feedback: PanelState<{ feedback: GetPracticeReviewFeedbackResponse }>;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: ProposalRejectionReason, note?: string) => void;
}

/** One piece of feedback as its level reads it, and the approval decision it may be waiting on. */
export function useFeedbackController(
	workspaceSlug: string,
	feedbackId: string,
): FeedbackController {
	const queryClient = useQueryClient();
	const feedbackQuery = useQuery({
		...getPracticeReviewFeedbackOptions({ path: { workspaceSlug, feedbackId } }),
		refetchInterval: ({ state: { data } }) =>
			data !== undefined && isDeliveryInProgress(data) ? DELIVERY_POLL_MS : false,
	});
	const decision = useMutation({
		...decideFeedbackProposalMutation(),
		// Settled, not succeeded: a decision refused because someone else decided first still means
		// the lists and counts have moved.
		onSettled: () => {
			void invalidateWorkspaceReads(queryClient, workspaceSlug, PRACTICE_REVIEW_READS);
		},
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

	return {
		feedback: panelState(feedbackQuery, (feedback) => ({ status: "ready" as const, feedback })),
		isDeciding: decision.isPending,
		onApprove: () =>
			decision.mutate({ path: { workspaceSlug, feedbackId }, body: { decision: "APPROVED" } }),
		onReject: (rejectionReason, rejectionNote) =>
			decision.mutate({
				path: { workspaceSlug, feedbackId },
				body: { decision: "REJECTED", rejectionReason, rejectionNote },
			}),
	};
}
