import { useId, useState } from "react";

import { cn } from "cn";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

/** `FeedbackResponseRequestDTO.comment` caps the comment; a dispute has to carry one. */
const FEEDBACK_COMMENT_MAX_LENGTH = 2000;

export interface ResponseReason<TValue extends string = string> {
	value: TValue;
	label: string;
}

export interface ResponseComment<TReason extends string = string> {
	/** Only when the band offered reasons. */
	reason?: TReason;
	comment: string;
}

export interface ResponseCommentBandProps<TReason extends string = string> {
	/** The form's accessible name: what the reader is answering. */
	name: string;
	label: string;
	placeholder: string;
	/** A required sentence: Send does nothing until one is typed, and the field says so. */
	required?: boolean;
	/** One-of chips above the field; the chosen one travels with the comment. */
	reasons?: readonly ResponseReason<TReason>[];
	isPending?: boolean;
	onSend?: (comment: ResponseComment<TReason>) => void;
	onSkip?: () => void;
	className?: string;
}

/**
 * The band a response opens under a card's footer or an observation's response buttons, on the
 * action band's ground: a label, one line for Heph, and Skip or Send. The caller mounts it only
 * while the response is being given and keys it on the choice, so a draft never survives into
 * another choice's band.
 */
export function ResponseCommentBand<TReason extends string = string>({
	name,
	label,
	placeholder,
	required = false,
	reasons,
	isPending = false,
	onSend,
	onSkip,
	className,
}: ResponseCommentBandProps<TReason>) {
	const fieldId = useId();
	const [reason, setReason] = useState<TReason>();
	const [comment, setComment] = useState("");

	return (
		<form
			onSubmit={(event) => {
				event.preventDefault();
				onSend?.(reasons ? { reason, comment } : { comment });
			}}
			className={cn("flex flex-col gap-2.5 border-t bg-sidebar px-4 py-3", className)}
			aria-label={name}
		>
			<Label htmlFor={fieldId}>{label}</Label>
			{reasons && (
				<ToggleGroup
					aria-label="Reason"
					variant="outline"
					size="sm"
					spacing={2}
					value={reason === undefined ? [] : [reason]}
					onValueChange={(next) => setReason(next[0])}
					className="flex-wrap"
				>
					{reasons.map((candidate) => (
						<ToggleGroupItem
							key={candidate.value}
							value={candidate.value}
							className="min-w-0 bg-background"
						>
							{candidate.label}
						</ToggleGroupItem>
					))}
				</ToggleGroup>
			)}
			<Input
				id={fieldId}
				value={comment}
				// Leading blanks are dropped as they are typed, so a line of spaces is an empty line and
				// `required` reports it instead of Send silently doing nothing.
				onChange={(event) => setComment(event.target.value.trimStart())}
				required={required}
				disabled={isPending}
				maxLength={FEEDBACK_COMMENT_MAX_LENGTH}
				placeholder={placeholder}
				autoComplete="off"
			/>
			<div className="flex flex-wrap items-center justify-end gap-2">
				<Button
					type="button"
					variant="outline"
					disabled={isPending}
					onClick={onSkip}
					className="bg-background"
				>
					Skip
				</Button>
				<Button variant="mentor" type="submit" disabled={isPending}>
					{isPending && <Spinner />}
					{isPending ? "Sending…" : "Send"}
				</Button>
			</div>
		</form>
	);
}
