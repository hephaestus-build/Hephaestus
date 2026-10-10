import { describe, expect, it } from "vitest";

import { periodFromSearch, periodQuery, periodSearch, previousPeriod } from "./activity-period";

describe("periodFromSearch", () => {
	it("reads a custom range only when both days are valid and in order", () => {
		expect(periodFromSearch({ range: "1y", from: "2026-03-01", to: "2026-03-31" })).toStrictEqual({
			kind: "custom",
			from: new Date(2026, 2, 1),
			to: new Date(2026, 2, 31),
		});
		expect(periodFromSearch({ range: "1y", from: "2026-03-31", to: "2026-03-01" })).toStrictEqual({
			kind: "preset",
			preset: "1y",
		});
		expect(periodFromSearch({ range: "30d", from: "2026-03-01" })).toStrictEqual({
			kind: "preset",
			preset: "30d",
		});
	});
});

describe("periodSearch", () => {
	it("writes a custom range as its days and clears the preset, and the other way round", () => {
		const custom = {
			kind: "custom",
			from: new Date(2026, 2, 1),
			to: new Date(2026, 2, 31),
		} as const;
		expect(periodSearch(custom)).toStrictEqual({
			range: undefined,
			from: "2026-03-01",
			to: "2026-03-31",
		});
		expect(periodSearch({ kind: "preset", preset: "all" })).toStrictEqual({
			range: "all",
			from: undefined,
			to: undefined,
		});
	});
});

describe("periodQuery", () => {
	it("leaves a preset to the server and ends a custom range at the night after its last day", () => {
		expect(periodQuery({ kind: "preset", preset: "90d" })).toStrictEqual({ range: "90d" });
		expect(
			periodQuery({ kind: "custom", from: new Date(2026, 2, 1), to: new Date(2026, 2, 31) }),
		).toStrictEqual({ range: "custom", from: new Date(2026, 2, 1), to: new Date(2026, 3, 1) });
	});
});

describe("previousPeriod", () => {
	const span = { from: new Date("2026-09-01T00:00:00Z"), to: new Date("2026-10-01T00:00:00Z") };

	it("reads the same length just before the span the server counted", () => {
		expect(previousPeriod({ kind: "preset", preset: "30d" }, span)).toStrictEqual({
			query: {
				range: "custom",
				from: new Date("2026-08-02T00:00:00Z"),
				to: new Date("2026-09-01T00:00:00Z"),
			},
			name: "the previous 30 days",
		});
	});

	it("names a custom range's days and has nothing before all of the history", () => {
		const custom = {
			kind: "custom",
			from: new Date(2026, 8, 1),
			to: new Date(2026, 8, 7),
		} as const;
		expect(previousPeriod(custom, span)?.name).toBe("the previous 7 days");
		expect(previousPeriod({ kind: "preset", preset: "all" }, span)).toBeUndefined();
	});
});
