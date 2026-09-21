import { type ComponentProps, useId, useState } from "react";

import { PrimaryButton } from "@/components/common/PrimaryButton";
import type { BadgeVariant } from "@/components/practice-vocabulary/status-def";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Spinner } from "@/components/ui/spinner";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";

/** `FeedbackResponseRequestDTO.comment` caps the comment; a dispute has to carry one. */
const FEEDBACK_COMMENT_MAX_LENGTH = 2000;

/** What a response says: agreeing, disputing, or neither. */
export type ResponseTone = "positive" | "negative" | "neutral";

/**
 * The tone a response wears, read off its registry entry's badge variant: the success green
 * agrees, the destructive red and the warning amber both dispute — "Not helpful" and "Disputed"
 * press the same red — and every other variant says neither.
 */
export function toneOf(variant: BadgeVariant): ResponseTone {
	switch (variant) {
		case "success":
			return "positive";
		case "destructive":
		case "warning":
			return "negative";
		default:
			return "neutral";
	}
}

/**
 * A pressed response is tinted in its own colour — light green for the agreeing one, light red for
 * the disputing one, the muted ground for the one that says neither — on the ground, the text, the
 * icon and the border, so the choices read as opposites and none is mistaken for an unpressed
 * outline in either theme. The tints are the badge primitive's: the colour at a tenth over the
 * ground, a fifth in the dark theme. The dark-mode outline paints its own ground and border, so
 * both are restated here.
 */
const PRESSED_TINT: Record<ResponseTone, string> = {
	positive:
		"border-success/40 bg-success/10 text-success hover:bg-success/15 hover:text-success dark:border-success/40 dark:bg-success/20 dark:hover:bg-success/25",
	negative:
		"border-destructive/40 bg-destructive/10 text-destructive hover:bg-destructive/15 hover:text-destructive dark:border-destructive/40 dark:bg-destructive/20 dark:hover:bg-destructive/25",
	neutral:
		"border-foreground/25 bg-muted text-foreground hover:bg-muted hover:text-foreground dark:border-foreground/25 dark:bg-muted dark:hover:bg-muted",
};

export interface ResponseButtonProps extends Omit<ComponentProps<typeof Button>, "variant"> {
	tone: ResponseTone;
	/** Whether this is the response the reader chose; a pressed button wears its tone. */
	pressed: boolean;
}

/** One of the responses a reader can give: a card's "Helpful", an observation's "Addressed". */
export function ResponseButton({ tone, pressed, className, ...props }: ResponseButtonProps) {
	return (
		<Button
			type="button"
			variant="outline"
			aria-pressed={pressed}
			className={cn(pressed && PRESSED_TINT[tone], className)}
			{...props}
		/>
	);
}

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
	reasons?: ReadonlyArray<ResponseReason<TReason>>;
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
					value={reason ? [reason] : []}
					onValueChange={(next) => setReason(next[0])}
					className="flex-wrap"
				>
					{reasons.map((candidate) => (
						<ToggleGroupItem
							key={candidate.value}
							value={candidate.value}
							className="min-w-0 rounded-full bg-background px-2.5"
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
				className="bg-background"
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
				<PrimaryButton type="submit" disabled={isPending}>
					{isPending && <Spinner />}
					{isPending ? "Sending…" : "Send"}
				</PrimaryButton>
			</div>
		</form>
	);
}
