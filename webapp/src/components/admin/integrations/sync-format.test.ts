import { describe, expect, it } from "vitest";

import { freshnessTone } from "./sync-format";

describe("freshnessTone", () => {
	const now = new Date("2026-08-07T12:00:00.000Z");
	const hourly = 3600;
	const ago = (seconds: number) => new Date(now.getTime() - seconds * 1000);

	it("calls a resource fresh for the whole gap between two runs", () => {
		// One cadence old is what every resource is right before its next scheduled run, so flagging
		// at 1x would tint the entire fleet on a working schedule.
		expect(freshnessTone(ago(hourly), hourly, now)).toBe("fresh");
		expect(freshnessTone(ago(2 * hourly), hourly, now)).toBe("fresh");
	});

	it("calls it stale once a run has actually been missed", () => {
		expect(freshnessTone(ago(2 * hourly + 1), hourly, now)).toBe("stale");
		expect(freshnessTone(ago(6 * hourly), hourly, now)).toBe("stale");
	});

	it("calls it very stale once it is long past explaining away", () => {
		expect(freshnessTone(ago(6 * hourly + 1), hourly, now)).toBe("veryStale");
	});

	it("says it has never run rather than guessing an age", () => {
		expect(freshnessTone(undefined, hourly, now)).toBe("never");
		expect(freshnessTone(null, hourly, now)).toBe("never");
		expect(freshnessTone("not a date", hourly, now)).toBe("never");
	});

	/**
	 * An age is only readable against the schedule behind it. With no cadence — including the zero a
	 * connection without one reports, which there is nothing to divide by — the honest answer is that
	 * we do not know, not that the resource is late.
	 */
	it("declines to judge freshness with no cadence to judge it against", () => {
		expect(freshnessTone(ago(60 * hourly), undefined, now)).toBe("unknown");
		expect(freshnessTone(ago(60 * hourly), null, now)).toBe("unknown");
		expect(freshnessTone(ago(60 * hourly), 0, now)).toBe("unknown");
		expect(freshnessTone(ago(60 * hourly), -1, now)).toBe("unknown");
	});

	it("reads an ISO string, which is what the SDK actually hands over", () => {
		expect(freshnessTone(ago(10 * hourly).toISOString(), hourly, now)).toBe("veryStale");
	});
});
