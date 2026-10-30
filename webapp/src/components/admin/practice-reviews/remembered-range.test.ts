import { afterEach, describe, expect, it, vi } from "vitest";

import { rangeToRestore, rememberedRange, rememberRange } from "./remembered-range";

describe("the overview's remembered range", () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it("restores the range chosen in this tab when the address names none", () => {
		rememberRange("acme", "all");
		expect(rangeToRestore("acme", "", "30d")).toBe("all");
		// Another workspace keeps its own.
		expect(rangeToRestore("other", "", "30d")).toBeUndefined();
	});

	it("lets an address that names a range win", () => {
		rememberRange("acme", "all");
		expect(rangeToRestore("acme", "?range=90d", "90d")).toBeUndefined();
	});

	it("restores nothing when the remembered range is the one on screen", () => {
		rememberRange("acme", "30d");
		expect(rangeToRestore("acme", "", "30d")).toBeUndefined();
	});

	it("ignores what is no range", () => {
		sessionStorage.setItem("hephaestus.practice-reviews.range:acme", "forever");
		expect(rememberedRange("acme")).toBeUndefined();
	});

	it("forgets quietly where the browser refuses storage", () => {
		vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
			throw new DOMException("denied", "SecurityError");
		});
		vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
			throw new DOMException("denied", "SecurityError");
		});
		expect(() => rememberRange("acme", "all")).not.toThrow();
		expect(rangeToRestore("acme", "", "30d")).toBeUndefined();
	});
});
