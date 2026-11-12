import { describe, expect, it } from "vitest";

import { type PurposeStatusInput, purposeStatus } from "./purpose-status-defs";

const ready = { enabled: true, ready: true };

const base: PurposeStatusInput = {
	bindings: [],
	served: [false, false],
	off: false,
};

describe("purposeStatus", () => {
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
		expect(purposeStatus({ ...base, off: true, bindings: [ready, broken] })).toBe("OFF");
		expect(purposeStatus({ ...base, bindings: [{ enabled: false, ready: false }] })).toBe(
			"NOT_SET",
		);
	});
});
