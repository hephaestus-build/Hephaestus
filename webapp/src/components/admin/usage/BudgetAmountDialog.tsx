import { type ReactNode, type SubmitEvent, useId, useState } from "react";

import { Button } from "@/components/ui/button";
import {
	Dialog,
	DialogBody,
	DialogClose,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogForm,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";

import { type Fx, FxApprox, fxCapHint } from "./fx";

export interface BudgetAmountDialogProps {
	open: boolean;
	title: string;
	description: ReactNode;
	fieldLabel: string;
	/** The cap in force today, in USD; `null`/`undefined` means uncapped (no "Remove cap"). */
	currentValueUsd?: number | null;
	isPending: boolean;
	/** Rendered as this field's error, not a toast: the value that was rejected is still on screen. */
	serverError?: string | null;
	submitLabel?: string;
	removeLabel?: string;
	fx?: Fx;
	/** Defaults to `false`: a caller that has not thought about it gets no estimate, not a frozen one. */
	isCurrentMonth?: boolean;
	onOpenChange: (open: boolean) => void;
	/** `null` removes the cap; a number (USD, >= 0, at most 2 decimals) sets it. */
	onSubmit: (valueUsd: number | null) => void;
}

/**
 * The money-cap editor both budget dialogs share: one set of rules — USD, >= 0, cent precision,
 * `null` removes, `0` pauses immediately — so the wrappers supply only the copy.
 */
export function BudgetAmountDialog({
	open,
	isPending,
	onOpenChange,
	...contentProps
}: BudgetAmountDialogProps) {
	return (
		<Dialog open={open} onOpenChange={onOpenChange}>
			{/* Keyed on the amount the form seeds from, so the input never carries a stale value. */}
			{open && (
				<BudgetAmountDialogContent
					key={contentProps.currentValueUsd ?? "none"}
					isPending={isPending}
					{...contentProps}
				/>
			)}
		</Dialog>
	);
}

type BudgetAmountDialogContentProps = Omit<BudgetAmountDialogProps, "open" | "onOpenChange">;

function BudgetAmountDialogContent({
	title,
	description,
	fieldLabel,
	currentValueUsd,
	isPending,
	serverError,
	submitLabel = "Save cap",
	removeLabel = "Remove cap",
	fx,
	isCurrentMonth = false,
	onSubmit,
}: BudgetAmountDialogContentProps) {
	const fieldId = useId();
	const fxHintId = useId();
	const errorId = useId();
	const [value, setValue] = useState(currentValueUsd == null ? "" : String(currentValueUsd));
	// Withheld until the first submit so the field isn't red before anything was attempted.
	const [showError, setShowError] = useState(false);
	// The server error stays until the amount is edited, so it reads as "this value was rejected".
	const [dismissedServerError, setDismissedServerError] = useState<string | null>(null);

	const parsed = Number.parseFloat(value);
	const isEmpty = value.trim() === "";
	// At most two decimals: a cap is an amount of money, and the server column is NUMERIC(10,2).
	const hasCentPrecision = /^\d*(?:\.\d{0,2})?$/u.test(value.trim());
	const isValid = !isEmpty && Number.isFinite(parsed) && parsed >= 0 && hasCentPrecision;
	const canRemove = currentValueUsd != null;
	// Names the remove button only when it is on screen; a subject with no cap yet has none.
	const emptyError = canRemove ? `Enter an amount, or use ${removeLabel}.` : "Enter an amount.";
	let localError = "Use at most two decimal places.";
	if (isEmpty) {
		localError = emptyError;
	} else if (!Number.isFinite(parsed) || parsed < 0) {
		localError = "Enter an amount of $0 or more.";
	}
	const liveServerError =
		serverError != null && serverError !== dismissedServerError ? serverError : null;
	const errorMessage = showError && !isValid ? localError : liveServerError;
	const isInvalid = errorMessage != null;
	const fxHint = fxCapHint(isValid ? parsed : null, fx, isCurrentMonth);

	const handleSubmit = (event: SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (!isValid) {
			// The submit button stays enabled precisely so this reveals *why* the value is rejected.
			setShowError(true);
			return;
		}
		onSubmit(parsed);
	};

	return (
		<DialogContent>
			<DialogForm onSubmit={handleSubmit}>
				<DialogHeader>
					<DialogTitle>{title}</DialogTitle>
					<DialogDescription>{description}</DialogDescription>
				</DialogHeader>
				{/* The footer stacks on a narrow viewport, so the body scrolls rather than the dialog. */}
				<DialogBody className="py-1">
					<FieldGroup>
						<Field data-invalid={isInvalid}>
							<FieldLabel htmlFor={fieldId}>{fieldLabel}</FieldLabel>
							<Input
								id={fieldId}
								type="number"
								inputMode="decimal"
								min={0}
								step={0.01}
								placeholder="e.g. 25.00"
								value={value}
								aria-invalid={isInvalid}
								aria-describedby={
									[fxHint == null ? null : fxHintId, isInvalid ? errorId : null]
										.filter(Boolean)
										.join(" ") || undefined
								}
								onChange={(event) => {
									setValue(event.target.value);
									setShowError(false);
									setDismissedServerError(serverError ?? null);
								}}
								disabled={isPending}
								// oxlint-disable-next-line jsx-a11y/no-autofocus -- The budget-cap dialog opens to collect this one amount and holds no other writable control.
								autoFocus
							/>
							{fxHint != null && (
								<FieldDescription id={fxHintId}>
									<FxApprox conversion={fxHint.conversion} />
									{fxHint.tail}
								</FieldDescription>
							)}
							{errorMessage != null && <FieldError id={errorId}>{errorMessage}</FieldError>}
						</Field>
					</FieldGroup>
				</DialogBody>
				<DialogFooter>
					{canRemove && (
						<Button
							type="button"
							variant="destructive-outline"
							className="sm:mr-auto"
							disabled={isPending}
							onClick={() => onSubmit(null)}
						>
							{removeLabel}
						</Button>
					)}
					<DialogClose render={<Button type="button" variant="outline" disabled={isPending} />}>
						Cancel
					</DialogClose>
					<Button type="submit" disabled={isPending}>
						{isPending ? <Spinner className="size-4" /> : null}
						{submitLabel}
					</Button>
				</DialogFooter>
			</DialogForm>
		</DialogContent>
	);
}
