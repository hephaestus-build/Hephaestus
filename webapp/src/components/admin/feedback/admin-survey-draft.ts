import type { CreateSurvey, Question } from "@/api/types.gen";

/**
 * What the composer holds while a survey is written: strings as the fields hold them, so a
 * half-typed value never has to round-trip through the wire shape. `toCreateSurvey` is the one
 * place the draft becomes a request.
 */
export interface QuestionDraft {
	id: string;
	prompt: string;
	type: Question["type"];
	/** One choice per line; only read for the choice types. */
	choices: string;
	lowLabel: string;
	highLabel: string;
	required: boolean;
}

export interface SurveyDraft {
	title: string;
	description: string;
	/** `ALL_WORKSPACES` or a workspace id as the select holds it. */
	audience: string;
	/** `datetime-local` values. */
	startsAt: string;
	endsAt: string;
	questions: QuestionDraft[];
}

export const ALL_WORKSPACES = "all";

/** The server caps a survey at 20 questions; response rates fall off well before that. */
export const MAX_QUESTIONS = 20;
export const RECOMMENDED_MAX_QUESTIONS = 5;

export const TITLE_MAX_LENGTH = 160;
export const DESCRIPTION_MAX_LENGTH = 500;
export const PROMPT_MAX_LENGTH = 300;
export const CHOICE_MAX_LENGTH = 200;
export const SCALE_LABEL_MAX_LENGTH = 60;
const MIN_CHOICES = 2;
const MAX_CHOICES = 20;
/** Room in the choices textarea for the most choices at their longest, each on its own line. */
export const CHOICES_TEXT_MAX_LENGTH = (CHOICE_MAX_LENGTH + 1) * MAX_CHOICES;

export function isChoiceType(type: Question["type"]): boolean {
	return type === "SINGLE_CHOICE" || type === "MULTIPLE_CHOICE";
}

export function newQuestionDraft(id: string): QuestionDraft {
	return {
		id,
		prompt: "",
		type: "TEXT",
		choices: "",
		lowLabel: "",
		highLabel: "",
		required: false,
	};
}

export function emptySurveyDraft(firstQuestionId: string): SurveyDraft {
	return {
		title: "",
		description: "",
		audience: ALL_WORKSPACES,
		startsAt: "",
		endsAt: "",
		questions: [newQuestionDraft(firstQuestionId)],
	};
}

export function parseChoices(text: string): string[] {
	return text
		.split("\n")
		.map((choice) => choice.trim())
		.filter(Boolean);
}

/** The wire shape of one question, as the preview and the request both read it. */
export function prepareQuestion(draft: QuestionDraft): Question {
	const question: Question = {
		id: draft.id,
		prompt: draft.prompt.trim(),
		type: draft.type,
		options: isChoiceType(draft.type) ? parseChoices(draft.choices) : [],
		required: draft.required,
	};
	if (draft.type === "RATING") {
		question.lowLabel = draft.lowLabel.trim();
		question.highLabel = draft.highLabel.trim();
	}
	return question;
}

export function prepareQuestions(questions: readonly QuestionDraft[]): Question[] {
	return questions.map(prepareQuestion);
}

export interface QuestionDraftErrors {
	prompt?: string;
	choices?: string;
	lowLabel?: string;
	highLabel?: string;
}

export interface SurveyDraftErrors {
	title?: string;
	description?: string;
	endsAt?: string;
	/** A problem with the list itself rather than with one question. */
	questions?: string;
	/** Parallel to the draft's questions. */
	questionErrors: QuestionDraftErrors[];
}

function validateQuestion(draft: QuestionDraft): QuestionDraftErrors {
	const errors: QuestionDraftErrors = {};
	if (!draft.prompt.trim()) errors.prompt = "Write the question.";
	if (isChoiceType(draft.type)) {
		const choices = parseChoices(draft.choices);
		if (choices.length < MIN_CHOICES || choices.length > MAX_CHOICES) {
			errors.choices = "Enter 2–20 choices, one per line.";
		} else if (new Set(choices).size !== choices.length) {
			errors.choices = "Each choice must be different.";
		} else if (choices.some((choice) => choice.length > CHOICE_MAX_LENGTH)) {
			errors.choices = "Keep each choice to 200 characters or fewer.";
		}
	}
	if (draft.type === "RATING") {
		if (!draft.lowLabel.trim()) errors.lowLabel = "Label the low end of the scale.";
		if (!draft.highLabel.trim()) errors.highLabel = "Label the high end of the scale.";
	}
	return errors;
}

/**
 * Mirrors the server's rules (`SurveyQuestions.validateDefinition` and the DTO constraints) so a
 * refused draft is explained beside the field rather than by a 400. `now` stands in for a blank
 * start.
 */
export function validateSurveyDraft(draft: SurveyDraft, now: number): SurveyDraftErrors {
	const errors: SurveyDraftErrors = { questionErrors: draft.questions.map(validateQuestion) };
	if (!draft.title.trim()) errors.title = "Give the survey a title.";
	if (!draft.description.trim()) errors.description = "Say what the answers are for.";
	if (draft.endsAt) {
		const start = draft.startsAt ? new Date(draft.startsAt).getTime() : now;
		if (new Date(draft.endsAt).getTime() <= start) {
			errors.endsAt = "The end must be after the start.";
		}
	}
	if (draft.questions.length === 0) errors.questions = "Add at least one question.";
	return errors;
}

export function hasDraftErrors(errors: SurveyDraftErrors): boolean {
	const { questionErrors, ...survey } = errors;
	return (
		Object.values(survey).some(Boolean) ||
		questionErrors.some((question) => Object.values(question).some(Boolean))
	);
}

export function toCreateSurvey(draft: SurveyDraft, publishedAt: Date): CreateSurvey {
	return {
		title: draft.title.trim(),
		description: draft.description.trim(),
		workspaceId: draft.audience === ALL_WORKSPACES ? undefined : Number(draft.audience),
		startsAt: draft.startsAt ? new Date(draft.startsAt) : publishedAt,
		endsAt: draft.endsAt ? new Date(draft.endsAt) : undefined,
		questions: prepareQuestions(draft.questions),
	};
}
