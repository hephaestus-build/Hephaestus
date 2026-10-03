import { afterEach, describe, expect, it, vi } from "vitest";

import {
	asDate,
	formatCalendarDate,
	formatDayRange,
	formatDayTime,
	formatWeekdayDay,
	type Wire,
} from "./dates";

describe("asDate", () => {
	// The instant, not merely the type: `toBeInstanceOf(Date)` is satisfied by `new Date(0)`.
	it("parses an ISO string, which is how a hand-parsed payload still arrives", () => {
		expect(asDate("2026-07-24T10:30:00.000Z")?.toISOString()).toBe("2026-07-24T10:30:00.000Z");
	});

	it("passes a real Date through untouched, as the generated client returns it", () => {
		const date = new Date("2026-07-24T10:30:00.000Z");
		expect(asDate(date)).toBe(date);
	});

	it.each([
		["null", null],
		["undefined", undefined],
		["an empty string", ""],
		["a non-date string", "not a date"],
		["an Invalid Date", new Date("nonsense")],
	])("degrades %s to undefined rather than to a fabricated now", (_name, value) => {
		expect(asDate(value)).toBeUndefined();
	});
});

/**
 * `Wire` earns its keep only if it *rejects* the shape it exists to prevent, so the assertions here
 * are the `@ts-expect-error`s: each one fails the typecheck the moment `Wire` stops turning a `Date`
 * into a string. That is the regression that would let an MSW fixture carry a `Date` into a payload
 * that is about to be serialised, hiding a field whose real wire spelling nobody ever checked.
 */
describe("Wire", () => {
	interface View {
		id: string;
		createdAt: Date;
		nested: { at: Date }[];
	}

	it("accepts the wire shape, checked against the generated view", () => {
		const view: Wire<View> = {
			id: "a",
			createdAt: "2026-07-24T10:30:00.000Z",
			nested: [{ at: "2026-07-24T10:30:00.000Z" }],
		};

		// Not `toBeInstanceOf(Date)`: the value really is a string, which is the point.
		expect(asDate(view.createdAt)?.toISOString()).toBe("2026-07-24T10:30:00.000Z");
	});

	it("rejects a Date at the top level", () => {
		// @ts-expect-error a real Date is exactly what `Wire` exists to keep out of an MSW fixture
		const view: Wire<View> = { id: "a", createdAt: new Date(0), nested: [] };

		expect(view.id).toBe("a");
	});

	it("rejects a Date nested inside an array, where the recursion could quietly stop", () => {
		// @ts-expect-error `Wire` must recurse through arrays, not just top-level members
		const view: Wire<View> = { id: "a", createdAt: "x", nested: [{ at: new Date(0) }] };

		expect(view.id).toBe("a");
	});

	it("leaves a non-Date member alone", () => {
		const id: Wire<View>["id"] = "a";

		expect(id).toBe("a");
	});
});

describe("formatWeekdayDay", () => {
	const today = new Date(2026, 8, 27, 15, 0);

	it("names the weekday and leaves out the current year", () => {
		expect(formatWeekdayDay(new Date(2026, 8, 21, 9, 30), today)).toBe("Monday, 21 September");
	});

	it("writes the year of a day in another one", () => {
		expect(formatWeekdayDay(new Date(2025, 11, 31, 9, 30), today)).toBe(
			"Wednesday, 31 December 2025",
		);
	});
});

describe("formatDayTime", () => {
	const today = new Date(2026, 8, 27, 15, 0);

	it("leaves out the current year", () => {
		expect(formatDayTime(new Date(2026, 8, 9, 14, 10), today)).toBe("9 September, 2:10 pm");
	});

	it("writes the year of a moment in another one", () => {
		expect(formatDayTime(new Date(2027, 0, 4, 9, 5), today)).toBe("4 January 2027, 9:05 am");
	});
});

describe("formatDayRange", () => {
	it.each([
		[new Date(2026, 8, 27), new Date(2026, 8, 27), "27 September 2026"],
		[new Date(2026, 8, 21), new Date(2026, 8, 27), "21–27 September 2026"],
		[new Date(2026, 7, 29), new Date(2026, 8, 27), "29 August – 27 September 2026"],
		[new Date(2025, 8, 28), new Date(2026, 8, 27), "28 September 2025 – 27 September 2026"],
	])("writes %s to %s once per shared month and year", (from, to, expected) => {
		expect(formatDayRange(from, to)).toBe(expected);
	});
});

describe("formatCalendarDate", () => {
	afterEach(() => {
		vi.unstubAllEnvs();
	});

	it("writes the wire's day west of UTC, where its midnight is still the day before", () => {
		vi.stubEnv("TZ", "America/Los_Angeles");
		const wireDate = new Date("2026-09-09T00:00:00.000Z");
		// Node rereads TZ when it is assigned; without that, this test would pass vacuously in UTC.
		expect(wireDate.getDate()).toBe(8);

		expect(formatCalendarDate(wireDate)).toBe("9 September 2026");
	});
});
