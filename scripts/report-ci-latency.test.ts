import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import {
	cancellationBudget,
	classifyCancellation,
	queueAncestors,
	failedJobNames,
	isFastTier,
	isFullVerification,
	isLatencySample,
	latencyBudget,
	queueTierFailures,
	renderLatencyBudget,
	selectLatencyRuns,
} from "./report-ci-latency.ts";

function queueTimelinePage(nodes: unknown[], hasPreviousPage: boolean, startCursor: string) {
	return JSON.stringify({
		data: {
			repository: {
				pullRequest: { timelineItems: { nodes, pageInfo: { hasPreviousPage, startCursor } } },
			},
		},
	});
}

void test("a cancelled integration sibling does not turn a unit failure into a queue-tier catch", () => {
	const failedJobs = failedJobNames([
		{ name: "Test (unit) / App Server: Unit and architecture", conclusion: "failure" },
		{ name: "Test (providers) / App Server: Integration (providers)", conclusion: "cancelled" },
	]);
	assert.equal(queueTierFailures([{ conclusion: "failure", failedJobs }]).queueTierFailed, 0);
	const integrationFailure = failedJobNames([
		{ name: "Test (providers) / App Server: Integration (providers)", conclusion: "failure" },
	]);
	assert.equal(
		queueTierFailures([{ conclusion: "failure", failedJobs: integrationFailure }]).queueTierFailed,
		1,
	);
});

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

void test("the pull-request cohort is the server fast tier", () => {
	const unit = { name: "Test / App Server: Unit and architecture" };
	assert.equal(isFastTier([unit, { name: "Quality / App Server / Gates" }]), true);
	// Docs-only and duplicate-skipped runs have no server suites; ci:full adds the queue tier.
	assert.equal(isFastTier([{ name: "Quality / Tooling and Docs / Gates" }]), false);
	assert.equal(isFastTier([unit, { name: "Test / App Server: Integration (providers)" }]), false);
	assert.equal(
		isFastTier([unit, { name: "Test (review) / App Server: Integration (review)" }]),
		true,
		"the review shard runs in the fast tier",
	);
	const callerUnit = { name: "Test (unit) / App Server: Unit and architecture" };
	assert.equal(isFastTier([callerUnit]), true);
	assert.equal(
		isFastTier([callerUnit, { name: "Test (providers) / App Server: Integration (providers)" }]),
		false,
	);
});

void test("migration PR latency includes database checks while full-suite runs stay excluded", () => {
	const database = [{ name: "Build / Database migrations" }, { name: "Build / PostgreSQL drills" }];
	const review = { name: "Server tests / Integration (review)" };
	const unit = { name: "Server tests / Unit" };
	assert.equal(isFastTier([review, ...database]), true, "migration-only PRs have no Unit job");
	assert.equal(isFastTier([unit, review, ...database]), true, "mixed migration/source PRs");
	assert.equal(isFastTier(database), false, "a run that exercised no server test is not sampled");
	for (const name of [
		"Server tests / Integration (1 of 8)",
		"Browser tests / Webapp",
		"Browser tests / Extension",
	]) {
		assert.equal(isFastTier([review, ...database, { name }]), false, name);
	}
	assert.equal(
		queueTierFailures([{ conclusion: "failure", failedJobs: database.map((job) => job.name) }])
			.queueTierFailed,
		1,
		"database failures still count in the merge-group revert criterion",
	);
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
	// Below the minimum the share is not a verdict yet.
	assert.deepEqual(queueTierFailures(groups), {
		groups: 6,
		queueTierFailed: 2,
		fastTierFailed: 2,
		queueTierShare: null,
	});
	const enough = [
		...groups,
		...Array.from({ length: 14 }, () => ({ conclusion: "success", failedJobs: [] })),
	];
	assert.equal(queueTierFailures(enough).queueTierShare, 2 / 20);
	assert.equal(
		queueTierFailures([
			{
				conclusion: "failure",
				failedJobs: ["Test (providers) / App Server: Integration (providers)"],
			},
		]).queueTierFailed,
		1,
	);
});

