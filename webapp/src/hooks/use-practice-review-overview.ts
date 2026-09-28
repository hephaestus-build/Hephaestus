import { type UseQueryResult, useQuery } from "@tanstack/react-query";
import { subDays } from "date-fns";

import {
	getPracticeReviewOverviewOptions,
	listPracticeReviewFeedbackOptions,
	listPracticeReviewsOptions,
} from "@/api/@tanstack/react-query.gen";
import type { PracticeReviewOverview } from "@/api/types.gen";
import { ACTIVITY_RANGE_DEFS, type ActivityRange } from "@/components/activity/activity-range";
import type { OutcomeScope } from "@/components/admin/practice-reviews/review-outcomes";
import {
	ACTIVE_REVIEW_POLL_MS,
	type FeedbackSearch,
	feedbackQuery,
	type RunsSearch,
	runsQuery,
} from "@/components/admin/practice-reviews/review-search";
import {
	type PracticeReviewOverviewState,
	type PreviousReviewPeriodState,
	type ReviewCountState,
	toSectionState,
} from "@/components/admin/practice-reviews/review-states";
import type { ReviewAttentionProps } from "@/components/admin/practice-reviews/ReviewAttention";
import { panelState } from "@/components/common/panel-state";
import {
	AWAITING_APPROVAL_SEARCH,
	awaitingApprovalOptions,
} from "@/hooks/use-feedback-awaiting-approval";
import { browserTimeZone } from "@/lib/dates";
import { keepSameSubject } from "@/runtime/tanstack-query/keep-same-subject";

/**
 * What the reviews did from `from` until now, in the reader's time zone, re-read while a review in
 * it is still queued or running. While another range loads, the previous range's figures stand in,
 * marked stale.
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

/**
 * The period of the same length before `from`, for the overview's totals to be set against. A read
 * of its own, so the overview never waits for it, and nothing but the totals pays for it. It is not
 * re-read, since nothing in it moves.
 */
export function usePreviousReviewPeriod(
	workspaceSlug: string,
	from: Date,
	range: ActivityRange,
): PreviousReviewPeriodState {
	const { days, previous: name } = ACTIVITY_RANGE_DEFS[range];
	const query = useQuery(
		getPracticeReviewOverviewOptions({
			path: { workspaceSlug },
			query: { from: subDays(from, days), to: from, zone: browserTimeZone() },
		}),
	);
	return panelState(query, (overview) => ({
		status: "ready" as const,
		period: { overview, name },
	}));
}

type ReviewAttentionReads = Omit<ReviewAttentionProps, "workspaceSlug" | "rangeInSentence">;

/** A count read as the total of a one-row page: the problems line needs how many, never which. */
const COUNT_ONLY = 1;

/** A one-row page's total, stale while another range's stands in for the range just chosen. */
function toCountState(
	query: UseQueryResult<{ page?: { totalElements?: number } }, unknown>,
): ReviewCountState {
	return panelState(query, (page) => ({
		status: "ready" as const,
		total: page.page?.totalElements ?? 0,
		stale: query.isPlaceholderData,
	}));
}

/**
 * What an admin owes the reviews: every piece of feedback awaiting approval, whenever it was
 * composed and oldest first, and how much failed within `scope`. Each group reads the very list it
 * links to, so the two cannot disagree. The approvals are the admin navigation's read too — one
 * cache entry — and are re-read on the overview's schedule, so the page and its navigation never
 * show two different answers to "how many await approval".
 */
export function useReviewAttention(
	workspaceSlug: string,
	scope: OutcomeScope,
	overview: PracticeReviewOverviewState,
): ReviewAttentionReads {
	const range = { from: scope.from, to: scope.to };
	const failedDeliveries: Partial<FeedbackSearch> = {
		...range,
		deliveryState: ["FAILED", "PARTIALLY_FAILED"],
	};
	const failedReviews: Partial<RunsSearch> = { ...range, status: ["FAILED", "TIMED_OUT"] };
	const unprocessedResults: Partial<RunsSearch> = { ...range, resultProcessing: ["FAILED"] };
	const path = { workspaceSlug };
	const live = {
		placeholderData: keepSameSubject({ workspaceSlug }),
		refetchInterval:
			overview.status === "ready" && reviewsActive(overview.overview)
				? ACTIVE_REVIEW_POLL_MS
				: false,
	} as const;
	const approvalsQuery = useQuery({ ...awaitingApprovalOptions(workspaceSlug), ...live });
	const failedDeliveriesQuery = useQuery({
		...listPracticeReviewFeedbackOptions({
			path,
			query: feedbackQuery(failedDeliveries, COUNT_ONLY),
		}),
		...live,
	});
	const failedReviewsQuery = useQuery({
		...listPracticeReviewsOptions({ path, query: runsQuery(failedReviews, COUNT_ONLY) }),
		...live,
	});
	const unprocessedResultsQuery = useQuery({
		...listPracticeReviewsOptions({ path, query: runsQuery(unprocessedResults, COUNT_ONLY) }),
		...live,
	});
	return {
		approvals: {
			state: toSectionState(approvalsQuery),
			list: { list: "feedback", search: AWAITING_APPROVAL_SEARCH },
		},
		failedReviews: {
			state: toCountState(failedReviewsQuery),
			list: { list: "runs", search: failedReviews },
		},
		unprocessedResults: {
			state: toCountState(unprocessedResultsQuery),
			list: { list: "runs", search: unprocessedResults },
		},
		failedDeliveries: {
			state: toCountState(failedDeliveriesQuery),
			list: { list: "feedback", search: failedDeliveries },
		},
	};
}

function reviewsActive({ reviews }: PracticeReviewOverview): boolean {
	return reviews.queued + reviews.running > 0;
}
