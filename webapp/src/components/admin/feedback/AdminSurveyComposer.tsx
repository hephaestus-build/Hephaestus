import deepEqual from "fast-deep-equal";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { type ReactNode, useId, useState } from "react";

import type { CreateSurvey, Question } from "@/api/types.gen";
import { type FormError, FormErrorSummary } from "@/components/common/FormErrorSummary";
import { useNow } from "@/components/common/use-now";
import { ProductSurveyForm } from "@/components/feedback/ProductSurveyForm";
import {
	type AnswerDraft,
	NPS_LABELS,
	QUESTION_TYPE_LABELS,
	surveyEstimate,
} from "@/components/feedback/survey-questions";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Field,
	FieldContent,
	FieldDescription,
	FieldError,
	FieldGroup,
	FieldLabel,
	FieldLegend,
	FieldSet,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";

import {
	ALL_WORKSPACES,
	CHOICE_MAX_LENGTH,
	DESCRIPTION_MAX_LENGTH,
	emptySurveyDraft,
	hasDraftErrors,
	isChoiceType,
	MAX_QUESTIONS,
	newQuestionDraft,
	PROMPT_MAX_LENGTH,
	prepareQuestions,
	type QuestionDraft,
	RECOMMENDED_MAX_QUESTIONS,
	SCALE_LABEL_MAX_LENGTH,
	type SurveyDraft,
	type SurveyDraftErrors,
	TITLE_MAX_LENGTH,
	toCreateSurvey,
	validateSurveyDraft,
} from "./admin-survey-draft";

export interface AdminSurveyComposerProps {
	/** The audiences on offer besides every workspace. */
	workspaces: readonly { id: number; displayName: string }[];
	isPending: boolean;
	/** What "leave without saving" does. The host owns it, because only the host knows where back is. */
	cancel: ReactNode;
	/**
	 * Returns whatever the dispatch returned; a promise keeps the unsaved-changes guard down until it
	 * settles, so a successful publish closes the level without asking about the draft.
	 */
	onSubmit: (survey: CreateSurvey) => unknown;
}

const isQuestionType = (value: string): value is Question["type"] =>
	Object.hasOwn(QUESTION_TYPE_LABELS, value);
const QUESTION_TYPES = Object.keys(QUESTION_TYPE_LABELS)
	.filter(isQuestionType)
	.map((value) => ({ value, label: QUESTION_TYPE_LABELS[value] }));

const NO_ERRORS: SurveyDraftErrors = { questionErrors: [] };

/** The `aria-describedby` for a field: its help text, plus its error only while it has one. */
function describedBy(...ids: (string | false | undefined)[]): string | undefined {
	return ids.filter(Boolean).join(" ") || undefined;
}

/**
 * A guarded drawer level — `webapp/AGENTS.md` § Guarded levels — that writes one survey. The
 * questions are frozen once published, so the preview tab shows exactly what members will get
 * before the one decision this level offers.
 */
