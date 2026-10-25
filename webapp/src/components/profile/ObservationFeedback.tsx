import type { ReactElement } from "react";

import { cn } from "cn";
import type { OwnFeedbackRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { StatusBadge } from "@/components/common/StatusBadge";
import { useNow } from "@/components/common/use-now";
import { DELIVERY_STATE_DEFS } from "@/components/practice-vocabulary/delivery-state-defs";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import { FEEDBACK_RESOLUTION_DEFS } from "@/components/practice-vocabulary/feedback-resolution-defs";
import { FEEDBACK_USEFULNESS_DEFS } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import { formatDayTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

/**
 * Where a piece of feedback that lives in Hephaestus itself opens, as a link to render: the
 * practice's Feedback tab for feedback on the practice page, the conversation with Heph for feedback
 * raised there. Feedback on the work links to its comment from its own copies.
 */
export interface ObservationFeedbackLinks {
	/** The way to this piece's own card on the practice's Feedback tab, by its feedback id. */
	practicePage?: (feedbackId: string) => ReactElement;
	conversation?: ReactElement;
}

export interface ObservationFeedbackProps {
	feedback: OwnFeedbackRef[];
	links?: ObservationFeedbackLinks;
}

/**
 * The block's links in the accent and underlined at rest, so the way to the feedback reads as a
 * link before a hover, as the guide's outbound links do.
 */
const LINK_AT_REST = "text-mentor underline";

/** The feedback card's edge, so the feedback an observation gave reads as the card it became. */
const FEEDBACK_BOX = "rounded-xl border border-foreground/15 bg-background p-3 shadow-sm";

/**
 * The feedback an observation gave, one box each: where it went (on the work, on the practice page,
 * in the conversation with Heph), its delivery state as every feedback surface names it, and the way
 * to read it there once it was delivered.
 * Nothing here answers it: the reader responds where the feedback is. An observation has no rating
 * of its own (the server records responses per piece of feedback only). A response already given is
 * said in one read-only line.
 */
export function ObservationFeedback({ feedback, links }: ObservationFeedbackProps) {
	const today = new Date(useNow());
	if (feedback.length === 0) {
		return null;
	}
	return (
		<ul aria-label="Feedback from this observation" className="flex min-w-0 flex-col gap-2">
			{feedback.map((piece, index) => (
				<li
					key={piece.id}
					data-observation-feedback=""
					className={cn("flex min-w-0 flex-col gap-2", FEEDBACK_BOX)}
				>
					<p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
						{/* Numbered as an open observation numbers its problems; the list counts them already. */}
						<span
							aria-hidden
							data-destination-number=""
							className="flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-foreground tabular-nums"
						>
							{index + 1}
						</span>
						<StatusBadge def={FEEDBACK_DESTINATION_DEFS[piece.destination]} />
						<StatusBadge def={DELIVERY_STATE_DEFS[piece.state]} />
						{piece.state === "DELIVERED" && piece.deliveredAt !== null && (
							<time dateTime={piece.deliveredAt.toISOString()} className="text-muted-foreground">
								{formatDayTime(piece.deliveredAt, today)}
							</time>
						)}
					</p>
					<FeedbackWhere feedback={piece} links={links} />
					<ResponseLine feedback={piece} />
				</li>
			))}
		</ul>
	);
}

/** The way to the feedback where it lives: its comment on the work, or its place in Hephaestus. */
function FeedbackWhere({
	feedback,
	links,
}: {
	feedback: OwnFeedbackRef;
	links: ObservationFeedbackLinks | undefined;
}) {
	if (feedback.destination === "ON_WORK") {
		const urls = feedback.copies.flatMap((copy) =>
			hasText(copy.externalUrl) ? [copy.externalUrl] : [],
		);
		if (urls.length === 0) {
			return null;
		}
		return (
			<p className="flex flex-wrap gap-x-3 gap-y-1 text-sm">
				{urls.map((url, index) => (
					<InlineLink key={url} href={url} external className={LINK_AT_REST}>
						{urls.length === 1 ? "Read the comment on the work" : `Read comment ${index + 1}`}
					</InlineLink>
				))}
			</p>
		);
	}
	const target =
		feedback.destination === "PRACTICE_PAGE"
			? links?.practicePage?.(feedback.id)
			: links?.conversation;
	if (target === undefined || feedback.state !== "DELIVERED") {
		return null;
	}
	return (
		<p className="text-sm">
			<InlineLink render={target} className={LINK_AT_REST}>
				{feedback.destination === "PRACTICE_PAGE"
					? "Read it on this practice’s Feedback tab"
					: "Read it in your conversation with Heph"}
			</InlineLink>
		</p>
	);
}

/** A response already given, read only: what was said, not a control to say it again. */
function ResponseLine({ feedback }: { feedback: OwnFeedbackRef }) {
	const { response } = feedback;
	if (
		response === null ||
		(response.resolution === null && response.usefulness === null && !hasText(response.comment))
	) {
		return null;
	}
	return (
		<p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
			<span>Your response:</span>
			{response.resolution !== null && (
				<StatusBadge def={FEEDBACK_RESOLUTION_DEFS[response.resolution]} />
			)}
			{response.usefulness !== null && (
				<StatusBadge def={FEEDBACK_USEFULNESS_DEFS[response.usefulness]} />
			)}
			{hasText(response.comment) && <q>{response.comment}</q>}
		</p>
	);
}
