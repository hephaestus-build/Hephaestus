import assert from "node:assert/strict";
import { test } from "node:test";

import {
	isSample,
	largest,
	NOT_STARTED,
	parseSample,
	positiveInteger,
	parseQueueObservation,
	queueReport,
	RUNTIME_SAMPLE_FLOOR,
	verdict,
	type ReviewOutcome,
} from "./lib/sandbox-footprint-report.ts";

const sample = {
	t: 1,
	label: "review-0/runtime",
	current: 10,
	peak: 12,
	anon: 4,
	file: 3,
	shmem: 1,
	kernel: 1,
	cpuUsec: 100,
	throttledUsec: 0,
	pids: 2,
	procs: [{ pid: 7, cmd: "node gateway-run.ts", rss: 5, hwm: 6, anon: 4, file: 1, shmem: 0 }],
	tmpfs: { "/tmp": 4096 },
};

const clean = (index: number): ReviewOutcome => ({
	index,
	initExit: 0,
	runtimeExit: 0,
	uploads: 1,
	initSamples: 3,
	runtimeSamples: RUNTIME_SAMPLE_FLOOR,
});

const run = (reviews: ReviewOutcome[], overrides: Partial<Parameters<typeof verdict>[0]> = {}) =>
	verdict({
		reviews,
		practices: 4,
		submits: reviews.length * 4,
		malformed: [],
		errors: [],
		...overrides,
	});

await test("isSample accepts a full sample and one without tmpfs", () => {
	assert.equal(isSample(sample), true);
	const { tmpfs: _tmpfs, ...withoutTmpfs } = sample;
	assert.equal(isSample(withoutTmpfs), true);
});

await test("isSample rejects a missing numeric field, a bad process and a non-object", () => {
	const { peak: _peak, ...withoutPeak } = sample;
	assert.equal(isSample(withoutPeak), false);
	assert.equal(isSample({ ...sample, procs: [{ pid: 7, cmd: "node" }] }), false);
	assert.equal(isSample("sample"), false);
	assert.equal(isSample(null), false);
	assert.equal(isSample([sample]), false);
});

await test("parseSample returns null for a line that is not JSON or not a sample", () => {
	assert.deepEqual(parseSample(JSON.stringify(sample)), sample);
	assert.equal(parseSample('{"t": 1, "label": "review-0/runtime"'), null);
	assert.equal(parseSample('{"t": 1}'), null);
	assert.equal(parseSample(""), null);
});

await test("largest keeps the top entries, largest first", () => {
	const entries = Array.from({ length: 15 }, (_, index): [string, number] => [
		`/tmp/${index}`,
		index,
	]);
	const kept = largest(entries, ([, bytes]) => bytes, 10);
	assert.equal(kept.length, 10);
	assert.deepEqual(kept.at(0), ["/tmp/14", 14]);
	assert.deepEqual(kept.at(-1), ["/tmp/5", 5]);
	assert.equal(entries.length, 15);
	assert.deepEqual(
		largest(entries.slice(0, 2), ([, bytes]) => bytes, 10),
		[
			["/tmp/1", 1],
			["/tmp/0", 0],
		],
	);
});

await test("verdict passes a clean run", () => {
	assert.deepEqual(run([clean(0), clean(1)]), { passed: true, reasons: [] });
});

await test("verdict fails a runtime without samples", () => {
	assert.deepEqual(run([{ ...clean(0), runtimeSamples: 0 }]), {
		passed: false,
		reasons: [`review 0: the runtime has 0 samples, fewer than ${String(RUNTIME_SAMPLE_FLOOR)}`],
	});
	assert.deepEqual(run([{ ...clean(0), initSamples: 0 }]).reasons, [
		"review 0: the initializer has no samples",
	]);
});

await test("verdict fails a review whose result the gateway did not receive", () => {
	assert.deepEqual(run([clean(0), { ...clean(1), uploads: 0 }]).reasons, [
		"review 1: the gateway received no result upload",
	]);
});

await test("verdict names a runtime that the kernel or a signal killed", () => {
	assert.deepEqual(run([{ ...clean(1), index: 1, runtimeExit: 137 }]).reasons, [
		"review 1: runtime exited 137 (killed: the cgroup memory limit or a signal)",
	]);
});

