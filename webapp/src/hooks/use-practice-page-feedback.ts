import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useSpinDelay } from "spin-delay";

import { getOwnPracticePageFeedbackOptions } from "@/api/@tanstack/react-query.gen";
import type { FeedbackResponse, FeedbackResponseRequest, GroupRef } from "@/api/types.gen";
import { type LoadState, queryLoadState } from "@/components/common/panel-state";
import { toFeedbackCard } from "@/components/practice-profile/practice-feedback-cards";
import type { FeedbackUsefulness } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import type {
	FeedbackBand,
	FeedbackRatingProps,
	PracticeFeedbackCardEntry,
	ResolvingAnswer,
	ResponseControl,
} from "@/components/practice-vocabulary/PracticeFeedbackCard";
import { useFeedbackResponseWrite } from "@/hooks/use-feedback-response-write";

export interface PracticePageFeedbackRequest {
	workspaceSlug: string;
	groups: GroupRef[];
	/**
	 * Whether to read the cards at all. Reading them delivers them, so a page that may yet send the
	 * reader away holds the request until it knows it will not.
	 */
	enabled?: boolean;
}

export interface PracticePageFeedbackCards {
	cards: PracticeFeedbackCardEntry[];
	ratingProps: (feedbackId: string) => FeedbackRatingProps;
	state: LoadState;
}

/**
 * The response a press on a rating writes: the pressed rating, keeping the resolution and the
 * comment; pressed on the rating already chosen, the rating withdrawn with its note. A disagreement
 * is not the rating's to take back, so it keeps its reason, which the server requires.
 */
export function nextRating(
	current: FeedbackResponseRequest | undefined,
	pressed: FeedbackUsefulness,
): FeedbackResponseRequest {
	const withdrawing = current?.usefulness === pressed;
	const disagreed = current?.resolution === "DISAGREE";
	return {
		usefulness: withdrawing ? undefined : pressed,
		resolution: current?.resolution,
		comment: withdrawing && !disagreed ? undefined : current?.comment,
	};
}

// A disagreement owns the comment; opening a rating note would replace its reason.
export function opensNoteBand(response: FeedbackResponseRequest): boolean {
	return response.usefulness !== undefined && response.resolution !== "DISAGREE";
}

/**
 * The response a press on Addressed or Not applicable writes: the pressed answer in place of the one
 * before, a disagreement included, keeping the rating and the comment; pressed on the answer already
 * chosen, the answer taken back and the rest kept. The server closes the card on the first and
 * reopens it on the second.
 */
export function nextResolution(
	current: FeedbackResponseRequest | undefined,
	pressed: ResolvingAnswer,
): FeedbackResponseRequest {
	return {
		usefulness: current?.usefulness,
		resolution: current?.resolution === pressed ? undefined : pressed,
		comment: current?.comment,
	};
}

/** The response Send writes: the comment added under the rating it was written for. */
export function withComment(
	current: FeedbackResponseRequest | undefined,
	comment: string,
): FeedbackResponseRequest {
	return {
		usefulness: current?.usefulness,
		resolution: current?.resolution,
		comment: comment.trim() || undefined,
	};
}

/** Send in the disagreement band: it replaces any earlier answer and keeps the rating. */
export function withDisagreement(
	current: FeedbackResponseRequest | undefined,
	comment: string,
): FeedbackResponseRequest {
	return { usefulness: current?.usefulness, resolution: "DISAGREE", comment: comment.trim() };
}

/** Disagree on a standing disagreement: takes it back with its reason and keeps the rating. */
export function withoutDisagreement(
	current: FeedbackResponseRequest | undefined,
): FeedbackResponseRequest {
	return { usefulness: current?.usefulness, resolution: undefined, comment: undefined };
}

interface OpenBand {
	feedbackId: string;
	band: FeedbackBand;
}

/** The control a write came from. The mutation cannot say: its variables are the whole response. */
interface WriteFrom {
	feedbackId: string;
	control: ResponseControl;
}

