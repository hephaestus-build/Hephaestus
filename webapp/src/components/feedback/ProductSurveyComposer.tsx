import { useId, useState } from "react";

import type { CreateSurvey, Question } from "@/api/types.gen";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { FieldDescription, FieldError } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useUnsavedChanges } from "@/hooks/use-unsaved-changes";

const TYPES = [
	{ label: "Free text", value: "TEXT" },
	{ label: "Single choice", value: "SINGLE_CHOICE" },
	{ label: "Rating (1–5)", value: "RATING" },
] as const;

interface ProductSurveyComposerProps {
	workspaces: { id: number; displayName: string }[];
	isPending: boolean;
	error?: string;
	onSubmit: (survey: CreateSurvey) => Promise<boolean>;
}

type QuestionDraft = Omit<Question, "options"> & { choices: string };

function newQuestion(): QuestionDraft {
	return { id: crypto.randomUUID(), prompt: "", type: "TEXT", choices: "", required: false };
}

function choiceError(question: Question): string | undefined {
	if (question.type !== "SINGLE_CHOICE") return undefined;
	if (question.options.length < 2 || question.options.length > 20) return "Enter 2–20 choices.";
	if (new Set(question.options).size !== question.options.length)
		return "Each choice must be different.";
	if (question.options.some((option) => option.length > 200))
		return "Keep each choice to 200 characters or fewer.";
	return undefined;
}

