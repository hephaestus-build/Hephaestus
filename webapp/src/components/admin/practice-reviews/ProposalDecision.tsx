import { CheckIcon, CircleXIcon } from "lucide-react";
import { useId, useState } from "react";

import type { GetPracticeReviewFeedbackResponse } from "@/api/types.gen";
import { Section } from "@/components/layout/Section";
import { DELIVERY_PLACE_DEFS } from "@/components/practice-vocabulary/delivery-place-defs";
import { placementLabel } from "@/components/practice-vocabulary/placement-defs";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
				<p role="alert" className="rounded-lg border border-destructive/40 p-3 text-sm">
					This review package is unavailable, so it cannot be approved. Reject it or wait for a
					replacement.
				</p>
			) : (
				<ReviewPackage feedback={feedback} defaultExpanded />
			)}
		</Section>
	);
}

export interface ProposalDecisionProps {
	/** A proposal with no package has nothing to authorize, so only rejection is offered. */
	canApprove: boolean;
	isDeciding: boolean;
	onApprove: () => void;
	onReject: (reason: ProposalRejectionReason, note?: string) => void;
}

/** The level's footer: reject, with a reason, or approve for delivery. */
export function ProposalDecision({
	canApprove,
	isDeciding,
	onApprove,
	onReject,
}: ProposalDecisionProps) {
	return (
		<>
			<RejectFeedbackPopover disabled={isDeciding} onReject={onReject} />
			<Button disabled={isDeciding || !canApprove} onClick={onApprove}>
				{isDeciding ? <Spinner /> : <CheckIcon />} Approve for delivery
			</Button>
		</>
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
