import {
	CheckIcon,
	ChevronLeftIcon,
	ChevronRightIcon,
	CircleAlertIcon,
	CircleXIcon,
} from "lucide-react";
import { useId, useState } from "react";

import type { ReviewFeedbackDetail } from "@/api/types.gen";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { copyKindsPhrase } from "@/components/practice-vocabulary/feedback-copy-defs";
import {
	FEEDBACK_DECISION_REASONS,
	type FeedbackDecisionReason,
} from "@/components/practice-vocabulary/feedback-decision-defs";
import { adminWithheldText } from "@/components/practice-vocabulary/feedback-destination-defs";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import {
	Popover,
	PopoverContent,
	PopoverDescription,
	PopoverHeader,
	PopoverTitle,
	PopoverTrigger,
} from "@/components/ui/popover";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";

import { FeedbackBody } from "./FeedbackBody";
import { levelFoldKey } from "./level-folds";
import { feedbackLevel } from "./review-levels";
import { ReviewPackage } from "./ReviewPackage";

/** Exactly what approval sends, expanded, because approving it unread is the failure to design out. */
export function ProposalPackage({ feedback }: { feedback: ReviewFeedbackDetail }) {
	// A private destination's text is never shown here, nor counted: the destination's own words
	// stand in.
	if (adminWithheldText(feedback.destination) !== undefined) {
		return (
			<Section
				level={3}
				size="lg"
				title="What will be sent"
				foldKey={levelFoldKey("feedback", "will-send")}
			>
				<FeedbackBody feedback={{ ...feedback, body: null }} />
			</Section>
		);
	}
	const phrase = copyKindsPhrase(feedback.copies);
	return (
		<Section
			level={3}
			size="lg"
			title="What will be sent"
			foldKey={levelFoldKey("feedback", "will-send")}
			description={phrase === "" ? undefined : phrase.charAt(0).toUpperCase() + phrase.slice(1)}
		>
			{feedback.copies.length === 0 ? (
				<Alert variant="destructive">
					<CircleAlertIcon aria-hidden />
					<AlertDescription>
						This review package is unavailable, so it cannot be approved. Reject it or wait for a
						replacement.
					</AlertDescription>
				</Alert>
			) : (
				<ReviewPackage feedback={feedback} defaultExpanded />
			)}
		</Section>
	);
}

/** Where one proposal sits among all awaiting approval, oldest first. */
export interface ApprovalQueue {
	/** 1-based. */
	position: number;
	total: number;
	/** The feedback ids either side of this one, when there are any. */
	previous?: string;
	next?: string;
}

export interface ProposalDecisionProps {
	/** A proposal with no package has nothing to authorize, so only rejection is offered. */
	canApprove: boolean;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: FeedbackDecisionReason, note?: string) => void;
	/**
	 * Where this proposal sits in the approval queue; absent when it is not in the part of the queue
	 * that was read. In a queue, a decision moves on to another proposal still waiting, or closes the
	 * level when this is the only one — the caller does that in `onApprove` and `onReject` — so the
	 * button says so.
	 */
	queue?: ApprovalQueue;
}

/**
 * The level's first row: where this proposal is in the queue and a step either way, then reject,
 * with a reason, or approve for delivery.
 */
export function ProposalDecision({
	canApprove,
	isDeciding,
	onApprove,
	onReject,
	queue,
}: ProposalDecisionProps) {
	let approveLabel = "Approve for delivery";
	if (queue) {
		// By the total, not by position or `next`: the last one reached may have others skipped on the
		// way, and a queue longer than the page it was read in goes on past it.
		approveLabel = queue.total === 1 ? "Approve and close" : "Approve and open next";
	}
	return (
		<>
			{queue && queue.total > 1 && <QueueSteps queue={queue} />}
			<RejectFeedbackPopover disabled={isDeciding} onReject={onReject} />
			<Button disabled={isDeciding || !canApprove} onClick={onApprove}>
				{isDeciding ? <Spinner /> : <CheckIcon />} {approveLabel}
			</Button>
		</>
	);
}

