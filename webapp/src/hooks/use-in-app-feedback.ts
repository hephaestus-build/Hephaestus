import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { getInAppFeedbackOptions } from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponseRequest, PracticeGroup } from "@/api/types.gen";
import { type LoadState, queryLoadState } from "@/components/common/panel-state";
import { toFeedbackCard } from "@/components/practice-profile/practice-feedback-cards";
import type { FeedbackUsefulness } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import type {
	FeedbackComment,
	FeedbackRatingProps,
	PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";

export interface InAppFeedbackRequest {
	workspaceSlug: string;
	/** The workspace's groups; a card's colour and icon are its group's. */
	groups: PracticeGroup[];
	/**
	 * Whether to read the cards at all. Reading them delivers them, so a page that may yet send the
	 * reader away holds the request until it knows it will not.
	 */
	enabled?: boolean;
}

export interface InAppFeedback {
	/** Every readable card, newest first; empty until loaded. */
	cards: PracticeFeedbackCardEntry[];
	/** The card props that wire one piece of feedback's rating to the server. */
	ratingProps: (feedbackId: string) => FeedbackRatingProps;
	state: LoadState;
}

/**
 * The response a press on a rating writes: the pressed rating, keeping the resolution and the
 * comment the reader gave before; pressed on the rating already chosen, the rating withdrawn,
 * comment and all. A dispute stands or falls with its comment, so withdrawing drops it too — the
 * server rejects a DISPUTED response that carries no comment. The comment band is open exactly
 * while a rating stands.
 */
export function nextRating(
	current: FeedbackResponseRequest | undefined,
	pressed: FeedbackUsefulness,
): FeedbackResponseRequest {
	const withdrawing = current?.usefulness === pressed;
	const resolution = current?.resolution;
	return {
		usefulness: withdrawing ? undefined : pressed,
		resolution: withdrawing && resolution === "DISPUTED" ? undefined : resolution,
		comment: withdrawing ? undefined : current?.comment,
	};
}

/**
 * The response Send writes: the comment added under the rating it was written for. "Not accurate"
 * is the wire's dispute, which needs the sentence the band just collected.
 */
export function withComment(
	current: FeedbackResponseRequest | undefined,
	{ reason, comment }: FeedbackComment,
): FeedbackResponseRequest {
	const text = comment.trim() || undefined;
	return {
		usefulness: current?.usefulness,
		resolution: reason === "not-accurate" && text !== undefined ? "DISPUTED" : current?.resolution,
		comment: text,
	};
}

/**
 * Nothing in flight and nothing to wait for. A query held back by `enabled` reports `isPending`
 * for as long as it is held, which a page reads as a skeleton that never resolves; with the
 * reading disabled the feedback is settled and empty instead.
 */
const SETTLED_EMPTY: LoadState = { status: "ready" };

/**
 * The developer's in-app practice feedback and the ratings on it.
 *
 * Reading the cards is what delivers them: the server flips an unread card to delivered on this
 * very GET, so a card reads as new exactly once. Each card arrives with the developer's current
 * response, so the list is the one query; a card whose response is being written says so on its
 * own rating buttons, and a refetch that adds a card never re-skeletons the list.
 *
 * A rating is written as the complete response, keeping the resolution the reader chose before:
 * "Helpful" and "Not helpful" replace the usefulness, Send adds the comment, and pressing the
 * chosen rating again withdraws it.
 */
export function useInAppFeedback({
	workspaceSlug,
	groups,
	enabled = true,
}: InAppFeedbackRequest): InAppFeedback {
	const [openComment, setOpenComment] = useState<string>();
	const feedbackQuery = useQuery({
		...getInAppFeedbackOptions({ path: { workspaceSlug } }),
		enabled,
	});
	const feedback = feedbackQuery.data ?? [];
	const { write, pendingResponses } = useFeedbackResponseWrite(
		workspaceSlug,
		(feedbackId) => feedback.find((item) => item.id === feedbackId)?.groupSlug,
	);
	// The response a write is carrying stands over the recorded one until the refetch brings it back.
	const responseOf = (feedbackId: string): FeedbackResponseRequest | undefined =>
		pendingResponses.get(feedbackId) ?? feedback.find((item) => item.id === feedbackId)?.response;

	const rate = (feedbackId: string, usefulness: FeedbackUsefulness) => {
		const next = nextRating(responseOf(feedbackId), usefulness);
		write(feedbackId, next);
		setOpenComment(next.usefulness === undefined ? undefined : feedbackId);
	};
	const send = (feedbackId: string, comment: FeedbackComment) => {
		write(feedbackId, withComment(responseOf(feedbackId), comment));
		setOpenComment(undefined);
	};

	return {
		cards: feedback.map((item) => toFeedbackCard(item, groups)),
		ratingProps: (feedbackId) => ({
			usefulness: responseOf(feedbackId)?.usefulness,
			commentOpen: openComment === feedbackId,
			isPending: pendingResponses.has(feedbackId),
			onRate: (usefulness) => rate(feedbackId, usefulness),
			onSendComment: (comment) => send(feedbackId, comment),
			onSkipComment: () => setOpenComment(undefined),
		}),
		state: enabled ? queryLoadState(feedbackQuery) : SETTLED_EMPTY,
	};
}
