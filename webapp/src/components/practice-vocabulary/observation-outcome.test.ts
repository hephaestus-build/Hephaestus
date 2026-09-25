import { describe, expect, it } from "vitest";

import { observationOutcome } from "./observation-outcome";

describe("observation outcome contract", () => {
	it("derives the four assessed cells and the statuses that stand in for them", () => {
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
		expect(observationOutcome({ assessmentStatus: "UNDETERMINED", presence: undefined })).toBe(
			"UNDETERMINED",
		);
	});

	it("refuses an assessed observation missing either axis", () => {
		expect(() => observationOutcome({ assessmentStatus: "ASSESSED", presence: "PRESENT" })).toThrow(
			"Assessed observations require presence and assessment",
		);
	});
});