// A disabled query stays pending; report settled absence so its page does not show a permanent skeleton.
const SETTLED_EMPTY: LoadState = { status: "ready" };

// Reading practice-page cards delivers them; hold the request until navigation is settled.
export function usePracticePageFeedback({
	workspaceSlug,
	groups,
	enabled = true,
}: PracticePageFeedbackRequest): PracticePageFeedbackCards {
	const [openBand, setOpenBand] = useState<OpenBand>();
	const [lastWrite, setLastWrite] = useState<WriteFrom>();
	const feedbackQuery = useQuery({
		...getOwnPracticePageFeedbackOptions({ path: { workspaceSlug } }),
		enabled,
	});
	const feedback = feedbackQuery.data ?? [];
	const responses = useFeedbackResponseWrite(workspaceSlug);
	const { pendingResponses } = responses;
	const recordedOf = (feedbackId: string): FeedbackResponse | undefined =>
		feedback.find((item) => item.id === feedbackId)?.response ?? undefined;
	const responseOf = (feedbackId: string) =>
		responses.responseOf(feedbackId, recordedOf(feedbackId));

	const writeFrom = (
		feedbackId: string,
		control: ResponseControl,
		response: FeedbackResponseRequest,
	) => {
		setLastWrite({ feedbackId, control });
		responses.write(feedbackId, recordedOf(feedbackId), response);
	};
	// Disable the SSR default so Saving does not appear immediately when the page mounts.
	const showSaving = useSpinDelay(
		lastWrite !== undefined && pendingResponses.has(lastWrite.feedbackId),
		{ delay: 1000, minDuration: 500, ssr: false },
	);

	const rate = (feedbackId: string, usefulness: FeedbackUsefulness) => {
		const next = nextRating(responseOf(feedbackId), usefulness);
		writeFrom(feedbackId, "rating", next);
		setOpenBand(opensNoteBand(next) ? { feedbackId, band: "comment" } : undefined);
	};
	const resolve = (feedbackId: string, answer: ResolvingAnswer) => {
		writeFrom(feedbackId, "answer", nextResolution(responseOf(feedbackId), answer));
	};
	const send = (feedbackId: string, comment: string) => {
		writeFrom(feedbackId, "send", withComment(responseOf(feedbackId), comment));
		setOpenBand(undefined);
	};
	const disagree = (feedbackId: string) => {
		const current = responseOf(feedbackId);
		if (current.resolution === "DISAGREE") {
			writeFrom(feedbackId, "disagree", withoutDisagreement(current));
			setOpenBand(undefined);
			return;
		}
		const open = openBand?.feedbackId === feedbackId && openBand.band === "disagreement";
		setOpenBand(open ? undefined : { feedbackId, band: "disagreement" });
	};
	const sendDisagreement = (feedbackId: string, comment: string) => {
		writeFrom(feedbackId, "send", withDisagreement(responseOf(feedbackId), comment));
		setOpenBand(undefined);
	};

	return {
		cards: feedback.map((item) => toFeedbackCard(item, groups)),
		ratingProps: (feedbackId) => ({
			usefulness: responseOf(feedbackId).usefulness,
			resolution: responseOf(feedbackId).resolution,
			openBand: openBand?.feedbackId === feedbackId ? openBand.band : undefined,
			isPending: pendingResponses.has(feedbackId),
			saving: showSaving && lastWrite?.feedbackId === feedbackId ? lastWrite.control : undefined,
			onRate: (usefulness) => rate(feedbackId, usefulness),
			onSendComment: (comment) => send(feedbackId, comment),
			onSkipComment: () => setOpenBand(undefined),
			onDisagree: () => disagree(feedbackId),
			onSendDisagreement: (comment) => sendDisagreement(feedbackId, comment),
			onResolve: (answer) => resolve(feedbackId, answer),
		}),
		state: enabled ? queryLoadState(feedbackQuery) : SETTLED_EMPTY,
	};
}
