import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { rememberTourOffered, tourOffered, tourRecordKey } from "./tour-start";

describe("the first visit's offer record", () => {
	// The test setup remembers the offer for the signed-in test account; these cases start empty.
	beforeEach(() => localStorage.clear());
	afterEach(() => {
		vi.restoreAllMocks();
		localStorage.clear();
	});

	it("belongs to one account: another account on the same browser is still owed its offer", () => {
		expect(tourOffered("5")).toBe(false);
		rememberTourOffered("5");
		expect(tourOffered("5")).toBe(true);
		expect(tourOffered("7")).toBe(false);
		expect(localStorage.getItem(tourRecordKey("offered", "5"))).toBe("1");
	});

	it("ignores a record from before records named their account", () => {
		localStorage.setItem("hephaestus:guided-tour:practice-profile:offered", "1");
		expect(tourOffered("5")).toBe(false);
	});

	it("counts as offered when the account is unknown or storage cannot be read, so it never opens on every visit", () => {
		expect(tourOffered(undefined)).toBe(true);
		rememberTourOffered(undefined);
		expect(localStorage).toHaveLength(0);

		vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
			throw new DOMException("Blocked", "SecurityError");
		});
		expect(tourOffered("5")).toBe(true);
	});

	it("survives storage that refuses to save", () => {
		vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
			throw new DOMException("Full", "QuotaExceededError");
		});
		expect(() => rememberTourOffered("5")).not.toThrow();
	});
});
