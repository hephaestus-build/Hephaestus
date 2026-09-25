import type { InfiniteData } from "@tanstack/react-query";

import type {
	FeedbackResponseRequest,
	ObservationDetail,
	PracticeGroupReviewRun,
	PracticeGroupReviewRunsPage,
} from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { loadedPages } from "@/runtime/tanstack-query/spring-page";

/** The review-run feed of one practice level, with its paging while earlier runs exist. */
export type ReviewRunFeedState = PanelState<{
	runs: PracticeGroupReviewRun[];
	hasMore: boolean;
	isLoadingMore: boolean;
	onLoadMore: () => void;
}>;

/**
 * A level with no runs of its own: ready, empty, and with nothing more to load — `hasMore: false`
 * keeps the paging button off screen, so the callback is never reached.
 */
export const EMPTY_REVIEW_RUN_FEED: ReviewRunFeedState = {
	status: "ready",
	runs: [],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: () => undefined,
};

/**
 * How every review-run feed pages: a page says whether another follows and which number it is, and
 * the feed ends where it does not. The developer's own level and an admin's user view read the
 * same endpoint shape, so the rule is written once.
 */
export function nextReviewRunPage(lastPage: PracticeGroupReviewRunsPage): number | undefined {
	return lastPage.hasNext === true ? (lastPage.page ?? 0) + 1 : undefined;
}

/** The slice of an infinite query the feed reads, so a hook hands its result in as it is. */
interface ReviewRunQuery {
	isError: boolean;
	error: unknown;
	isPending: boolean;
	data: InfiniteData<PracticeGroupReviewRunsPage> | undefined;
	hasNextPage: boolean;
	isFetchingNextPage: boolean;
	refetch: () => unknown;
	fetchNextPage: () => unknown;
}

/** One infinite query as the feed's state: failed, loading, or the loaded pages as one list. */
export function reviewRunFeedState(query: ReviewRunQuery): ReviewRunFeedState {
	if (query.isError) {
		return {
			status: "error",
			error: query.error,
			onRetry: () => {
				void query.refetch();
			},
		};
	}
	if (query.isPending) {
		return { status: "loading" };
	}
	return {
		status: "ready",
		runs: loadedPages(query.data).flatMap((page) => page.content),
		hasMore: query.hasNextPage,
		isLoadingMore: query.isFetchingNextPage,
		onLoadMore: () => {
			void query.fetchNextPage();
		},
	};
}

/**
 * The reader's response to an observation, as the route wires it: one object handed down the feed
 * to every row. Whether a row is open is the row's own — the feed carries every observation in
 * full, so nothing is loaded when one opens and there is nothing for the route to hold.
 */
export interface ObservationControls {
	onRespond?: (observation: ObservationDetail, response: FeedbackResponse) => void;
	/** The feedback whose response is being written; its row's buttons wait. */
	pendingFeedbackId?: string;
}
/** Complete replacement payload for a feedback response. */
export type FeedbackResponse = FeedbackResponseRequest;
export function isEmptyFeedbackResponse(response: FeedbackResponse): boolean {
	return (
		response.usefulness === undefined &&
		response.resolution === undefined &&
		(response.comment === undefined || response.comment.trim() === "")
	);
}
export function feedbackResponseOf(observation: ObservationDetail): FeedbackResponse {
	return {
		usefulness: observation.feedbackResponse?.usefulness,
		resolution: observation.feedbackResponse?.resolution,
		comment: observation.feedbackResponse?.comment,
	};
}