export function AdminSurveyComposer({
	workspaces,
	isPending,
	cancel,
	onSubmit,
}: AdminSurveyComposerProps) {
	const id = useId();
	const now = useNow();
	const [initial] = useState(() => emptySurveyDraft(crypto.randomUUID()));
	const [draft, setDraft] = useState(initial);
	const [refusals, setRefusals] = useState(0);
	const [view, setView] = useState<"edit" | "preview">("edit");
	const [previewDraft, setPreviewDraft] = useState<AnswerDraft>({});
	const unsavedChanges = useUnsavedChanges({
		isDirty: !deepEqual(draft, initial),
		disabled: isPending,
	});

	const errors = refusals > 0 ? validateSurveyDraft(draft, now) : NO_ERRORS;
	const prepared = prepareQuestions(draft.questions);
	const audiences = [
		{ value: ALL_WORKSPACES, label: "All workspaces" },
		...workspaces.map((workspace) => ({
			value: String(workspace.id),
			label: workspace.displayName,
		})),
	];

	const patch = (change: Partial<SurveyDraft>) =>
		setDraft((previous) => ({ ...previous, ...change }));
	const patchQuestion = (index: number, change: Partial<QuestionDraft>) =>
		setDraft((previous) => ({
			...previous,
			questions: previous.questions.map((question, at) =>
				at === index ? { ...question, ...change } : question,
			),
		}));
	const moveQuestion = (index: number, direction: -1 | 1) =>
		setDraft((previous) => {
			const questions = [...previous.questions];
			const [moved] = questions.splice(index, 1);
			if (moved) questions.splice(index + direction, 0, moved);
			return { ...previous, questions };
		});
	const removeQuestion = (index: number) =>
		setDraft((previous) => ({
			...previous,
			questions: previous.questions.filter((_, at) => at !== index),
		}));
	const addQuestion = () =>
		setDraft((previous) =>
			previous.questions.length >= MAX_QUESTIONS
				? previous
				: {
						...previous,
						questions: [...previous.questions, newQuestionDraft(crypto.randomUUID())],
					},
		);

	const fieldId = (name: string) => `${id}-${name}`;
	const questionFieldId = (index: number, name: string) => `${id}-q${index}-${name}`;
	const showEditor = () => setView("edit");

	const summary: FormError[] = [
		errors.title && { fieldId: fieldId("title"), message: errors.title },
		errors.description && { fieldId: fieldId("description"), message: errors.description },
		errors.endsAt && { fieldId: fieldId("end"), message: errors.endsAt },
		errors.questions && { fieldId: fieldId("add-question"), message: errors.questions },
		...errors.questionErrors.flatMap((question, index) =>
			[
				question.prompt && {
					fieldId: questionFieldId(index, "prompt"),
					message: `Question ${index + 1}: ${question.prompt}`,
				},
				question.choices && {
					fieldId: questionFieldId(index, "choices"),
					message: `Question ${index + 1}: ${question.choices}`,
				},
				question.lowLabel && {
					fieldId: questionFieldId(index, "low"),
					message: `Question ${index + 1}: ${question.lowLabel}`,
				},
				question.highLabel && {
					fieldId: questionFieldId(index, "high"),
					message: `Question ${index + 1}: ${question.highLabel}`,
				},
			].filter((entry): entry is FormError => Boolean(entry)),
		),
	]
		.filter((entry): entry is FormError => Boolean(entry))
		.map((entry) => ({ ...entry, reveal: showEditor }));

	const submit = (event: React.SubmitEvent<HTMLFormElement>) => {
		event.preventDefault();
		if (isPending) return;
		// Validated against the submit instant, which is what a blank start becomes.
		const publishedAt = new Date();
		const refused = validateSurveyDraft(draft, publishedAt.getTime());
		if (hasDraftErrors(refused)) {
			setRefusals((count) => count + 1);
			setView("edit");
			return;
		}
		// After dispatch, not before: `track` needs the promise the dispatch returns.
		unsavedChanges.track(onSubmit(toCreateSurvey(draft, publishedAt)));
	};

	return (
		<>
			{unsavedChanges.dialog}
			<form onSubmit={submit} className="flex min-h-0 flex-1 flex-col" noValidate>
				<Tabs
					value={view}
					onValueChange={(next) => setView(next === "preview" ? "preview" : "edit")}
					className="flex min-h-0 flex-1 flex-col gap-0"
				>
					<DrawerBody className="flex flex-col gap-6">
						<TabsList aria-label="Composer view" className="self-start">
							<TabsTrigger value="edit">Edit</TabsTrigger>
							<TabsTrigger value="preview">Preview</TabsTrigger>
						</TabsList>

						<TabsContent value="edit" className="flex flex-col gap-8">
							<FormErrorSummary key={refusals} errors={summary} />
							<fieldset disabled={isPending} className="contents">
								<FieldGroup className="gap-5">
									<Field data-invalid={errors.title ? "true" : undefined}>
										<FieldLabel htmlFor={fieldId("title")}>Title</FieldLabel>
										<Input
											id={fieldId("title")}
											value={draft.title}
											maxLength={TITLE_MAX_LENGTH}
											required
											aria-invalid={Boolean(errors.title)}
											aria-describedby={describedBy(errors.title && fieldId("title-error"))}
											onChange={(event) => patch({ title: event.target.value })}
										/>
										{errors.title && (
											<FieldError id={fieldId("title-error")}>{errors.title}</FieldError>
										)}
									</Field>

									<Field data-invalid={errors.description ? "true" : undefined}>
										<FieldLabel htmlFor={fieldId("description")}>Purpose</FieldLabel>
										<Textarea
											id={fieldId("description")}
											value={draft.description}
											rows={3}
											maxLength={DESCRIPTION_MAX_LENGTH}
											required
											aria-invalid={Boolean(errors.description)}
											aria-describedby={describedBy(
												fieldId("description-help"),
												errors.description && fieldId("description-error"),
											)}
											onChange={(event) => patch({ description: event.target.value })}
										/>
										<FieldDescription id={fieldId("description-help")}>
											What decision will these answers help you make? Members see this.
										</FieldDescription>
										{errors.description && (
											<FieldError id={fieldId("description-error")}>
												{errors.description}
											</FieldError>
										)}
									</Field>

									<Field orientation="responsive">
										<FieldContent>
											<FieldLabel id={fieldId("audience-label")} htmlFor={fieldId("audience")}>
												Audience
											</FieldLabel>
										</FieldContent>
										<Select
											items={audiences}
											value={draft.audience}
											disabled={isPending}
											onValueChange={(value) => value && patch({ audience: value })}
										>
											<SelectTrigger
												id={fieldId("audience")}
												className="w-full @md/field-group:w-56"
											>
												<SelectValue />
											</SelectTrigger>
											<SelectContent aria-labelledby={fieldId("audience-label")}>
												{audiences.map((audience) => (
													<SelectItem key={audience.value} value={audience.value}>
														{audience.label}
													</SelectItem>
												))}
											</SelectContent>
										</Select>
									</Field>

									<div className="grid gap-5 sm:grid-cols-2">
										<Field>
											<FieldLabel htmlFor={fieldId("start")}>Start</FieldLabel>
											<Input
												id={fieldId("start")}
												type="datetime-local"
												value={draft.startsAt}
												aria-describedby={fieldId("start-help")}
												onChange={(event) => patch({ startsAt: event.target.value })}
											/>
											<FieldDescription id={fieldId("start-help")}>
												Leave blank to open when published.
											</FieldDescription>
										</Field>
										<Field data-invalid={errors.endsAt ? "true" : undefined}>
											<FieldLabel htmlFor={fieldId("end")}>End</FieldLabel>
											<Input
												id={fieldId("end")}
												type="datetime-local"
												value={draft.endsAt}
												aria-invalid={Boolean(errors.endsAt)}
												aria-describedby={describedBy(
													fieldId("end-help"),
													errors.endsAt && fieldId("end-error"),
												)}
												onChange={(event) => patch({ endsAt: event.target.value })}
											/>
											<FieldDescription id={fieldId("end-help")}>
												Times are in your device's timezone. Set an end so invitations do not go
												stale.
											</FieldDescription>
											{errors.endsAt && (
												<FieldError id={fieldId("end-error")}>{errors.endsAt}</FieldError>
											)}
										</Field>
									</div>
								</FieldGroup>

								<section className="flex flex-col gap-4" aria-labelledby={fieldId("questions")}>
									<div className="space-y-1">
										<h2 id={fieldId("questions")} className="font-semibold text-lg">
											Questions
										</h2>
										<p className="max-w-2xl text-muted-foreground text-sm">
											Short surveys get answered: aim for 1–3 questions, closed questions first and
											one optional free-text question last.
										</p>
									</div>
									{draft.questions.map((question, index) => (
										<QuestionCard
											key={question.id}
											index={index}
											count={draft.questions.length}
											question={question}
											errors={errors.questionErrors[index] ?? {}}
											fieldId={(name) => questionFieldId(index, name)}
											disabled={isPending}
											onChange={(change) => patchQuestion(index, change)}
											onMove={(direction) => moveQuestion(index, direction)}
											onRemove={() => removeQuestion(index)}
										/>
									))}
									{draft.questions.length > RECOMMENDED_MAX_QUESTIONS && (
										<FieldDescription className="text-warning">
											This survey has {draft.questions.length} questions; response rates drop
											sharply beyond {RECOMMENDED_MAX_QUESTIONS}.
										</FieldDescription>
									)}
									{errors.questions && <FieldError>{errors.questions}</FieldError>}
									<Button
										id={fieldId("add-question")}
										type="button"
										variant="outline"
										className="self-start"
										disabled={draft.questions.length >= MAX_QUESTIONS}
										onClick={addQuestion}
									>
										<Plus aria-hidden />
										Add question
									</Button>
								</section>
							</fieldset>
						</TabsContent>

						<TabsContent value="preview" className="flex flex-col gap-6">
							<div className="space-y-1">
								<h2 className="font-semibold text-lg break-words">
									{draft.title.trim() || "Untitled survey"}
								</h2>
								{draft.description.trim() && (
									<p className="text-muted-foreground text-sm break-words whitespace-pre-wrap">
										{draft.description.trim()}
									</p>
								)}
								<p className="text-muted-foreground text-xs">{surveyEstimate(prepared)}</p>
							</div>
							<ProductSurveyForm
								questions={prepared}
								draft={previewDraft}
								onDraftChange={setPreviewDraft}
								idPrefix={fieldId("preview")}
							/>
						</TabsContent>
					</DrawerBody>
				</Tabs>

				<DrawerFooter>
					{cancel}
					<Button type="submit" disabled={isPending}>
						{isPending && <Spinner className="size-4" />}
						{isPending ? "Publishing…" : "Publish survey"}
					</Button>
				</DrawerFooter>
			</form>
		</>
	);
}

