import { describe, expect, it } from "vitest";

import { observationSeverity } from "./ReviewBadges";

describe("observation severity", () => {
	it.each([
		["PRESENT", "GOOD", undefined],
		["ABSENT", "GOOD", "Critical"],
		["PRESENT", "BAD", "Critical"],
		["ABSENT", "BAD", undefined],
	] as const)(
		"shows severity only for negative outcomes: %s + %s",
		(presence, assessment, label) => {
			expect(
				observationSeverity({
					assessmentStatus: "ASSESSED",
					presence,
					assessment,
					severity: "CRITICAL",
				})?.label,
			).toBe(label);
		},
	);

	it.each(["NOT_APPLICABLE", "UNDETERMINED"] as const)(
		"does not show severity for %s",
		(assessmentStatus) => {
			expect(
				observationSeverity({
					assessmentStatus,
					presence: undefined,
					assessment: undefined,
					severity: undefined,
				}),
			).toBeUndefined();
		},
	);
});
