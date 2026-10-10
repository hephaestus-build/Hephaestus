import { describe, expect, it } from "vitest";

import { parseSearch, stringifySearch } from "./router-search";

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
