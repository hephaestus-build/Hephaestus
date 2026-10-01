import { describe, expect, it } from "vitest";

import { parseGate } from "./practice-precondition";

describe("parseGate", () => {
	it("keeps a complete gate", () => {
		const gate = {
			skipReason: "the change has no Swift code",
			anyOf: [{ changedPathMatches: ["**/*.swift"] }],
		};
		expect(parseGate(JSON.stringify(gate))).toStrictEqual({ value: gate });
	});

	it("rejects malformed and unknown fields instead of discarding them", () => {
		expect(parseGate("{").error).toBeDefined();
		expect(
			parseGate(
				JSON.stringify({
					skipReason: "none",
					anyOf: [{ changedPathMatches: ["**/*.swift"], typo: true }],
				}),
			).error,
		).toBeDefined();
		expect(
			parseGate(JSON.stringify({ skipReason: "none", anyOf: [{ changedPathMatches: [" "] }] }))
				.error,
		).toBeDefined();
	});

	it("treats an empty field as an explicit clear", () => {
		expect(parseGate("")).toStrictEqual({});
	});
});
