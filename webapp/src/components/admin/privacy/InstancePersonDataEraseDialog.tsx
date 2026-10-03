import { type SubmitEvent, useId, useState } from "react";

import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldContent, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";

const CONFIRM_PHRASE = "ERASE";

export interface InstancePersonDataEraseDialogProps {
	open: boolean;
	onOpenChange: (open: boolean) => void;
	/** A failed job resumes from its first unfinished store. */
	resume: boolean;
	rowCount: number;
	storeCount: number;
	/** Feedback still posted on a provider; the operator must confirm it was removed there. */
	externalDeliveryCount: number;
	onConfirm: (externalCopiesRemoved: boolean) => void;
}

/** Closes on confirm; the page reports the outcome (ADR 0027). */
export function InstancePersonDataEraseDialog({
	open,
	onOpenChange,
	resume,
	rowCount,
	storeCount,
	externalDeliveryCount,
	onConfirm,
}: InstancePersonDataEraseDialogProps) {
	const id = useId();
	const [confirmText, setConfirmText] = useState("");
	const [externalCopiesRemoved, setExternalCopiesRemoved] = useState(false);
	const [submitted, setSubmitted] = useState(false);
	const needsExternalConfirmation = externalDeliveryCount > 0;
	const phraseMismatch = submitted && confirmText !== CONFIRM_PHRASE;
	const externalMissing = submitted && needsExternalConfirmation && !externalCopiesRemoved;

	function handleOpenChange(next: boolean) {
		if (!next) {
			setConfirmText("");
			setExternalCopiesRemoved(false);
			setSubmitted(false);
		}
		onOpenChange(next);
	}

	function confirm(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		setSubmitted(true);
		if (confirmText !== CONFIRM_PHRASE || (needsExternalConfirmation && !externalCopiesRemoved)) {
			return;
		}
		onConfirm(externalCopiesRemoved);
		handleOpenChange(false);
	}

	return (
		<AlertDialog open={open} onOpenChange={handleOpenChange}>
			<AlertDialogContent>
				<AlertDialogHeader>
					<AlertDialogTitle>
						{resume ? "Resume erasure?" : "Permanently erase this person's data?"}
					</AlertDialogTitle>
					<AlertDialogDescription>
						{resume
							? "Stores that were already erased stay erased. The job continues with the remaining stores."
							: `This erases or anonymises ${rowCount} rows in ${storeCount} stores and blocks further processing of these identities. It cannot be undone.`}
					</AlertDialogDescription>
				</AlertDialogHeader>
				<form onSubmit={confirm} className="grid gap-4">
					{needsExternalConfirmation && (
						<Field orientation="horizontal" data-invalid={externalMissing}>
							<Checkbox
								id={`${id}-external`}
								checked={externalCopiesRemoved}
								onCheckedChange={setExternalCopiesRemoved}
								aria-invalid={externalMissing}
								aria-describedby={externalMissing ? `${id}-external-error` : undefined}
							/>
							<FieldContent>
								<FieldLabel htmlFor={`${id}-external`}>
									I removed the {externalDeliveryCount} feedback copies still posted on providers
								</FieldLabel>
								{externalMissing && (
									<FieldError id={`${id}-external-error`}>
										Erasure does not remove provider comments. Remove them first.
									</FieldError>
								)}
							</FieldContent>
						</Field>
					)}
					<Field data-invalid={phraseMismatch}>
						<FieldLabel htmlFor={`${id}-phrase`}>
							Type <span className="font-mono font-medium">{CONFIRM_PHRASE}</span> to confirm
						</FieldLabel>
						<Input
							id={`${id}-phrase`}
							value={confirmText}
							onChange={(event) => setConfirmText(event.target.value)}
							autoComplete="off"
							autoCapitalize="off"
							spellCheck={false}
							aria-invalid={phraseMismatch}
							aria-describedby={phraseMismatch ? `${id}-phrase-error` : undefined}
						/>
						{phraseMismatch && (
							<FieldError id={`${id}-phrase-error`}>Type {CONFIRM_PHRASE} exactly.</FieldError>
						)}
					</Field>
					<AlertDialogFooter>
						<AlertDialogCancel>Cancel</AlertDialogCancel>
						<AlertDialogAction type="submit" variant="destructive">
							{resume ? "Resume erasure" : "Erase data"}
						</AlertDialogAction>
					</AlertDialogFooter>
				</form>
			</AlertDialogContent>
		</AlertDialog>
	);
}
