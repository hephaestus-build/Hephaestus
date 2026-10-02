import { describe, expect, it } from "vitest";

import { observationSeverity } from "./ReviewBadges";

describe("observation severity", () => {
	it.each(["MET", "NOT_APPLICABLE", "UNDETERMINED"] as const)(
		"does not show severity for %s",
		(outcome) => {
			expect(observationSeverity({ outcome, severity: "CRITICAL" })).toBeUndefined();
		},
	);
	it("shows the severity of a not-met observation", () => {
		expect(observationSeverity({ outcome: "NOT_MET", severity: "CRITICAL" })?.label).toBe(
			"Critical",
		);
	});
});
