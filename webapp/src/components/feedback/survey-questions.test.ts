import { describe, expect, it } from "vitest";

import type { Question } from "@/api/types.gen";

import { formatAnswer, isDraftComplete, surveyEstimate, toAnswers } from "./survey-questions";

const questions: Question[] = [
	{
		id: "rating",
		prompt: "Useful?",
		type: "RATING",
		options: [],
		required: true,
		lowLabel: "No",
		highLabel: "Yes",
	},
	{ id: "one", prompt: "Pick", type: "SINGLE_CHOICE", options: ["A", "B"], required: false },
	{ id: "many", prompt: "Pick any", type: "MULTIPLE_CHOICE", options: ["X", "Y"], required: false },
	{ id: "why", prompt: "Why?", type: "TEXT", options: [], required: false },
];

describe("survey questions", () => {
	it("estimates the time from the question mix", () => {
		expect(surveyEstimate([{ type: "RATING" }])).toBe("1 question · under a minute");
		expect(surveyEstimate(questions)).toBe("4 questions · about 2 minutes");
	});

	it("treats blank text and empty choice lists as unanswered", () => {
		expect(isDraftComplete(questions, { rating: 3, why: "  " })).toBe(true);
		expect(isDraftComplete(questions, { why: "text" })).toBe(false);
	});

	it("puts every answer in the field its question type expects and drops the rest", () => {
		expect(
			toAnswers(questions, { rating: 4, one: "B", many: ["Y", "X"], why: " Fast ", stale: "x" }),
		).toStrictEqual([
			{ questionId: "rating", rating: 4 },
			{ questionId: "one", choices: ["B"] },
			{ questionId: "many", choices: ["Y", "X"] },
			{ questionId: "why", text: "Fast" },
		]);
		expect(toAnswers(questions, { many: [], why: "" })).toStrictEqual([]);
	});

	it("reads an answer back as text", () => {
		expect(formatAnswer({ questionId: "q", rating: 5 })).toBe("5");
		expect(formatAnswer({ questionId: "q", choices: ["A", "B"] })).toBe("A, B");
		expect(formatAnswer({ questionId: "q", text: "Hi" })).toBe("Hi");
	});
});
