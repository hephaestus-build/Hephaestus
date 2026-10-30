import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { toast } from "@/lib/toast";

import { requestReviewMutation } from "@/api/@tanstack/react-query.gen";
import type { CreateReviewRequest, RequestReviewData, ReviewRequestOutcome } from "@/api/types.gen";
import { PRACTICE_REVIEW_READS } from "@/components/admin/practice-reviews/review-search";
import { ITEM_EVENT_REASON_SENTENCES } from "@/components/practice-vocabulary/item-event-defs";
import { problemDetailOf } from "@/lib/problem-detail";
import { invalidateWorkspaceReads } from "@/runtime/tanstack-query/invalidate-workspace-reads";

const READS_OF_STARTED_REVIEW: ReadonlySet<string> = new Set([
	...PRACTICE_REVIEW_READS,
	"getOwnItemTrace",
	"listOwnItemReviews",
	"listOwnReviews",
	"getOwnPracticeProfileOverview",
]);

export interface ReviewRefusal {
	workspaceSlug: string;
	itemId: string;
	outcome: ReviewRequestOutcome;
}

/** The review an accepted ask created, and the work it was asked about. */
export interface AcceptedReviewRequest {
	itemId: string;
	reviewId: string;
}

interface RequestPracticeReviewOptions {
	/**
	 * Whether a level on screen shows this item, and so says its refusal inline; asked when the
	 * answer lands, since the reader may have moved on by then. Anywhere else a refusal is a toast.
	 */
	showsInline?: (itemId: string) => boolean;
}

/** Keep a refusal inline only while its item level is visible; navigation can change before the answer arrives. */
export function useRequestPracticeReview(
	workspaceSlug: string,
	{ showsInline }: RequestPracticeReviewOptions = {},
) {
	const queryClient = useQueryClient();
	const [refusal, setRefusal] = useState<ReviewRefusal>();
	// Which ask may answer inline; earlier requests still get their answer in a toast.
	const latestAsk = useRef<Pick<RequestReviewData, "path" | "body"> | undefined>(undefined);
	// A level that closed before the answer landed can no longer say it.
	const mounted = useRef(false);
	useEffect(() => {
		mounted.current = true;
		return () => {
			mounted.current = false;
		};
	}, []);
	const mutation = useMutation({
		...requestReviewMutation(),
		onSuccess: (outcome, submitted) => {
			const { path, body } = submitted;
			if (outcome.status === "REFUSED") {
				// Asked in a workspace the reader has since left, so its fix and settings are not this
				// page's to show; the refusal kept below is read only in the workspace it was asked in.
				const here = path.workspaceSlug === workspaceSlug;
				if (
					mounted.current &&
					here &&
					submitted === latestAsk.current &&
					showsInline?.(body.itemId) === true
				) {
					setRefusal({ workspaceSlug: path.workspaceSlug, itemId: body.itemId, outcome });
				} else {
					toast.warning("Review request declined", {
						description:
							outcome.reason === null ? undefined : ITEM_EVENT_REASON_SENTENCES[outcome.reason],
					});
				}
				return;
			}
			void invalidateWorkspaceReads(queryClient, path.workspaceSlug, READS_OF_STARTED_REVIEW);
			// Accepted is not started: the new review's own read says whether it is queued or running.
			toast.success("Review requested");
		},
		onError: (error) =>
			toast.error("We could not request the review", {
				description: problemDetailOf(error, "Try again in a moment."),
			}),
	});
	// The answer to the latest ask, read off the mutation rather than kept beside it: a new ask, an
	// error or a refusal leaves no acceptance to show. An ask from another workspace is not this one's.
	const { data, variables } = mutation;
	const asked = variables?.path.workspaceSlug === workspaceSlug ? variables.body.itemId : undefined;
	const accepted: AcceptedReviewRequest | undefined =
		asked !== undefined && data?.status === "SUBMITTED" && data.reviewId !== null
			? { itemId: asked, reviewId: data.reviewId }
			: undefined;
	const submission = (itemId: string) => {
		// A new ask replaces the answer to the last one, so an alert never outlives its refusal.
		setRefusal(undefined);
		const body: CreateReviewRequest = { itemId };
		const submitted = { path: { workspaceSlug }, body };
		latestAsk.current = submitted;
		return submitted;
	};
	return {
		ask: (itemId: string) => {
			mutation.mutate(submission(itemId));
		},
		/**
		 * The same ask, for a caller whose own work spans it. It settles once the answer is handled:
		 * a failure was already reported above, so it resolves without an outcome instead of rejecting.
		 */
		askAndSettle: async (itemId: string): Promise<ReviewRequestOutcome | undefined> => {
			try {
				return await mutation.mutateAsync(submission(itemId));
			} catch {
				return undefined;
			}
		},
		asking: mutation.isPending ? asked : undefined,
		// An earlier ask can finish later; only the current mutation's answer belongs inline.
		refusal:
			refusal?.workspaceSlug === workspaceSlug && refusal.outcome === data ? refusal : undefined,
		/**
		 * The review the latest ask created. A caller that outlives the level it asked from shows it
		 * only on the work it was asked about.
		 */
		accepted,
	};
}
