import { Questionnaire as QuestionnairePrimitive } from "@shadcn/react/questionnaire";
import { type ComponentProps, createContext, type ReactNode, useContext } from "react";

import type { Answer, Question } from "@/api/types.gen";
import {
	Questionnaire,
	QuestionnaireActions,
	QuestionnaireChoice,
	QuestionnaireChoices,
	QuestionnaireDescription,
	QuestionnaireError,
	QuestionnaireInput,
	QuestionnaireItem,
	QuestionnaireNext,
	QuestionnairePrevious,
	QuestionnaireProgress,
	QuestionnaireSkip,
	QuestionnaireSubmit,
	QuestionnaireTitle,
} from "@/components/ui/questionnaire";
import { cn } from "@/lib/utils";

import {
	ANSWER_TEXT_MAX_LENGTH,
	answersFromFormData,
	NPS_LABELS,
	NPS_SCALE,
	OTHER_ANSWER_MAX_LENGTH,
	otherAnswerOf,
	RATING_SCALE,
	type SurveyResponseDraft,
	surveyShortcuts,
} from "./survey-questions";

interface SurveyQuestionnaireContextValue {
	questions: Question[];
	draft: SurveyResponseDraft;
	onDraftChange: (draft: SurveyResponseDraft) => void;
	disabled: boolean;
	last: boolean;
}

const Context = createContext<SurveyQuestionnaireContextValue | null>(null);

function useSurveyQuestionnaire(part: string) {
	const value = useContext(Context);
	if (!value) throw new Error(`${part} must be rendered inside SurveyQuestionnaire`);
	return value;
}

export interface SurveyQuestionnaireProps extends Omit<
	ComponentProps<typeof Questionnaire>,
	"items" | "item" | "defaultItem" | "onItemChange" | "shortcuts" | "onSubmit"
> {
	questions: Question[];
	/** The answers so far and the question in view, owned by the host so closing keeps them. */
	draft: SurveyResponseDraft;
	onDraftChange: (draft: SurveyResponseDraft) => void;
	onSubmit: (answers: Answer[]) => void;
	/**
	 * A submission is in flight: the answers stay on screen and checked while the whole form is
	 * `inert`, which also drops it from the accessibility tree until the send settles.
	 */
	disabled?: boolean;
	children: ReactNode;
}

/**
 * One survey on the shadcn Questionnaire: a native form, one question at a time, answers read from
 * `FormData` on submit. The host composes the parts — `Progress`, `Items`, `Actions` — around its own
 * header and footer, which is what lets the dialog and the composer preview share one form.
 */
export function SurveyQuestionnaire({
	questions,
	draft,
	onDraftChange,
	onSubmit,
	disabled = false,
	children,
	...props
}: SurveyQuestionnaireProps) {
	const first = questions[0]?.id;
	const item = questions.some((question) => question.id === draft.item) ? draft.item : first;
	return (
		<Context
			value={{ questions, draft, onDraftChange, disabled, last: item === questions.at(-1)?.id }}
		>
			<Questionnaire
				aria-busy={disabled}
				items={questions.map((question) => ({
					name: question.id,
					required: question.required,
					choices: choiceValues(question).map((value) => ({ value })),
				}))}
				item={item}
				onItemChange={(next) => onDraftChange({ ...draft, item: next })}
				shortcuts={surveyShortcuts(questions)}
				onSubmit={(event) => {
					event.preventDefault();
					if (!disabled)
						onSubmit(answersFromFormData(questions, new FormData(event.currentTarget)));
				}}
				{...props}
			>
				{children}
			</Questionnaire>
		</Context>
	);
}

function choiceValues(question: Question): readonly string[] {
	if (question.type === "RATING") return RATING_SCALE.map(String);
	if (question.type === "NPS") return NPS_SCALE.map(String);
	return question.options;
}

function Progress(props: ComponentProps<typeof QuestionnaireProgress>) {
	return <QuestionnaireProgress {...props} />;
}

