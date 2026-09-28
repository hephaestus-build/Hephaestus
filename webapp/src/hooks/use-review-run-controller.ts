import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { toast } from "sonner";

import {
	cancelAgentJobMutation,
	getAgentJobOptions,
	getAgentJobQueryKey,
	listPracticeReviewFeedbackOptions,
	listPracticeReviewFeedbackQueryKey,
	listPracticeReviewObservationsOptions,
	listPracticeReviewObservationsQueryKey,
	retryAgentJobDeliveryMutation,
} from "@/api/@tanstack/react-query.gen";
import type { AgentJob, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import {
	ACTIVE_REVIEW_POLL_MS,
	PRACTICE_REVIEW_READS,
	REVIEW_PREVIEW_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

import {
	type ReviewSectionState,
	toSectionState,
} from "@/components/admin/practice-reviews/review-states";

/**
 * Everything the review level needs, already resolved: the run, the two previews of what it
 * produced, and the two actions an operator can take on it.
 *
 * The screen is handed states, not queries. "Still running" reaches it as a section's `pending`
 * status — that a running review is re-asked for on a timer, and that the timer stops by itself at a
 * terminal status, is this module's business alone.
 */
export interface ReviewRunController {
	job: PanelState<{ job: AgentJob }>;
	observations: ReviewSectionState<ReviewObservation>;
	feedback: ReviewSectionState<ReviewFeedback>;
	onCancel: () => void;
	cancelPending: boolean;
	onRetryResultProcessing: () => void;
	retryResultProcessingPending: boolean;
}

export function useReviewRunController(workspaceSlug: string, jobId: string): ReviewRunController {
	const queryClient = useQueryClient();
	const jobQuery = useQuery({
		...getAgentJobOptions({ path: { workspaceSlug, jobId } }),
		refetchInterval: (result) =>
			result.state.data?.status === "QUEUED" ||
			result.state.data?.status === "RUNNING" ||
			result.state.data?.deliveryStatus === "PENDING"
				? ACTIVE_REVIEW_POLL_MS
				: false,
	});
	const runIsActive = jobQuery.data?.status === "QUEUED" || jobQuery.data?.status === "RUNNING";
	const observationsQuery = useQuery({
		...listPracticeReviewObservationsOptions({
			path: { workspaceSlug },
			// The screen shows only the observations most worth acting on, so it has to *ask* for that
			// ordering: the endpoint's default is newest-first, and re-sorting a page of five here would
			// order the five that happened to arrive rather than the five that matter.
			query: { agentJobId: jobId, sort: "ACTIONABILITY", size: REVIEW_PREVIEW_SIZE },
		}),
		refetchInterval: runIsActive ? ACTIVE_REVIEW_POLL_MS : false,
	});
	const feedbackQuery = useQuery({
		...listPracticeReviewFeedbackOptions({
			path: { workspaceSlug },
			query: { agentJobId: jobId, size: REVIEW_PREVIEW_SIZE },
		}),
		refetchInterval: runIsActive ? ACTIVE_REVIEW_POLL_MS : false,
	});

	// Processing finishes after execution. Reload the outputs when either stage settles, including
	// retries of a completed review; its previously cached feedback can otherwise stay stale.
	useEffect(() => {
		if (!jobQuery.data || runIsActive || jobQuery.data.deliveryStatus === "PENDING") {
			return;
		}
		void queryClient.invalidateQueries({
			queryKey: listPracticeReviewObservationsQueryKey({
				path: { workspaceSlug },
				query: { agentJobId: jobId, sort: "ACTIONABILITY", size: REVIEW_PREVIEW_SIZE },
			}),
		});
		void queryClient.invalidateQueries({
			queryKey: listPracticeReviewFeedbackQueryKey({
				path: { workspaceSlug },
				query: { agentJobId: jobId, size: REVIEW_PREVIEW_SIZE },
			}),
		});
	}, [jobQuery.data, runIsActive, queryClient, workspaceSlug, jobId]);

	/**
	 * Both actions answer with the job as it now stands, so it is written straight into the cache;
	 * the lists and counts it appears in are re-read, since one row's status cannot rebuild them.
	 */
	const updateJob = (job: AgentJob) => {
		queryClient.setQueryData(getAgentJobQueryKey({ path: { workspaceSlug, jobId } }), job);
		void invalidateWorkspaceReads(queryClient, workspaceSlug, PRACTICE_REVIEW_READS);
	};
	const cancelJob = useMutation({
		...cancelAgentJobMutation(),
		onSuccess: (job) => {
			updateJob(job);
			toast.success("Review cancelled");
		},
		onError: (error) =>
			toast.error("Couldn't cancel the review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});
	const retryResultProcessing = useMutation({
		...retryAgentJobDeliveryMutation(),
		onSuccess: (job) => {
			updateJob(job);
			toast.success("Result processing queued for retry");
		},
		onError: (error) =>
			toast.error("Couldn't retry result processing", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});

	return {
		job: panelState(jobQuery, (job) => ({ status: "ready" as const, job })),
		observations: toSectionState<ReviewObservation>(observationsQuery, runIsActive),
		feedback: toSectionState<ReviewFeedback>(
			feedbackQuery,
			runIsActive || jobQuery.data?.deliveryStatus === "PENDING",
		),
		onCancel: () => cancelJob.mutate({ path: { workspaceSlug, jobId } }),
		cancelPending: cancelJob.isPending,
		onRetryResultProcessing: () => retryResultProcessing.mutate({ path: { workspaceSlug, jobId } }),
		retryResultProcessingPending: retryResultProcessing.isPending,
	};
}
