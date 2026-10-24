import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
	isFullVerification,
	isLatencySample,
	latencyBudget,
	queueTierFailures,
	renderLatencyBudget,
	selectLatencyRuns,
} from "./report-ci-latency.ts";

void test("sampling takes first-attempt successful merge groups inside the window", () => {
	const now = Date.parse("2026-01-30T00:00:00Z");
	const run = {
		event: "merge_group",
		status: "completed",
		conclusion: "success",
		run_attempt: 1,
		head_branch: "gh-readonly-queue/main/pr-12-abc",
		created_at: "2026-01-29T00:00:00Z",
	};
	// A pull request runs only the fast tier, and a push repeats a verified tree.
	const excluded = [
		{ event: "pull_request" },
		{ event: "push" },
		{ conclusion: "failure" },
		{ conclusion: "cancelled" },
		{ run_attempt: 2 },
		{ status: "in_progress" },
		{ created_at: "2025-12-31T00:00:00Z" },
		{ created_at: "2026-01-31T00:00:00Z" },
	];
	assert.deepEqual(
		selectLatencyRuns(
			{ workflow_runs: [run, ...excluded.map((fields) => ({ ...run, ...fields }))] },
			now,
			"merge_group",
		),
		[run],
	);
	// The pull-request cohort samples the same way, from its own event.
	const pullRequest = { ...run, event: "pull_request", head_branch: "feature" };
	assert.deepEqual(selectLatencyRuns({ workflow_runs: [run, pullRequest] }, now, "pull_request"), [
		pullRequest,
	]);
});

void test("a release candidate's evidence run is not a latency sample", () => {
	assert.equal(isLatencySample([{ name: "Detect changes" }]), true);
	assert.equal(
		isLatencySample([{ name: "Detect changes" }, { name: "Release evidence preflight" }]),
		false,
	);
});

void test("the revert criterion counts groups a queue-tier job failed, apart from fast-tier failures", () => {
	const groups = [
		{ conclusion: "success", failedJobs: [] },
		{ conclusion: "success", failedJobs: [] },
		{
			conclusion: "failure",
			failedJobs: ["Test / App Server: Integration (providers)", "CI Status Gate"],
		},
		{ conclusion: "failure", failedJobs: ["Build / App Server: PostgreSQL drills"] },
		// A fast-tier failure, and a hung prerequisite, are not the queue tier's catch.
		{ conclusion: "failure", failedJobs: ["Security / Dependencies, secrets, and policy"] },
		{ conclusion: "failure", failedJobs: ["Detect changes", "CI Status Gate"] },
		// The queue regrouped this one.
		{ conclusion: "cancelled", failedJobs: [] },
	];
	assert.deepEqual(queueTierFailures(groups), {
		groups: 6,
		queueTierFailed: 2,
		fastTierFailed: 2,
		queueTierShare: 2 / 6,
	});
	assert.equal(queueTierFailures([]).queueTierShare, null);
});

const verificationJobs = [
	"Test / App Server: Unit and architecture",
	"Quality / Webapp",
	"Quality / Webapp: Stories",
	"Build / Webapp: E2E",
	"Build / App Server: Database",
	"Build / App Server: Generated artifacts",
];
function integration(...shards: string[]) {
	return shards.map((shard) => `Test / App Server: Integration (${shard})`);
}

void test("lightweight runs cannot make full verification's latency budget pass", () => {
	for (const shards of [
		["providers", "runtime-and-startup", "practices-and-agent", "application"],
		["providers", "runtime-and-startup", "application"],
		["providers-and-startup", "application"],
	]) {
		const names = [...verificationJobs, ...integration(...shards)];
		for (const webappName of ["Quality / Webapp", "Quality / Webapp / Gates"]) {
			const jobs = names.map((name) => ({ name: name === "Quality / Webapp" ? webappName : name }));
			assert.equal(isFullVerification(jobs), true, shards.join(","));
			for (const { name } of jobs) {
				// The four-shard layout without its fourth shard is the historical three-shard layout.
				const historical =
					shards.length === 4 && name === "Test / App Server: Integration (practices-and-agent)";
				assert.equal(isFullVerification(jobs.filter((job) => job.name !== name)), historical, name);
			}
		}
	}
	assert.equal(isFullVerification([{ name: "Quality / Tooling and Docs" }]), false);
});

void test("only a complete integration shard layout counts as full verification", () => {
	for (const shards of [
		["providers", "application", "unexpected"],
		["providers-and-startup", "providers", "application"],
		["providers-and-startup", "application", "runtime-and-startup"],
		["providers", "runtime-and-startup", "application", "unexpected"],
		["providers", "runtime-and-startup", "practices-and-agent", "application", "unexpected"],
	]) {
		const jobs = [...verificationJobs, ...integration(...shards)].map((name) => ({ name }));
		assert.equal(isFullVerification(jobs), false, shards.join(","));
	}
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
	assert.throws(() => latencyBudget([Number.NaN]), /finite/u);
});

void test(
	"the CLI reports insufficient evidence without failing but rejects malformed API data",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "ci-latency-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		await mkdir(path.join(directory, ".changeset"));
		await writeFile(path.join(directory, ".changeset/config.json"), '{"baseBranch":"main"}');
		await writeFile(
			path.join(directory, "gh"),
			// `gh api --paginate --slurp` prints one array of pages.
			`#!/bin/sh\nprintf '%s\\n' '[{"workflow_runs":[]}]'\n`,
			{ mode: 0o755 },
		);
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("report-ci-latency.ts", import.meta.url))],
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
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /::notice title=Full-verification latency baseline incomplete/u);
		const evidence = await readFile(path.join(directory, "tmp/ci-metrics/ci-latency.json"), "utf8");
		assert.match(evidence, /"status": "insufficient-data"/u);
		assert.match(evidence, /"fullVerification": \[\]/u);
		assert.match(evidence, /"queueTierShare": null/u);
		await writeFile(path.join(directory, "gh"), "#!/bin/sh\necho '[{}]'\n", { mode: 0o755 });
		const invalid = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("report-ci-latency.ts", import.meta.url))],
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
		assert.notEqual(invalid.status, 0);
		assert.match(invalid.stderr, /workflow_runs/u);
	},
);

void test("latency summaries preserve exceeded targets and distinguish absent evidence", () => {
	assert.match(
		renderLatencyBudget(latencyBudget(Array.from({ length: 10 }, () => 900))),
		/\*\*exceeded\*\*/u,
	);
	assert.match(renderLatencyBudget(latencyBudget([])), /Not available/u);
	assert.match(
		renderLatencyBudget(latencyBudget(Array.from({ length: 10 }, () => 300))),
		/\*\*within-budget\*\*/u,
	);
});
