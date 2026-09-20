import { describe, expect, it } from "vitest";

import { asDate, type Wire } from "./dates";

describe("asDate", () => {
	// The instant, not merely the type: `toBeInstanceOf(Date)` is satisfied by `new Date(0)`.
	it("parses an ISO string, which is how a hand-parsed payload still arrives", () => {
		expect(asDate("2026-07-24T10:30:00.000Z")?.toISOString()).toBe("2026-07-24T10:30:00.000Z");
	});

	it("passes a real Date through untouched, which is what the SDK now returns", () => {
		const date = new Date("2026-07-24T10:30:00.000Z");
		expect(asDate(date)).toBe(date);
	});

	it.each([
		["null", null],
		["undefined", undefined],
		["an empty string", ""],
		["a non-date string", "not a date"],
		["an Invalid Date", new Date("nonsense")],
	])("degrades %s to undefined rather than to a fabricated now", (_name, value) => {
		expect(asDate(value)).toBeUndefined();
	});
});

/**
 * `Wire` earns its keep only if it *rejects* the shape it exists to prevent, so the assertions here
 * are the `@ts-expect-error`s: each one fails the typecheck the moment `Wire` stops turning a `Date`
 * into a string. That is the regression that would let an MSW fixture carry a `Date` into a payload
 * that is about to be serialised, hiding a field whose real wire spelling nobody ever checked.
 */
describe("Wire", () => {
	interface View {
		id: string;
		createdAt: Date;
		nested: { at: Date }[];
	}

	it("accepts the wire shape, checked against the generated view", () => {
		const view: Wire<View> = {
			id: "a",
			createdAt: "2026-07-24T10:30:00.000Z",
			nested: [{ at: "2026-07-24T10:30:00.000Z" }],
		};

		// Not `toBeInstanceOf(Date)`: the value really is a string, which is the point.
		expect(asDate(view.createdAt)?.toISOString()).toBe("2026-07-24T10:30:00.000Z");
	});

	it("rejects a Date at the top level", () => {
		// @ts-expect-error a real Date is exactly what `Wire` exists to keep out of an MSW fixture
		const view: Wire<View> = { id: "a", createdAt: new Date(0), nested: [] };

		expect(view.id).toBe("a");
	});

	it("rejects a Date nested inside an array, where the recursion could quietly stop", () => {
		// @ts-expect-error `Wire` must recurse through arrays, not just top-level members
		const view: Wire<View> = { id: "a", createdAt: "x", nested: [{ at: new Date(0) }] };

		expect(view.id).toBe("a");
	});

	it("leaves a non-Date member alone", () => {
		const id: Wire<View>["id"] = "a";

		expect(id).toBe("a");
	});
});
