import { describe, expect, it } from "vitest";

import {
	multiValue,
	narrowToEnum,
	nonEmpty,
	pageParam,
	parseSearch,
	stringifySearch,
} from "./search-params";

describe("multiValue", () => {
	it("normalizes one query parameter to the repeated-parameter shape", () => {
		expect(multiValue.parse("CREATED")).toStrictEqual(["CREATED"]);
	});

	it("deduplicates repeated values", () => {
		expect(multiValue.parse(["CREATED", "CREATED", "UPDATED"])).toStrictEqual([
			"CREATED",
			"UPDATED",
		]);
	});

	it("drops invalid input", () => {
		expect(multiValue.parse(42)).toBeUndefined();
	});
});

describe("nonEmpty", () => {
	it("omits an empty selection", () => {
		expect(nonEmpty([])).toBeUndefined();
	});

	it("keeps selected values", () => {
		expect(nonEmpty(["CREATED"])).toStrictEqual(["CREATED"]);
	});
});

describe("pageParam", () => {
	it("keeps page one out of the URL", () => {
		expect(pageParam(0)).toBeUndefined();
		expect(pageParam(undefined)).toBeUndefined();
	});

	it("carries every later page", () => {
		expect(pageParam(1)).toBe(1);
		expect(pageParam(12)).toBe(12);
	});
});

describe("narrowToEnum", () => {
	const allowed = ["CREATED", "UPDATED"] as const;

	it("drops values the API would reject", () => {
		expect(narrowToEnum(["CREATED", "RETIRED"], allowed)).toStrictEqual(["CREATED"]);
	});

	it("omits a selection when every value is unknown", () => {
		expect(narrowToEnum(["RETIRED"], allowed)).toBeUndefined();
	});

	it("omits an empty selection", () => {
		expect(narrowToEnum([], allowed)).toBeUndefined();
	});
});

describe("search serialisation", () => {
	it("writes a list as repeated keys with readable paths", () => {
		expect(stringifySearch({ repo: ["acme/api", "acme/web"], detail: ["person:ada"] })).toBe(
			"?repo=acme/api&repo=acme/web&detail=person:ada",
		);
	});

	it("reads back every value with its type", () => {
		const search = {
			range: "1y",
			page: 2,
			hidden: true,
			team: "2024",
			repo: ["acme/api", "7", "true"],
			only: ["acme/api"],
			view: { open: true },
		};
		expect(parseSearch(stringifySearch(search))).toStrictEqual({ ...search, only: "acme/api" });
	});

	it("writes nothing for an empty search", () => {
		expect(stringifySearch({ repo: [], team: undefined })).toBe("");
	});

	it("keeps characters that would end a value encoded", () => {
		expect(parseSearch(stringifySearch({ q: "a&b=c+d#e" }))).toStrictEqual({ q: "a&b=c+d#e" });
	});
});
