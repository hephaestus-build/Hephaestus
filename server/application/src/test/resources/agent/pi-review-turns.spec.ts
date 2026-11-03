import assert from "node:assert/strict";
import test from "node:test";

import {
	missingSlugs,
	shouldRecordNow,
	spent,
} from "../../../main/resources/agent/pi-review-turns.ts";

const piece = { modelCalls: 6, outputTokens: 8000 };

void test("a turn is asked to record with two calls left, or when its tokens only pay for what it owes", () => {
	const budget = { modelCalls: piece.modelCalls * 3, outputTokens: piece.outputTokens * 3 };
	assert.equal(shouldRecordNow({ modelCalls: 10, outputTokens: 1000 }, budget, 3, 1500), false);
	assert.equal(shouldRecordNow({ modelCalls: 16, outputTokens: 1000 }, budget, 3, 1500), true);
	assert.equal(shouldRecordNow({ modelCalls: 4, outputTokens: 19_000 }, budget, 3, 1500), false);
	assert.equal(shouldRecordNow({ modelCalls: 4, outputTokens: 19_600 }, budget, 3, 1500), true);
});

void test("a turn has spent its budget when either its calls or its output tokens are used up", () => {
	const budget = { modelCalls: piece.modelCalls * 3, outputTokens: piece.outputTokens * 3 };
	assert.equal(spent({ modelCalls: 17, outputTokens: 23_999 }, budget), false);
	assert.equal(spent({ modelCalls: 18, outputTokens: 0 }, budget), true);
	assert.equal(spent({ modelCalls: 1, outputTokens: 24_000 }, budget), true);
});

void test("missing practices keep the review's order", () => {
	assert.deepEqual(missingSlugs(["a", "b", "c"], ["c", "a"]), ["b"]);
	assert.deepEqual(missingSlugs(["a"], ["a", "a"]), []);
});
