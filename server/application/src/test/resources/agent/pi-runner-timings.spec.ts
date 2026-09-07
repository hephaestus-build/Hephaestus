import assert from "node:assert/strict";
import test from "node:test";

import {
	armRetryWindow,
	deriveCompositionWindow,
	deriveReconBudget,
	deriveRetryWindow,
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

void test("the retry inherits what the initial pass did not spend", () => {
	const timeouts = deriveTimeouts(900_000);
	// The initial pass returned after 400s of its 765s slice, with the process budget untouched.
	assert.equal(deriveRetryWindow(timeouts, 400_000, 900_000), 500_000);
	// It used every second it was given: the retry gets exactly its reservation.
	assert.equal(deriveRetryWindow(timeouts, timeouts.initialMs, 135_000), timeouts.retryMs);
	// A reservation cannot create time after the process budget is exhausted.
	assert.equal(deriveRetryWindow(timeouts, 900_000, 0), 0);
});

void test("the retry leaves composition its slice of what the process has left", () => {
	const timeouts = deriveTimeouts(1_740_000, true);
	// 18s of SDK and model runtime setup ran before the first pass, which returned after 736s of its
	// own slice. 743s of review budget are unspent, but only 725s of them can be spent here without
	// pushing composition past the watchdog.
	assert.equal(deriveRetryWindow(timeouts, 736_000, 1_740_000 - 754_000), 725_000);
});

void test("the retry's abort fires on the window it was given, not on the reserved slice", (t) => {
	t.mock.timers.enable({ apis: ["setTimeout"] });
	const timeouts = deriveTimeouts(900_000);
	let aborted = false;
	const retry = armRetryWindow(timeouts, 400_000, 900_000, () => {
		aborted = true;
	});

	assert.equal(retry.windowMs, 500_000);
	t.mock.timers.tick(timeouts.retryMs);
	assert.equal(aborted, false);
	t.mock.timers.tick(retry.windowMs - timeouts.retryMs);
	assert.equal(aborted, true);
	clearTimeout(retry.timer);
});

for (const invalid of [-1, Number.NaN]) {
	void test(`rejects invalid initial elapsed time ${invalid}`, () => {
		assert.throws(
			() => deriveRetryWindow(deriveTimeouts(900_000), invalid, 900_000),
			/initialElapsedMs must be a non-negative/,
		);
	});
}

void test("rejects a stage timeout that cannot be spent", () => {
	assert.throws(
		() => deriveRetryWindow({ initialMs: 100, retryMs: -1, compositionMs: 0 }, 0, 100),
		/retryMs must be a non-negative/,
	);
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

void test("retry cannot consume composition time or exceed the remaining process budget", () => {
	const timeouts = deriveTimeouts(900_000, true);
	assert.equal(
		deriveRetryWindow(timeouts, timeouts.initialMs, timeouts.compositionMs + 5_000),
		5_000,
	);
	assert.equal(deriveRetryWindow(timeouts, timeouts.initialMs, timeouts.compositionMs), 0);
	assert.equal(deriveRetryWindow(timeouts, timeouts.initialMs, -1), 0);
	for (const remaining of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
		assert.throws(
			() => deriveRetryWindow(timeouts, 0, remaining),
			/remainingProcessMs must be finite/,
		);
	}
});

void test("an exhausted retry window aborts before any work can start", () => {
	let aborted = false;
	const retry = armRetryWindow(deriveTimeouts(900_000), 900_000, 0, () => {
		aborted = true;
	});
	assert.equal(aborted, true);
	assert.equal(retry.windowMs, 0);
	assert.equal(retry.timer, undefined);
});

void test("composition uses only the time left after admission and session setup", () => {
	assert.equal(deriveCompositionWindow(135_000, 150_000), 135_000);
	assert.equal(deriveCompositionWindow(135_000, 10_000), 10_000);
	assert.equal(deriveCompositionWindow(135_000, 0), 0);
	assert.equal(deriveCompositionWindow(135_000, -1), 0);
	assert.throws(() => deriveCompositionWindow(135_000, Number.NaN), /finite/);
});
