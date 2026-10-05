import { useState } from "react";

import type { FeedbackResponseRequest } from "@/api/types.gen";
import type {
	FeedbackBand,
	FeedbackRatingProps,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import {
	nextRating,
	nextResolution,
	withComment,
	withDispute,
	withoutDispute,
} from "@/hooks/use-in-app-feedback";

/** What the reader said about one piece of feedback, and the band still open under it. */
interface FeedbackRating {
	response: FeedbackResponseRequest;
	openBand?: FeedbackBand;
}

/**
 * The ratings a story holds in place of the server, keyed by feedback id: the same contract
 * `useInAppFeedback` fulfils on the wire, without a network, and on the hook's own `nextRating`,
 * `withComment` and `nextResolution` rules — a press on a rating opens the comment band; pressing the
 * chosen rating again withdraws it and closes the band; Send and Skip close the band and keep the
 * rating; Addressed and Not applicable replace the answer, and a second press takes it back;
 * Disagree opens the dispute's band, whose Send writes the dispute, and takes a standing one back. The
 * card's state is the fixture's, since closing it is the server's to decide.
 */
export function useFeedbackRatings() {
	const [ratings, setRatings] = useState<Record<string, FeedbackRating | undefined>>({});
	const update = (
		feedbackId: string,
		next: (current: FeedbackRating | undefined) => FeedbackRating | undefined,
	) => setRatings((current) => ({ ...current, [feedbackId]: next(current[feedbackId]) }));

	/** The card props that wire one piece of feedback to this state. */
	const ratingProps = (feedbackId: string): FeedbackRatingProps => ({
		usefulness: ratings[feedbackId]?.response.usefulness,
		resolution: ratings[feedbackId]?.response.resolution,
		openBand: ratings[feedbackId]?.openBand,
		onRate: (usefulness) =>
			update(feedbackId, (current) => {
				const response = nextRating(current?.response, usefulness);
				return { response, openBand: response.usefulness === undefined ? undefined : "comment" };
			}),
		onSendComment: (comment) =>
			update(
				feedbackId,
				(current) => current && { response: withComment(current.response, comment) },
			),
		onSkipComment: () => update(feedbackId, (current) => current && { response: current.response }),
		onDisagree: () =>
			update(feedbackId, (current) =>
				current?.response.resolution === "DISPUTED"
					? { response: withoutDispute(current.response) }
					: {
							response: current?.response ?? {},
							openBand: current?.openBand === "dispute" ? undefined : "dispute",
						},
			),
		onSendDispute: (comment) =>
			update(feedbackId, (current) => ({
				response: withDispute(current?.response, comment),
			})),
		onResolve: (answer) =>
			update(feedbackId, (current) => ({
				response: nextResolution(current?.response, answer),
				openBand: current?.openBand,
			})),
	});

	return { ratingProps };
}