void test("native worker failures survive fail-fast cancellation; regrouping and reporter-only failures do not", () => {
	const groups = [
		...Array.from({ length: 20 }, () => ({ conclusion: "success", failedJobs: [] })),
		{
			conclusion: "cancelled",
			failedJobs: ["Server tests / Integration (1 of 8)", "CI Status Gate"],
		},
		{ conclusion: "cancelled", failedJobs: ["Security / Dependencies, secrets and policy"] },
		{ conclusion: "cancelled", failedJobs: [] },
		{ conclusion: "cancelled", failedJobs: ["CI Status Gate"] },
		{ conclusion: "cancelled", failedJobs: ["Server tests / Stop the merge group early"] },
	];
	assert.deepEqual(queueTierFailures(groups), {
		groups: 22,
		queueTierFailed: 1,
		fastTierFailed: 1,
		queueTierShare: 1 / 22,
	});
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
		["review", "1", "2", "3", "4", "5", "6", "7", "8"],
		["providers", "runtime-and-startup", "practices-and-agent", "application", "review"],
		["providers", "runtime-and-startup", "practices-and-agent", "application"],
		["providers", "runtime-and-startup", "application"],
		["providers-and-startup", "application"],
	]) {
		const names = [...verificationJobs, ...integration(...shards)];
		for (const webappName of ["Quality / Webapp", "Quality / Webapp / Gates"]) {
			const jobs = names.map((name) => ({ name: name === "Quality / Webapp" ? webappName : name }));
			assert.equal(isFullVerification(jobs), true, shards.join(","));
			for (const { name } of jobs) {
				// Each layout without its newest shard is the historical layout before it.
				const historical =
					(shards.length === 5 && name === "Test / App Server: Integration (review)") ||
					(shards.length === 4 && name === "Test / App Server: Integration (practices-and-agent)");
				assert.equal(isFullVerification(jobs.filter((job) => job.name !== name)), historical, name);
			}
		}
	}
	assert.equal(isFullVerification([{ name: "Quality / Tooling and Docs" }]), false);
	const current = [
		...verificationJobs,
		...integration("review", "1", "2", "3", "4", "5", "6", "7", "8"),
	].map((name) => ({
		name:
			name === "Test / App Server: Unit and architecture"
				? "Test (unit) / App Server: Unit and architecture"
				: name.replace(
						/^Test \/ App Server: Integration \((?<shard>[^)]+)\)$/u,
						"Test ($<shard>) / App Server: Integration ($<shard>)",
					),
	}));
	assert.equal(isFullVerification(current), true);
});

