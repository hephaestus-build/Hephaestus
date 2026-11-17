import { Plus, Trash2 } from "lucide-react";
import { useRef } from "react";

import { Button } from "@/components/ui/button";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldLabel,
	FieldTitle,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { hasText } from "@/lib/text";

import type { ReviewedByKind } from "./review-settings";
import {
	HUMAN_REASON_FIELD_ID,
	limitFieldId,
	MAX_REASON_LENGTH,
	REVIEWED_BY_DESCRIPTIONS,
	REVIEWED_BY_FIELD_ID,
	REVIEWED_BY_LABELS,
	type ReviewedByDraft,
} from "./reviewed-by";

const REVIEWED_BY_ORDER: ReviewedByKind[] = ["HEPHAESTUS", "PEOPLE", "NOBODY"];

export interface PracticeReviewedByEditorProps {
	value: ReviewedByDraft;
	onChange: (value: ReviewedByDraft) => void;
	/**
	 * What the practice keeps, unused, while people or nobody review it, as phrases such as "the
	 * practice script". Choosing Hephaestus again uses each of them.
	 */
	kept: readonly string[];
	error?: string;
	errorFocusId?: string;
	disabled?: boolean;
}

function joinPhrases(phrases: readonly string[]): string {
	if (phrases.length <= 1) {
		return phrases.join("");
	}
	return `${phrases.slice(0, -1).join(", ")} and ${phrases.at(-1) ?? ""}`;
}

// Reviewed by controls result production; feedback mode controls delivery.
export function PracticeReviewedByEditor({
	value,
	onChange,
	kept,
	error,
	errorFocusId,
	disabled = false,
}: PracticeReviewedByEditorProps) {
	const addLimitButton = useRef<HTMLButtonElement>(null);
	const reasonInvalid = hasText(error) && errorFocusId === HUMAN_REASON_FIELD_ID;

	const focusLimit = (target: number | "add") => {
		requestAnimationFrame(() => {
			if (target === "add") {
				addLimitButton.current?.focus();
			} else {
				document.getElementById(limitFieldId(target))?.focus();
			}
		});
	};

	return (
		<section
			className="space-y-4"
			aria-labelledby={`${REVIEWED_BY_FIELD_ID}-heading`}
			aria-describedby={hasText(error) ? `${REVIEWED_BY_FIELD_ID}-error` : undefined}
		>
			<div>
				<h2
					id={`${REVIEWED_BY_FIELD_ID}-heading`}
					className="text-lg font-semibold"
					// A focus target for a form-level error, not a Tab stop.
					tabIndex={-1}
				>
					Reviewed by
				</h2>
				<p className="text-sm text-muted-foreground">
					Choose who reviews this practice. Feedback mode decides separately whether its feedback is
					sent.
				</p>
			</div>

			<RadioGroup
				id={REVIEWED_BY_FIELD_ID}
				value={value.by}
				onValueChange={(by) => onChange({ ...value, by })}
				className="gap-3"
				aria-label="Reviewed by"
			>
				{REVIEWED_BY_ORDER.map((by) => (
					<FieldLabel key={by} htmlFor={`${REVIEWED_BY_FIELD_ID}-${by}`}>
						<Field orientation="horizontal" data-disabled={disabled}>
							<RadioGroupItem id={`${REVIEWED_BY_FIELD_ID}-${by}`} value={by} disabled={disabled} />
							<FieldContent>
								<FieldTitle>{REVIEWED_BY_LABELS[by]}</FieldTitle>
								<FieldDescription>{REVIEWED_BY_DESCRIPTIONS[by]}</FieldDescription>
							</FieldContent>
						</Field>
					</FieldLabel>
				))}
			</RadioGroup>

			{kept.length > 0 && (
				<p className="text-sm text-muted-foreground">
					Hephaestus keeps {joinPhrases(kept)} unused while it does not review this practice. Choose
					Hephaestus again to use them.
				</p>
			)}

			{value.by === "PEOPLE" && (
				<Field data-invalid={reasonInvalid || undefined}>
					<FieldLabel htmlFor={HUMAN_REASON_FIELD_ID}>Why do people review it? *</FieldLabel>
					<Input
						id={HUMAN_REASON_FIELD_ID}
						disabled={disabled}
						value={value.humanReason.description}
						onChange={(event) =>
							onChange({ ...value, humanReason: { description: event.target.value } })
						}
						maxLength={MAX_REASON_LENGTH}
						aria-invalid={reasonInvalid || undefined}
						aria-describedby={`${HUMAN_REASON_FIELD_ID}-description${
							reasonInvalid ? ` ${REVIEWED_BY_FIELD_ID}-error` : ""
						}`}
					/>
					<FieldDescription id={`${HUMAN_REASON_FIELD_ID}-description`}>
						Name what a person can see that the captured work cannot show.
					</FieldDescription>
				</Field>
			)}

			{value.by === "HEPHAESTUS" && (
				<div className="space-y-3">
					<div>
						<p className="font-medium">What the review cannot support</p>
						<p className="text-sm text-muted-foreground">
							List the claims this kind of work can never support, even when every source is
							captured.
						</p>
					</div>
					{value.limits.map((limit, index) => {
						const rowId = String(index);
						return (
							<div key={rowId} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[1fr_auto]">
								<Field>
									<FieldLabel htmlFor={limitFieldId(index)}>
										Description <span className="sr-only">for limit {index + 1}</span>
									</FieldLabel>
									<Input
										disabled={disabled}
										id={limitFieldId(index)}
										value={limit.description}
										onChange={(event) =>
											onChange({
												...value,
												limits: value.limits.map((item, itemIndex) =>
													itemIndex === index ? { description: event.target.value } : item,
												),
											})
										}
										maxLength={MAX_REASON_LENGTH}
									/>
								</Field>
								<Button
									type="button"
									disabled={disabled}
									variant="ghost"
									size="icon-sm"
									className="self-end"
									onClick={() => {
										const remaining = value.limits.filter((_, itemIndex) => itemIndex !== index);
										onChange({ ...value, limits: remaining });
										focusLimit(
											remaining.length === 0 ? "add" : Math.min(index, remaining.length - 1),
										);
									}}
									aria-label={`Remove limit ${index + 1}`}
								>
									<Trash2 className="size-4" />
								</Button>
							</div>
						);
					})}
					<Button
						ref={addLimitButton}
						type="button"
						disabled={disabled}
						variant="outline"
						size="sm"
						onClick={() => {
							onChange({ ...value, limits: [...value.limits, { description: "" }] });
							focusLimit(value.limits.length);
						}}
					>
						<Plus className="size-4" />
						Add limit
					</Button>
				</div>
			)}
			{hasText(error) && <FieldError id={`${REVIEWED_BY_FIELD_ID}-error`}>{error}</FieldError>}
		</section>
	);
}
