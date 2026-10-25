import assert from "node:assert/strict";
import { glob, readFile } from "node:fs/promises";
import { test } from "node:test";

import { isMap, parseDocument, visit } from "yaml";

import {
	type AttemptJob,
	attributable,
	failedTestCases,
	flakyJobs,
	flakyTests,
	REPORT_STEP,
	renderFlakes,
	reportArtifacts,
	rerunsOf,
} from "./report-flakes.ts";

const job = (
	name: string,
	conclusion: string | null,
	failedSteps: string[] = [],
	startedAt = "2026-10-24T10:00:00Z",
	reported = true,
): AttemptJob => ({
	name,
	conclusion,
	startedAt,
	reported,
	url: `https://github.com/example/project/actions/runs/1/job/${name}`,
	failedSteps,
});

void test("a job that failed and then passed on a later attempt of the run is flaky", () => {
	const attempts = new Map([
		[1, [job("Integration", "failure", ["Run integration tests"]), job("Unit", "success")]],
		[2, [job("Integration", "success")]],
	]);
	assert.deepEqual(flakyJobs(attempts), [
		{ attempt: 1, job: job("Integration", "failure", ["Run integration tests"]) },
	]);
});

void test("a failure that never passed again, and the aggregate gate, are not flakes", () => {
	const attempts = new Map([
		[1, [job("Integration", "failure"), job("CI Status Gate", "failure")]],
		[2, [job("Integration", "failure"), job("CI Status Gate", "success")]],
	]);
	assert.deepEqual(flakyJobs(attempts), []);
});

void test("a failure passes only when a later attempt passes, not an earlier one", () => {
	const attempts = new Map([
		[2, [job("Stories", "failure")]],
		[1, [job("Stories", "success")]],
	]);
	assert.deepEqual(flakyJobs(attempts), []);
});

void test("a job that fails twice and then passes counts once, at its first failure", () => {
	const attempts = new Map([
		[1, [job("Stories", "failure")]],
		[2, [job("Stories", "failure")]],
		[3, [job("Stories", "success")]],
	]);
	assert.deepEqual(
		flakyJobs(attempts).map(({ attempt }) => attempt),
		[1],
	);
});

void test("a test that fails again in the last attempt is a real failure, not a flake", () => {
	// Job A flaked: its test passed on the rerun. Job B failed in both attempts.
	const firstAttempt = ["A > flaky", "B > broken"];
	const lastAttempt = ["B > broken"];
	assert.deepEqual(flakyTests(firstAttempt, lastAttempt), ["A > flaky"]);
});

const page = (...runs: object[]) => ({ workflow_runs: runs });

const at = (hour: number) => `2026-10-24T${String(hour).padStart(2, "0")}:00:00Z`;

void test("tests stay unknown unless every never-passing job ran again in the last attempt", () => {
	const flaky = [{ attempt: 1, job: job("A", "failure", [], at(10)) }];
	const first = [job("A", "failure", [], at(10)), job("B", "failure", [], at(10))];
	// "Re-run failed jobs": B ran again and failed again, so its tests can be subtracted.
	const rerunBoth = new Map([
		[1, first],
		[2, [job("A", "success", [], at(11)), job("B", "failure", [], at(11))]],
	]);
	assert.equal(attributable(rerunBoth, flaky, 2), true);
	// Only A was rerun: the attempt lists B again with its earlier start.
	const rerunOne = new Map([
		[1, first],
		[2, [job("A", "success", [], at(11)), job("B", "failure", [], at(10))]],
	]);
	assert.equal(attributable(rerunOne, flaky, 2), false);
	// B ran again in attempt 2, but attempt 3 reran only A and lists B with its attempt-2 start.
	const threeAttempts = new Map([
		[1, first],
		[2, [job("A", "failure", [], at(10)), job("B", "failure", [], at(11))]],
		[3, [job("A", "success", [], at(12)), job("B", "failure", [], at(11))]],
	]);
	assert.equal(attributable(threeAttempts, flaky, 3), false);
	// B ran again and failed again, but its report upload did not succeed.
	const unreported = new Map([
		[1, first],
		[2, [job("A", "success", [], at(11)), job("B", "failure", [], at(11), false)]],
	]);
	assert.equal(attributable(unreported, flaky, 2), false);
	// A job that never passed and never left a report adds no tests to the earlier reports.
	const imageBuild = new Map([
		[1, [job("A", "failure", [], at(10)), job("Image", "failure", [], at(10), false)]],
		[2, [job("A", "success", [], at(11)), job("Image", "failure", [], at(10), false)]],
	]);
	assert.equal(attributable(imageBuild, flaky, 2), true);
	// A cancelled rerun of B leaves no report to subtract.
	const cancelled = new Map([
		[1, first],
		[2, [job("A", "success", [], at(11)), job("B", "cancelled", [], at(11))]],
	]);
	assert.equal(attributable(cancelled, flaky, 2), false);
	// No job failed for real, so the flaky attempt's reports alone are enough.
	const onlyFlaky = new Map([
		[1, [job("A", "failure", [], at(10))]],
		[2, [job("A", "success", [], at(11))]],
	]);
	assert.equal(attributable(onlyFlaky, flaky, 2), true);
});