function Items({ className, ...props }: ComponentProps<"div">) {
	const { questions, draft, onDraftChange, disabled } = useSurveyQuestionnaire("Items");
	const answer = (id: string, value: SurveyResponseDraft["answers"][string]) =>
		onDraftChange({ ...draft, answers: { ...draft.answers, [id]: value } });
	return (
		<div className={cn("flex min-w-0 flex-col", className)} inert={disabled} {...props}>
			{questions.map((question) => {
				const value = draft.answers[question.id];
				const scale =
					question.type === "RATING" || question.type === "NPS"
						? {
								points: choiceValues(question),
								low: question.type === "RATING" ? question.lowLabel : NPS_LABELS.low,
								high: question.type === "RATING" ? question.highLabel : NPS_LABELS.high,
							}
						: undefined;
				const multiple = question.type === "MULTIPLE_CHOICE";
				const chosen = Array.isArray(value) ? value : typeof value === "string" ? [value] : [];
				const other = otherAnswerOf(question, chosen);
				const Choice = scale ? ScaleChoice : QuestionnaireChoice;
				return (
					<QuestionnaireItem
						key={question.id}
						name={question.id}
						required={question.required}
						multiple={multiple}
						// A skip unchecks the answers without a change event; the draft has to follow, or the
						// answer comes back on reopen.
						onStatusChange={(status) => {
							if (status === "skipped") answer(question.id, undefined);
						}}
					>
						<QuestionnaireTitle>{question.prompt}</QuestionnaireTitle>
						{scale?.low && scale.high && (
							<QuestionnaireDescription>
								{scale.points[0]} = {scale.low} · {scale.points[scale.points.length - 1]} ={" "}
								{scale.high}
							</QuestionnaireDescription>
						)}
						{!question.required && (
							<QuestionnaireDescription>
								Optional — skip it if it doesn't apply.
							</QuestionnaireDescription>
						)}
						{question.type === "TEXT" ? (
							<QuestionnaireInput
								aria-label="Your answer"
								placeholder="Type your answer…"
								maxLength={ANSWER_TEXT_MAX_LENGTH}
								defaultValue={typeof value === "string" ? value : undefined}
								onChange={(event) => answer(question.id, event.target.value)}
								render={(inputProps) => <MultilineInput {...inputProps} />}
							/>
						) : (
							<QuestionnaireChoices
								className={cn(
									scale &&
										(scale.points.length > 5
											? "grid-cols-6 gap-1.5 sm:grid-cols-11"
											: "grid-cols-5 gap-1.5"),
								)}
							>
								{choiceValues(question).map((option) => (
									<Choice
										key={option}
										value={option}
										defaultChecked={chosen.includes(option)}
										onChange={(event) => {
											if (multiple) {
												const kept = chosen.filter((entry) => entry !== option);
												answer(question.id, event.target.checked ? [...kept, option] : kept);
											} else if (event.target.checked) answer(question.id, option);
										}}
									>
										{option}
									</Choice>
								))}
								{question.allowOther && (
									<QuestionnaireInput
										aria-label="Another answer"
										placeholder="Another answer…"
										maxLength={OTHER_ANSWER_MAX_LENGTH}
										defaultValue={other}
										onChange={(event) => {
											const text = event.target.value;
											const options = chosen.filter((entry) => question.options.includes(entry));
											if (multiple) answer(question.id, text.trim() ? [...options, text] : options);
											else answer(question.id, text.trim() ? text : options[0]);
										}}
									/>
								)}
							</QuestionnaireChoices>
						)}
						<QuestionnaireError>
							{question.required
								? "Choose an answer to continue."
								: "Choose an answer, or skip this question."}
						</QuestionnaireError>
					</QuestionnaireItem>
				);
			})}
		</div>
	);
}

/**
 * A scale point is a square tile whose number is the whole label; the chosen tile is filled and
 * bold, not only recoloured, so the choice reads without colour. Composed from the primitive's
 * parts because the styled choice always draws an indicator dot beside its label.
 */
function ScaleChoice({
	children,
	className,
	...props
}: ComponentProps<typeof QuestionnairePrimitive.Choice>) {
	return (
		<QuestionnairePrimitive.Choice
			className={cn(
				"relative flex min-h-10 cursor-pointer items-center justify-center rounded-lg border border-input text-sm tabular-nums transition-colors select-none hover:bg-muted/50 has-[>input:focus-visible]:border-ring has-[>input:focus-visible]:ring-3 has-[>input:focus-visible]:ring-ring/50 data-checked:border-primary data-checked:bg-primary data-checked:font-semibold data-checked:text-primary-foreground data-invalid:border-destructive dark:bg-input/20",
				className,
			)}
			{...props}
		>
			<QuestionnairePrimitive.ChoiceInput className="absolute inset-0 z-10 size-full cursor-pointer opacity-0" />
			<QuestionnairePrimitive.ChoiceLabel>{children}</QuestionnairePrimitive.ChoiceLabel>
		</QuestionnairePrimitive.Choice>
	);
}

/**
 * The questionnaire's freeform answer is typed as an `<input>`, and its single-line Enter continues
 * to the next question. A survey answer needs line breaks, so this renders a textarea, keeps plain
 * Enter inside it, and announces the ⌘/Ctrl+Enter the questionnaire honours from anywhere. The
 * primitive reads `target.value`, `element.type` and `name` from the element, which a textarea
 * serves alike.
 */
function MultilineInput({ type: _type, ...props }: ComponentProps<"input">) {
	return (
		<textarea
			// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- The primitive's input props; every one the textarea receives is valid on it too.
			{...(props as ComponentProps<"textarea">)}
			rows={3}
			aria-keyshortcuts="Control+Enter Meta+Enter"
			className={cn(props.className, "h-auto min-h-20 resize-y py-2 field-sizing-content")}
			onKeyDown={(event) => {
				if (event.key === "Enter" && !event.metaKey && !event.ctrlKey) event.stopPropagation();
			}}
		/>
	);
}

interface ActionsProps extends ComponentProps<typeof QuestionnaireActions> {
	submitLabel: ReactNode;
}

function Actions({ submitLabel, ...props }: ActionsProps) {
	const { disabled, last } = useSurveyQuestionnaire("Actions");
	return (
		<QuestionnaireActions {...props}>
			<QuestionnairePrevious disabled={disabled} />
			{/* Skipping the last question submits the survey, and the label has to say so. */}
			<QuestionnaireSkip disabled={disabled}>{last ? "Skip and send" : "Skip"}</QuestionnaireSkip>
			<QuestionnaireNext disabled={disabled} />
			<QuestionnaireSubmit disabled={disabled}>{submitLabel}</QuestionnaireSubmit>
		</QuestionnaireActions>
	);
}

SurveyQuestionnaire.Progress = Progress;
SurveyQuestionnaire.Items = Items;
SurveyQuestionnaire.Actions = Actions;
