import type { Answer, Question } from "@/api/types.gen";

/** The server's scales and text limits; a value outside them is refused, not clipped. */
export const RATING_SCALE = [1, 2, 3, 4, 5] as const;
export const NPS_SCALE = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;
export const NPS_LABELS = { low: "Not at all likely", high: "Extremely likely" } as const;
export const ANSWER_TEXT_MAX_LENGTH = 4000;
export const OTHER_ANSWER_MAX_LENGTH = 200;

export const QUESTION_TYPE_LABELS: Record<Question["type"], string> = {
	TEXT: "Free text",
	SINGLE_CHOICE: "Single choice",
	MULTIPLE_CHOICE: "Multiple choice",
	RATING: "Rating (1–5)",
	NPS: "Recommendation (0–10)",
};

export type AnswerDraft = Record<string, string | string[] | undefined>;

/** What a member has typed or chosen so far, and the question they were on. */
export interface SurveyResponseDraft {
	answers: AnswerDraft;
	item?: string;
}

export const EMPTY_SURVEY_RESPONSE_DRAFT: SurveyResponseDraft = { answers: {} };

/**
 * "3 questions · about 1 minute": 15 seconds per closed question and 45 for free text, rounded up
 * to whole minutes. Stated up front because an invitation that hides its cost is not an invitation.
 */
export function surveyEstimate(questions: readonly Pick<Question, "type">[]): string {
	const seconds = questions.reduce((sum, q) => sum + (q.type === "TEXT" ? 45 : 15), 0);
	const count = `${questions.length} ${questions.length === 1 ? "question" : "questions"}`;
	if (seconds < 60) return `${count} · under a minute`;
	const minutes = Math.ceil(seconds / 60);
	return `${count} · about ${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}

/**
 * Answer shortcuts are one mode for the whole questionnaire. Numbers read naturally on a 1–5 scale
 * but would mislabel a 0–10 one (keys start at 1), and letters would be nonsense on either, so a
 * survey with a recommendation question gets none.
 */
export function surveyShortcuts(
	questions: readonly Pick<Question, "type">[],
): "letters" | "numbers" | undefined {
	if (questions.some((q) => q.type === "NPS")) return undefined;
	return questions.some((q) => q.type === "RATING") ? "numbers" : "letters";
}

/** The free-text choice of a choice question, if the member wrote one instead of picking an option. */
export function otherAnswerOf(question: Question, chosen: readonly string[]): string | undefined {
	return question.allowOther
		? chosen.find((entry) => !question.options.includes(entry))
		: undefined;
}

/** The wire shape of a submitted questionnaire: one answer per answered question, in its own field. */
export function answersFromFormData(questions: readonly Question[], data: FormData): Answer[] {
	const answers: Answer[] = [];
	for (const question of questions) {
		const values = data
			.getAll(question.id)
			.filter((entry): entry is string => typeof entry === "string");
		// A textarea submits CRLF line breaks; the stored answer keeps the LF the member typed.
		const trimmed = values.map((entry) => entry.replaceAll("\r\n", "\n").trim()).filter(Boolean);
		if (trimmed.length === 0) continue;
		if (question.type === "TEXT") answers.push({ questionId: question.id, text: trimmed[0] });
		else if (question.type === "RATING" || question.type === "NPS")
			answers.push({ questionId: question.id, rating: Number(trimmed[0]) });
		else answers.push({ questionId: question.id, choices: trimmed });
	}
	return answers;
}

export function formatAnswer(answer: Answer): string {
	if (answer.text !== undefined) return answer.text;
	if (answer.rating !== undefined) return String(answer.rating);
	return (answer.choices ?? []).join(", ");
}
