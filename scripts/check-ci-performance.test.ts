import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseSummary, regressions } from "./check-ci-performance.ts";
import type { TestSummary } from "./summarize-test-results.ts";

const summary = (startup: number, wall = 200): TestSummary => ({
	schemaVersion: 3,
	name: "profile",
	files: 1,
	tests: 1,
	failures: 0,
	errors: 0,
	skipped: 0,
	testTimeSeconds: 100,
	slowest: [],
	performance: {
		wallTimeSeconds: wall,
		contextStarts: 10,
		contextStartupSeconds: startup,
		contextCacheMisses: 10,
	},
});

await test("does not signal an absolute-budget miss from one profile", () => {
	const current = summary(121);
	assert.ok(current.performance);
	current.performance.contextStarts = 16;
	assert.deepEqual(regressions(current, []), []);
});

await test("signals only three consecutive misses against five prior profiles", () => {
	const slow = summary(121, 250);
	assert.ok(slow.performance);
	slow.performance.contextStarts = 16;
	const baseline = [100, 101, 99, 100, 100].map((startup) => summary(startup, 200));
	assert.deepEqual(regressions(slow, [...baseline, slow, slow]), [
		"context starts exceeded 15 in three consecutive profiles",
		"context startup exceeded 120s in three consecutive profiles",
		"wall time exceeded variance limit 240.0 three times",
	]);
});

await test("rejects incomplete, failed, and nonfinite profiles before comparison", () => {
	for (const invalid of [
		{ ...summary(100), files: 0 },
		{ ...summary(100), tests: 0 },
		{ ...summary(100), skipped: 1 },
		{ ...summary(100), failures: 1 },
		{ ...summary(100), errors: 1 },
		{ ...summary(100), testTimeSeconds: -1 },
		{ ...summary(100), testTimeSeconds: Infinity },
		{ ...summary(100), tests: 1.5 },
		summary(100, 0),
	]) {
		assert.throws(() => regressions(invalid, []));
		assert.throws(() => regressions(summary(100), [invalid]));
	}
});

await test("validates numeric fields in persisted profiles", () => {
	assert.deepEqual(parseSummary(JSON.stringify(summary(100))), summary(100));
	assert.throws(
		() =>
			parseSummary(
				JSON.stringify(summary(100)).replace('"wallTimeSeconds":200', '"wallTimeSeconds":1e999'),
			),
		/Invalid CI metrics field/,
	);
	assert.throws(
		() => parseSummary(JSON.stringify({ ...summary(100), testTimeSeconds: -1 })),
		/Invalid CI metrics field/,
	);
});

await test("missing Spring logs cannot appear as a faster profile", () => {
	for (const key of ["contextStarts", "contextCacheMisses"] as const) {
		const current = summary(100);
		assert.ok(current.performance);
		current.performance[key] = 0;
		assert.throws(() => regressions(current, []), /no Spring context measurements/);
	}
});

await test("CLI warns on sustained regressions but fails for corrupt or failed-test evidence", async (context) => {
	const directory = await mkdtemp(path.join(tmpdir(), "profile-advisory-"));
	context.after(() => rm(directory, { recursive: true, force: true }));
	const history = path.join(directory, "history");
	const current = path.join(directory, "current.json");
	const output = path.join(directory, "summary.md");
	await mkdir(history);
	for (let i = 0; i < 7; i++)
		await writeFile(
			path.join(history, `${i}.json`),
			JSON.stringify(summary(100, i < 5 ? 200 : 300)),
		);
	const run = () =>
		spawnSync(
			process.execPath,
			[fileURLToPath(new URL("./check-ci-performance.ts", import.meta.url)), current, history],
			{
				env: { ...process.env, GITHUB_STEP_SUMMARY: output },
				encoding: "utf8",
			},
		);
	await writeFile(current, JSON.stringify(summary(100, 300)));
	const exceeded = run();
	assert.equal(exceeded.status, 0, exceeded.stderr);
	assert.match(exceeded.stdout, /::warning title=Integration profile regression::wall time/);
	assert.match(await readFile(output, "utf8"), /Status: \*\*regression\*\*/);
	await writeFile(current, JSON.stringify({ ...summary(100), failures: 1 }));
	assert.notEqual(run().status, 0);
	await writeFile(current, "not json");
	assert.notEqual(run().status, 0);
	await writeFile(current, JSON.stringify(summary(100)));
	await writeFile(path.join(history, "0.json"), "not json");
	assert.notEqual(run().status, 0);
});

await test("rejects profiles from the old process-accounting schema", () => {
	assert.throws(
		() => parseSummary(JSON.stringify({ ...summary(10), schemaVersion: 2 })),
		/Invalid CI metrics summary/,
	);
});
