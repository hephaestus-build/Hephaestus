import type { InfiniteData } from "@tanstack/react-query";

import type {
	FeedbackResponseRequest,
	ObservationDetail,
	PracticeGroupReviewRun,
	PracticeGroupReviewRunsPage,
} from "@/api/types.gen";
import { type PanelState, queryLoadState } from "@/components/common/panel-state";
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
 * the feed ends where it does not.
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
	const state = queryLoadState(query);
	if (state.status !== "ready") {
		return state;
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
	onRespond?: (observation: ObservationDetail, response: FeedbackResponseRequest) => void;
	/**
	 * The response being written to each piece of feedback; a row shows it over the one it arrived
	 * with, and its buttons wait until the write lands.
	 */
	pendingResponses?: ReadonlyMap<string, FeedbackResponseRequest>;
}

export function isEmptyFeedbackResponse(response: FeedbackResponseRequest): boolean {
	return (
		response.usefulness === undefined &&
		response.resolution === undefined &&
		(response.comment === undefined || response.comment.trim() === "")
	);
}