/**
 * "2 of 7" and a step to either neighbour. Each step swaps the level in front rather than opening one
 * over it, so the stack stays one deep however far the reader walks.
 */
function QueueSteps({ queue }: { queue: ApprovalQueue }) {
	const step = buttonVariants({ variant: "ghost", size: "icon" });
	return (
		<nav aria-label="Feedback awaiting approval" className="mr-auto flex items-center gap-1">
			{queue.previous === undefined ? (
				<Button variant="ghost" size="icon" disabled aria-label="Previous">
					<ChevronLeftIcon aria-hidden />
				</Button>
			) : (
				<DetailStackLink
					entry={feedbackLevel(queue.previous)}
					swap
					className={step}
					aria-label="Previous"
				>
					<ChevronLeftIcon aria-hidden />
				</DetailStackLink>
			)}
			<span className="px-1 text-sm text-muted-foreground tabular-nums">
				{queue.position} of {queue.total}
			</span>
			{queue.next === undefined ? (
				<Button variant="ghost" size="icon" disabled aria-label="Next">
					<ChevronRightIcon aria-hidden />
				</Button>
			) : (
				<DetailStackLink entry={feedbackLevel(queue.next)} swap className={step} aria-label="Next">
					<ChevronRightIcon aria-hidden />
				</DetailStackLink>
			)}
		</nav>
	);
}

function RejectFeedbackPopover({
	disabled,
	onReject,
}: {
	disabled: boolean;
	onReject: ProposalDecisionProps["onReject"];
}) {
	const [open, setOpen] = useState(false);
	const [reason, setReason] = useState<FeedbackDecisionReason | "">("");
	const [note, setNote] = useState("");
	const rejectionId = useId();
	const noteId = useId();
	return (
		<Popover open={open} onOpenChange={setOpen}>
			<PopoverTrigger render={<Button variant="outline" disabled={disabled} />}>
				<CircleXIcon />
				Reject feedback
			</PopoverTrigger>
			<PopoverContent
				align="end"
				side="bottom"
				className="w-[min(24rem,calc(100vw-2rem))] gap-4 p-4"
			>
				<PopoverHeader>
					<PopoverTitle>Reject this feedback</PopoverTitle>
					<PopoverDescription>
						Choose a reason. Add a note if the reason needs more detail.
					</PopoverDescription>
				</PopoverHeader>
				<RadioGroup
					value={reason}
					onValueChange={(value) => setReason(value)}
					aria-label="Rejection reason"
					className="gap-1"
				>
					{FEEDBACK_DECISION_REASONS.map((option) => (
						<label
							key={option.value}
							htmlFor={`${rejectionId}-${option.value}`}
							className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted has-data-checked:bg-muted"
						>
							<RadioGroupItem id={`${rejectionId}-${option.value}`} value={option.value} />
							<span>{option.label}</span>
						</label>
					))}
				</RadioGroup>
				<Field>
					<FieldLabel htmlFor={noteId}>Note</FieldLabel>
					<Textarea
						id={noteId}
						name="feedback-rejection-note"
						autoComplete="off"
						value={note}
						onChange={(event) => setNote(event.target.value)}
						maxLength={500}
						placeholder="What should be corrected or reconsidered…"
					/>
					<FieldDescription>Optional · {note.length}/500</FieldDescription>
				</Field>
				<div className="flex justify-end gap-2 border-t pt-3">
					<Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
						Cancel
					</Button>
					<Button
						variant="destructive"
						size="sm"
						disabled={disabled || !reason}
						onClick={() => {
							if (reason) {
								onReject(reason, note.trim() || undefined);
							}
						}}
					>
						Reject feedback
					</Button>
				</div>
			</PopoverContent>
		</Popover>
	);
}
