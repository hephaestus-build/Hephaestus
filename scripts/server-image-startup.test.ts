import assert from "node:assert/strict";
import { test } from "node:test";

import { READY_RATIO_LIMIT, readyRatio } from "./server-image-startup.ts";

void test("a cold first start of the image does not fail the startup ratio", () => {
	assert.ok(readyRatio([64.5, 45.9], [45.3, 46.1]) <= READY_RATIO_LIMIT);
});

void test("an image that starts 30 % slower than the base on the same runner fails the startup ratio", () => {
	assert.ok(readyRatio([31.2, 30.1], [23.1, 23.6]) > READY_RATIO_LIMIT);
});

void test("the startup ratio needs a start of each image", () => {
	assert.throws(() => readyRatio([], [23.1]), /needs a start of each image/u);
});
