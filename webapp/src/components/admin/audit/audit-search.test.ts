import { describe, expect, it } from "vitest";

import {
	auditSearchSchema,
	dayAfterInstant,
	dayStartInstant,
	fromDayParam,
	toDateRange,
} from "./audit-search";

describe("day bounds", () => {
	it("sends the next midnight as the exclusive upper bound", () => {
		const bound = dayAfterInstant(new Date(2026, 6, 15));
		expect(bound.getDate()).toBe(16);
		expect(bound.getHours()).toBe(0);
	});

	it("sends midnight of the picked day as the inclusive lower bound", () => {
		const bound = dayStartInstant(new Date(2026, 6, 15));
		expect(bound.getDate()).toBe(15);
		expect(bound.getHours()).toBe(0);
	});
});

describe("fromDayParam", () => {
	it.each(["2026-02-31", "2026-13-01", "not-a-date", "2026"])("rejects %s", (value) => {
		expect(fromDayParam(value)).toBeUndefined();
	});

	it("parses a well-formed day", () => {
		expect(fromDayParam("2026-07-15")?.getDate()).toBe(15);
	});
});

describe("toDateRange", () => {
	it("keeps an open-ended range", () => {
		expect(toDateRange({ from: "2026-07-01", to: undefined })?.to).toBeUndefined();
	});

	it("is undefined when neither bound is usable", () => {
		expect(toDateRange({ from: undefined, to: undefined })).toBeUndefined();
	});
});

describe("auditSearchSchema", () => {
	it("opens the log rather than erroring on a hand-typed or stale link", () => {
		expect(auditSearchSchema.parse({ tab: "nope", accountId: "abc", from: 5 })).toStrictEqual({
			tab: "signins",
			accountId: undefined,
			from: undefined,
		});
	});

	it("accepts a single value where the API accepts repeated ones", () => {
		expect(auditSearchSchema.parse({ action: "CREATED" }).action).toStrictEqual(["CREATED"]);
	});
});
