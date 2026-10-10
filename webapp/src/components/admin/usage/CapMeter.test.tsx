import { describe, expect, it } from "vitest";

import { capState } from "./CapMeter";

/**
 * The percentages are written out rather than derived from `BUDGET_WARN_PERCENT`: a row spelled
 * `BUDGET_WARN_PERCENT - 1` restates the implementation and stays green for any threshold at all.
 */
describe("capState", () => {
	it.each<[string, number | undefined, boolean, boolean, "PAUSED" | "NEAR" | null]>([
		["nothing to say well below the threshold", 24.8, false, true, null],
		["still nothing one point short of it", 79, false, true, null],
		["a warning exactly at the threshold", 80, false, true, "NEAR"],
		["a warning past it", 92, false, true, "NEAR"],
		["a pause, which outranks the warning", 92, true, true, "PAUSED"],
		["a pause even at zero spend, because a $0 cap pauses at once", 100, true, true, "PAUSED"],
		["nothing without a cap to compare against", undefined, false, true, null],
		// A closed month is measured against *today's* caps, so it can be over one with nothing paused.
		["nothing on a closed month, however far over", 140, false, false, null],
		["nothing on a closed month, even while paused today", 140, true, false, null],
	])("reports %s", (_name, percent, paused, isCurrentMonth, expected) => {
		expect(capState(percent, paused, isCurrentMonth)).toBe(expected);
	});
});
