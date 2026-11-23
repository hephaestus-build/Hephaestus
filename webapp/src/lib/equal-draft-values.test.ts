import { expect, it } from "vitest";

import { equalDraftValues } from "./equal-draft-values";

it.each([
	{ left: { name: "Group", color: null }, right: { color: null, name: "Group" }, equal: true },
	{
		left: [{ slug: "first" }, { slug: "second" }],
		right: [{ slug: "second" }, { slug: "first" }],
		equal: false,
	},
	{ left: { criteria: [{ text: "A" }] }, right: { criteria: [{ text: "B" }] }, equal: false },
	{ left: { reads: ["a"] }, right: { reads: ["a", "b"] }, equal: false },
	{ left: { reads: [{ source: "a" }] }, right: { reads: [{ source: "a" }] }, equal: true },
	{ left: { scan: undefined }, right: { scan: undefined }, equal: true },
	{ left: { scan: undefined }, right: {}, equal: false },
	{ left: { scan: undefined }, right: { script: undefined }, equal: false },
	{ left: [null], right: { 0: null }, equal: false },
	{ left: Number.NaN, right: Number.NaN, equal: true },
])("compares $left and $right as $equal", ({ left, right, equal }) => {
	expect(equalDraftValues(left, right)).toBe(equal);
	expect(equalDraftValues(right, left)).toBe(equal);
});
