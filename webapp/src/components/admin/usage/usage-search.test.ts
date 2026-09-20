import { afterEach, describe, expect, it, vi } from "vitest";

import { monthOf, usageSearchSchema } from "./usage-search";

function atMonth(iso: string) {
	vi.useFakeTimers();
	vi.setSystemTime(new Date(iso));
}

afterEach(() => {
	vi.useRealTimers();
});

describe("usageSearchSchema", () => {
	it("keeps a month the reader navigated to", () => {
		atMonth("2026-07-15T00:00:00.000Z");
		expect(usageSearchSchema.parse({ month: "2026-06" })).toStrictEqual({ month: "2026-06" });
	});

	// `toStrictEqual`, not `toEqual`: it separates an absent `month` from one present as `undefined`,
	// which is what the schema's `.catch()` produces.
	it("leaves a bare link meaning 'this month' rather than freezing one into it", () => {
		expect(usageSearchSchema.parse({})).toStrictEqual({});
	});

	it.each([
		["a malformed month", "june"],
		["a month number that does not exist", "2026-13"],
		["a month with no zero padding", "2026-7"],
		["a value of the wrong type", 202_607],
	])("opens the report on this month rather than erroring on %s", (_name, month) => {
		expect(usageSearchSchema.parse({ month })).toStrictEqual({ month: undefined });
	});

	it("clamps a future month to now, because there is no such thing as future spend", () => {
		atMonth("2026-07-15T00:00:00.000Z");
		expect(usageSearchSchema.parse({ month: "2027-01" })).toStrictEqual({ month: "2026-07" });
	});

	it("leaves the current month alone", () => {
		atMonth("2026-07-15T00:00:00.000Z");
		expect(usageSearchSchema.parse({ month: "2026-07" })).toStrictEqual({ month: "2026-07" });
	});
});

describe("monthOf", () => {
	it("reads the month out of the URL when it says one", () => {
		expect(monthOf({ month: "2026-03" })).toBe("2026-03");
	});

	it("falls back to the current UTC month when the URL says nothing", () => {
		// A local-time fallback would report August here.
		atMonth("2026-07-31T23:30:00.000Z");
		expect(monthOf({})).toBe("2026-07");
	});
});
