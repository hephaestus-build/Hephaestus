import { describe, expect, it } from "vitest";
import { observationResult } from "./observation-result";
import {
	derivedOutcome,
	OUTCOME_COUNT_NOUNS,
	OUTCOME_DEFS,
	outcomeCountNoun,
} from "./outcome-defs";

describe("outcome count nouns", () => {
	it.each([
		["POSITIVE", 1, "positive outcome"],
		["POSITIVE", 0, "positive outcomes"],
		["NEGATIVE", 1, "negative outcome"],
		["NEGATIVE", 27, "negative outcomes"],
	] as const)("names %s × %i as %s", (outcome, count, noun) => {
		expect(outcomeCountNoun(outcome, count)).toBe(noun);
	});

	it("starts every noun with its outcome's label, so a count and a badge read as one word", () => {
		for (const outcome of ["POSITIVE", "NEGATIVE"] as const) {
			expect(OUTCOME_COUNT_NOUNS[outcome].one).toBe(OUTCOME_DEFS[outcome].label.toLowerCase());
		}
	});
});

describe("derived observation outcomes", () => {
	it.each([
		["PRESENT", "GOOD", "POSITIVE", "Positive outcome"],
		["ABSENT", "GOOD", "NEGATIVE", "Negative outcome"],
		["PRESENT", "BAD", "NEGATIVE", "Negative outcome"],
		["ABSENT", "BAD", "POSITIVE", "Positive outcome"],
	] as const)("%s / %s gives %s", (presence, assessment, outcome, label) => {
		expect(derivedOutcome(presence, assessment)).toBe(outcome);
		expect(observationResult({ assessmentStatus: "ASSESSED", presence, assessment }).label).toBe(
			label,
		);
	});
	it.each(["NOT_APPLICABLE", "UNDETERMINED"] as const)(
		"%s is not a positive or negative outcome",
		(assessmentStatus) => {
			expect(observationResult({ assessmentStatus }).label).not.toMatch(/Positive|Negative/u);
		},
	);
});