await test("verdict reports a runtime that did not start once, not as missing samples", () => {
	const decided = run([
		{ ...clean(0), initExit: 1, runtimeExit: NOT_STARTED, runtimeSamples: 0, uploads: 0 },
	]);
	assert.deepEqual(decided.reasons, [
		"review 0: initializer exited 1",
		"review 0: the runtime did not start",
	]);
});

await test("verdict fails when the submissions do not match reviews times practices", () => {
	assert.deepEqual(run([clean(0), clean(1)], { submits: 7 }), {
		passed: false,
		reasons: ["the model received 7 result submissions, not 8 (2 reviews × 4 practices)"],
	});
});

await test("verdict fails on unreadable sampler lines", () => {
	assert.deepEqual(run([clean(0)], { malformed: ["{not json", "{}"] }).reasons, [
		"2 sampler lines were unreadable; the first: {not json",
	]);
});

await test("verdict fails a run without reviews and keeps the errors that stopped it", () => {
	assert.deepEqual(run([]), { passed: false, reasons: ["no review ran"] });
	assert.deepEqual(run([], { errors: ["the run stopped: tar exited with code 2"] }).reasons, [
		"the run stopped: tar exited with code 2",
		"no review ran",
	]);
});

await test("positiveInteger rejects zero, fractions and typos", () => {
	assert.equal(positiveInteger("12", "--tool-calls"), 12);
	for (const value of ["0", "-1", "1.5", "1e3", "abc", "", " 3"]) {
		assert.throws(() => positiveInteger(value, "--reviews"), {
			message: `--reviews must be a whole number above 0, but is "${value}"`,
		});
	}
});

await test("queues separate conversation order from capacity and provider time", () => {
	const report = queueReport([
		parseQueueObservation({
			id: "turn-1",
			kind: "mentor",
			submitted: 0,
			eligible: 220000,
			granted: 221000,
			firstEvent: 224000,
		}),
		parseQueueObservation({
			id: "turn-2",
			kind: "mentor",
			submitted: 0,
			eligible: 0,
			granted: 5000,
		}),
		parseQueueObservation({
			id: "review-1",
			kind: "review",
			submitted: 0,
			eligible: 120000,
			podReady: 125000,
			granted: 127000,
		}),
		parseQueueObservation({
			id: "composition-1",
			kind: "composition",
			submitted: 0,
			eligible: 2000,
			granted: 12000,
		}),
	]);
	assert.deepEqual(report.mentor?.capacity, { count: 2, p50Ms: 1000, p95Ms: 5000, maxMs: 5000 });
	assert.equal(report.mentor?.intentionalConversationWait.p95Ms, 220000);
	assert.equal(report.mentor?.endToEnd.p95Ms, 221000);
	assert.equal(report.mentor?.upstreamFirstEvent.p95Ms, 3000);
	assert.equal(report.review?.podReady.p95Ms, 5000);
	assert.equal(report.review?.modelAfterPodReady.p95Ms, 2000);
	assert.equal(report.composition?.capacity.p95Ms, 10000);
	assert.equal(report.mentor?.sufficientForObjectives, false);
});

await test("absent observations never become zero-latency evidence", () => {
	assert.deepEqual(queueReport([]).mentor?.capacity, {
		count: 0,
		p50Ms: null,
		p95Ms: null,
		maxMs: null,
	});
});

await test("queue input rejects impossible, missing, non-finite and negative phases", () => {
	const valid = { id: "r", kind: "review", submitted: 1, eligible: 2, podReady: 3, granted: 4 };
	for (const value of [
		null,
		{ ...valid, eligible: 0 },
		{ ...valid, granted: NaN },
		{ ...valid, podReady: undefined },
		{ ...valid, podReady: 5 },
		{ ...valid, firstEvent: 3 },
		{ ...valid, submitted: -1 },
	]) {
		assert.throws(() => parseQueueObservation(value));
	}
	assert.equal(isSample({ ...sample, current: Infinity }), false);
	assert.equal(isSample({ ...sample, procs: [{ ...sample.procs[0], rss: -1 }] }), false);
});
