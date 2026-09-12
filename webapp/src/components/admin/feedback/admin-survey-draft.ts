import type { CreateSurvey, Question, Survey, SurveyInvitation } from "@/api/types.gen";

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
	/** Only read for the choice types. */
	allowOther: boolean;
}

export interface SurveyDraft {
	title: string;
	description: string;
	purpose: Survey["purpose"];
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
		allowOther: false,
	};
}

export function emptySurveyDraft(firstQuestionId: string): SurveyDraft {
	return {
		title: "",
		description: "",
		purpose: "PRODUCT",
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
		allowOther: isChoiceType(draft.type) && draft.allowOther,
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

/** A `datetime-local` value as an instant in the device's timezone; blank is no date. */
function localDateTime(value: string): Date | undefined {
	return value ? new Date(value) : undefined;
}

/**
 * Mirrors the server's rules (`SurveyQuestions.validateDefinition` and the DTO constraints) so a
 * refused draft is explained beside the field rather than by a 400. `now` stands in for a blank
 * start.
 */
export function validateSurveyDraft(draft: SurveyDraft, now: number): SurveyDraftErrors {
	const errors: SurveyDraftErrors = { questionErrors: draft.questions.map(validateQuestion) };
	if (!draft.title.trim()) errors.title = "Give the survey a title.";
	if (!draft.description.trim()) errors.description = "Tell members why you're asking.";
	const endsAt = localDateTime(draft.endsAt);
	if (endsAt && endsAt.getTime() <= (localDateTime(draft.startsAt)?.getTime() ?? now)) {
		errors.endsAt = "The end must be after the start.";
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

/**
 * A research purpose needs a study to belong to. Without an organisation — the programme was
 * switched off while the draft was open — the survey is published as a product survey rather than
 * refused, because that is the only survey this instance can still run.
 */
function purposeOf(
	draft: SurveyDraft,
	researchOrganization: string | undefined,
): Survey["purpose"] {
	return draft.purpose === "RESEARCH" && researchOrganization ? "RESEARCH" : "PRODUCT";
}

export function toCreateSurvey(
	draft: SurveyDraft,
	publishedAt: Date,
	researchOrganization: string | undefined,
): CreateSurvey {
	return {
		title: draft.title.trim(),
		description: draft.description.trim(),
		purpose: purposeOf(draft, researchOrganization),
		workspaceId: draft.audience === ALL_WORKSPACES ? undefined : Number(draft.audience),
		startsAt: localDateTime(draft.startsAt) ?? publishedAt,
		endsAt: localDateTime(draft.endsAt),
		questions: prepareQuestions(draft.questions),
	};
}

/** The invitation members would get were the draft published as it stands; Preview shows it. */
export function toPreviewSurvey(
	draft: SurveyDraft,
	researchOrganization: string | undefined,
): SurveyInvitation {
	const purpose = purposeOf(draft, researchOrganization);
	return {
		id: "preview",
		title: draft.title.trim(),
		description: draft.description.trim(),
		purpose,
		researchOrganization: purpose === "RESEARCH" ? researchOrganization : undefined,
		questions: prepareQuestions(draft.questions),
		endsAt: localDateTime(draft.endsAt),
		seen: true,
	};
}
