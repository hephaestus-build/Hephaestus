import { useId, useState } from "react";

import { cn } from "cn";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { hasText } from "@/lib/text";

/** `FeedbackResponseRequestDTO.comment` caps the comment; a dispute has to carry one. */
const FEEDBACK_COMMENT_MAX_LENGTH = 2000;

export interface ResponseCommentBandProps {
	/** The form's accessible name: what the reader is answering. */
	name: string;
	label: string;
	placeholder: string;
	/** A required sentence: Send does nothing until one is typed, and the field says so. */
	required?: boolean;
	/** Who else reads the sentence, said under the field before it is sent; nothing when only the reader and Heph do. */
	audience?: string;
	/** Any write is in flight, so every control waits. */
	isPending?: boolean;
	/** The write in flight is this band's, so Send says "Sending…". */
	sending?: boolean;
	onSend?: (comment: string) => void;
	onSkip?: () => void;
	className?: string;
}

/**
 * The band a response opens under a card's footer or an observation's response buttons, on the
 * action band's ground: a label, one line for Heph, and Skip or Send. The caller mounts it only
 * while the response is being given and keys it on the choice, so a draft never survives into
 * another choice's band.
 */
export function ResponseCommentBand({
	name,
	label,
	placeholder,
	required = false,
	audience,
	isPending = false,
	sending = false,
	onSend,
	onSkip,
	className,
}: ResponseCommentBandProps) {
	const fieldId = useId();
	const audienceId = useId();
	const [comment, setComment] = useState("");

	return (
		<form
			onSubmit={(event) => {
				event.preventDefault();
				onSend?.(comment);
			}}
			className={cn("flex flex-col gap-2.5 border-t bg-sidebar px-4 py-3", className)}
			aria-label={name}
		>
			<Label htmlFor={fieldId}>{label}</Label>
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
				aria-describedby={hasText(audience) ? audienceId : undefined}
			/>
			{hasText(audience) && (
				<p id={audienceId} className="text-sm text-muted-foreground">
					{audience}
				</p>
			)}
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
					{sending && <Spinner />}
					{sending ? "Sending…" : "Send"}
				</Button>
			</div>
		</form>
	);
}
