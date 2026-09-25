import { type QueryClient, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { toast } from "sonner";

import {
	deleteFeedbackResponseMutation,
	getInAppFeedbackOptions,
	getInAppFeedbackQueryKey,
	getPracticeProfileOverviewQueryKey,
	listPracticeGroupReviewRunsInfiniteQueryKey,
	replaceFeedbackResponseMutation,
} from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponseRequest, PracticeGroup } from "@/api/types.gen";
import { type LoadState, queryLoadState } from "@/components/common/panel-state";
import { toFeedbackCard } from "@/components/practice-profile/practice-feedback-cards";
import type { FeedbackUsefulness } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import type {
	FeedbackComment,
	FeedbackRatingProps,
	PracticeFeedbackCardEntry,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { filedUnder, pathString, usePendingMutationIds } from "@/hooks/use-pending-mutation-ids";
import { problemDetailOf } from "@/lib/problem-detail";
import { hasText } from "@/lib/text";

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
 * Every query that carries the developer's response to a piece of feedback: the cards, the
 * overview and — when the response was written on a group's level — that group's review-run feed,
 * whose observations carry the response too. A write on any surface refreshes them all, so a card
 * behind the drawer never reads a response the level has already replaced. The feed's key is
 * built with no `query`, which matches the feed at every page size and practice.
 */
export function invalidateFeedbackResponses(
	queryClient: QueryClient,
	workspaceSlug: string,
	groupSlug?: string,
) {
	void queryClient.invalidateQueries({
		queryKey: getInAppFeedbackQueryKey({ path: { workspaceSlug } }),
	});
	void queryClient.invalidateQueries({
		queryKey: getPracticeProfileOverviewQueryKey({ path: { workspaceSlug } }),
	});
	if (hasText(groupSlug)) {
		void queryClient.invalidateQueries({
			queryKey: listPracticeGroupReviewRunsInfiniteQueryKey({ path: { workspaceSlug, groupSlug } }),
		});
	}
}

/**
 * Every write of a feedback response, whatever surface it was made on, so the rating buttons of
 * the piece of feedback being written wait wherever it is on screen — a card on the page and the
 * same feedback on a practice level are one write.
 */
export const FEEDBACK_RESPONSE_WRITE_KEY = ["feedback-response", "write"];

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
 * Nothing in flight and nothing to wait for. A query held back by `enabled` reports `isPending`
 * for as long as it is held, which a page reads as a skeleton that never resolves; with the
 * reading disabled the feedback is settled and empty instead, as `usePracticeGroupDetail`'s feed
 * is while its level is closed.
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
 * "Helpful" and "Not helpful" replace the usefulness, Send adds the comment (and disputes the
 * feedback when the reason is "Not accurate"), and pressing the chosen rating again withdraws it.
 * Whether the comment band is open is the page's alone.
 */
export function useInAppFeedback({
	workspaceSlug,
	groups,
	enabled = true,
}: InAppFeedbackRequest): InAppFeedback {
	const queryClient = useQueryClient();
	const [openComment, setOpenComment] = useState<string>();
	const feedbackQuery = useQuery({
		...getInAppFeedbackOptions({ path: { workspaceSlug } }),
		enabled,
	});
	const feedback = feedbackQuery.data ?? [];
	const responseOf = (feedbackId: string) =>
		feedback.find((item) => item.id === feedbackId)?.response;
	const written = (_result: unknown, variables: { path: { feedbackId: string } }) =>
		invalidateFeedbackResponses(
			queryClient,
			workspaceSlug,
			feedback.find((item) => item.id === variables.path.feedbackId)?.groupSlug,
		);
	const replaceMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, replaceFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) => toast.error(problemDetailOf(error, "Could not save your rating")),
	});
	const deleteMutation = useMutation({
		...filedUnder(FEEDBACK_RESPONSE_WRITE_KEY, deleteFeedbackResponseMutation()),
		onSuccess: written,
		onError: (error) => toast.error(problemDetailOf(error, "Could not withdraw your rating")),
	});

	const write = (feedbackId: string, body: FeedbackResponseRequest) => {
		if (body.usefulness === undefined && body.resolution === undefined) {
			deleteMutation.mutate({ path: { workspaceSlug, feedbackId } });
			return;
		}
		replaceMutation.mutate({ path: { workspaceSlug, feedbackId }, body });
	};
	const rate = (feedbackId: string, usefulness: FeedbackUsefulness) => {
		const next = nextRating(responseOf(feedbackId), usefulness);
		write(feedbackId, next);
		setOpenComment(next.usefulness === undefined ? undefined : feedbackId);
	};
	const send = (feedbackId: string, comment: FeedbackComment) => {
		const current = responseOf(feedbackId);
		const text = comment.comment.trim() || undefined;
		write(feedbackId, {
			usefulness: current?.usefulness,
			// "Not accurate" is the wire's dispute, which needs the sentence the band just collected.
			resolution:
				comment.reason === "not-accurate" && text !== undefined ? "DISPUTED" : current?.resolution,
			comment: text,
		});
		setOpenComment(undefined);
	};
	const pendingFeedbackIds = usePendingMutationIds(FEEDBACK_RESPONSE_WRITE_KEY, (variables) =>
		pathString(variables, "feedbackId"),
	);

	return {
		cards: feedback.map((item) => toFeedbackCard(item, groups)),
		ratingProps: (feedbackId) => ({
			usefulness: responseOf(feedbackId)?.usefulness,
			commentOpen: openComment === feedbackId,
			isPending: pendingFeedbackIds.has(feedbackId),
			onRate: (usefulness) => rate(feedbackId, usefulness),
			onSendComment: (comment) => send(feedbackId, comment),
			onSkipComment: () => setOpenComment(undefined),
		}),
		state: enabled ? queryLoadState(feedbackQuery) : SETTLED_EMPTY,
	};
}
