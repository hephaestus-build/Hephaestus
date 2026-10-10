import assert from "node:assert/strict";
import { test } from "node:test";

import { optionalNumber, optionalString } from "./json.ts";

void test("an empty string or a value of another type holds no text", () => {
	assert.deepEqual(
		["main", "", 3, null, undefined].map((value) => optionalString(value)),
		["main", undefined, undefined, undefined, undefined],
	);
});

void test("only a finite number is a number", () => {
	assert.deepEqual(
		[0, 2.5, Number.NaN, Number.POSITIVE_INFINITY, "3"].map((value) => optionalNumber(value)),
		[0, 2.5, undefined, undefined, undefined],
	);
});