void test("every CI step that uploads attempt reports is recognised as the job's report", async () => {
	const steps: string[] = [];
	for await (const file of glob(".github/workflows/*.{yml,yaml}")) {
		visit(parseDocument(await readFile(file, "utf8")), {
			Map(_, node) {
				const upload = node.get("uses");
				const inputs = node.get("with");
				if (
					typeof upload === "string" &&
					upload.startsWith("actions/upload-artifact@") &&
					isMap(inputs) &&
					/-reports-\$\{\{ github\.run_attempt \}\}/u.test(String(inputs.get("name")))
				) {
					steps.push(String(node.get("name")));
				}
			},
		});
	}
	assert.ok(steps.length >= 4, `found only ${steps.length} report uploads`);
	for (const name of steps) {
		assert.match(name, REPORT_STEP);
	}
});

void test("reruns are listed once, even where two day windows share a bound", () => {
	const runs = rerunsOf([
		page({ id: 1, run_attempt: 2 }, { id: 2, run_attempt: 1 }),
		page({ id: 1, run_attempt: 2 }, { id: 3, run_attempt: 3 }),
	]);
	assert.deepEqual(
		runs.map((run) => run.id),
		[1, 3],
	);
});

void test("failed and errored JUnit cases are named by class and test", () => {
	const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="de.example.GatewayIntegrationTest" tests="3">
  <testcase name="passes" classname="de.example.GatewayIntegrationTest" time="0.1"/>
  <testcase name="reachesTheEndpoint()" classname="de.example.GatewayIntegrationTest" time="1.2">
    <failure message="expected 200">stack</failure>
  </testcase>
  <testcase name="startsTheSandbox()" classname="de.example.GatewayIntegrationTest" time="0.4">
    <error message="port in use">stack</error>
  </testcase>
</testsuite>`;
	assert.deepEqual(failedTestCases(xml), [
		"de.example.GatewayIntegrationTest > reachesTheEndpoint()",
		"de.example.GatewayIntegrationTest > startsTheSandbox()",
	]);
});

void test("Vitest suites without class names are named by their suite", () => {
	const xml = `<testsuites><testsuite name="src/routes/-mentor-route.test.tsx">
  <testcase name="stops a live answer"><failure message="timed out"/></testcase>
</testsuite></testsuites>`;
	assert.deepEqual(failedTestCases(xml), [
		"src/routes/-mentor-route.test.tsx > stops a live answer",
	]);
});

void test("only unexpired report artifacts of the failed attempt are read", () => {
	const artifacts = [
		{ name: "server-integration-providers-reports-1", expired: false },
		{ name: "server-integration-providers-reports-2", expired: false },
		{ name: "webapp-unit-reports-1", expired: true },
		{ name: "server-build-1", expired: false },
	];
	assert.deepEqual(reportArtifacts(artifacts, 1), ["server-integration-providers-reports-1"]);
});

void test("the report ranks tests and jobs by how often they flaked", () => {
	const rendered = renderFlakes(
		[
			{
				runId: 11,
				sha: "a",
				event: "pull_request",
				createdAt: "2026-10-20T10:00:00Z",
				jobs: [{ attempt: 1, job: job("Integration", "failure", ["Run integration tests"]) }],
				failedTests: ["A > b", "A > b"],
			},
			{
				runId: 12,
				sha: "b",
				event: "merge_group",
				createdAt: "2026-10-22T10:00:00Z",
				jobs: [{ attempt: 1, job: job("Integration", "failure", ["Run integration tests"]) }],
				failedTests: ["A > b"],
			},
			{
				runId: 13,
				sha: "c",
				event: "pull_request",
				createdAt: "2026-10-23T10:00:00Z",
				jobs: [{ attempt: 1, job: job("Windows | Gates", "failure", ["Quality gates"]) }],
				failedTests: null,
			},
		],
		14,
	);
	assert.match(rendered, /3 runs failed and then passed on a rerun of the same commit/u);
	assert.match(rendered, /\| 2 \| 2026-10-22 \| A > b \| 11, 12 \|/u);
	assert.match(rendered, /\| 2 \| 2026-10-22 \| Integration \| Run integration tests \|/u);
	assert.match(rendered, /\| 1 \| 2026-10-23 \| Windows \\\| Gates \| Quality gates \|/u);
});
