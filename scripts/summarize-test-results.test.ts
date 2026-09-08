import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { markdown, parseJUnit, parsePerformance, summarize } from "./summarize-test-results.ts";

const REPORT = `<?xml version="1.0"?>
<testsuite name="example" tests="3" failures="1" errors="0" skipped="1" time="1.75">
  <testcase name="fast &amp; safe" classname="ExampleTest" time="0.25"/>
  <testcase name="slow" classname="ExampleTest" time="1.5"><failure message="nope"/></testcase>
  <testcase name="disabled" classname="ExampleTest" time="0"><skipped/></testcase>
</testsuite>`;

await test("parses outcomes and XML entities from JUnit test cases", () => {
	assert.deepEqual(parseJUnit(REPORT), [
		{
			className: "ExampleTest",
			name: "fast & safe",
			timeSeconds: 0.25,
			failed: false,
			errored: false,
			skipped: false,
		},
		{
			className: "ExampleTest",
			name: "slow",
			timeSeconds: 1.5,
			failed: true,
			errored: false,
			skipped: false,
		},
		{
			className: "ExampleTest",
			name: "disabled",
			timeSeconds: 0,
			failed: false,
			errored: false,
			skipped: true,
		},
	]);
});

await test("rejects invalid individual timings before they can disappear in a sum", () => {
	for (const time of [
		'time="-10"',
		'time="NaN"',
		'time="Infinity"',
		'time="1e999"',
		'time=""',
		"",
	]) {
		assert.throws(
			() =>
				summarize("Server", [
					`<testsuite><testcase name="invalid" ${time}/><testcase name="valid" time="20"/></testsuite>`,
				]),
			/Invalid JUnit testcase time/,
		);
	}
});

await test("allows missing timing only for skipped tests", () => {
	assert.equal(
		parseJUnit('<testsuite><testcase name="disabled"><skipped/></testcase></testsuite>')[0]
			?.timeSeconds,
		0,
	);
	assert.throws(
		() =>
			parseJUnit(
				'<testsuite><testcase name="disabled" time="NaN"><skipped/></testcase></testsuite>',
			),
		/Invalid JUnit testcase time/,
	);
});

await test("summarizes reports and ranks the slowest tests", () => {
	const summary = summarize("Server", [REPORT]);
	assert.equal(summary.tests, 3);
	assert.equal(summary.failures, 1);
	assert.equal(summary.errors, 0);
	assert.equal(summary.skipped, 1);
	assert.equal(summary.testTimeSeconds, 1.75);
	assert.deepEqual(summary.slowest[0], { test: "ExampleTest.slow", seconds: 1.5 });
	assert.match(markdown(summary), /\*\*3 tests\*\* · 1 failed · 0 errors · 1 skipped/);
});

await test("uses XML structure rather than matching tag-like text", () => {
	const report = `<testsuites><testsuite name="edge cases">
		<testcase classname="ExampleTest" name="accepts > in attributes" time="0.1">
			<system-out><![CDATA[diagnostic text containing <failure but no failure element]]></system-out>
		</testcase>
		<testcase classname="ExampleTest" name="failed" time="0.2"><failure>assertion</failure></testcase>
	</testsuite></testsuites>`;

	assert.deepEqual(parseJUnit(report), [
		{
			className: "ExampleTest",
			name: "accepts > in attributes",
			timeSeconds: 0.1,
			failed: false,
			errored: false,
			skipped: false,
		},
		{
			className: "ExampleTest",
			name: "failed",
			timeSeconds: 0.2,
			failed: true,
			errored: false,
			skipped: false,
		},
	]);
});

await test("extracts wall and context metrics without claiming daemon CPU or memory", () => {
	const performance = parsePerformance(
		"Started FirstTest in 4.25 seconds\nDefaultContextCache@abc missCount = 1\nStarted SecondTest in 5.75 seconds\nDefaultContextCache@abc missCount = 2",
		`User time (seconds): 12.5
System time (seconds): 2.5
Elapsed (wall clock) time (h:mm:ss or m:ss): 1:03.50
Maximum resident set size (kbytes): 524288`,
	);
	assert.deepEqual(performance, {
		wallTimeSeconds: 63.5,
		contextStarts: 2,
		contextStartupSeconds: 10,
		contextCacheMisses: 2,
	});
});

await test("does not turn missing wall time into zero", () => {
	assert.throws(() => parsePerformance("", ""), /Missing or invalid elapsed/);
});

await test("profile CLI preserves diagnostics and fails when no tests were reported", async (context) => {
	const directory = await mkdtemp(join(tmpdir(), "profile-summary-"));
	context.after(() => rm(directory, { recursive: true, force: true }));
	const reports = join(directory, "reports");
	await mkdir(reports);
	const log = join(directory, "run.log");
	const resources = join(directory, "resources.txt");
	const output = join(directory, "summary.json");
	await writeFile(log, "");
	await writeFile(
		resources,
		"User time (seconds): 1\nSystem time (seconds): 1\nElapsed (wall clock) time (h:mm:ss or m:ss): 0:02\nMaximum resident set size (kbytes): 100",
	);
	const script = fileURLToPath(new URL("./summarize-test-results.ts", import.meta.url));
	const result = spawnSync(process.execPath, [script, "profile", reports, output, log, resources], {
		encoding: "utf8",
		env: { ...process.env, GITHUB_STEP_SUMMARY: join(directory, "step-summary.md") },
	});
	assert.equal(result.status, 1);
	assert.match(result.stderr, /Profile contains no executed tests/);
	assert.match(await readFile(output, "utf8"), /"tests": 0/);
	const ordinary = spawnSync(process.execPath, [script, "report", reports, output], {
		encoding: "utf8",
		env: { ...process.env, GITHUB_STEP_SUMMARY: join(directory, "step-summary.md") },
	});
	assert.equal(ordinary.status, 0);
});

await test("verification profiles allow no Spring contexts but still require successful executed tests", async (context) => {
	const directory = await mkdtemp(join(tmpdir(), "verification-profile-"));
	context.after(() => rm(directory, { recursive: true, force: true }));
	const reports = join(directory, "reports");
	await mkdir(reports);
	const log = join(directory, "run.log");
	const resources = join(directory, "resources.txt");
	await writeFile(log, "");
	await writeFile(
		resources,
		"User time (seconds): 1\nSystem time (seconds): 1\nElapsed (wall clock) time (h:mm:ss or m:ss): 0:02\nMaximum resident set size (kbytes): 100",
	);
	const script = fileURLToPath(new URL("./summarize-test-results.ts", import.meta.url));
	for (const [kind, body, valid] of [
		["verification", "", true],
		["integration", "", false],
		["verification", "<skipped/>", false],
		["verification", "<failure/>", false],
		["unknown", "", false],
	] as const) {
		await writeFile(
			join(reports, "TEST-example.xml"),
			`<testsuite><testcase classname="Example" name="test" time="1">${body}</testcase></testsuite>`,
		);
		const result = spawnSync(
			process.execPath,
			[script, "profile", reports, join(directory, "summary.json"), log, resources, kind],
			{
				encoding: "utf8",
				env: { ...process.env, GITHUB_STEP_SUMMARY: join(directory, "step-summary.md") },
			},
		);
		assert.equal(result.status, valid ? 0 : 1, `${kind} ${body}: ${result.stderr}`);
	}
});
