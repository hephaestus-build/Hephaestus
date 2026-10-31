import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	cancelReviewMutation,
	getReviewOptions,
	listReviewFeedbackChecksOptions,
	listReviewsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackCheck, ReviewDetail } from "@/api/types.gen";
import {
	ACTIVE_REVIEW_POLL_MS,
	PRACTICE_REVIEW_READS,
} from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { isActiveReview } from "@/components/practice-vocabulary/review-status-defs";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";
import { retryQuery } from "@/runtime/tanstack-query/query-defaults";

import { useInvalidateOnSettle } from "./use-invalidate-on-settle";

export interface ReviewController {
	review: PanelState<{ review: ReviewDetail }>;
	feedbackChecks: PanelState<{ checks: FeedbackCheck[] }>;
	onCancel: () => void;
	cancelPending: boolean;
}

function isMoving(review: ReviewDetail): boolean {
	return (
		isActiveReview(review.status) ||
		(review.runtime?.jobs ?? []).some((job) => job.processingStatus === "PENDING")
	);
}

export function useReviewController(workspaceSlug: string, reviewId: string): ReviewController {
	const queryClient = useQueryClient();
	const reviewQuery = useQuery({
		...getReviewOptions({ path: { workspaceSlug, reviewId } }),
		retry: retryQuery,
		refetchInterval: (result) =>
			result.state.data !== undefined && isMoving(result.state.data)
				? ACTIVE_REVIEW_POLL_MS
				: false,
	});
	const checksOptions = listReviewFeedbackChecksOptions({ path: { workspaceSlug, reviewId } });
	const checksQuery = useQuery({ ...checksOptions, retry: retryQuery });

	// Refresh checks and lists after processing finishes; their last polled state can predate the final results.
	// The work's lists include the read of whether a review of it is still active.
	const settled = reviewQuery.data !== undefined && !isMoving(reviewQuery.data);
	useInvalidateOnSettle(reviewId, reviewQuery.data !== undefined && !settled, [
		checksOptions.queryKey,
		listReviewsQueryKey({ path: { workspaceSlug }, query: { reviewId } }),
		listReviewsQueryKey({ path: { workspaceSlug }, query: { itemId: reviewQuery.data?.item.id } }),
	]);

	// Cancellation returns a summary, so refresh detail rather than treating that answer as complete.
	const cancelReview = useMutation({
		...cancelReviewMutation(),
		onSuccess: () => {
			void invalidateWorkspaceReads(queryClient, workspaceSlug, PRACTICE_REVIEW_READS);
			toast.success("Review canceled");
		},
		onError: (error) =>
			toast.error("We could not cancel the review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});

	return {
		review: panelState(reviewQuery, (review) => ({ status: "ready" as const, review })),
		feedbackChecks: panelState(checksQuery, (checks) => ({ status: "ready" as const, checks })),
		onCancel: () => cancelReview.mutate({ path: { workspaceSlug, reviewId } }),
		cancelPending: cancelReview.isPending,
	};
}