export function ProductSurveyComposer({
	workspaces,
	isPending,
	error,
	onSubmit,
}: ProductSurveyComposerProps) {
	const id = useId();
	const [title, setTitle] = useState("");
	const [description, setDescription] = useState("");
	const [workspace, setWorkspace] = useState("all");
	const [startsAt, setStartsAt] = useState("");
	const [endsAt, setEndsAt] = useState("");
	const [questions, setQuestions] = useState<QuestionDraft[]>(() => [newQuestion()]);
	const [submittedDateError, setSubmittedDateError] = useState<string>();
	const { dialog } = useUnsavedChanges({
		isDirty: !!(
			title ||
			description ||
			startsAt ||
			endsAt ||
			workspace !== "all" ||
			questions.length > 1 ||
			questions.some(
				(question) =>
					!!question.prompt ||
					!!question.choices ||
					!!question.required ||
					question.type !== "TEXT",
			)
		),
		disabled: isPending,
	});
	const workspaceOptions = [
		{ value: "all", label: "All workspaces" },
		...workspaces.map((w) => ({ value: String(w.id), label: w.displayName })),
	];
	const update = (index: number, change: Partial<QuestionDraft>) =>
		setQuestions(questions.map((q, i) => (i === index ? { ...q, ...change } : q)));
	const prepared = questions.map(({ choices, ...question }) => ({
		...question,
		prompt: question.prompt.trim(),
		options:
			question.type === "SINGLE_CHOICE"
				? choices
						.split("\n")
						.map((option) => option.trim())
						.filter(Boolean)
				: [],
	}));
	const choiceErrors = prepared.map(choiceError);
	const invalidChoice = choiceErrors.some(Boolean);
	const dateError =
		startsAt && endsAt && new Date(endsAt) <= new Date(startsAt)
			? "The end must be after the start."
			: submittedDateError;

	return (
		<>
			<form
				className="space-y-5"
				onSubmit={(event) => {
					event.preventDefault();
					if (
						isPending ||
						invalidChoice ||
						!!dateError ||
						!title.trim() ||
						!description.trim() ||
						prepared.some((q) => !q.prompt)
					)
						return;
					const start = startsAt ? new Date(startsAt) : new Date();
					if (endsAt && new Date(endsAt) <= start) {
						setSubmittedDateError("The end must be after the start.");
						return;
					}
					void onSubmit({
						title: title.trim(),
						description: description.trim(),
						workspaceId: workspace === "all" ? undefined : Number(workspace),
						startsAt: start,
						endsAt: endsAt ? new Date(endsAt) : undefined,
						questions: prepared,
					});
				}}
			>
				<fieldset disabled={isPending} className="space-y-5">
					<legend className="sr-only">Survey authoring</legend>
					<p className="text-sm text-muted-foreground">
						Keep it short and ask one thing at a time. Questions are optional by default. Responses
						stay on this instance; this is not a research survey.
					</p>
					<div className="space-y-2">
						<Label htmlFor={`${id}-title`}>Title</Label>
						<Input
							id={`${id}-title`}
							value={title}
							maxLength={160}
							required
							onChange={(e) => setTitle(e.target.value)}
						/>
					</div>
					<div className="space-y-2">
						<Label htmlFor={`${id}-purpose`}>Purpose</Label>
						<Textarea
							id={`${id}-purpose`}
							value={description}
							maxLength={500}
							required
							onChange={(e) => setDescription(e.target.value)}
							placeholder="What decision will these answers help you make?"
						/>
					</div>
					<div className="space-y-2">
						<Label htmlFor={`${id}-audience`}>Audience</Label>
						<Select
							disabled={isPending}
							items={workspaceOptions}
							value={workspace}
							onValueChange={(value) => value && setWorkspace(value)}
						>
							<SelectTrigger id={`${id}-audience`}>
								<SelectValue />
							</SelectTrigger>
							<SelectContent aria-label="Audience">
								{workspaceOptions.map((w) => (
									<SelectItem key={w.value} value={w.value}>
										{w.label}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
					</div>
					<div className="grid gap-4 sm:grid-cols-2">
						<div className="space-y-2">
							<Label htmlFor={`${id}-start`}>Start (optional)</Label>
							<Input
								id={`${id}-start`}
								type="datetime-local"
								value={startsAt}
								aria-describedby={`${id}-start-hint`}
								onChange={(e) => {
									setStartsAt(e.target.value);
									setSubmittedDateError(undefined);
								}}
							/>
							<FieldDescription id={`${id}-start-hint`}>
								Leave blank to start when published.
							</FieldDescription>
						</div>
						<div className="space-y-2">
							<Label htmlFor={`${id}-end`}>End (optional)</Label>
							<Input
								id={`${id}-end`}
								type="datetime-local"
								value={endsAt}
								aria-invalid={!!dateError}
								aria-describedby={`${id}-end-hint${dateError ? ` ${id}-end-error` : ""}`}
								onChange={(e) => {
									setEndsAt(e.target.value);
									setSubmittedDateError(undefined);
								}}
							/>
							<FieldDescription id={`${id}-end-hint`}>
								Times use your device's local timezone. Set an end to avoid stale invitations.
							</FieldDescription>
							{dateError && <FieldError id={`${id}-end-error`}>{dateError}</FieldError>}
						</div>
					</div>
					{questions.map((q, index) => (
						<fieldset key={q.id} className="space-y-3 rounded-lg border p-4">
							<legend className="px-1 font-medium">Question {index + 1}</legend>
							<div className="space-y-2">
								<Label htmlFor={`${id}-${q.id}-prompt`}>Question</Label>
								<Textarea
									id={`${id}-${q.id}-prompt`}
									required
									maxLength={300}
									value={q.prompt}
									onChange={(e) => update(index, { prompt: e.target.value })}
								/>
							</div>
							<div className="space-y-2">
								<Label htmlFor={`${id}-${q.id}-type`}>Answer type</Label>
								<Select
									disabled={isPending}
									items={TYPES}
									value={q.type}
									onValueChange={(value) => value && update(index, { type: value })}
								>
									<SelectTrigger id={`${id}-${q.id}-type`}>
										<SelectValue />
									</SelectTrigger>
									<SelectContent aria-label="Answer type">
										{TYPES.map((t) => (
											<SelectItem key={t.value} value={t.value}>
												{t.label}
											</SelectItem>
										))}
									</SelectContent>
								</Select>
							</div>
							{q.type === "SINGLE_CHOICE" && (
								<div className="space-y-2">
									<Label htmlFor={`${id}-${q.id}-choices`}>Choices (one per line)</Label>
									<Textarea
										id={`${id}-${q.id}-choices`}
										value={q.choices}
										aria-invalid={!!choiceErrors[index]}
										aria-describedby={`${id}-${q.id}-hint${choiceErrors[index] ? ` ${id}-${q.id}-error` : ""}`}
										onChange={(e) => update(index, { choices: e.target.value })}
									/>
									<FieldDescription id={`${id}-${q.id}-hint`}>
										Use 2–20 distinct choices, up to 200 characters each. Include “Not applicable”
										when appropriate.
									</FieldDescription>
									{choiceErrors[index] && (
										<FieldError id={`${id}-${q.id}-error`}>{choiceErrors[index]}</FieldError>
									)}
								</div>
							)}
							{q.type === "RATING" && (
								<p className="text-xs text-muted-foreground">
									Explain what 1 and 5 mean in the question. For named scale points, use single
									choice instead.
								</p>
							)}
							<div className="flex flex-wrap items-center justify-between gap-2">
								<div className="flex items-center gap-2">
									<Checkbox
										id={`${id}-${q.id}-required`}
										disabled={isPending}
										checked={q.required}
										onCheckedChange={(required) => update(index, { required })}
									/>
									<Label htmlFor={`${id}-${q.id}-required`}>Required</Label>
								</div>
								<Button
									type="button"
									variant="ghost"
									size="sm"
									disabled={questions.length === 1}
									onClick={() => setQuestions(questions.filter((question) => question.id !== q.id))}
								>
									Remove question {index + 1}
								</Button>
							</div>
						</fieldset>
					))}
					<Button
						type="button"
						variant="outline"
						disabled={questions.length >= 20}
						onClick={() => setQuestions([...questions, newQuestion()])}
					>
						Add question
					</Button>
				</fieldset>
				<p role="alert" className="text-sm text-destructive">
					{error}
				</p>
				<p className="text-sm text-muted-foreground">
					Invitations appear in the header during the survey’s schedule, without opening a dialog.
					Questions cannot be edited after publishing. You can pause invitations at any time.
				</p>
				<Button
					type="submit"
					disabled={
						isPending ||
						invalidChoice ||
						!!dateError ||
						!title.trim() ||
						!description.trim() ||
						prepared.some((q) => !q.prompt)
					}
				>
					{isPending ? "Publishing…" : "Publish survey"}
				</Button>
			</form>
			{dialog}
		</>
	);
}
