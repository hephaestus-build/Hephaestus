import { describe, expect, it } from "vitest";
import { OBSERVATION_OUTCOME_PRESENTATION, observationOutcome } from "./observation-outcome";

describe("observation outcome contract", () => {
	it("derives the five matrix cells the server outcome vector counts", () => {
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "PRESENT", assessment: "GOOD" }),
		).toBe("PRESENT_GOOD");
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "GOOD" }),
		).toBe("ABSENT_GOOD");
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "PRESENT", assessment: "BAD" }),
		).toBe("PRESENT_BAD");
		expect(
			observationOutcome({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "BAD" }),
		).toBe("ABSENT_BAD");
		expect(observationOutcome({ assessmentStatus: "NOT_APPLICABLE", presence: undefined })).toBe(
			"NOT_APPLICABLE",
		);
	});

	it("keeps an inconclusive verdict apart from work that offered no opportunity", () => {
		expect(observationOutcome({ assessmentStatus: "UNDETERMINED", presence: undefined })).toBe(
			"UNDETERMINED",
		);
		expect(OBSERVATION_OUTCOME_PRESENTATION.UNDETERMINED.label).not.toBe(
			OBSERVATION_OUTCOME_PRESENTATION.NOT_APPLICABLE.label,
		);
	});

	it("presents every outcome the server can record", () => {
		expect(Object.keys(OBSERVATION_OUTCOME_PRESENTATION)).toStrictEqual([
			"PRESENT_GOOD",
			"ABSENT_GOOD",
			"PRESENT_BAD",
			"ABSENT_BAD",
			"NOT_APPLICABLE",
			"UNDETERMINED",
		]);
	});
});
