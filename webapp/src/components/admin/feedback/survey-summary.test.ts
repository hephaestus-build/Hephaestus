import { describe, expect, it } from "vitest";

import { completionRate, distributionRows, npsBuckets, percentOf, shareOf } from "./survey-summary";

const counts = (values: number[]) =>
	values.map((count, value) => ({ value: String(value), count }));

describe("npsBuckets", () => {
	it("cuts the scale at 6/7 and 8/9", () => {
		expect(npsBuckets(counts([1, 0, 0, 1, 0, 1, 2, 2, 3, 3, 2]))).toStrictEqual({
			promoters: 5,
			passives: 5,
			detractors: 5,
			total: 15,
		});
	});

	it("ignores values outside the scale rather than counting them somewhere", () => {
		expect(
			npsBuckets([
				{ value: "11", count: 4 },
				{ value: "yes", count: 4 },
				{ value: "10", count: 1 },
			]),
		).toStrictEqual({ promoters: 1, passives: 0, detractors: 0, total: 1 });
	});
});

describe("completionRate", () => {
	it("is a whole percent of those invited", () => {
		expect(completionRate({ invited: 42, responded: 17, declined: 4 })).toBe("40%");
	});

	it("is a dash, not zero, before anyone was invited", () => {
		expect(completionRate({ invited: 0, responded: 0, declined: 0 })).toBe("—");
		expect(percentOf(0, 0)).toBeUndefined();
	});
});

describe("distributionRows", () => {
	it("gives each row its share of the answers and a width relative to the largest", () => {
		expect(distributionRows(counts([1, 3, 0]), 4)).toStrictEqual([
			{ value: "0", count: 1, percent: "25%", width: 100 / 3 },
			{ value: "1", count: 3, percent: "75%", width: 100 },
			{ value: "2", count: 0, percent: "0%", width: 0 },
		]);
	});

	it("shares the answers with those who typed their own, so the options no longer sum to 100%", () => {
		// 3 of the 8 who answered wrote something in; the options split the same 8, not just 5.
		const other = 3;
		expect(distributionRows(counts([4, 1]), 5 + other)).toStrictEqual([
			{ value: "0", count: 4, percent: "50%", width: 100 },
			{ value: "1", count: 1, percent: "13%", width: 25 },
		]);
		expect(shareOf(other, 5 + other)).toBe("38%");
	});

	it("draws nothing when nobody answered", () => {
		expect(distributionRows(counts([0, 0]), 0)).toStrictEqual([
			{ value: "0", count: 0, percent: "0%", width: 0 },
			{ value: "1", count: 0, percent: "0%", width: 0 },
		]);
	});
});
