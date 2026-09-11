import type { Answer, Question } from "@/api/types.gen";

/** Rating scales are fixed by the server; the client only knows how to draw them. */
export const RATING_SCALE = [1, 2, 3, 4, 5] as const;
export const NPS_SCALE = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;
export const NPS_LABELS = { low: "Not at all likely", high: "Extremely likely" } as const;

export const QUESTION_TYPE_LABELS: Record<Question["type"], string> = {
	TEXT: "Free text",
	SINGLE_CHOICE: "Single choice",
	MULTIPLE_CHOICE: "Multiple choice",
	RATING: "Rating (1–5)",
	NPS: "Recommendation (0–10)",
};

/** One respondent's in-progress answers, keyed by question; the shape follows the question type. */
export type AnswerDraft = Record<string, string | string[] | number | undefined>;

/**
 * Roughly how long the survey takes, so an invitation can say so: about 15 seconds per closed
 * question and 45 for free text, rounded up to whole minutes ("under a minute" below one).
 */
export function surveyEstimate(questions: readonly Pick<Question, "type">[]): string {
	const seconds = questions.reduce((sum, q) => sum + (q.type === "TEXT" ? 45 : 15), 0);
	const count = `${questions.length} ${questions.length === 1 ? "question" : "questions"}`;
	if (seconds < 60) return `${count} · under a minute`;
	const minutes = Math.ceil(seconds / 60);
	return `${count} · about ${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}

export function isAnswered(value: AnswerDraft[string]): boolean {
	if (Array.isArray(value)) return value.length > 0;
	if (typeof value === "string") return value.trim().length > 0;
	return typeof value === "number";
}

export function isDraftComplete(questions: readonly Question[], draft: AnswerDraft): boolean {
	return questions.every((q) => !q.required || isAnswered(draft[q.id]));
}

/** The wire shape of a draft: one answer per answered question, in the question's own field. */
export function toAnswers(questions: readonly Question[], draft: AnswerDraft): Answer[] {
	const answers: Answer[] = [];
	for (const question of questions) {
		const value = draft[question.id];
		if (!isAnswered(value)) continue;
		if (typeof value === "number") answers.push({ questionId: question.id, rating: value });
		else if (Array.isArray(value)) answers.push({ questionId: question.id, choices: value });
		else if (typeof value === "string") {
			const text = value.trim();
			if (question.type === "TEXT") answers.push({ questionId: question.id, text });
			else answers.push({ questionId: question.id, choices: [text] });
		}
	}
	return answers;
}

/** How a stored answer reads back, for an inbox or an export preview. */
export function formatAnswer(answer: Answer): string {
	if (answer.text !== undefined) return answer.text;
	if (answer.rating !== undefined) return String(answer.rating);
	return (answer.choices ?? []).join(", ");
}