interface QuestionCardProps {
	index: number;
	count: number;
	question: QuestionDraft;
	errors: SurveyDraftErrors["questionErrors"][number];
	fieldId: (name: string) => string;
	disabled: boolean;
	onChange: (change: Partial<QuestionDraft>) => void;
	onMove: (direction: -1 | 1) => void;
	onRemove: () => void;
}

function QuestionCard({
	index,
	count,
	question,
	errors,
	fieldId,
	disabled,
	onChange,
	onMove,
	onRemove,
}: QuestionCardProps) {
	const number = index + 1;
	return (
		<FieldSet className="rounded-lg border p-4">
			<FieldLegend className="px-1">Question {number}</FieldLegend>
			<FieldGroup className="gap-4">
				<Field data-invalid={errors.prompt ? "true" : undefined}>
					<FieldLabel htmlFor={fieldId("prompt")}>Prompt</FieldLabel>
					<Textarea
						id={fieldId("prompt")}
						value={question.prompt}
						rows={2}
						maxLength={PROMPT_MAX_LENGTH}
						required
						aria-invalid={Boolean(errors.prompt)}
						aria-describedby={describedBy(errors.prompt && fieldId("prompt-error"))}
						onChange={(event) => onChange({ prompt: event.target.value })}
					/>
					{errors.prompt && <FieldError id={fieldId("prompt-error")}>{errors.prompt}</FieldError>}
				</Field>

				<Field orientation="responsive">
					<FieldContent>
						<FieldLabel id={fieldId("type-label")} htmlFor={fieldId("type")}>
							Answer type
						</FieldLabel>
					</FieldContent>
					<Select
						items={QUESTION_TYPES}
						value={question.type}
						disabled={disabled}
						onValueChange={(value) => value && onChange({ type: value })}
					>
						<SelectTrigger id={fieldId("type")} className="w-full @md/field-group:w-56">
							<SelectValue />
						</SelectTrigger>
						<SelectContent aria-labelledby={fieldId("type-label")}>
							{QUESTION_TYPES.map((type) => (
								<SelectItem key={type.value} value={type.value}>
									{type.label}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</Field>

				{isChoiceType(question.type) && (
					<Field data-invalid={errors.choices ? "true" : undefined}>
						<FieldLabel htmlFor={fieldId("choices")}>Choices (one per line)</FieldLabel>
						<Textarea
							id={fieldId("choices")}
							value={question.choices}
							rows={4}
							maxLength={(CHOICE_MAX_LENGTH + 1) * 20}
							required
							aria-invalid={Boolean(errors.choices)}
							aria-describedby={describedBy(errors.choices && fieldId("choices-error"))}
							onChange={(event) => onChange({ choices: event.target.value })}
						/>
						{errors.choices && (
							<FieldError id={fieldId("choices-error")}>{errors.choices}</FieldError>
						)}
					</Field>
				)}

				{question.type === "RATING" && (
					<div className="flex flex-col gap-2">
						<div className="grid gap-4 sm:grid-cols-2">
							<Field data-invalid={errors.lowLabel ? "true" : undefined}>
								<FieldLabel htmlFor={fieldId("low")}>Label for 1</FieldLabel>
								<Input
									id={fieldId("low")}
									value={question.lowLabel}
									maxLength={SCALE_LABEL_MAX_LENGTH}
									required
									aria-invalid={Boolean(errors.lowLabel)}
									aria-describedby={describedBy(
										fieldId("scale-help"),
										errors.lowLabel && fieldId("low-error"),
									)}
									onChange={(event) => onChange({ lowLabel: event.target.value })}
								/>
								{errors.lowLabel && (
									<FieldError id={fieldId("low-error")}>{errors.lowLabel}</FieldError>
								)}
							</Field>
							<Field data-invalid={errors.highLabel ? "true" : undefined}>
								<FieldLabel htmlFor={fieldId("high")}>Label for 5</FieldLabel>
								<Input
									id={fieldId("high")}
									value={question.highLabel}
									maxLength={SCALE_LABEL_MAX_LENGTH}
									required
									aria-invalid={Boolean(errors.highLabel)}
									aria-describedby={describedBy(
										fieldId("scale-help"),
										errors.highLabel && fieldId("high-error"),
									)}
									onChange={(event) => onChange({ highLabel: event.target.value })}
								/>
								{errors.highLabel && (
									<FieldError id={fieldId("high-error")}>{errors.highLabel}</FieldError>
								)}
							</Field>
						</div>
						<FieldDescription id={fieldId("scale-help")}>
							Label both ends so every respondent reads the scale the same way.
						</FieldDescription>
					</div>
				)}

				{question.type === "NPS" && (
					<FieldDescription>
						0–10, labelled {NPS_LABELS.low} → {NPS_LABELS.high}
					</FieldDescription>
				)}

				<Field orientation="horizontal">
					<Checkbox
						id={fieldId("required")}
						checked={question.required}
						onCheckedChange={(checked) => onChange({ required: checked })}
					/>
					<FieldLabel htmlFor={fieldId("required")}>Required</FieldLabel>
				</Field>
			</FieldGroup>

			<div className="flex flex-wrap gap-1">
				<Button
					type="button"
					variant="ghost"
					size="icon-sm"
					disabled={index === 0}
					aria-label={`Move question ${number} up`}
					onClick={() => onMove(-1)}
				>
					<ArrowUp aria-hidden />
				</Button>
				<Button
					type="button"
					variant="ghost"
					size="icon-sm"
					disabled={index === count - 1}
					aria-label={`Move question ${number} down`}
					onClick={() => onMove(1)}
				>
					<ArrowDown aria-hidden />
				</Button>
				<Button
					type="button"
					variant="ghost"
					size="icon-sm"
					aria-label={`Remove question ${number}`}
					onClick={onRemove}
				>
					<Trash2 aria-hidden />
				</Button>
			</div>
		</FieldSet>
	);
}
