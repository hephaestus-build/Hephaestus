import { describe, expect, it } from "vitest";

import { type PurposeStatusInput, purposeStatus } from "./purpose-status-defs";

const ready = { enabled: true, ready: true };

const base: PurposeStatusInput = {
	bindings: [],
	served: [false, false],
	off: false,
	requiredNeedUnmet: false,
	used: true,
};

describe("purposeStatus", () => {
	it("says nothing about a precompute model that no practice uses, whatever it assigns", () => {
		expect(purposeStatus({ ...base, used: false })).toBeNull();
		// An assignment that serves nobody is no gap: the row's cells show it as not used.
		expect(
			purposeStatus({ ...base, used: false, bindings: [ready], served: [false, false] }),
		).toBeNull();
		expect(
			purposeStatus({ ...base, used: false, bindings: [{ enabled: true, ready: false }] }),
		).toBeNull();
	});

	it("draws nothing once every member is served, and says how many are not otherwise", () => {
		expect(purposeStatus(base)).toBe("NOT_SET");
		expect(purposeStatus({ ...base, bindings: [ready], served: [true, true] })).toBeNull();
		expect(purposeStatus({ ...base, bindings: [ready], served: [true, true, false] })).toBe(
			"PARTLY_SET",
		);
	});

	it("ranks off over attention, and attention over coverage", () => {
		const broken = { enabled: true, ready: false };
		expect(purposeStatus({ ...base, bindings: [ready, broken], served: [true, true] })).toBe(
			"NEEDS_ATTENTION",
		);
		expect(
			purposeStatus({ ...base, bindings: [ready], served: [true, true], requiredNeedUnmet: true }),
		).toBe("NEEDS_ATTENTION");
		expect(purposeStatus({ ...base, off: true, requiredNeedUnmet: true })).toBe("OFF");
		// A model switched off on purpose is a choice, not a fault.
		expect(purposeStatus({ ...base, bindings: [{ enabled: false, ready: false }] })).toBe(
			"NOT_SET",
		);
	});
});
