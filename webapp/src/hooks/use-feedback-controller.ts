import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "@/lib/toast";

import {
	createFeedbackDecisionMutation,
	getReviewFeedbackOptions,
	recheckFeedbackDeliveryMutation,
	retryFeedbackMutation,
} from "@/api/@tanstack/react-query.gen";
import type { ReviewFeedbackDetail } from "@/api/types.gen";
import type { ApprovalQueue } from "@/components/admin/practice-reviews/ProposalDecision";
import { PRACTICE_REVIEW_READS } from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { isDeliveryInProgress } from "@/components/practice-vocabulary/delivery-state-defs";
import type { FeedbackDecisionReason } from "@/components/practice-vocabulary/feedback-decision-defs";
import {
	deliveryCheck,
	withdrawalCorrection,
} from "@/components/practice-vocabulary/feedback-send-defs";
import {
	WITHHOLDING_REASON_DEFS,
	type WithholdingReason,
	withholdingReasonSentence,
} from "@/components/practice-vocabulary/withholding-defs";
import { isRecord } from "@/lib/is-record";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

import { awaitingApprovalOptions } from "./use-feedback-awaiting-approval";

/** How often feedback still being sent, checked, or removed is re-read until it settles. */
const DELIVERY_POLL_MS = 2000;

const READS_OF_FEEDBACK_DECISION: ReadonlySet<string> = new Set([
	...PRACTICE_REVIEW_READS,
	"getOwnPracticeProfileOverview",
	"getOwnPracticePageFeedback",
]);

interface FeedbackController {
	feedback: PanelState<{ feedback: ReviewFeedbackDetail }>;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: FeedbackDecisionReason, note?: string) => void;
	onChangeWithdrawal: (withdrawn: boolean, note: string) => Promise<unknown>;
	isChangingWithdrawal: boolean;
	onRetryWithdrawal: () => void;
	isRetryingWithdrawal: boolean;
	onCheckDelivery: () => void;
	isCheckingDelivery: boolean;
}

function isWithholdingReason(code: unknown): code is WithholdingReason {
	return typeof code === "string" && Object.hasOwn(WITHHOLDING_REASON_DEFS, code);
}

/**
 * Why the server refused an approval, in the sheet's own words, when the refusal names a withholding
 * reason. Only a refusal by the publication context carries one, so a reason is never invented.
 */
function refusalSentence(error: unknown): string | undefined {
	if (!isRecord(error)) {
		return undefined;
	}
	const { code } = error;
	return isWithholdingReason(code) ? withholdingReasonSentence(code) : undefined;
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

export function useFeedbackController(
	workspaceSlug: string,
	feedbackId: string,
	{ onDecided }: FeedbackControllerOptions = {},
): FeedbackController {
	const queryClient = useQueryClient();
	const path = { workspaceSlug, feedbackId };
	const feedbackOptions = getReviewFeedbackOptions({ path });
	const feedbackQuery = useQuery({
		...feedbackOptions,
		refetchInterval: ({ state: { data } }) =>
			data !== undefined &&
			(isDeliveryInProgress(data) ||
				deliveryCheck(data) === "checking" ||
				withdrawalCorrection(data) === "pending")
				? DELIVERY_POLL_MS
				: false,
	});
	// Approvals bind the reviewed copy digest; a changed package requires a fresh read.
	const contentDigest = feedbackQuery.data?.contentDigest ?? undefined;
	const approval = useMutation({
		...createFeedbackDecisionMutation(),
		// Settled, not succeeded: a decision refused because someone else decided first still means
		// the lists and counts have moved. Returned, so the decision stays pending until they are read
		// again: the buttons cannot be pressed twice on the record just decided, and whatever moves on
		// after it reads the queue as it is now.
		onSettled: async () =>
			invalidateWorkspaceReads(queryClient, workspaceSlug, READS_OF_FEEDBACK_DECISION),
		onSuccess: (decision) => {
			toast.success(
				decision.action === "APPROVE"
					? "Feedback approved. Delivery is being checked."
					: "Feedback rejected",
			);
		},
		onError: (error) => {
			toast.error("We could not save your decision on this feedback", {
				description: refusalSentence(error) ?? problemDetailOf(error),
			});
		},
	});

	const withdrawal = useMutation({
		...createFeedbackDecisionMutation(),
		onSettled: async () =>
			invalidateWorkspaceReads(queryClient, workspaceSlug, READS_OF_FEEDBACK_DECISION),
		onSuccess: (decision) => {
			toast.success(decision.action === "WITHDRAW" ? "Feedback withdrawn" : "Feedback restored");
		},
		onError: (error) => {
			toast.error("We could not change this feedback", { description: problemDetailOf(error) });
		},
	});

	const retry = useMutation({
		...retryFeedbackMutation(),
		onSettled: async () =>
			invalidateWorkspaceReads(queryClient, workspaceSlug, READS_OF_FEEDBACK_DECISION),
		onSuccess: () => {
			toast.success("Removing the feedback from the work again");
		},
		onError: (error) => {
			toast.error("We could not retry the withdrawal", { description: problemDetailOf(error) });
		},
	});

	const recheck = useMutation({
		...recheckFeedbackDeliveryMutation(),
		onSettled: async () =>
			invalidateWorkspaceReads(queryClient, workspaceSlug, READS_OF_FEEDBACK_DECISION),
		onSuccess: () => {
			toast.success("Checking whether the comment appeared", {
				description: "This posts nothing.",
			});
		},
		onError: (error) => {
			toast.error("We could not check delivery", { description: problemDetailOf(error) });
		},
	});

	const decided = {
		onSuccess: onDecided,
		// A refusal is read by what it left behind, not by its status: "already decided" and "no
		// longer eligible" took the feedback out of approval, while "sending is paused" left it waiting
		// — and the re-read above has already landed by the time this runs.
		onError: () => {
			const state = queryClient.getQueryData(feedbackOptions.queryKey)?.state;
			if (state !== undefined && state !== "AWAITING_APPROVAL") {
				onDecided?.();
			}
		},
	};
	return {
		feedback: panelState(feedbackQuery, (feedback) => ({ status: "ready" as const, feedback })),
		isDeciding: approval.isPending,
		onApprove: () => approval.mutate({ path, body: { action: "APPROVE", contentDigest } }, decided),
		onReject: (reasonCode, note) =>
			approval.mutate(
				{ path, body: { action: "REJECT", reasonCode, note, contentDigest } },
				decided,
			),
		isChangingWithdrawal: withdrawal.isPending,
		onChangeWithdrawal: async (withdrawn, note) =>
			withdrawal.mutateAsync({
				path,
				body: { action: withdrawn ? "WITHDRAW" : "RESTORE", note },
			}),
		isRetryingWithdrawal: retry.isPending,
		onRetryWithdrawal: () => retry.mutate({ path }),
		isCheckingDelivery: recheck.isPending,
		onCheckDelivery: () => recheck.mutate({ path }),
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
		total: query.data?.totalElements ?? ids.length,
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
		const page = await queryClient.query({
			...awaitingApprovalOptions(workspaceSlug, APPROVAL_QUEUE_SIZE),
			retry: false,
		});
		return page.content.find((item) => item.id !== feedbackId)?.id;
	};
}
