import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { classSeconds } from "./integration-test-durations.ts";
import { COMMITTED, parseDurations, record } from "./lib/integration-durations.ts";
import { parseJson } from "./lib/json.ts";

const now = new Date("2026-11-11T12:00:00Z");
const committed = { shards: 8, recordedAt: "2026-11-01T00:00:00Z", seconds: { A: 10, B: 1 } };

void test("the committed default is valid and names every shard's seconds", async () => {
	const durations = parseDurations(parseJson(await readFile(COMMITTED, "utf8")), COMMITTED);
	assert.ok(durations.shards > 1);
	assert.ok(Object.keys(durations.seconds).length > durations.shards);
});

void test("rejects durations a partition cannot use", () => {
	for (const value of [
		{ ...committed, shards: 0 },
		{ ...committed, shards: 1.5 },
		{ ...committed, recordedAt: "yesterday" },
		{ ...committed, seconds: {} },
		{ ...committed, seconds: { A: -1 } },
		{ ...committed, seconds: { A: "10" } },
	]) {
		assert.throws(() => parseDurations(value, "durations"));
	}
});

void test("reads the top-level class from the test cases, not the display name or file", () => {
	assert.deepEqual(
		classSeconds(
			'<testsuite name="A display name" time="1.5"><testcase classname="de.Example$Nested" name="a()"/></testsuite>',
		),
		["de.Example", 1.5],
	);
	assert.equal(classSeconds('<testsuite name="Empty" time="0"/>'), undefined);
	assert.throws(() => classSeconds('<testsuite><testcase classname="de.A"/></testsuite>'));
	assert.throws(() => classSeconds('<testsuite time="1"><testcase name="a()"/></testsuite>'));
});

void test("a run moves each class half way and forgets the classes it did not run", () => {
	const measured = new Map([
		["A", 20],
		["C", 3.04],
	]);
	assert.deepEqual(record(measured, committed, 8, now), {
		shards: 8,
		recordedAt: now.toISOString(),
		seconds: { A: 15, C: 3 },
	});
});
