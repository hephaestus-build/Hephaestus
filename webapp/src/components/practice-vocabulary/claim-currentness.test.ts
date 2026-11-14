import { describe, expect, it } from "vitest";

import { claimCurrentnessNote, currentnessCopy } from "./claim-currentness";

// A local wall-clock time, so the copy reads the same in every time zone.
const CAPTURED_AT = new Date(2026, 10, 3, 14, 5);

describe("currentnessCopy", () => {
	it("says nothing about a current observation", () => {
		expect(
			currentnessCopy({
				currentness: "CURRENT",
				unverifiableReason: null,
				capturedAt: CAPTURED_AT,
			}),
		).toBeUndefined();
	});

	it("says that the record is missing, with no time, when the record is missing", () => {
		const copy = currentnessCopy({
			currentness: "UNVERIFIABLE",
			unverifiableReason: "RECORD_MISSING",
			capturedAt: null,
		});

		expect(copy?.badge).toBe("Unverified observation");
		expect(copy?.note).toBe("We cannot verify what this observation was based on.");
		expect(copy?.description).toMatch(
			/^The record of what this observation was based on is missing/u,
		);
	});

	it("says that the work could not be read, and the time of the work it is based on, when the work is unreadable", () => {
		const copy = currentnessCopy({
			currentness: "UNVERIFIABLE",
			unverifiableReason: "WORK_UNREADABLE",
			capturedAt: CAPTURED_AT,
		});

		expect(copy?.badge).toBe("Cannot verify now");
		expect(copy?.note).toBe(
			"We could not read the current work to check this observation. It is based on the work as of 3 November 2026, 2:05 pm.",
		);
		expect(copy?.description).toContain("It is not shown as current until we can check it again.");
		expect(copy?.description).not.toContain("missing");
	});

	it("omits the time when an unreadable observation has none", () => {
		expect(
			claimCurrentnessNote({
				currentness: "UNVERIFIABLE",
				unverifiableReason: "WORK_UNREADABLE",
				capturedAt: null,
			}),
		).toBe("We could not read the current work to check this observation.");
	});

	it("claims no reason when an unverifiable observation names none", () => {
		expect(
			claimCurrentnessNote({
				currentness: "UNVERIFIABLE",
				unverifiableReason: null,
				capturedAt: null,
			}),
		).toBe("We cannot check this observation against the current work.");
	});
});
