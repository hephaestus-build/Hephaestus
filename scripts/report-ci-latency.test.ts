import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { isFullVerification, latencyBudget, selectLatencyRuns } from "./report-ci-latency.ts";

void test("sampling excludes stale/future, retried, failed and release validation", () => {
	const now = Date.parse("2026-01-30T00:00:00Z");
	const run = {
		event: "pull_request",
		status: "completed",
		conclusion: "success",
		run_attempt: 1,
		head_branch: "feature",
		created_at: "2026-01-29T00:00:00Z",
	};
	const excluded = [
		{ event: "merge_group" },
		{ conclusion: "failure" },
		{ conclusion: "cancelled" },
		{ run_attempt: 2 },
		{ head_branch: "changeset-release/main" },
		{ status: "in_progress" },
		{ created_at: "2025-12-31T00:00:00Z" },
		{ created_at: "2026-01-31T00:00:00Z" },
	];
	assert.deepEqual(
		selectLatencyRuns(
			{ workflow_runs: [run, ...excluded.map((fields) => ({ ...run, ...fields }))] },
			"changeset-release/main",
			now,
		),
		[run],
	);
});

void test("lightweight PRs cannot make full verification's latency budget pass", () => {
	const names = [
		"Test / App Server: Unit and architecture",
		"Quality / Webapp",
		"Quality / Webapp: Stories",
		"Build / Webapp: E2E",
		"Build / App Server: Database",
		"Build / App Server: Generated artifacts",
		"Test / App Server: Integration (providers)",
		"Test / App Server: Integration (application)",
	];
	const jobs = names.map((name) => ({ name }));
	assert.equal(isFullVerification(jobs), true);
	for (const name of names)
		assert.equal(isFullVerification(jobs.filter((job) => job.name !== name)), false, name);
	assert.equal(isFullVerification([{ name: "Quality / Tooling and Docs" }]), false);
});

void test("the target constrains both the median and tail, and insufficient data is not success", () => {
	assert.equal(latencyBudget([]).status, "insufficient-data");
	assert.equal(latencyBudget([1]).status, "insufficient-data");
	assert.equal(latencyBudget(Array.from({ length: 10 }, () => 360)).status, "within-budget");
	assert.equal(latencyBudget(Array.from({ length: 10 }, () => 361)).status, "exceeded");
	const tail = latencyBudget([1, 2, 3, 4, 5, 6, 7, 8, 1800, 1800]);
	assert.equal(tail.p50Seconds, 5.5);
	assert.equal(tail.p90Seconds, 1800);
	assert.equal(tail.status, "exceeded");
	assert.throws(() => latencyBudget([Number.NaN]), /finite/);
});

void test(
	"the CLI saves insufficient-sample evidence before failing",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "ci-latency-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		await mkdir(path.join(directory, ".changeset"));
		await writeFile(path.join(directory, ".changeset/config.json"), '{"baseBranch":"main"}');
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh\nprintf '%s\\n' '{"workflow_runs":[]}'\n`,
			{ mode: 0o755 },
		);
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("./report-ci-latency.ts", import.meta.url))],
			{
				cwd: directory,
				env: {
					...process.env,
					GITHUB_REPOSITORY: "owner/repo",
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				},
				encoding: "utf8",
			},
		);
		assert.equal(result.status, 1, result.stderr);
		const evidence = await readFile(path.join(directory, "tmp/ci-metrics/ci-latency.json"), "utf8");
		assert.match(evidence, /"status": "insufficient-data"/);
		assert.match(evidence, /"runs": \[\]/);
	},
);
