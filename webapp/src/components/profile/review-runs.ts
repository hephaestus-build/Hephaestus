import type {
	FeedbackResponseRequest,
	ObservationDetail,
	PracticeGroupReviewRun,
} from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import {
	type InfiniteQueryLike,
	infiniteListState,
	type MorePages,
} from "@/runtime/tanstack-query/infinite-list";

/**
 * A feed of reviews, newest first, with its paging while earlier ones exist: a practice level's
 * reviews on one group, or the reviews of the reader's own work.
 */
export type ReviewRunFeedState<TRun = PracticeGroupReviewRun> = PanelState<
	{ runs: TRun[] } & MorePages
>;

/**
 * A level with no runs of its own: ready, empty, and with nothing more to load — `hasMore: false`
 * keeps the paging button off screen, so the callback is never reached.
 */
export const EMPTY_REVIEW_RUN_FEED = {
	status: "ready",
	runs: [],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: () => undefined,
} satisfies ReviewRunFeedState<never>;

/** One infinite query as the feed's state: failed, loading, or the loaded pages as one list. */
export function reviewRunFeedState<TRun>(
	query: InfiniteQueryLike<{ content: TRun[] }>,
): ReviewRunFeedState<TRun> {
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