void test("only a complete integration shard layout counts as full verification", () => {
	for (const shards of [
		["providers", "application", "unexpected"],
		["providers-and-startup", "providers", "application"],
		["providers-and-startup", "application", "runtime-and-startup"],
		["providers", "runtime-and-startup", "application", "unexpected"],
		["providers", "runtime-and-startup", "practices-and-agent", "application", "unexpected"],
		[
			"providers",
			"runtime-and-startup",
			"practices-and-agent",
			"application",
			"review",
			"unexpected",
		],
		["review", "1", "2", "3", "4", "5", "6", "7"],
		["review", "1", "2", "3", "4", "5", "6", "7", "8", "9"],
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
					GITHUB_STEP_SUMMARY: undefined,
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
					GITHUB_STEP_SUMMARY: undefined,
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

void test(
	"the CLI retains first-attempt failure evidence while a later rerun is in progress",
	{
		skip: process.platform === "win32",
	},
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "ci-native-failure-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		const timestamp = new Date().toISOString();
		const runs = JSON.stringify([
			{
				workflow_runs: [
					{
						id: 1,
						event: "merge_group",
						status: "in_progress",
						conclusion: null,
						run_attempt: 2,
						created_at: timestamp,
					},
				],
			},
		]);
		const jobs = JSON.stringify([
			{
				total_count: 2,
				jobs: [
					{
						run_id: 1,
						run_attempt: 1,
						name: "Server tests / Integration (1 of 8)",
						conclusion: "failure",
					},
					{ run_id: 1, run_attempt: 1, name: "CI Status Gate", conclusion: "cancelled" },
				],
			},
		]);
		const calls = path.join(directory, "calls.log");
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh
printf '%s\\n' "$*" >> "$CI_REPORT_API_LOG"
case "$*" in
  *event=merge_group*) printf '%s\\n' '${runs}' ;;
  */runs/1/attempts/1) printf '%s\\n' '{"id":1,"event":"merge_group","run_attempt":1,"status":"completed","conclusion":"cancelled","created_at":"${timestamp}"}' ;;
  */runs/1/attempts/1/jobs*) printf '%s\\n' '${jobs}' ;;
  *) printf '%s\\n' '[{"workflow_runs":[]}]' ;;
esac
`,
			{ mode: 0o755 },
		);
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("report-ci-latency.ts", import.meta.url))],
			{
				cwd: directory,
				encoding: "utf8",
				env: {
					...process.env,
					GITHUB_REPOSITORY: "owner/repo",
					GITHUB_STEP_SUMMARY: undefined,
					CI_REPORT_API_LOG: calls,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				},
			},
		);
		assert.equal(result.status, 0, result.stderr);
		const apiCalls = await readFile(calls, "utf8");
		assert.match(apiCalls, /runs\/1\/attempts\/1\/jobs/u);
		assert.doesNotMatch(apiCalls, /attempts\/2/u);
		const report: unknown = JSON.parse(
			await readFile(path.join(directory, "tmp/ci-metrics/ci-latency.json"), "utf8"),
		);
		assert.ok(typeof report === "object" && report !== null && "failures" in report);
		assert.ok("cancellations" in report);
		assert.deepEqual(report.cancellations, {
			groups: 1,
			cancelled: 1,
			causes: { worker_failure: 1, predecessor_drop: 0, manual: 0, timeout: 0, unknown: 0 },
			nonOwnFailure: 0,
			share: 0,
			limit: 0.1,
			status: "within-budget",
		});
		assert.deepEqual(report.failures, {
			groups: 1,
			queueTierFailed: 1,
			fastTierFailed: 0,
			queueTierShare: null,
		});
	},
);

void test("the recorded 183 first attempts retain all 70 cancellations and their causes", async () => {
	const fixture = asRecord(
		parseJson(await readFile(new URL("fixtures/ci-cancellations.json", import.meta.url), "utf8")),
		"fixture",
	);
	const runs = asArray(fixture.runs, "runs").map((value) => asRecord(value, "run"));
	const removals = asArray(fixture.removals, "removals").map((value) => {
		const entry = asRecord(value, "removal");
		assert.ok(typeof entry.pullRequest === "number");
		return {
			head: asString(entry.head, "head"),
			reason: asString(entry.reason, "reason"),
			at: asString(entry.at, "at"),
			pullRequest: entry.pullRequest,
		};
	});
	const groups = runs
		.filter((run) => run.counted === true)
		.map((run) => ({
			conclusion: asString(run.conclusion, "conclusion"),
			...(run.conclusion === "cancelled"
				? {
						cancellation: classifyCancellation(
							run,
							asArray(run.jobs, "jobs").map((job) => asRecord(job, "job")),
							// Failed workers need no queue metadata; the fixture keeps only their native evidence.
							run.head_branch === undefined ? [] : queueAncestors(run, runs),
							removals,
						),
					}
				: {}),
		}));
	groups.push(
		...asArray(fixture.successfulRunIds, "successful runs")
			.filter((id) => !runs.some((run) => run.counted === true && run.id === id))
			.map(() => ({ conclusion: "success" })),
	);
	assert.deepEqual(cancellationBudget(groups), {
		groups: 183,
		cancelled: 70,
		causes: { worker_failure: 60, predecessor_drop: 9, manual: 1, timeout: 0, unknown: 0 },
		nonOwnFailure: 10,
		share: 10 / 183,
		limit: 0.1,
		status: "within-budget",
	});
	const descendant = runs.find((run) => run.id === 43_018_554_661);
	assert.ok(descendant);
	const chain = queueAncestors(descendant, runs);
	assert.ok(
		chain.some((run) => run.id === 43_015_530_065 && run.conclusion === "success"),
		"a successful intermediate group remains in the invalidated chain",
	);
});

void test("cancellation attribution requires exact trees and causal timestamps", () => {
	const run = {
		head_sha: "a".repeat(40),
		head_branch: `gh-readonly-queue/main/pr-1-${"b".repeat(40)}`,
		created_at: "2026-11-13T10:00:00Z",
		updated_at: "2026-11-13T10:10:00Z",
	};
	const jobs = [
		{ name: "CI Status Gate", conclusion: "failure" },
		{
			name: "Build / Generated API",
			conclusion: "cancelled",
			completed_at: "2026-11-13T10:05:00Z",
		},
	];
	const manual = {
		head: run.head_sha,
		reason: "manual",
		at: "2026-11-13T10:04:00Z",
		pullRequest: 1,
	};
	assert.equal(classifyCancellation(run, jobs, [run], [manual]).cause, "manual");
	for (const removal of [
		{ ...manual, head: "c".repeat(40) },
		{ ...manual, at: "2026-11-13T09:59:00Z" },
		{ ...manual, at: "2026-11-13T10:06:00Z" },
	]) {
		assert.equal(classifyCancellation(run, jobs, [run], [removal]).cause, "unknown");
	}
	assert.equal(
		classifyCancellation(run, [{ name: "Build / Generated API", conclusion: "timed_out" }], [], [])
			.cause,
		"timeout",
	);
	assert.equal(
		classifyCancellation(
			run,
			[{ name: "Build / Stop the merge group early", conclusion: "failure" }],
			[],
			[],
		).cause,
		"unknown",
	);
});

void test("the seven-day cancellation guard keeps unknowns and enforces the inclusive 10 percent limit", () => {
	const cancelled = { conclusion: "cancelled", cancellation: { cause: "manual" as const } };
	const other = Array.from({ length: 9 }, () => ({ conclusion: "failure" }));
	assert.equal(cancellationBudget([cancelled, ...other]).status, "within-budget");
	assert.equal(cancellationBudget([cancelled, ...other.slice(1)]).status, "exceeded");
	assert.equal(cancellationBudget([]).status, "insufficient-data");
	const unknown = cancellationBudget([{ conclusion: "cancelled" }, ...other]);
	assert.equal(unknown.status, "incomplete");
	assert.equal(unknown.nonOwnFailure, 1);
	assert.equal(unknown.causes.unknown, 1);
	assert.match(
		renderLatencyBudget(latencyBudget([]), [], queueTierFailures([]), unknown),
		/Unknown \| 1/u,
	);
});

void test(
	"the CLI reads paginated queue evidence and flags the existing report",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "ci-queue-cause-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		const created = new Date(Date.now() - 300_000).toISOString();
		const removed = new Date(Date.now() - 120_000).toISOString();
		const completed = new Date(Date.now() - 60_000).toISOString();
		const head = "a".repeat(40);
		const runs = JSON.stringify([
			{
				workflow_runs: [
					{
						id: 1,
						event: "merge_group",
						status: "completed",
						conclusion: "cancelled",
						run_attempt: 1,
						head_sha: head,
						head_branch: `gh-readonly-queue/main/pr-1-${"b".repeat(40)}`,
						created_at: created,
						updated_at: completed,
					},
				],
			},
		]);
		const jobs = JSON.stringify([
			{
				total_count: 1,
				jobs: [
					{
						run_id: 1,
						run_attempt: 1,
						name: "Build / Generated API",
						conclusion: "cancelled",
						completed_at: completed,
					},
				],
			},
		]);
		const newest = queueTimelinePage([], true, "older");
		const older = queueTimelinePage(
			[{ createdAt: removed, reason: "manual", beforeCommit: { oid: head } }],
			false,
			"oldest",
		);
		const calls = path.join(directory, "calls.log");
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh
printf '%s\\n' "$*" >> "$CI_REPORT_API_LOG"
case "$*" in
  *event=merge_group*) printf '%s\\n' '${runs}' ;;
  */runs/1/attempts/1/jobs*) printf '%s\\n' '${jobs}' ;;
  *cursor=older*) printf '%s\\n' '${older}' ;;
  *graphql*) printf '%s\\n' '${newest}' ;;
  *) printf '%s\\n' '[{"workflow_runs":[]}]' ;;
esac
`,
			{ mode: 0o755 },
		);
		const invoke = () =>
			spawnSync(
				process.execPath,
				[fileURLToPath(new URL("report-ci-latency.ts", import.meta.url))],
				{
					cwd: directory,
					encoding: "utf8",
					env: {
						...process.env,
						GITHUB_REPOSITORY: "owner/repo",
						GITHUB_STEP_SUMMARY: undefined,
						CI_REPORT_API_LOG: calls,
						PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					},
				},
			);
		const result = invoke();
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /::warning title=Merge-group cancellation guard/u);
		const report = asRecord(
			parseJson(await readFile(path.join(directory, "tmp/ci-metrics/ci-latency.json"), "utf8")),
			"report",
		);
		assert.deepEqual(report.cancellations, {
			groups: 1,
			cancelled: 1,
			causes: { worker_failure: 0, predecessor_drop: 0, manual: 1, timeout: 0, unknown: 0 },
			nonOwnFailure: 1,
			share: 1,
			limit: 0.1,
			status: "exceeded",
		});
		assert.match(await readFile(calls, "utf8"), /cursor=older/u);
		// A queue branch can lead through hundreds of already merged groups. Stop at main.
		const ancestorHeads = Array.from({ length: 101 }, (_, index) =>
			index === 0 ? "b".repeat(40) : index.toString(16).padStart(40, "0"),
		);
		const old = new Date(Date.now() - 20 * 24 * 60 * 60 * 1000).toISOString();
		const ancestors = ancestorHeads.slice(0, -1).map((sha, index) => {
			const parent = ancestorHeads[index + 1];
			assert.ok(parent !== undefined);
			return {
				id: index + 2,
				event: "merge_group",
				status: "completed",
				conclusion: "cancelled",
				run_attempt: 1,
				head_sha: sha,
				head_branch: `gh-readonly-queue/main/pr-${index + 2}-${parent}`,
				created_at: old,
				updated_at: old,
			};
		});
		const original = asRecord(asArray(parseJson(runs), "pages")[0], "page");
		const longChain = JSON.stringify([
			{ workflow_runs: [...asArray(original.workflow_runs, "runs"), ...ancestors] },
		]);
		const noReason = queueTimelinePage(
			[{ createdAt: removed, reason: null, beforeCommit: { oid: head } }],
			false,
			"unknown",
		);
		const merged = queueTimelinePage(
			[
				{
					createdAt: new Date(Date.parse(created) - 60_000).toISOString(),
					reason: "merged",
					beforeCommit: { oid: ancestorHeads[0] },
				},
			],
			false,
			"merged",
		);
		await writeFile(calls, "");
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh
printf '%s\\n' "$*" >> "$CI_REPORT_API_LOG"
case "$*" in
  *event=merge_group*) printf '%s\\n' '${longChain}' ;;
  */runs/1/attempts/1/jobs*) printf '%s\\n' '${jobs}' ;;
  *number=1*) printf '%s\\n' '${noReason}' ;;
  *number=2*) printf '%s\\n' '${merged}' ;;
  *graphql*) exit 99 ;;
  *) printf '%s\\n' '[{"workflow_runs":[]}]' ;;
