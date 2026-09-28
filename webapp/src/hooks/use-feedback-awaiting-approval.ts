import { useQuery } from "@tanstack/react-query";

import { listPracticeReviewFeedbackOptions } from "@/api/@tanstack/react-query.gen";
import {
	type FeedbackSearch,
	feedbackQuery,
	REVIEW_PREVIEW_SIZE,
} from "@/components/admin/practice-reviews/review-search";

/**
 * Every piece of feedback awaiting approval, whenever it was composed and oldest first: a decision
 * owed has no range, and the one waiting longest is the one to take next.
 */
export const AWAITING_APPROVAL_SEARCH: Partial<FeedbackSearch> = {
	deliveryState: ["AWAITING_APPROVAL"],
	order: "OLDEST",
};

/**
 * The read of feedback awaiting approval: the first `size`, oldest first, and the total. At the
 * default size the admin navigation and Needs you share one cache entry, so their counts cannot
 * disagree; the approval queue reads the same search at a page of its own.
 */
export function awaitingApprovalOptions(workspaceSlug: string, size = REVIEW_PREVIEW_SIZE) {
	return listPracticeReviewFeedbackOptions({
		path: { workspaceSlug },
		query: feedbackQuery(AWAITING_APPROVAL_SEARCH, size),
	});
}

/**
 * How many pieces of feedback await approval in the workspace, for the admin navigation. Undefined
 * until it is known, and while `enabled` is off — for anyone who cannot approve. A decision refreshes
 * it with every other read of feedback, and the overview's re-reads refresh it while it is open.
 */
export function useFeedbackAwaitingApproval(
	workspaceSlug: string | undefined,
	enabled: boolean,
): number | undefined {
	const query = useQuery({
		...awaitingApprovalOptions(workspaceSlug ?? ""),
		enabled: enabled && workspaceSlug !== undefined,
	});
	return query.data?.page?.totalElements;
}
