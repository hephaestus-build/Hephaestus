import { describe, expect, it } from "vitest";
import { OBSERVATION_KIND_PRESENTATION, observationKind } from "./observation-kind";

describe("observation kind contract", () => {
	it("derives the five matrix cells the server outcome vector counts", () => {
		expect(
			observationKind({ assessmentStatus: "ASSESSED", presence: "PRESENT", assessment: "GOOD" }),
		).toBe("PRESENT_GOOD");
		expect(
			observationKind({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "GOOD" }),
		).toBe("ABSENT_GOOD");
		expect(
			observationKind({ assessmentStatus: "ASSESSED", presence: "PRESENT", assessment: "BAD" }),
		).toBe("PRESENT_BAD");
		expect(
			observationKind({ assessmentStatus: "ASSESSED", presence: "ABSENT", assessment: "BAD" }),
		).toBe("ABSENT_BAD");
		expect(observationKind({ assessmentStatus: "NOT_APPLICABLE", presence: undefined })).toBe(
			"NOT_APPLICABLE",
		);
	});

	it("keeps an inconclusive verdict apart from work that offered no opportunity", () => {
		expect(observationKind({ assessmentStatus: "UNDETERMINED", presence: undefined })).toBe(
			"UNDETERMINED",
		);
		expect(OBSERVATION_KIND_PRESENTATION.UNDETERMINED.label).not.toBe(
			OBSERVATION_KIND_PRESENTATION.NOT_APPLICABLE.label,
		);
	});

	it("presents every outcome the server can record", () => {
		expect(Object.keys(OBSERVATION_KIND_PRESENTATION)).toStrictEqual([
			"PRESENT_GOOD",
			"ABSENT_BAD",
			"PRESENT_BAD",
			"ABSENT_GOOD",
			"NOT_APPLICABLE",
			"UNDETERMINED",
		]);
	});
});
