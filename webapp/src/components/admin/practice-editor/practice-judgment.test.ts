import { describe, expect, it } from "vitest";

import type { PracticeJudgment, PracticeRule } from "@/api/types.gen";
import { mockDescriptionJudgment, mockStartingJudgment } from "@/mocks/fixtures/practice";

import {
	judgmentProblems,
	judgmentText,
	questionKeyOf,
	ruleCondition,
	ruleIdOf,
	ruleResult,
} from "./practice-judgment";

function withRules(rules: PracticeJudgment["rules"]): PracticeJudgment {
	return { questions: mockDescriptionJudgment.questions, rules };
}

/** One rule of the description fixture by id. */
function rule(id: string): PracticeRule {
	const found = mockDescriptionJudgment.rules.find((candidate) => candidate.id === id);
	if (found === undefined) {
		throw new Error(`The fixture has no rule ${id}`);
	}
	return found;
}

const noWhat = rule("no-what");
const noWhy = rule("no-why");
const met = rule("met");

describe("judgmentProblems", () => {
	it("accepts the starting judgment and a written one", () => {
		expect(judgmentProblems(mockStartingJudgment)).toStrictEqual([]);
		expect(judgmentProblems(mockDescriptionJudgment)).toStrictEqual([]);
	});

	it("refuses a last rule with conditions, since some answers would decide nothing", () => {
		expect(judgmentProblems(withRules([noWhat, noWhy]))).toStrictEqual([
			"The last rule must have no conditions, so every combination of answers has an outcome.",
		]);
	});

	it("names a rule an earlier rule always shadows", () => {
		const catchAll = { ...noWhy, id: "anything", when: {} };
		expect(judgmentProblems(withRules([noWhat, catchAll, noWhy, met]))).toStrictEqual([
			"Rule 3 can never decide: an earlier rule always matches first. Remove it or move it up.",
			"Rule 4 can never decide: an earlier rule always matches first. Remove it or move it up.",
		]);
	});

	it("keeps severity exactly for Not met", () => {
		const { severity: _severity, ...withoutSeverity } = noWhat;
		expect(
			judgmentProblems(withRules([withoutSeverity, noWhy, { ...met, severity: "MINOR" }])),
		).toStrictEqual([
			"Rule 1 decides Not met and needs a severity.",
			"Rule 3 gives a severity, but only a Not met rule has one.",
		]);
	});

	it("asks for every part of a question and a reason of sentence length", () => {
		const judgment: PracticeJudgment = {
			questions: [
				...mockDescriptionJudgment.questions,
				{ key: "question", title: "Ends with a period.", question: "", yes: "", no: " " },
			],
			rules: [noWhat, noWhy, { ...met, reason: "Fine." }],
		};
		expect(judgmentProblems(judgment)).toStrictEqual([
			"Question 3: write the title as a statement without a final period.",
			"Question 3 needs the question text.",
			"Question 3 needs to say what a yes means.",
			"Question 3 needs to say what a no means.",
			"Rule 3 needs a reason of 10–200 characters: one sentence about the work.",
		]);
	});

	it("refuses a result label where people read the text, as the server does", () => {
		expect(
			judgmentProblems(withRules([noWhat, noWhy, { ...met, reason: "Every check is MET here." }])),
		).toStrictEqual([
			"The reason of rule 3 names the review result “MET”. The rules decide the result; say what the evidence shows in plain words.",
		]);
	});

	it("names a question no rule uses", () => {
		const judgment: PracticeJudgment = {
			questions: [
				...mockDescriptionJudgment.questions,
				{
					key: "has_tests",
					title: "Has tests",
					question: "Are there tests?",
					yes: "Yes.",
					no: "No.",
				},
			],
			rules: mockDescriptionJudgment.rules,
		};
		expect(judgmentProblems(judgment)).toStrictEqual([
			"Question 3 is not used by any rule. Use it in a rule or remove it.",
		]);
	});
});

describe("questionKeyOf", () => {
	it("turns a title into the words the reviewer answers under, never reusing a key", () => {
		expect(questionKeyOf("States why the change exists", new Set())).toBe(
			"states_why_the_change_exists",
		);
		expect(questionKeyOf("Says why", new Set(["says_why"]))).toBe("says_why_2");
		expect(questionKeyOf("2 reviewers approve", new Set())).toBe("reviewers_approve");
		expect(questionKeyOf("", new Set())).toBe("question");
	});
});

describe("ruleIdOf", () => {
	it("finds a free id", () => {
		expect(ruleIdOf(new Set(["met", "rule-3"]))).toBe("rule-4");
		expect(ruleIdOf(new Set(["rule-2", "met"]))).toBe("rule-3");
	});
});

describe("rule sentences", () => {
	it("reads a rule as its conditions and its result", () => {
		expect(ruleCondition(noWhat, mockDescriptionJudgment.questions)).toBe(
			"When “Says what changed” is no",
		);
		expect(ruleResult(noWhat)).toBe("Not met, Major");
		expect(ruleCondition(met, mockDescriptionJudgment.questions)).toBe("In every other case");
		expect(ruleResult(met)).toBe("Met");
	});

	it("writes the whole judgment as text to compare", () => {
		expect(judgmentText(mockDescriptionJudgment)).toBe(
			[
				"Questions",
				"1. Says what changed — Does the title or description say what the change does?",
				"2. Says why the change exists — Does the title or description say why the change is needed? A restated title does not count.",
				"",
				"Rules, first match decides",
				"When “Says what changed” is no: Not met, Major. The description does not say what the change does.",
				"When “Says why the change exists” is no: Not met, Minor. The description says what changed but not why.",
				"In every other case: Met. The description says what changed and why.",
			].join("\n"),
		);
	});
});
