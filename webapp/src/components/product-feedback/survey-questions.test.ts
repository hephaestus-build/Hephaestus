import { describe, expect, it } from "vitest";

import type { Question } from "@/api/types.gen";

import {
	answersFromFormData,
	formatAnswer,
	otherAnswerOf,
	surveyEstimate,
	surveyShortcuts,
} from "./survey-questions";

const rating: Question = {
	id: "rating",
	prompt: "Useful?",
	type: "RATING",
	options: [],
	required: true,
	allowOther: false,
	lowLabel: "No",
	highLabel: "Yes",
};
const one: Question = {
	id: "one",
	prompt: "Pick",
	type: "SINGLE_CHOICE",
	options: ["A", "B"],
	required: false,
	allowOther: true,
};
const many: Question = {
	id: "many",
	prompt: "Pick any",
	type: "MULTIPLE_CHOICE",
	options: ["X", "Y"],
	required: false,
	allowOther: false,
};
const why: Question = {
	id: "why",
	prompt: "Why?",
	type: "TEXT",
	options: [],
	required: false,
	allowOther: false,
};
const nps: Question = {
	...rating,
	id: "nps",
	type: "NPS",
	lowLabel: undefined,
	highLabel: undefined,
};

describe("survey questions", () => {
	it("estimates the time from the question mix", () => {
		expect(surveyEstimate([rating])).toBe("1 question · under a minute");
		expect(surveyEstimate([rating, one, many, why])).toBe("4 questions · about 2 minutes");
	});

	it("picks the one shortcut mode that reads right for every question", () => {
		expect(surveyShortcuts([one, many])).toBe("letters");
		expect(surveyShortcuts([one, rating])).toBe("numbers");
		expect(surveyShortcuts([one, rating, nps])).toBeUndefined();
	});

	it("reads each answered question from the form in the field its type expects", () => {
		const data = new FormData();
		data.append("rating", "4");
		data.append("one", "Something else");
		data.append("many", "Y");
		data.append("many", "X");
		data.append("why", " Fast\r\nand clear ");
		expect(answersFromFormData([rating, one, many, why], data)).toStrictEqual([
			{ questionId: "rating", rating: 4 },
			{ questionId: "one", choices: ["Something else"] },
			{ questionId: "many", choices: ["Y", "X"] },
			{ questionId: "why", text: "Fast\nand clear" },
		]);
		const blank = new FormData();
		blank.append("why", "   ");
		expect(answersFromFormData([rating, why], blank)).toStrictEqual([]);
	});

	it("tells a written-in answer from a chosen option", () => {
		expect(otherAnswerOf(one, ["A"])).toBeUndefined();
		expect(otherAnswerOf(one, ["A", "Something else"])).toBe("Something else");
		expect(otherAnswerOf(many, ["Something else"])).toBeUndefined();
	});

	it("reads an answer back as text", () => {
		expect(formatAnswer({ questionId: "q", rating: 5 })).toBe("5");
		expect(formatAnswer({ questionId: "q", choices: ["A", "B"] })).toBe("A, B");
		expect(formatAnswer({ questionId: "q", text: "Hi" })).toBe("Hi");
	});
});
