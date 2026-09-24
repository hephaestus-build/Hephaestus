import { describe, expect, it } from "vitest";

import { OBSERVATION_OUTCOME_PRESENTATION, observationOutcome } from "./observation-outcome";

describe("observation outcome contract", () => {
	it("derives the four assessed cells and the status that stands in for a fifth", () => {
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "PRESENT", assessment: "GOOD" }),
		).toBe("PRESENT_GOOD");
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "BAD" }),
		).toBe("ABSENT_BAD");
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "PRESENT", assessment: "BAD" }),
		).toBe("PRESENT_BAD");
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "GOOD" }),
		).toBe("ABSENT_GOOD");
		expect(observationOutcome({ assessmentStatus: "NOT_APPLICABLE", presence: undefined })).toBe(
			"NOT_APPLICABLE",
		);
	});

	it("reads the absent cells against the behaviour's desirability", () => {
		// A desirable behaviour absent is the gap; an undesirable one absent is the risk avoided.
		expect(OBSERVATION_OUTCOME_PRESENTATION.ABSENT_GOOD.className).toBe("text-destructive");
		expect(OBSERVATION_OUTCOME_PRESENTATION.ABSENT_BAD.className).toBe("text-success");
	});

	it("keeps an undetermined verdict apart from work that offered no opportunity", () => {
		expect(observationOutcome({ assessmentStatus: "UNDETERMINED", presence: undefined })).toBe(
			"UNDETERMINED",
		);
		expect(OBSERVATION_OUTCOME_PRESENTATION.UNDETERMINED.label).not.toBe(
			OBSERVATION_OUTCOME_PRESENTATION.NOT_APPLICABLE.label,
		);
	});

	it("refuses an assessed observation missing either axis", () => {
		expect(() => observationOutcome({ assessmentStatus: "ASSESSED", presence: "PRESENT" })).toThrow(
			"Assessed observations require presence and assessment",
		);
	});

	it("presents every outcome the server can record", () => {
		expect(Object.keys(OBSERVATION_OUTCOME_PRESENTATION)).toStrictEqual([
			"PRESENT_GOOD",
			"ABSENT_BAD",
			"PRESENT_BAD",
			"ABSENT_GOOD",
			"NOT_APPLICABLE",
			"UNDETERMINED",
		]);
	});
});
