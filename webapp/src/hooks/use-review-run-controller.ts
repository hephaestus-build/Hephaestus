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
	listPracticeReviewsQueryKey,
	retryAgentJobDeliveryMutation,
} from "@/api/@tanstack/react-query.gen";
import type { AgentJob, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import { ACTIVE_REVIEW_POLL_MS } from "@/components/admin/practice-reviews/review-search";
import {
	REVIEW_PREVIEW_SIZE,
	type ReviewSectionState,
} from "@/components/admin/practice-reviews/ReviewOutputSections";
import { problemDetailOf } from "@/lib/problem-detail";

/**
 * Everything the review detail screen needs, already resolved: the run, the two previews of what it
 * produced, and the two actions an operator can take on it.
 *
 * The screen is handed states, not queries. "Still running" reaches it as a section's `pending`
 * status — that a running review is re-asked for on a timer, and that the timer stops by itself at a
 * terminal status, is this module's business alone.
 */
export interface ReviewRunController {
	job: AgentJob | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry: () => void;
	observations: ReviewSectionState<ReviewObservation>;
	feedback: ReviewSectionState<ReviewFeedback>;
	onCancel: () => void;
	cancelPending: boolean;
	onRetryResultProcessing: () => void;
	retryResultProcessingPending: boolean;
}

/** The shape of a paged query this module reads, spelled out rather than taken as a
 * `UseQueryResult` so the element type is inferred from the page's own `content`. */
interface PagedQuery<T> {
	isLoading: boolean;
	isError: boolean;
	error: unknown;
	refetch: () => unknown;
	data?: { content?: T[]; page?: { totalElements?: number } };
}

/**
 * `pending` is the one state a page of results cannot report for itself: an empty answer from a run
 * still in flight means "not yet", and the identical answer from a finished run means "none". The
 * caller knows which, so it passes `stillRunning` in.
 */
function toSectionState<T>(query: PagedQuery<T>, stillRunning: boolean): ReviewSectionState<T> {
	if (query.isLoading) return { status: "loading" };
	if (query.isError) {
		return { status: "error", error: query.error, onRetry: () => void query.refetch() };
	}
	const items = query.data?.content ?? [];
	if (stillRunning && items.length === 0) return { status: "pending" };
	return { status: "ready", items, total: query.data?.page?.totalElements ?? 0 };
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
		if (!jobQuery.data || runIsActive || jobQuery.data.deliveryStatus === "PENDING") return;
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
	 * Both actions answer with the job as it now stands, so it is written straight into the cache
	 * rather than refetched. The list of reviews is only invalidated: it is a different page's data,
	 * and this one row's new status is not enough to rebuild whatever filtered page that reader is on.
	 */
	const updateJob = (job: AgentJob) => {
		queryClient.setQueryData(getAgentJobQueryKey({ path: { workspaceSlug, jobId } }), job);
		void queryClient.invalidateQueries({
			queryKey: listPracticeReviewsQueryKey({ path: { workspaceSlug } }),
		});
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
		job: jobQuery.data,
		isLoading: jobQuery.isLoading,
		error: jobQuery.error,
		onRetry: () => void jobQuery.refetch(),
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
