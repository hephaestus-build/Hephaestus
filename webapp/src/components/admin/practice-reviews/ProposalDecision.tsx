import {
	CheckIcon,
	ChevronLeftIcon,
	ChevronRightIcon,
	CircleAlertIcon,
	CircleXIcon,
} from "lucide-react";
import { useId, useState } from "react";

import type { GetPracticeReviewFeedbackResponse } from "@/api/types.gen";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { placementLabel } from "@/components/practice-vocabulary/placement-defs";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
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
import { hasText } from "@/lib/text";

import {
	PROPOSAL_REJECTION_REASONS,
	type ProposalRejectionReason,
} from "./proposal-rejection-vocabulary";
import { feedbackLevel } from "./review-levels";
import { ReviewFact } from "./ReviewFactGrid";
import { ReviewPackage } from "./ReviewPackage";

/** The facts only a proposal has: where it will appear and the revision it was written against. */
export function ProposalFacts({ feedback }: { feedback: GetPracticeReviewFeedbackResponse }) {
	const placements = [
		...new Set(
			feedback.proposedPlacements.map((placement) =>
				placementLabel(feedback.channel, placement.type),
			),
		),
	];
	return (
		<>
			<ReviewFact label="Will appear as">
				<div className="flex flex-wrap gap-1.5">
					{placements.length > 0 ? (
						placements.map((placement) => (
							<Badge key={placement} variant="outline">
								{placement}
							</Badge>
						))
					) : (
						<span className="text-muted-foreground">
							{DELIVERY_PLACE_DEFS[feedback.channel].label}
						</span>
					)}
				</div>
			</ReviewFact>
			{hasText(feedback.reviewedRevision) && (
				<ReviewFact label="Reviewed revision">
					<code className="text-xs break-all">{feedback.reviewedRevision}</code>
				</ReviewFact>
			)}
		</>
	);
}

/** Exactly what approval sends, expanded, because approving it unread is the failure to design out. */
export function ProposalPackage({ feedback }: { feedback: GetPracticeReviewFeedbackResponse }) {
	const { proposedPlacements } = feedback;
	const summary = proposedPlacements.find((placement) => placement.type === "SUMMARY");
	const inline = proposedPlacements.filter((placement) => placement.type === "INLINE");
	return (
		<Section
			level={3}
			title="What will be sent"
			description={`${summary ? "1 summary" : "No summary"} and ${inline.length} ${
				inline.length === 1 ? "line comment" : "line comments"
			}`}
		>
			{proposedPlacements.length === 0 ? (
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
	onReject: (reason: ProposalRejectionReason, note?: string) => void;
	/**
	 * Where this proposal sits in the approval queue; absent when it is not in the part of the queue
	 * that was read. In a queue, a decision moves on to another proposal still waiting, or closes the
	 * level when this is the only one — the caller does that in `onApprove` and `onReject` — so the
	 * button says so.
	 */
	queue?: ApprovalQueue;
}

/**
 * The level's footer: where this proposal is in the queue and a step either way, then reject, with
 * a reason, or approve for delivery.
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
		approveLabel = queue.total === 1 ? "Approve and close" : "Approve and next";
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
		// Below `sm` the footer stacks in reverse, which would put the steps last on screen while
		// they are first in the tab order; ordered last, the reversal shows them first, as they read.
		<nav
			aria-label="Feedback awaiting approval"
			className="flex items-center gap-1 max-sm:order-last sm:mr-auto"
		>
			{queue.previous === undefined ? (
				<Button variant="ghost" size="icon" disabled aria-label="Previous">
					<ChevronLeftIcon />
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
					<ChevronRightIcon />
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
	const [reason, setReason] = useState<ProposalRejectionReason | "">("");
	const [note, setNote] = useState("");
	const rejectionId = useId();
	const noteId = useId();
	return (
		<Popover open={open} onOpenChange={setOpen}>
			<PopoverTrigger render={<Button variant="outline" disabled={disabled} />}>
				<CircleXIcon />
				Reject feedback
			</PopoverTrigger>
			<PopoverContent align="end" side="top" className="w-[min(24rem,calc(100vw-2rem))] gap-4 p-4">
				<PopoverHeader>
					<PopoverTitle>Reject this feedback</PopoverTitle>
					<PopoverDescription>
						Choose the reason. Add a note when the category is not enough.
					</PopoverDescription>
				</PopoverHeader>
				<RadioGroup
					value={reason}
					onValueChange={(value) => setReason(value)}
					aria-label="Rejection category"
					className="gap-1"
				>
					{PROPOSAL_REJECTION_REASONS.map((option) => (
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
