import { describe, expect, it } from "vitest";

import { productSurveyAvailability } from "./product-survey-status";

describe("survey availability", () => {
	const survey = { active: true, startsAt: new Date(1000), endsAt: new Date(2000) };
	it("does not label a scheduled survey as accepting responses", () => {
		expect(productSurveyAvailability(survey, 999)).toBe("Scheduled");
	});
	it("accepts responses at the inclusive start", () => {
		expect(productSurveyAvailability(survey, 1000)).toBe("Accepting responses");
	});
	it("ends at the exclusive end", () => {
		expect(productSurveyAvailability(survey, 2000)).toBe("Ended");
	});
	it("keeps a paused survey paused during its schedule", () => {
		expect(productSurveyAvailability({ ...survey, active: false }, 1500)).toBe("Paused");
	});
});
