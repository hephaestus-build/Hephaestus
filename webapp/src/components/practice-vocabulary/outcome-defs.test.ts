import { describe, expect, it } from "vitest";
import { observationResult } from "./observation-result";
import { OUTCOME_DEFS, outcomeCountNoun } from "./outcome-defs";

describe("observation outcomes", () => {
	it.each(["MET", "NOT_MET", "NOT_APPLICABLE", "UNDETERMINED"] as const)(
		"names %s consistently on rows and counts",
		(outcome) => {
			expect(observationResult({ outcome })).toStrictEqual(OUTCOME_DEFS[outcome]);
			expect(outcomeCountNoun(outcome, 1)).toBe(
				`${OUTCOME_DEFS[outcome].label.toLowerCase()} observation`,
			);
			expect(outcomeCountNoun(outcome, 0)).toBe(
				`${OUTCOME_DEFS[outcome].label.toLowerCase()} observations`,
			);
		},
	);
});
