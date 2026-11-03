import assert from "node:assert/strict";
import { test } from "node:test";

import { foldedReviewOptions, oneLine } from "./bounded-text.ts";
import { DIRECTION_CHARS, FACT_CHARS } from "./contract.ts";

void test("text already within the bounds reaches the review byte for byte", () => {
	const options = {
		directions: ["Read  the two  files."],
		facts: { spacing: "a  b", count: 2 },
	};
	assert.deepEqual(foldedReviewOptions(options), options);
});

void test("long or multi-line text is folded to one line, and an empty fact is no fact", () => {
	const folded = foldedReviewOptions({
		directions: ["first\nsecond", "x".repeat(DIRECTION_CHARS + 5)],
		facts: { lines: "a\n b", empty: " ", long: "y".repeat(FACT_CHARS + 1) },
	});
	assert.deepEqual(folded, {
		directions: ["first second", `${"x".repeat(DIRECTION_CHARS - 1)}…`],
		facts: { lines: "a b", long: `${"y".repeat(FACT_CHARS - 1)}…` },
	});
});

void test("a key that is not a fact name is camelCased, unless that name is taken", () => {
	assert.deepEqual(foldedReviewOptions({ facts: { "objective-c": 1 } })?.facts, { objectiveC: 1 });
	assert.deepEqual(foldedReviewOptions({ facts: { "objective-c": 1, objectiveC: 2 } })?.facts, {
		"objective-c": 1,
		objectiveC: 2,
	});
});

void test("a cut does not split a surrogate pair", () => {
	const cut = oneLine(`${"a".repeat(8)}😀😀`, 10);
	assert.equal(cut, `${"a".repeat(8)}…`);
	assert.ok(cut.isWellFormed());
});
