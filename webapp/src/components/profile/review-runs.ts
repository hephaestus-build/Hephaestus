import type {
	FeedbackResponseRequest,
	ObservationDetail,
	PracticeGroupReviewRun,
} from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";

/** The review-run feed of one practice level, with its paging while earlier runs exist. */
export type ReviewRunFeedState = PanelState<{
	runs: PracticeGroupReviewRun[];
	hasMore: boolean;
	isLoadingMore: boolean;
	onLoadMore: () => void;
}>;

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
export type { FeedbackUsefulness } from "@/components/practice-vocabulary/feedback-usefulness-defs";
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
		usefulness: observation.feedbackUsefulness,
		resolution: observation.feedbackResolution,
		comment: observation.feedbackResponseComment,
	};
}
