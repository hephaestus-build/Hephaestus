import type {
	FeedbackResponseRequest,
	ObservationDetail,
	PracticeGroupReviewRun,
	PracticeGroupReviewRunsPage,
} from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import {
	type InfiniteQueryLike,
	infiniteListState,
	type MorePages,
} from "@/runtime/tanstack-query/infinite-list";

/** The review-run feed of one practice level, with its paging while earlier runs exist. */
export type ReviewRunFeedState = PanelState<{ runs: PracticeGroupReviewRun[] } & MorePages>;

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

/** One infinite query as the feed's state: failed, loading, or the loaded pages as one list. */
export function reviewRunFeedState(
	query: InfiniteQueryLike<PracticeGroupReviewRunsPage>,
): ReviewRunFeedState {
	return infiniteListState(query, (pages) => ({ runs: pages.flatMap((page) => page.content) }));
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
