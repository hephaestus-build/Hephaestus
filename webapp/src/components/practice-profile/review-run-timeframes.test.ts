import { describe, expect, it } from "vitest";

import { timeframeSince } from "./review-run-timeframes";

/** A Tuesday afternoon, so the bound has a time of day to drop. */
const NOW = new Date(2026, 8, 22, 15, 42, 7).getTime();

describe("timeframeSince", () => {
	it("reaches back over every run when no timeframe is chosen", () => {
		expect(timeframeSince(undefined, NOW)).toBeUndefined();
	});

	it.each([
		["7d", new Date(2026, 8, 15)],
		["30d", new Date(2026, 7, 23)],
		["90d", new Date(2026, 5, 24)],
	] as const)("counts %s back in whole days from the reader's own midnight", (timeframe, since) => {
		expect(timeframeSince(timeframe, NOW)).toStrictEqual(since);
	});

	it("gives the same bound all day, so the query key it becomes is stable", () => {
		const morning = new Date(2026, 8, 22, 0, 0, 1).getTime();
		const night = new Date(2026, 8, 22, 23, 59, 59).getTime();
		expect(timeframeSince("7d", morning)).toStrictEqual(timeframeSince("7d", night));
	});
});
