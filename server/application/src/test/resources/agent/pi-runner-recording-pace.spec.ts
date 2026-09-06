import assert from "node:assert/strict";
import test from "node:test";

import {
	createRecordingPace,
	promptTokens,
	RECORDING_CHECKPOINTS,
} from "../../../main/resources/agent/pi-runner-recording-pace.ts";

void test("the prompt is every bucket the provider split it into", () => {
	assert.equal(promptTokens({ input: 300, cacheRead: 20_000, cacheWrite: 5_000 }), 25_300);
});

void test("a session is asked once at each share of its context", () => {
	const pace = createRecordingPace(1000, true, [0.4, 0.8]);
	assert.equal(pace.checkpointReached(200), null);
	assert.equal(pace.checkpointReached(400), 0.4);
	assert.equal(pace.checkpointReached(500), null, "the same share is not asked for twice");
	assert.equal(pace.checkpointReached(810), 0.8);
	assert.equal(pace.checkpointReached(999), null, "there is nothing left to ask for");
});

void test("one turn that crosses several shares is asked once, for the furthest", () => {
	const pace = createRecordingPace(1000, true, [0.4, 0.65, 0.85]);
	assert.equal(pace.checkpointReached(100), null);
	assert.equal(pace.checkpointReached(900), 0.85);
	assert.equal(pace.checkpointReached(950), null);
});

void test("the window a session starts with is not a share it spent", () => {
	const pace = createRecordingPace(1000, true, [0.4, 0.65, 0.85]);
	assert.equal(pace.checkpointReached(700), null, "a forked session inherits a full window");
	assert.equal(pace.checkpointReached(720), null, "the shares it began past stay answered");
	assert.equal(pace.checkpointReached(860), 0.85);
});

void test("a window that empties is asked for again as it refills", () => {
	const pace = createRecordingPace(1000, true, [0.4, 0.8]);
	assert.equal(pace.checkpointReached(100), null);
	assert.equal(pace.checkpointReached(850), 0.8);
	assert.equal(pace.checkpointReached(150), null, "compaction is not itself an ask");
	assert.equal(pace.checkpointReached(450), 0.4, "what it fills again it is asked for again");
	assert.equal(pace.checkpointReached(900), 0.8);
});

void test("a turn that reports no usage is not a share of anything", () => {
	const pace = createRecordingPace(1000, true, [0.4]);
	assert.equal(pace.checkpointReached(0), null);
	assert.equal(pace.checkpointReached(Number.NaN), null);
	assert.equal(pace.checkpointReached(100), null);
	assert.equal(pace.checkpointReached(400), 0.4);
});

void test("the shipped checkpoints ask early and stay inside the window", () => {
	assert.ok(RECORDING_CHECKPOINTS.length > 0);
	assert.ok(
		(RECORDING_CHECKPOINTS[0] ?? 1) <= 0.5,
		"the first ask comes before the window is half spent",
	);
	assert.ok((RECORDING_CHECKPOINTS.at(-1) ?? 0) < 1, "the last ask leaves room to answer it");
});

void test("a session that starts empty is asked from its first turn", () => {
	// Nothing in its window arrived with it, so the first prompt is already something it went and read.
	const fresh = createRecordingPace(1000, false, [0.4, 0.85]);
	assert.equal(fresh.checkpointReached(900), 0.85);

	// A session forked from the shared reconnaissance opens on a window it did not read.
	const forked = createRecordingPace(1000, true, [0.4, 0.85]);
	assert.equal(forked.checkpointReached(900), null);
	assert.equal(
		forked.checkpointReached(950),
		null,
		"the shares it arrived holding are not asked for",
	);
});

void test("a window and its checkpoints have to make sense", () => {
	for (const invalid of [0, -1, Number.NaN]) {
		assert.throws(() => createRecordingPace(invalid, true), /positive number/);
	}
	assert.throws(() => createRecordingPace(1000, true, []), /at least one checkpoint/);
	assert.throws(() => createRecordingPace(1000, true, [0.8, 0.4]), /ascend/);
	assert.throws(() => createRecordingPace(1000, true, [0, 0.4]), /ascend/);
	assert.throws(() => createRecordingPace(1000, true, [0.4, 0.4]), /ascend/);
	assert.throws(() => createRecordingPace(1000, true, [0.4, 1.2]), /ascend/);
	assert.throws(() => createRecordingPace(1000, true, [Number.NaN]), /ascend/);
});
