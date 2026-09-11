import type { Question } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldLabel,
	FieldLegend,
	FieldSet,
	FieldTitle,
} from "@/components/ui/field";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";

import {
	type AnswerDraft,
	isAnswered,
	NPS_LABELS,
	NPS_SCALE,
	RATING_SCALE,
} from "./survey-questions";

export interface ProductSurveyFormProps {
	questions: Question[];
	draft: AnswerDraft;
	onDraftChange: (draft: AnswerDraft) => void;
	disabled?: boolean;
	/** Keeps the field ids unique when two copies of the form are on one page. */
	idPrefix?: string;
}

/**
 * The question fields of one survey, and nothing else: the surrounding title, purpose and buttons
 * belong to whoever hosts the form — the invitation dialog, or the composer's preview.
 *
 * Every closed question is a `fieldset` whose legend is the prompt, so a screen reader announces
 * the question with each option; "(required)" is in the legend because an asterisk alone is not
 * read. An optional closed question can be cleared, because a radio group cannot un-select itself.
 */
export function ProductSurveyForm({
	questions,
	draft,
	onDraftChange,
	disabled = false,
	idPrefix = "survey",
}: ProductSurveyFormProps) {
	const set = (questionId: string, value: AnswerDraft[string]) =>
		onDraftChange({ ...draft, [questionId]: value });
	const clear = (questionId: string) => {
		const next = { ...draft };
		delete next[questionId];
		onDraftChange(next);
	};
	return (
		<FieldSet disabled={disabled} className="gap-6">
			<FieldLegend className="sr-only">Survey questions</FieldLegend>
			{questions.map((question, index) => {
				const id = `${idPrefix}-${question.id}`;
				const value = draft[question.id];
				const legend = (
					<FieldLegend id={`${id}-legend`} className="break-words text-sm font-medium">
						{index + 1}. {question.prompt}{" "}
						<span className="font-normal text-muted-foreground">
							{question.required ? "(required)" : "(optional)"}
						</span>
					</FieldLegend>
				);
				const clearButton = !question.required && isAnswered(value) && (
					<Button
						type="button"
						variant="ghost"
						size="sm"
						className="self-start"
						onClick={() => clear(question.id)}
					>
						Clear answer
					</Button>
				);
				if (question.type === "TEXT") {
					return (
						<Field key={question.id}>
							<FieldLabel htmlFor={id} className="break-words">
								{index + 1}. {question.prompt}{" "}
								<span className="font-normal text-muted-foreground">
									{question.required ? "(required)" : "(optional)"}
								</span>
							</FieldLabel>
							<Textarea
								id={id}
								rows={3}
								maxLength={4000}
								required={question.required}
								aria-required={question.required}
								value={typeof value === "string" ? value : ""}
								onChange={(event) => set(question.id, event.target.value)}
							/>
						</Field>
					);
				}
				if (question.type === "MULTIPLE_CHOICE") {
					const chosen = Array.isArray(value) ? value : [];
					return (
						<FieldSet key={question.id} className="gap-3">
							{legend}
							<div className="flex flex-col gap-2">
								{question.options.map((option, optionIndex) => (
									<FieldLabel key={option} htmlFor={`${id}-${optionIndex}`}>
										<Field orientation="horizontal" className="rounded-md border px-3 py-2">
											<Checkbox
												id={`${id}-${optionIndex}`}
												checked={chosen.includes(option)}
												onCheckedChange={(checked) =>
													set(
														question.id,
														checked
															? [...chosen, option]
															: chosen.filter((entry) => entry !== option),
													)
												}
											/>
											<FieldContent>
												<FieldTitle className="break-words font-normal">{option}</FieldTitle>
											</FieldContent>
										</Field>
									</FieldLabel>
								))}
							</div>
							{clearButton}
						</FieldSet>
					);
				}
				if (question.type === "SINGLE_CHOICE") {
					return (
						<FieldSet key={question.id} className="gap-3">
							{legend}
							<RadioGroup
								aria-labelledby={`${id}-legend`}
								aria-required={question.required}
								value={typeof value === "string" ? value : null}
								onValueChange={(next) => next !== null && set(question.id, next)}
								className="gap-2"
							>
								{question.options.map((option, optionIndex) => (
									<FieldLabel key={option} htmlFor={`${id}-${optionIndex}`}>
										<Field orientation="horizontal" className="rounded-md border px-3 py-2">
											<RadioGroupItem id={`${id}-${optionIndex}`} value={option} />
											<FieldContent>
												<FieldTitle className="break-words font-normal">{option}</FieldTitle>
											</FieldContent>
										</Field>
									</FieldLabel>
								))}
							</RadioGroup>
							{clearButton}
						</FieldSet>
					);
				}
				const scale = question.type === "RATING" ? RATING_SCALE : NPS_SCALE;
				const low = question.type === "RATING" ? question.lowLabel : NPS_LABELS.low;
				const high = question.type === "RATING" ? question.highLabel : NPS_LABELS.high;
				return (
					<FieldSet key={question.id} className="gap-3">
						{legend}
						<RadioGroup
							aria-labelledby={`${id}-legend`}
							aria-required={question.required}
							value={typeof value === "number" ? String(value) : null}
							onValueChange={(next) => next !== null && set(question.id, Number(next))}
							className="flex flex-wrap gap-1.5"
						>
							{scale.map((point) => (
								<FieldLabel
									key={point}
									htmlFor={`${id}-${point}`}
									className={cn(
										"flex h-10 min-w-10 cursor-pointer items-center justify-center gap-0 rounded-md border px-2 text-sm tabular-nums transition-colors",
										"has-[[data-checked]]:border-primary has-[[data-checked]]:bg-primary has-[[data-checked]]:text-primary-foreground",
										"has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50",
									)}
								>
									<RadioGroupItem id={`${id}-${point}`} value={String(point)} className="sr-only" />
									{point}
								</FieldLabel>
							))}
						</RadioGroup>
						<FieldDescription className="flex justify-between gap-4">
							<span>
								{scale[0]} = {low}
							</span>
							<span>
								{scale[scale.length - 1]} = {high}
							</span>
						</FieldDescription>
						{clearButton}
					</FieldSet>
				);
			})}
		</FieldSet>
	);
}
