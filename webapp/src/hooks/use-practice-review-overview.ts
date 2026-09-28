import { useQuery } from "@tanstack/react-query";

import {
	getPracticeReviewOverviewOptions,
	listPracticeReviewFeedbackOptions,
	listPracticeReviewsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeReviewOverview } from "@/api/types.gen";
import type { OutcomeScope } from "@/components/admin/practice-reviews/review-outcomes";
import {
	ACTIVE_REVIEW_POLL_MS,
	type FeedbackSearch,
	feedbackQuery,
	type RunsSearch,
	runsQuery,
	REVIEW_PREVIEW_SIZE,
} from "@/components/admin/practice-reviews/review-search";
import {
	type PracticeReviewOverviewState,
	toSectionState,
} from "@/components/admin/practice-reviews/review-states";
import type { ReviewAttentionProps } from "@/components/admin/practice-reviews/ReviewAttention";
import { panelState } from "@/components/common/panel-state";
import { browserTimeZone } from "@/lib/dates";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";

/**
 * What the reviews did from `from` until now, in the reader's time zone, re-read while a review in
 * it is still queued or running.
 */
export function usePracticeReviewOverview(
	workspaceSlug: string,
	from: Date,
): PracticeReviewOverviewState {
	const query = useQuery({
		...getPracticeReviewOverviewOptions({
			path: { workspaceSlug },
			query: { from, zone: browserTimeZone() },
		}),
		placeholderData: keepSameSubject({ workspaceSlug }),
		refetchInterval: ({ state: { data } }) =>
			data !== undefined && reviewsActive(data) ? ACTIVE_REVIEW_POLL_MS : false,
	});
	return panelState(query, (overview) => ({
		status: "ready" as const,
		overview,
		stale: query.isPlaceholderData,
	}));
}

type ReviewAttentionReads = Pick<
	ReviewAttentionProps,
	"approvals" | "failedDeliveries" | "failedReviews"
>;

/**
 * What an admin owes the reviews: every piece of feedback awaiting approval, whenever it was
 * composed, and what failed within `scope`. Each group reads the first few rows of the very list it
 * links to, so the two cannot disagree; it is re-read on the overview's schedule, so the page never
 * shows two different answers to "how many await approval".
 */
export function useReviewAttention(
	workspaceSlug: string,
	scope: OutcomeScope,
	overview: PracticeReviewOverviewState,
): ReviewAttentionReads {
	const approvals: Partial<FeedbackSearch> = { deliveryState: ["AWAITING_APPROVAL"] };
	const failedDeliveries: Partial<FeedbackSearch> = {
		from: scope.from,
		to: scope.to,
		deliveryState: ["FAILED", "PARTIALLY_FAILED"],
	};
	const failedReviews: Partial<RunsSearch> = {
		from: scope.from,
		to: scope.to,
		status: ["FAILED", "TIMED_OUT"],
	};
	const path = { workspaceSlug };
	const live = {
		placeholderData: keepSameSubject({ workspaceSlug }),
		refetchInterval:
			overview.status === "ready" && reviewsActive(overview.overview)
				? ACTIVE_REVIEW_POLL_MS
				: false,
	} as const;
	const approvalsQuery = useQuery({
		...listPracticeReviewFeedbackOptions({
			path,
			query: feedbackQuery(approvals, REVIEW_PREVIEW_SIZE),
		}),
		...live,
	});
	const failedDeliveriesQuery = useQuery({
		...listPracticeReviewFeedbackOptions({
			path,
			query: feedbackQuery(failedDeliveries, REVIEW_PREVIEW_SIZE),
		}),
		...live,
	});
	const failedReviewsQuery = useQuery({
		...listPracticeReviewsOptions({ path, query: runsQuery(failedReviews, REVIEW_PREVIEW_SIZE) }),
		...live,
	});
	return {
		approvals: {
			state: toSectionState(approvalsQuery),
			list: { list: "feedback", search: approvals },
		},
		failedDeliveries: {
			state: toSectionState(failedDeliveriesQuery),
			list: { list: "feedback", search: failedDeliveries },
		},
		failedReviews: {
			state: toSectionState(failedReviewsQuery),
			list: { list: "runs", search: failedReviews },
		},
	};
}

function reviewsActive({ reviews }: PracticeReviewOverview): boolean {
	return reviews.queued + reviews.running > 0;
}
