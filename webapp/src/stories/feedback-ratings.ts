import { useState } from "react";

import type { FeedbackResponseRequest } from "@/api/types.gen";
import type { FeedbackRatingProps } from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { nextRating } from "@/hooks/use-in-app-feedback";

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
 * `useInAppFeedback` fulfils on the wire, without a network, and on the hook's own `nextRating`
 * rule — a press on a rating opens the comment band; pressing the chosen rating again withdraws it
 * and closes the band; Send and Skip close the band and keep the rating.
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
		commentOpen: ratings[feedbackId]?.commentOpen ?? false,
		onRate: (usefulness) =>
			update(feedbackId, (current) => {
				const response = nextRating(current?.response, usefulness);
				return { response, commentOpen: response.usefulness !== undefined };
			}),
		onSendComment: ({ comment }) =>
			update(
				feedbackId,
				(current) => current && { response: { ...current.response, comment }, commentOpen: false },
			),
		onSkipComment: () =>
			update(feedbackId, (current) => current && { ...current, commentOpen: false }),
	});

	return { ratingProps };
}
