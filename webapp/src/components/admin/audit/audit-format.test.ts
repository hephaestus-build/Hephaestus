import { describe, expect, it } from "vitest";

import { EVENT_TYPE_LABELS, eventLabel, eventSeverity } from "./audit-format";

describe("audit events", () => {
	it.each([
		["RESEARCH_DATASET_RELEASED", "Research dataset released"],
		["RESEARCH_REMOVAL_RECORDED", "Research removal recorded"],
		["INSTALLATION_EVENT_REPLAYED", "Installation event replayed"],
	] as const)("names %s in words", (eventType, label) => {
		expect(EVENT_TYPE_LABELS[eventType]).toBe(label);
		expect(eventLabel(eventType)).toBe(label);
	});

	it.each(["RESEARCH_DATASET_RELEASED", "RESEARCH_REMOVAL_RECORDED"])(
		"marks a successful %s as a warning, because personal data or an attestation left the instance",
		(eventType) => {
			expect(eventSeverity(eventType, "SUCCESS")).toBe("warning");
			expect(eventSeverity(eventType, "FAILURE")).toBe("error");
		},
	);

	it("keeps a routine event informational", () => {
		expect(eventSeverity("LOGIN", "SUCCESS")).toBe("info");
	});
});
