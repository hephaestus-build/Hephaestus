import type { Answer, Question } from "@/api/types.gen";

/** The server's scales and text limit; a value outside them is refused, not clipped. */
export const RATING_SCALE = [1, 2, 3, 4, 5] as const;
export const NPS_SCALE = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;
export const NPS_LABELS = { low: "Not at all likely", high: "Extremely likely" } as const;
export const ANSWER_TEXT_MAX_LENGTH = 4000;

export const QUESTION_TYPE_LABELS: Record<Question["type"], string> = {
	TEXT: "Free text",
	SINGLE_CHOICE: "Single choice",
	MULTIPLE_CHOICE: "Multiple choice",
	RATING: "Rating (1–5)",
	NPS: "Recommendation (0–10)",
};

export type AnswerDraft = Record<string, string | string[] | number | undefined>;

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

export function isAnswered(value: AnswerDraft[string]): boolean {
	if (Array.isArray(value)) return value.length > 0;
	if (typeof value === "string") return value.trim().length > 0;
	return typeof value === "number";
}

export function isDraftComplete(questions: readonly Question[], draft: AnswerDraft): boolean {
	return questions.every((q) => !q.required || isAnswered(draft[q.id]));
}

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

export function formatAnswer(answer: Answer): string {
	if (answer.text !== undefined) return answer.text;
	if (answer.rating !== undefined) return String(answer.rating);
	return (answer.choices ?? []).join(", ");
}
