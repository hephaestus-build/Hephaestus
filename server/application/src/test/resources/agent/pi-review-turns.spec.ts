import assert from "node:assert/strict";
import test from "node:test";

import {
	missingSlugs,
	shouldRecordNow,
	spent,
} from "../../../main/resources/agent/pi-review-turns.ts";

const piece = { modelCalls: 6, outputTokens: 8000, inputTokens: 200_000 };
const budget = {
	modelCalls: piece.modelCalls * 3,
	outputTokens: piece.outputTokens * 3,
	inputTokens: piece.inputTokens * 3,
};
const used = (modelCalls: number, outputTokens: number, inputTokens = 0) => ({
	modelCalls,
	outputTokens,
	inputTokens,
});

void test("a turn is asked to record with two calls left, or when its tokens only pay for what it owes", () => {
	assert.equal(shouldRecordNow(used(10, 1000), budget, 3, 1500, 0), false);
	assert.equal(shouldRecordNow(used(16, 1000), budget, 3, 1500, 0), true);
	assert.equal(shouldRecordNow(used(4, 19_000), budget, 3, 1500, 0), false);
	assert.equal(shouldRecordNow(used(4, 19_600), budget, 3, 1500, 0), true);
});

void test("a turn is asked to record when its input budget pays for two more prompts of its last size at most", () => {
	assert.equal(shouldRecordNow(used(4, 1000, 500_000), budget, 3, 1500, 40_000), false);
	assert.equal(shouldRecordNow(used(4, 1000, 520_000), budget, 3, 1500, 40_000), true);
});

void test("a turn has spent its budget when its calls, its output tokens or its input tokens are used up", () => {
	assert.equal(spent(used(17, 23_999, 599_999), budget), false);
	assert.equal(spent(used(18, 0), budget), true);
	assert.equal(spent(used(1, 24_000), budget), true);
	assert.equal(spent(used(1, 0, 600_000), budget), true);
});

void test("missing practices keep the review's order", () => {
	assert.deepEqual(missingSlugs(["a", "b", "c"], ["c", "a"]), ["b"]);
	assert.deepEqual(missingSlugs(["a"], ["a", "a"]), []);
});
