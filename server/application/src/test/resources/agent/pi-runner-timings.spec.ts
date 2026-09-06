import assert from "node:assert/strict";
import test from "node:test";

import {
	deriveReconBudget,
	deriveTimeouts,
	deriveTurnTiming,
	deriveWorkstreamBudget,
} from "../../../main/resources/agent/pi-runner-timings.ts";

void test("review budget reserves fifteen percent for a retry", () => {
	assert.deepEqual(deriveTimeouts(900_000), {
		initialMs: 765_000,
		retryMs: 135_000,
		compositionMs: 0,
	});
});

void test("the shared reconnaissance gets a turn's worth of time, not a share of the review", () => {
	// One model turn over the whole change: too small a share buys nothing, and the cap keeps a large
	// review from spending its observers' time here.
	assert.equal(deriveReconBudget(deriveTimeouts(900_000).initialMs), 120_000);
	assert.equal(deriveReconBudget(1_800_000), 180_000);
	assert.equal(deriveReconBudget(9_000_000), 240_000);
	// A pass too short to fund the floor keeps most of itself for the groups, and its deadline still
	// expires inside the pass rather than after the review is already over.
	assert.equal(deriveReconBudget(deriveTimeouts(120_000).initialMs), 25_500);
	for (const invalid of [0, -1, Number.NaN]) {
		assert.throws(() => deriveReconBudget(invalid), /positive number/);
	}
});

void test("a small review never allocates more time than it owns", () => {
	assert.deepEqual(deriveTimeouts(10_000), {
		initialMs: 8_500,
		retryMs: 1_500,
		compositionMs: 0,
	});
});

void test("a composing review reserves time for intervention before detection starts", () => {
	assert.deepEqual(deriveTimeouts(900_000, true), {
		initialMs: 650_250,
		retryMs: 114_750,
		compositionMs: 135_000,
	});
});

void test("composition and retry stay inside a small budget", () => {
	assert.deepEqual(deriveTimeouts(10_000, true), {
		initialMs: 7_225,
		retryMs: 1_275,
		compositionMs: 1_500,
	});
});

void test("the next turn adapts to the remaining time and practice batches", () => {
	assert.deepEqual(deriveTurnTiming(600_000, 5), {
		fairShareMs: 120_000,
		softNudgeMs: 72_000,
	});
	assert.deepEqual(deriveTurnTiming(119_999, 2), {
		fairShareMs: 59_999,
		softNudgeMs: 35_999,
	});
});

for (const invalid of [0, -1, 1.5, Number.NaN]) {
	void test(`rejects invalid remaining turn count ${invalid}`, () => {
		assert.throws(() => deriveTurnTiming(1_000, invalid), /positive integer/);
	});
}

void test("a workstream receives its share of all concurrently available time", () => {
	assert.equal(deriveWorkstreamBudget(7_200_000, 7, 27), 1_866_666);
});

void test("a workstream budget is not capped below its fair share", () => {
	assert.equal(deriveWorkstreamBudget(10_800_000, 1, 1), 10_800_000);
});

void test("rejects invalid workstream capacity", () => {
	assert.throws(() => deriveWorkstreamBudget(1_000, 0, 1), /activeSlots/);
	assert.throws(() => deriveWorkstreamBudget(1_000, 1, 0), /remainingWorkstreams/);
});
