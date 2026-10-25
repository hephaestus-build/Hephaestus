import type {
	FeedbackResponse,
	FeedbackResponseRequest,
	OwnFeedbackRef,
	ProfileReview,
	ResultList,
	ReviewRef,
} from "@/api/types.gen";
import type { PanelState } from "@/components/common/panel-state";
import { hasText } from "@/lib/text";
import {
	type InfiniteQueryLike,
	infiniteListState,
	type MorePages,
} from "@/runtime/tanstack-query/infinite-list";

/**
 * What a review's card draws: when the review was and of which work, its observations about the
 * reader and the reader's feedback from it. A listed review carries more; an observation read on its
 * own carries only this.
 */
export interface ReviewEntry {
	review: Pick<ReviewRef, "id" | "createdAt" | "item">;
	results: ResultList[];
	feedback: OwnFeedbackRef[];
}

export type ReviewFeedState = PanelState<{ reviews: ProfileReview[] } & MorePages>;

export const EMPTY_REVIEW_FEED = {
	status: "ready",
	reviews: [],
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: () => undefined,
} satisfies ReviewFeedState;

export function reviewFeedState(
	query: InfiniteQueryLike<{ content?: ProfileReview[] }>,
): ReviewFeedState {
	return infiniteListState(query, (pages) => ({
		reviews: pages.flatMap((page) => page.content ?? []),
	}));
}

/**
 * The reader's response to each piece of their feedback, as a route wires it: one object handed
 * down the feed to every piece. A result can be the basis of several pieces of feedback, so every
 * write names the feedback, never the result.
 */
export interface FeedbackResponseControls {
	/**
	 * Applies `change` over the response that stands — the one being written, else `recorded` — and
	 * writes the complete result, so a new resolution keeps the usefulness and a new usefulness
	 * keeps the resolution. A key present with no value removes that dimension. A response with nothing
	 * left in it is cleared.
	 */
	write: (
		feedbackId: string,
		recorded: FeedbackResponse | null,
		change: FeedbackResponseRequest,
	) => void;
	clear: (feedbackId: string) => void;
	responseOf: (feedbackId: string, recorded: FeedbackResponse | null) => FeedbackResponseRequest;
	/**
	 * The response being written to each piece of feedback, from whichever surface; a clear is the
	 * empty response. A surface shows it over the recorded one, and its buttons wait until the write
	 * lands.
	 */
	pendingResponses: ReadonlyMap<string, FeedbackResponseRequest>;
}

export function isEmptyFeedbackResponse(response: FeedbackResponseRequest): boolean {
	return (
		response.usefulness === undefined &&
		response.resolution === undefined &&
		!hasText(response.comment?.trim())
	);
}

export function responseRequestOf(
	response: FeedbackResponse | null | undefined,
): FeedbackResponseRequest {
	return {
		usefulness: response?.usefulness ?? undefined,
		resolution: response?.resolution ?? undefined,
		comment: response?.comment ?? undefined,
	};
}

export function resultSummary({
	summary,
	practiceName,
}: Pick<ResultList, "summary" | "practiceName">) {
	const trimmed = summary?.trim();
	return hasText(trimmed) ? trimmed : practiceName;
}
