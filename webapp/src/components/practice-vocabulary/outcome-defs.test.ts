import { describe, expect, it } from "vitest";
import { observationResult } from "./observation-result";
import { derivedOutcome } from "./outcome-defs";

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
			expect(observationResult({ assessmentStatus }).label).not.toMatch(/Positive|Negative/);
		},
	);
});
