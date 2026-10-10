import { describe, expect, it } from "vitest";

import {
	averageFractionDigits,
	formatAverageUsd,
	formatCapUsd,
	formatCostUsd,
	formatRateUsd,
} from "./money";

describe("formatCostUsd", () => {
	it("renders nothing spent in cents, so it lines up with the figures around it", () => {
		expect(formatCostUsd(0)).toBe("$0.00");
	});

	it("renders an amount too small for cents as <$0.01 rather than claiming $0.00", () => {
		expect(formatCostUsd(0.0004)).toBe("<$0.01");
		expect(formatCostUsd(0.004)).toBe("<$0.01");
	});

	it("renders everything else in cents", () => {
		expect(formatCostUsd(0.005)).toBe("$0.01");
		expect(formatCostUsd(0.85)).toBe("$0.85");
		expect(formatCostUsd(43)).toBe("$43.00");
	});

	it("renders an absent amount as an em dash", () => {
		expect(formatCostUsd(undefined)).toBe("—");
	});
});

describe("formatCapUsd", () => {
	it("drops the cents a round cap does not have, and keeps the ones it does", () => {
		expect(formatCapUsd(50)).toBe("$50");
		expect(formatCapUsd(0)).toBe("$0");
		expect(formatCapUsd(49.5)).toBe("$49.50");
	});

	it("renders no cap as an em dash", () => {
		expect(formatCapUsd(undefined)).toBe("—");
	});
});

describe("formatRateUsd", () => {
	it("keeps the digits the provider published rather than clamping to cents", () => {
		expect(formatRateUsd(0.075)).toBe("$0.075");
		expect(formatRateUsd(0.003)).toBe("$0.003");
		expect(formatCostUsd(0.003)).toBe("<$0.01");
	});

	it("never floors a rate to the sub-cent bound", () => {
		expect(formatRateUsd(0.0004)).toBe("$0.0004");
		expect(formatRateUsd(0.00004)).not.toContain("<");
	});

	it("still shows cents on a round rate", () => {
		expect(formatRateUsd(3)).toBe("$3.00");
		expect(formatRateUsd(0)).toBe("$0.00");
	});

	it("renders an absent rate as an em dash", () => {
		expect(formatRateUsd(undefined)).toBe("—");
	});
});

describe("averageFractionDigits", () => {
	it("gives a column of cents and nothing two decimals", () => {
		expect(averageFractionDigits([0.02, 0, 12.5, null])).toBe(2);
		expect(averageFractionDigits([])).toBe(2);
	});

	it("gives the whole column the decimals its smallest real cost needs, up to four", () => {
		expect(averageFractionDigits([0.02, 0.005])).toBe(3);
		expect(averageFractionDigits([0.02, 0.005, 0.00047, null])).toBe(4);
		expect(averageFractionDigits([0.0000123])).toBe(4);
	});
});

describe("formatAverageUsd", () => {
	it("prints every figure of a column with the column's decimals, so the points line up", () => {
		const digits = averageFractionDigits([0.02, 0.005, 0.0077]);
		expect([0.02, 0.005, 0.0077, 0].map((value) => formatAverageUsd(value, digits))).toStrictEqual([
			"$0.020",
			"$0.005",
			"$0.008",
			"$0.000",
		]);
		const finest = averageFractionDigits([0.02, 0.005, 0.00047]);
		expect([0.02, 0.005, 0.0077].map((value) => formatAverageUsd(value, finest))).toStrictEqual([
			"$0.0200",
			"$0.0050",
			"$0.0077",
		]);
	});

	it("keeps an average of a cent or more in cents, like every other figure", () => {
		expect(formatAverageUsd(0.0158, 2)).toBe("$0.02");
		expect(formatAverageUsd(12.5, 2)).toBe("$12.50");
	});

	it("never prints a real cost as nothing", () => {
		expect(formatAverageUsd(0.0000123, 4)).toBe("<$0.0001");
		expect(formatAverageUsd(0.00005, 4)).toBe("$0.0001");
		expect(formatAverageUsd(0, 4)).toBe("$0.0000");
	});
});
