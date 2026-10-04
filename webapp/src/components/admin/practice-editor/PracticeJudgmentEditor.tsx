import deepEqual from "fast-deep-equal";
import { ArrowDownIcon, ArrowUpIcon, PlusIcon, RotateCcwIcon, Trash2Icon } from "lucide-react";
import { useId } from "react";

import type { PracticeJudgment, PracticeQuestion, PracticeRule } from "@/api/types.gen";
import { statusValues } from "@/components/common/status-def";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { QUESTION_ANSWER_DEFS } from "@/components/practice-vocabulary/question-answer-defs";
import { SEVERITY_DEFS } from "@/components/practice-vocabulary/severity-defs";
import { Button } from "@/components/ui/button";
import {
	Field,
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
import { Textarea } from "@/components/ui/textarea";

import {
	MAX_MEANING_LENGTH,
	MAX_QUESTION_LENGTH,
	MAX_QUESTIONS,
	MAX_REASON_LENGTH,
	MAX_RULES,
	MAX_TITLE_LENGTH,
	questionKeyOf,
	ruleCondition,
	ruleIdOf,
	ruleResult,
} from "./practice-judgment";

/** The element a refused save focuses: the section heading, which names what to fix below it. */
export const PRACTICE_JUDGMENT_FOCUS_ID = "practice-judgment";

export interface PracticeJudgmentEditorProps {
	value: PracticeJudgment;
	/** What "Restore the starting questions" puts back. */
	starting: PracticeJudgment;
	/**
	 * The judgment as last saved. Its question keys and rule ids stay as they are, because recorded
	 * answers and observations refer to them; a question added since takes its key from its title.
	 */
	saved?: PracticeJudgment;
	disabled?: boolean;
	/** What stops a save, shown once a save was refused. */
	problems?: readonly string[];
	onChange: (value: PracticeJudgment) => void;
}

type Condition = "ANY" | "YES" | "NO";

const NO_PROBLEMS: readonly string[] = [];

const CONDITION_ITEMS: { value: Condition; label: string }[] = [
	{ value: "ANY", label: "Any answer" },
	{ value: "YES", label: QUESTION_ANSWER_DEFS.YES.label },
	{ value: "NO", label: QUESTION_ANSWER_DEFS.NO.label },
];
const OUTCOME_ITEMS = statusValues(OUTCOME_DEFS).map((value) => ({
	value,
	label: OUTCOME_DEFS[value].label,
}));
const SEVERITY_ITEMS = statusValues(SEVERITY_DEFS).map((value) => ({
	value,
	label: SEVERITY_DEFS[value].label,
}));

function conditionOf(rule: PracticeRule, key: string): Condition {
	const answer = rule.when[key];
	return answer === "YES" || answer === "NO" ? answer : "ANY";
}

function moved<T>(items: readonly T[], from: number, to: number): T[] {
	const next = [...items];
	const [item] = next.splice(from, 1);
	if (item !== undefined) {
		next.splice(to, 0, item);
	}
	return next;
}

/**
 * The questions a review answers for one practice, and the ordered rules that turn the answers into an
 * outcome. The first rule whose conditions match decides; the last rule has none, so every combination
 * of answers is decided.
 */
export function PracticeJudgmentEditor({
	value,
	starting,
	saved,
	disabled = false,
	problems = NO_PROBLEMS,
	onChange,
}: PracticeJudgmentEditorProps) {
	const id = useId();
	const { questions, rules } = value;
	const savedKeys = new Set(saved?.questions.map((question) => question.key));
	const lastRule = rules.length - 1;

	const updateQuestion = (index: number, patch: Partial<PracticeQuestion>) => {
		const current = questions[index];
		if (current === undefined) {
			return;
		}
		const next = { ...current, ...patch };
		if (patch.title !== undefined && !savedKeys.has(current.key)) {
			const taken = new Set(questions.filter((_, other) => other !== index).map((q) => q.key));
			next.key = questionKeyOf(patch.title, taken);
		}
		onChange({
			questions: questions.map((question, other) => (other === index ? next : question)),
			rules:
				next.key === current.key
					? rules
					: rules.map((rule) => ({
							...rule,
							when: Object.fromEntries(
								Object.entries(rule.when).map(([key, answer]) => [
									key === current.key ? next.key : key,
									answer,
								]),
							),
						})),
		});
	};

	const removeQuestion = (index: number) => {
		const key = questions[index]?.key;
		onChange({
			questions: questions.filter((_, other) => other !== index),
			rules: rules.map((rule) => ({
				...rule,
				when: Object.fromEntries(Object.entries(rule.when).filter(([other]) => other !== key)),
			})),
		});
	};

	const addQuestion = () => {
		const taken = new Set(questions.map((question) => question.key));
		onChange({
			questions: [
				...questions,
				{ key: questionKeyOf("", taken), title: "", question: "", yes: "", no: "" },
			],
			rules,
		});
	};

	const updateRule = (index: number, patch: Partial<PracticeRule>) => {
		onChange({
			questions,
			rules: rules.map((rule, other) => (other === index ? { ...rule, ...patch } : rule)),
		});
	};

	const setCondition = (index: number, key: string, condition: Condition) => {
		const rule = rules[index];
		if (rule === undefined) {
			return;
		}
		const when = Object.fromEntries(Object.entries(rule.when).filter(([other]) => other !== key));
		updateRule(index, { when: condition === "ANY" ? when : { ...when, [key]: condition } });
	};

	const addRule = () => {
		const first = questions[0];
		const rule: PracticeRule = {
			id: ruleIdOf(new Set(rules.map((existing) => existing.id))),
			when: first === undefined ? {} : { [first.key]: "NO" },
			outcome: "NOT_MET",
			severity: "MINOR",
			reason: "",
		};
		onChange({ questions, rules: [...rules.slice(0, lastRule), rule, ...rules.slice(lastRule)] });
	};

	return (
		<section className="space-y-6" aria-labelledby={PRACTICE_JUDGMENT_FOCUS_ID}>
			<div className="flex flex-wrap items-start justify-between gap-3">
				<div className="min-w-0 flex-1 space-y-1">
					<h2 id={PRACTICE_JUDGMENT_FOCUS_ID} tabIndex={-1} className="text-lg font-semibold">
						How the review decides
					</h2>
					<p className="max-w-2xl text-sm text-muted-foreground">
						The reviewer answers each question yes or no from the evidence it cites, or leaves it
						open. It never states the outcome: the first rule whose conditions match the answers
						decides it. An open answer decides only when every way of settling it leads to the same
						outcome.
					</p>
				</div>
				{!deepEqual(value, starting) && (
					<Button
						type="button"
						variant="outline"
						size="sm"
						disabled={disabled}
						onClick={() => onChange(starting)}
					>
						<RotateCcwIcon aria-hidden />
						Restore the starting questions
					</Button>
				)}
			</div>

			{problems.length > 0 && (
				<FieldError>
					<ul className="list-inside list-disc space-y-1">
						{problems.map((problem) => (
							<li key={problem}>{problem}</li>
						))}
					</ul>
				</FieldError>
			)}

			<FieldSet>
				<FieldLegend>Questions</FieldLegend>
				<FieldDescription>
					Ask about facts a reviewer can quote from the work. Write the title as the statement a yes
					affirms.
				</FieldDescription>
				<ol className="space-y-4">
					{questions.map((question, index) => {
						const name = `question ${index + 1}`;
						const fieldId = `${id}-question-${index}`;
						return (
							// A new question's key follows its title as it is typed, so keying the item on it would
							// remount the field under the cursor at every keystroke.
							<li key={index} className="space-y-3 rounded-lg border p-4">
								<div className="flex items-center justify-between gap-2">
									<h3 className="text-sm font-medium">Question {index + 1}</h3>
									<MoveButtons
										name={name}
										disabled={disabled}
										canMoveUp={index > 0}
										canMoveDown={index < questions.length - 1}
										canRemove={questions.length > 1}
										onMove={(to) => onChange({ questions: moved(questions, index, to), rules })}
										index={index}
										onRemove={() => removeQuestion(index)}
									/>
								</div>
								<FieldGroup className="gap-3">
									<Field>
										<FieldLabel htmlFor={`${fieldId}-title`}>Title</FieldLabel>
										<Input
											id={`${fieldId}-title`}
											value={question.title}
											maxLength={MAX_TITLE_LENGTH}
											placeholder="e.g. States why the change exists"
											onChange={(event) => updateQuestion(index, { title: event.target.value })}
										/>
									</Field>
									<Field>
										<FieldLabel htmlFor={`${fieldId}-question`}>Question</FieldLabel>
										<Textarea
											id={`${fieldId}-question`}
											value={question.question}
											maxLength={MAX_QUESTION_LENGTH}
											className="min-h-20"
											placeholder="e.g. Does the title or description say why the change is needed? A restated title does not count."
											onChange={(event) => updateQuestion(index, { question: event.target.value })}
										/>
									</Field>
									<Field>
										<FieldLabel htmlFor={`${fieldId}-yes`}>A yes means</FieldLabel>
										<Input
											id={`${fieldId}-yes`}
											value={question.yes}
											maxLength={MAX_MEANING_LENGTH}
											onChange={(event) => updateQuestion(index, { yes: event.target.value })}
										/>
									</Field>
									<Field>
										<FieldLabel htmlFor={`${fieldId}-no`}>A no means</FieldLabel>
										<Input
											id={`${fieldId}-no`}
											value={question.no}
											maxLength={MAX_MEANING_LENGTH}
											onChange={(event) => updateQuestion(index, { no: event.target.value })}
										/>
									</Field>
								</FieldGroup>
							</li>
						);
					})}
				</ol>
				<div>
					<Button
						type="button"
						variant="outline"
						size="sm"
						disabled={disabled || questions.length >= MAX_QUESTIONS}
						onClick={addQuestion}
					>
						<PlusIcon aria-hidden />
						Add question
					</Button>
				</div>
			</FieldSet>

			<FieldSet>
				<FieldLegend>Rules</FieldLegend>
				<FieldDescription>
					Read from the top: the first rule whose conditions match the answers decides the outcome.
					The last rule decides every case the others leave.
				</FieldDescription>
				<ol className="space-y-4">
					{rules.map((rule, index) => {
						const isLast = index === lastRule;
						const name = `rule ${index + 1}`;
						const fieldId = `${id}-rule-${index}`;
						return (
							<li key={rule.id} className="space-y-3 rounded-lg border p-4">
								<div className="flex items-start justify-between gap-2">
									<div className="min-w-0">
										<h3 className="text-sm font-medium">
											{isLast ? "Last rule" : `Rule ${index + 1}`}
										</h3>
										<p className="text-sm text-muted-foreground">
											{ruleCondition(rule, questions)}: {ruleResult(rule)}
										</p>
									</div>
									{!isLast && (
										<MoveButtons
											name={name}
											disabled={disabled}
											canMoveUp={index > 0}
											canMoveDown={index < lastRule - 1}
											canRemove={rules.length > 2}
											index={index}
											onMove={(to) => onChange({ questions, rules: moved(rules, index, to) })}
											onRemove={() =>
												onChange({ questions, rules: rules.filter((_, other) => other !== index) })
											}
										/>
									)}
								</div>
								<FieldGroup className="gap-3">
									{!isLast &&
										questions.map((question) => {
											const conditionId = `${fieldId}-when-${question.key}`;
											const condition = conditionOf(rule, question.key);
											return (
												<Field key={question.key} orientation="responsive">
													<FieldLabel htmlFor={conditionId}>
														{question.title.trim() || "Untitled question"}
													</FieldLabel>
													<Select
														items={CONDITION_ITEMS}
														value={condition}
														onValueChange={(next) => {
															const chosen = CONDITION_ITEMS.find((item) => item.value === next);
															if (chosen) {
																setCondition(index, question.key, chosen.value);
															}
														}}
													>
														<SelectTrigger id={conditionId} className="w-full @md/field-group:w-56">
															<SelectValue />
														</SelectTrigger>
														<SelectContent
															aria-label={`Answer to ${question.title.trim() || "this question"}`}
														>
															{CONDITION_ITEMS.map((item) => (
																<SelectItem key={item.value} value={item.value}>
																	{item.label}
																</SelectItem>
															))}
														</SelectContent>
													</Select>
												</Field>
											);
										})}
									<Field orientation="responsive">
										<FieldLabel htmlFor={`${fieldId}-outcome`}>Outcome</FieldLabel>
										<Select
											items={OUTCOME_ITEMS}
											value={rule.outcome}
											onValueChange={(next) => {
												const outcome = OUTCOME_ITEMS.find((item) => item.value === next)?.value;
												if (outcome !== undefined) {
													updateRule(index, {
														outcome,
														severity:
															outcome === "NOT_MET" ? (rule.severity ?? "MINOR") : undefined,
													});
												}
											}}
										>
											<SelectTrigger
												id={`${fieldId}-outcome`}
												className="w-full @md/field-group:w-56"
											>
												<SelectValue />
											</SelectTrigger>
											<SelectContent aria-label="Outcome">
												{OUTCOME_ITEMS.map((item) => (
													<SelectItem key={item.value} value={item.value}>
														{item.label}
													</SelectItem>
												))}
											</SelectContent>
										</Select>
									</Field>
									{rule.outcome === "NOT_MET" && (
										<Field orientation="responsive">
											<FieldLabel htmlFor={`${fieldId}-severity`}>Severity</FieldLabel>
											<Select
												items={SEVERITY_ITEMS}
												value={rule.severity ?? "MINOR"}
												onValueChange={(next) => {
													const severity = SEVERITY_ITEMS.find(
														(item) => item.value === next,
													)?.value;
													if (severity !== undefined) {
														updateRule(index, { severity });
													}
												}}
											>
												<SelectTrigger
													id={`${fieldId}-severity`}
													className="w-full @md/field-group:w-56"
												>
													<SelectValue />
												</SelectTrigger>
												<SelectContent aria-label="Severity">
													{SEVERITY_ITEMS.map((item) => (
														<SelectItem key={item.value} value={item.value}>
															{item.label}
														</SelectItem>
													))}
												</SelectContent>
											</Select>
										</Field>
									)}
									<Field>
										<FieldLabel htmlFor={`${fieldId}-reason`}>Reason</FieldLabel>
										<Input
											id={`${fieldId}-reason`}
											value={rule.reason}
											maxLength={MAX_REASON_LENGTH}
											placeholder="e.g. The description says what changed but not why."
											onChange={(event) => updateRule(index, { reason: event.target.value })}
										/>
										<FieldDescription>
											One sentence about the work. It heads every observation this rule decides.
										</FieldDescription>
									</Field>
								</FieldGroup>
							</li>
						);
					})}
				</ol>
				<div>
					<Button
						type="button"
						variant="outline"
						size="sm"
						disabled={disabled || rules.length >= MAX_RULES}
						onClick={addRule}
					>
						<PlusIcon aria-hidden />
						Add rule
					</Button>
				</div>
			</FieldSet>
		</section>
	);
}

interface MoveButtonsProps {
	/** "question 2", as the buttons' names read it. */
	name: string;
	index: number;
	disabled: boolean;
	canMoveUp: boolean;
	canMoveDown: boolean;
	canRemove: boolean;
	onMove: (to: number) => void;
	onRemove: () => void;
}

function MoveButtons({
	name,
	index,
	disabled,
	canMoveUp,
	canMoveDown,
	canRemove,
	onMove,
	onRemove,
}: MoveButtonsProps) {
	return (
		<div className="flex shrink-0 items-center gap-1">
			<Button
				type="button"
				variant="ghost"
				size="icon-sm"
				disabled={disabled || !canMoveUp}
				aria-label={`Move ${name} up`}
				onClick={() => onMove(index - 1)}
			>
				<ArrowUpIcon aria-hidden />
			</Button>
			<Button
				type="button"
				variant="ghost"
				size="icon-sm"
				disabled={disabled || !canMoveDown}
				aria-label={`Move ${name} down`}
				onClick={() => onMove(index + 1)}
			>
				<ArrowDownIcon aria-hidden />
			</Button>
			<Button
				type="button"
				variant="ghost"
				size="icon-sm"
				disabled={disabled || !canRemove}
				aria-label={`Remove ${name}`}
				onClick={onRemove}
			>
				<Trash2Icon aria-hidden />
			</Button>
		</div>
	);
}
