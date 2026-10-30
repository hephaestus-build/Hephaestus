import assert from "node:assert/strict";
import { test } from "node:test";

import { CapacityEvents } from "./lib/capacity-events.ts";
import {
	claimReplayTool,
	logicalReplayKey,
	parseReplay,
	replaySample,
	sensitivityGrid,
} from "./lib/capacity-replay.ts";

await test("the sensitivity grid preserves all 27 distinct assumption combinations", () => {
	const grid = sensitivityGrid();
	assert.equal(grid.length, 27);
	assert.equal(new Set(grid.map((row) => row.name)).size, 27);
	for (const row of grid) {
		const parsed = parseReplay(row);
		assert.equal(parsed.provenance, "assumed");
		assert.equal(parsed.samples.length, 100);
		const percent = Number(row.name.split("-").at(-1));
		assert.equal(parsed.samples.filter((sample) => sample.status === 429).length, percent);
	}
});

await test("logical replay is stable across request IDs, history order and run labels", () => {
	const old = "capacity-replay:old-run:7:2";
	assert.equal(
		logicalReplayKey(
			`${old} capacity-replay:first-run:3:1 capacity-replay:first-run:3:0`,
			"first-run",
		),
		"3:1",
	);
	assert.equal(
		logicalReplayKey("capacity-replay:second-run:3:0 capacity-replay:second-run:3:1", "second-run"),
		"3:1",
	);
	assert.equal(logicalReplayKey(old, "first-run"), null);
	assert.equal(
		logicalReplayKey("capacity-replay:first-run:3:1 capacity-replay:first-run:4:1", "first-run"),
		null,
	);
});

await test("replay follows content across reordered arrivals", () => {
	const { samples } = parseReplay(sensitivityGrid()[0]);
	const keys = ["synthetic-call-a", "synthetic-call-b", "synthetic-call-c"];
	const expected = new Map(keys.map((key) => [key, replaySample(samples, key)]));
	for (const key of keys.toReversed()) {
		assert.deepEqual(replaySample(samples, key), expected.get(key));
	}
});

await test("missing or impossible measurements cannot become a replay", () => {
	const sample = { ttftMs: 100, durationMs: 200, inputTokens: 1, outputTokens: 2, status: 200 };
	const document = { provenance: "measured", source: "synthetic fixture", samples: [sample] };
	for (const samples of [
		[],
		[{ ...sample, ttftMs: null }],
		[{ ...sample, durationMs: 20 }],
		[{ ...sample, status: 503 }],
		[{ ...sample, outputTokens: -1 }],
	]) {
		assert.throws(() => parseReplay({ ...document, samples }));
	}
	assert.throws(() => parseReplay({ ...document, provenance: "estimated" }));
	assert.throws(() => parseReplay({ ...document, source: "" }));
});

await test("SSE observation preserves split UTF-8 and excludes heartbeats", () => {
	const events = new CapacityEvents();
	const result: [string, string][] = [];
	const raw = new TextEncoder().encode(
		': ping\r\n\r\nevent: turn\r\ndata: {"text":"é"}\r\ndata: second\r\n\r\n',
	);
	for (const byte of raw) {
		events.push(Uint8Array.of(byte), (event, data) => {
			result.push([event, data]);
		});
	}
	assert.deepEqual(result, [["turn", '{"text":"é"}\nsecond']]);
});

await test("the synthetic provider ends after one tool call even when the client restates its user prompt", () => {
	const sent = new Set<string>();
	assert.equal(claimReplayTool(sent, "synthetic-turn-a"), true);
	assert.equal(claimReplayTool(sent, "synthetic-turn-a"), false);
	assert.equal(claimReplayTool(sent, "synthetic-turn-b"), true);
});