esac
`,
			{ mode: 0o755 },
		);
		const bounded = invoke();
		assert.equal(bounded.status, 0, bounded.stderr);
		assert.match(bounded.stdout, /Unknown \| 1/u);
		const boundedCalls = await readFile(calls, "utf8");
		assert.deepEqual(
			boundedCalls.match(/number=\d+/gu),
			["number=1", "number=2"],
			"only the cancelled group and its nearest merged predecessor need timeline reads",
		);
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh
case "$*" in
  *event=merge_group*) printf '%s\\n' '${runs}' ;;
  */jobs*) printf '%s\\n' '[{"total_count":0,"jobs":[]}]' ;;
  *graphql*) exit 99 ;;
  *) printf '%s\\n' '[{"workflow_runs":[]}]' ;;
esac
`,
			{ mode: 0o755 },
		);
		const missingJobs = invoke();
		assert.equal(missingJobs.status, 0, missingJobs.stderr);
		assert.match(missingJobs.stdout, /Unknown \| 1/u);
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh\nprintf '%s\\n' '[{"workflow_runs":[],"total_count":1000}]'\n`,
			{ mode: 0o755 },
		);
		const capped = invoke();
		assert.notEqual(capped.status, 0);
		assert.match(capped.stderr, /1,000-run daily API cap/u);
	},
);
