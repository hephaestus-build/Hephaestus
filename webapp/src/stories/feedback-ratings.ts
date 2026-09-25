import { useState } from "react";

import type { FeedbackResponseRequest } from "@/api/types.gen";
import type { FeedbackRatingProps } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { nextRating, nextResolution, withComment } from "@/hooks/use-in-app-feedback";

/**
 * What the reader said about one piece of feedback, and whether the comment band is still open
 * under it.
 */
interface FeedbackRating {
	response: FeedbackResponseRequest;
	commentOpen: boolean;
}

/**
 * The ratings a story holds in place of the server, keyed by feedback id: the same contract
 * `useInAppFeedback` fulfils on the wire, without a network, and on the hook's own `nextRating`,
 * `withComment` and `nextResolution` rules — a press on a rating opens the comment band; pressing the
 * chosen rating again withdraws it and closes the band; Send and Skip close the band and keep the
 * rating; Addressed and Not applicable replace the answer, and a second press takes it back. The
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
		commentOpen: ratings[feedbackId]?.commentOpen ?? false,
		onRate: (usefulness) =>
			update(feedbackId, (current) => {
				const response = nextRating(current?.response, usefulness);
				return { response, commentOpen: response.usefulness !== undefined };
			}),
		onSendComment: (comment) =>
			update(
				feedbackId,
				(current) =>
					current && { response: withComment(current.response, comment), commentOpen: false },
			),
		onSkipComment: () =>
			update(feedbackId, (current) => current && { ...current, commentOpen: false }),
		onResolve: (answer) =>
			update(feedbackId, (current) => ({
				response: nextResolution(current?.response, answer),
				commentOpen: current?.commentOpen ?? false,
			})),
	});

	return { ratingProps };
}
