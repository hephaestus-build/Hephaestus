import { useState } from "react";

import type { FeedbackResponseRequest, OwnFeedbackRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { ResponseButton, toneOf } from "@/components/common/ResponseButton";
import { ResponseCommentBand } from "@/components/common/ResponseCommentBand";
import { SectionLabel } from "@/components/common/SectionLabel";
import { statusValues } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";
import { FEEDBACK_DESTINATION_DEFS } from "@/components/practice-vocabulary/feedback-destination-defs";
import {
	FEEDBACK_RESOLUTION_DEFS,
	type FeedbackResolution,
} from "@/components/practice-vocabulary/feedback-resolution-defs";
import { FEEDBACK_USEFULNESS_DEFS } from "@/components/practice-vocabulary/feedback-usefulness-defs";
import { Button } from "@/components/ui/button";
import { formatShortDay } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { deliveredOnly } from "./ObservationFeedback";
import { type FeedbackResponseControls, isEmptyFeedbackResponse } from "./reviews";

export interface OwnFeedbackListProps {
	feedback: OwnFeedbackRef[];
	/** Absent where the reader may not respond, which leaves no response controls. */
	responses?: FeedbackResponseControls;
}

/**
 * The reader's feedback from a review: only what reached them (`deliveredOnly`). Feedback that was
 * withheld, awaits approval, was rejected or failed was never posted, so it is not listed as if it
 * were on the work.
 */
export function OwnFeedbackList({ feedback: given, responses }: OwnFeedbackListProps) {
	const feedback = deliveredOnly(given);
	if (feedback.length === 0) {
		return null;
	}
	return (
		<ul aria-label="Your feedback" className="flex min-w-0 flex-col gap-3">
			{feedback.map((piece) => (
				<li key={piece.id} className="flex min-w-0 flex-col gap-2.5">
					<p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
						<StatusBadge def={FEEDBACK_DESTINATION_DEFS[piece.destination]} />
						{piece.deliveredAt !== null && (
							<span>Delivered {formatShortDay(piece.deliveredAt)}</span>
						)}
					</p>
					<CopyLinks feedback={piece} />
					{responses !== undefined && piece.state === "DELIVERED" && (
						<FeedbackResponse feedback={piece} responses={responses} />
					)}
				</li>
			))}
		</ul>
	);
}

function CopyLinks({ feedback }: { feedback: OwnFeedbackRef }) {
	const urls = feedback.copies.flatMap((copy) =>
		hasText(copy.externalUrl) ? [copy.externalUrl] : [],
	);
	if (urls.length === 0) {
		return null;
	}
	if (urls.length === 1) {
		return (
			<p className="text-sm">
				<InlineLink href={urls[0]} external>
					Read the feedback comment
				</InlineLink>
			</p>
		);
	}
	return (
		<ul className="flex flex-wrap gap-x-3 gap-y-1 text-sm">
			{urls.map((url, index) => (
				<li key={url}>
					<InlineLink href={url} external>
						Read feedback comment {index + 1}
					</InlineLink>
				</li>
			))}
		</ul>
	);
}

/** A disagreement is the one answer the developer writes for somebody else to read. */
const DISAGREEMENT_AUDIENCE = "Add your reason so a workspace admin can read it.";

interface FeedbackResponseProps {
	feedback: OwnFeedbackRef;
	responses: FeedbackResponseControls;
}

function FeedbackResponse({ feedback, responses }: FeedbackResponseProps) {
	const [pendingResolution, setPendingResolution] = useState<FeedbackResolution>();
	const isPending = responses.pendingResponses.has(feedback.id);
	// Keep the pending response visible until it settles so controls do not jump between old and new answers.
	const current = responses.responseOf(feedback.id, feedback.response);
	const shownResolution = pendingResolution ?? current.resolution;
	const write = (change: FeedbackResponseRequest) =>
		responses.write(feedback.id, feedback.response, change);
	const chooseResolution = (resolution: FeedbackResolution) => {
		if (pendingResolution === resolution) {
			setPendingResolution(undefined);
			return;
		}
		if (current.resolution === resolution) {
			setPendingResolution(undefined);
			write({ resolution: undefined, comment: undefined });
			return;
		}
		setPendingResolution(resolution);
	};
	const record = (comment: string | undefined) => {
		if (pendingResolution === undefined) {
			return;
		}
		setPendingResolution(undefined);
		write({ resolution: pendingResolution, comment });
	};
	const destination = FEEDBACK_DESTINATION_DEFS[feedback.destination].label.toLowerCase();
	return (
		<div className="flex min-w-0 flex-col gap-2.5">
			<SectionLabel as="span" className="font-normal">
				Your response
			</SectionLabel>
			<div
				role="group"
				aria-label={`Your response to the feedback ${destination}`}
				className="flex flex-wrap items-center gap-2"
			>
				{statusValues(FEEDBACK_RESOLUTION_DEFS).map((value) => {
					const def = FEEDBACK_RESOLUTION_DEFS[value];
					return (
						<ResponseButton
							key={value}
							tone={toneOf(def.badgeVariant)}
							pressed={shownResolution === value}
							disabled={isPending}
							className="bg-background"
							onClick={() => chooseResolution(value)}
						>
							{def.label}
						</ResponseButton>
					);
				})}
				{!isEmptyFeedbackResponse(current) && (
					<Button
						type="button"
						variant="quiet"
						size="sm"
						disabled={isPending}
						onClick={() => {
							setPendingResolution(undefined);
							responses.clear(feedback.id);
						}}
					>
						Clear response
					</Button>
				)}
			</div>
			{/* What the reader already said: the rating given on the feedback card, and the comment
			    sent with the response that stands. */}
			{(current.usefulness !== undefined || hasText(current.comment)) && (
				<p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
					{current.usefulness !== undefined && (
						<StatusBadge def={FEEDBACK_USEFULNESS_DEFS[current.usefulness]} />
					)}
					{hasText(current.comment) && <q>{current.comment}</q>}
				</p>
			)}
			{pendingResolution === "DISAGREE" && (
				// Disagreement requires a reason; Skip closes the draft without recording it.
				<ResponseCommentBand
					key={pendingResolution}
					name="Why you disagree"
					label="Why do you disagree?"
					placeholder="One or two sentences on what is wrong"
					required
					audience={DISAGREEMENT_AUDIENCE}
					isPending={isPending}
					onSend={(comment) => {
						const reason = comment.trim();
						if (hasText(reason)) {
							record(reason);
						}
					}}
					onSkip={() => setPendingResolution(undefined)}
				/>
			)}
			{pendingResolution !== undefined && pendingResolution !== "DISAGREE" && (
				<ResponseCommentBand
					key={pendingResolution}
					name={pendingResolution === "ADDRESSED" ? "What you changed" : "Why this does not apply"}
					label="Anything to add?"
					placeholder="Optional: a note to yourself"
					isPending={isPending}
					onSend={(comment) => record(comment.trim() || undefined)}
					onSkip={() => record(undefined)}
				/>
			)}
		</div>
	);
}
