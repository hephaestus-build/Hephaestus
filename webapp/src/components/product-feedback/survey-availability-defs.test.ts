import { describe, expect, it } from "vitest";

import { surveyAvailability } from "./survey-availability-defs";

const now = Date.parse("2026-09-11T12:00:00Z");
const hour = 3_600_000;

describe("surveyAvailability", () => {
	it("derives the availability from the schedule and the pause flag", () => {
		expect(surveyAvailability({ active: true, startsAt: new Date(now - hour) }, now)).toBe("OPEN");
		expect(surveyAvailability({ active: true, startsAt: new Date(now + hour) }, now)).toBe(
			"SCHEDULED",
		);
		expect(surveyAvailability({ active: false, startsAt: new Date(now - hour) }, now)).toBe(
			"PAUSED",
		);
		expect(
			surveyAvailability(
				{ active: true, startsAt: new Date(now - 2 * hour), endsAt: new Date(now - hour) },
				now,
			),
		).toBe("ENDED");
	});

	it("lets an ended window win over a pause, and a pause over a future start", () => {
		expect(
			surveyAvailability(
				{ active: false, startsAt: new Date(now - 2 * hour), endsAt: new Date(now) },
				now,
			),
		).toBe("ENDED");
		expect(surveyAvailability({ active: false, startsAt: new Date(now + hour) }, now)).toBe(
			"PAUSED",
		);
	});
});
