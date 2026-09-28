import { describe, expect, it } from "vitest";

import { peakIndex } from "./activity-chart";

describe("peakIndex", () => {
	it("marks the first bucket with the most, and none when nothing happened", () => {
		expect(peakIndex([1, 4, 2, 4])).toBe(1);
		expect(peakIndex([0, 0])).toBeUndefined();
		expect(peakIndex([])).toBeUndefined();
	});
});
