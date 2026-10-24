import { skipToken, useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useQueryReadRefusal } from "@/runtime/tanstack-query/use-query-read-refusal";

import {
	getOwnReviewOptions,
	listOwnReviewsInfiniteOptions,
	listOwnReviewsInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { ProfileReviewDetail, ReviewRef } from "@/api/types.gen";
import { ACTIVE_REVIEW_POLL_MS } from "@/components/admin/practice-reviews/review-search";
import { type PanelState, panelState } from "@/components/common/panel-state";
import { useNow } from "@/components/common/use-now";
import {
	PROFILE_REVIEWS_PAGE_SIZE,
	type ReviewOrder,
	reviewRangeStart,
	type ReviewTimeframe,
} from "@/components/practice-profile/practice-profile-search";
import {
	reviewPositionsOnItem,
	reviewTotalsOnItem,
} from "@/components/practice-trace/review-groups";
import type { ReviewStatus } from "@/components/practice-vocabulary/review-status-defs";
import {
	EMPTY_REVIEW_FEED,
	type ReviewFeedState,
	reviewFeedState,
} from "@/components/profile/reviews";
import { useInvalidateOnSettle } from "@/hooks/use-invalidate-on-settle";
import { PRACTICE_PROFILE_POLL_MS } from "@/hooks/use-practice-profile-overview";
import { useRequestPracticeReview } from "@/hooks/use-request-practice-review";
import { retryQuery } from "@/runtime/tanstack-query/query-defaults";
import { springPageParams } from "@/runtime/tanstack-query/spring-page";

interface ProfileReviewsRequest {
	workspaceSlug: string;
	/** True while a level that shows the list is open; a closed drawer reads nothing. */
	listOpen: boolean;
	reviewId?: string;
	itemKind?: string[];
	since?: ReviewTimeframe;
	/** Only reviews in these statuses. */
	status?: ReviewStatus[];
	/** Oldest first; absent is newest first. */
	order?: ReviewOrder;
	/** Only the reviews of this work, by its id. */
	itemId?: string;
}

/** The open review, with its results, the reader's feedback from it and what they may see of its trace. */
export type ProfileReviewOpenState = PanelState<{ detail: ProfileReviewDetail }>;

function isActive(review: ReviewRef | undefined): boolean {
	return review?.status === "QUEUED" || review?.status === "RUNNING";
}

export function usePracticeProfileReviews({
	workspaceSlug,
	listOpen,
	reviewId,
	itemKind,
	since,
	status,
	itemId,
	order,
}: ProfileReviewsRequest) {
	const now = useNow();
	const listOptions = listOwnReviewsInfiniteOptions({
		path: { workspaceSlug },
		query: {
			size: PROFILE_REVIEWS_PAGE_SIZE,
			itemKind,
			itemId,
			status,
			order,
			since: since === undefined ? undefined : reviewRangeStart(now, since),
		},
	});
	const reviewsQuery = useInfiniteQuery({
		...listOptions,
		...springPageParams,
		enabled: listOpen,
		refetchInterval: PRACTICE_PROFILE_POLL_MS,
	});
	useQueryReadRefusal(listOptions.queryKey, reviewsQuery.isError, reviewsQuery.error);
	const reviewOptions = getOwnReviewOptions({
		path: { workspaceSlug, reviewId: reviewId ?? "" },
	});
	const reviewQuery = useQuery({
		...reviewOptions,
		queryFn: reviewId === undefined ? skipToken : reviewOptions.queryFn,
		retry: retryQuery,
		refetchInterval: (query) =>
			isActive(query.state.data?.review) ? ACTIVE_REVIEW_POLL_MS : false,
	});
	const active = isActive(reviewQuery.data?.review);

	const list: ReviewFeedState = listOpen ? reviewFeedState(reviewsQuery) : EMPTY_REVIEW_FEED;
	// Refresh the slower-polled list when an open review ends so its final results appear.
	useInvalidateOnSettle(reviewId, active, [
		listOwnReviewsInfiniteQueryKey({ path: { workspaceSlug } }),
	]);

	const openItemId = reviewQuery.data?.review.item.id;
	// Asking is the list's alone, so a refusal is always a toast: no level says it inline.
	const request = useRequestPracticeReview(workspaceSlug);

	// This hook outlives the open review, so an acceptance is said on a review only about its own
	// work, and never as a link to itself. A failed refresh keeps the work read before it.
	const { accepted } = request;
	const acceptedHere =
		accepted !== undefined &&
		(reviewId === undefined || (accepted.itemId === openItemId && accepted.reviewId !== reviewId))
			? accepted
			: undefined;

	const open: ProfileReviewOpenState = panelState(reviewQuery, (detail) => ({
		status: "ready" as const,
		detail,
	}));

	// Positions read the list newest first, so an oldest-first list cannot count them either.
	const sequenced =
		list.status === "ready" &&
		!list.hasMore &&
		itemKind === undefined &&
		since === undefined &&
		status === undefined &&
		order === undefined;
	return {
		list,
		/** How many reviews the filters keep, from the server; absent until the first page is in. */
		total: reviewsQuery.data?.pages[0]?.totalElements,
		// Exact positions require every review of a work; filters or partial pages would undercount.
		// Narrowed to one work, the list holds every review of it, so they still count.
		positions: sequenced
			? reviewPositionsOnItem(list.reviews.map(({ review }) => review))
			: undefined,
		/** How many reviews each review's work has, under the same condition as the positions. */
		totals: sequenced ? reviewTotalsOnItem(list.reviews.map(({ review }) => review)) : undefined,
		open,
		onReviewNow: request.ask,
		requesting: request.asking,
		/** The review the latest ask created, for the list or for the open review of the same work. */
		accepted: acceptedHere,
	};
}
