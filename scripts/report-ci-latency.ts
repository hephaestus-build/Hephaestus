import { appendFile, mkdir, writeFile } from "node:fs/promises";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { median, percentile } from "./lib/statistics.ts";
import { summarizeCiTimings } from "./report-ci-timings.ts";

const WINDOW_DAYS = 28;

/** The window of the queue-tier failure share, the tier split's revert criterion. */
const FAILURE_WINDOW_DAYS = 14;
const DAY = 24 * 60 * 60 * 1000;

/**
 * First-attempt successful runs of one event inside the window, newest first. Merge groups always
 * verify a change in full; pull requests run the fast tier (ci-cd.mdx § Two verification tiers).
 */
export function selectLatencyRuns(
	value: unknown,
	now: number,
	event: "merge_group" | "pull_request",
) {
	return asArray(asRecord(value, "runs response").workflow_runs, "workflow_runs")
		.map((run) => asRecord(run, "run"))
		.filter((run) => {
			const created = Date.parse(asString(run.created_at, "created_at"));
			if (!Number.isFinite(created)) {
				throw new Error("Invalid run creation timestamp");
			}
			return (
				run.event === event &&
				run.status === "completed" &&
				run.conclusion === "success" &&
				run.run_attempt === 1 &&
				created <= now &&
				created >= now - WINDOW_DAYS * DAY
			);
		})
		.toSorted((a, b) =>
			asString(b.created_at, "created_at").localeCompare(asString(a.created_at, "created_at")),
		);
}

// The sampling window still holds runs from earlier integration shard layouts.
const INTEGRATION_LAYOUTS = [
	["providers-and-startup", "application"],
	["providers", "runtime-and-startup", "application"],
	["providers", "runtime-and-startup", "practices-and-agent", "application"],
];

export function isFullVerification(jobs: readonly { name: string }[]) {
	const names = new Set(jobs.map((job) => job.name));
	const integrationJobs = [...names].filter((name) =>
		name.startsWith("Test / App Server: Integration ("),
	);
	return (
		[
			"Test / App Server: Unit and architecture",
			"Quality / Webapp: Stories",
			"Build / Webapp: E2E",
			"Build / App Server: Database",
			"Build / App Server: Generated artifacts",
		].every((name) => names.has(name)) &&
		(names.has("Quality / Webapp") || names.has("Quality / Webapp / Gates")) &&
		INTEGRATION_LAYOUTS.some(
			(shards) =>
				shards.length === integrationJobs.length &&
				shards.every((shard) => names.has(`Test / App Server: Integration (${shard})`)),
		)
	);
}

/** A release candidate's run also generates release evidence, which is not verification latency. */
export function isLatencySample(jobs: readonly { name: string }[]) {
	return !jobs.some((job) => job.name === "Release evidence preflight");
}

// The queue tier's job names, past drill names included; ci-contract.test.ts holds them to the
// jobs that carry the tier guard.
export const QUEUE_TIER_JOBS = [
	/^Test \/ App Server: Integration \(/u,
	/^Build \/ App Server: Database$/u,
	/^Build \/ App Server: PostgreSQL drill/u,
	/^Build \/ Webapp: E2E$/u,
	/^Build \/ Extension: E2E$/u,
];

/**
 * A pull request's fast-tier verdict: the server suites ran and no queue-tier job did. Docs-only,
 * duplicate-skipped and `ci:full` runs measure something else.
 */
export function isFastTier(jobs: readonly { name: string }[]) {
	return (
		jobs.some((job) => job.name === "Test / App Server: Unit and architecture") &&
		!jobs.some((job) => QUEUE_TIER_JOBS.some((pattern) => pattern.test(job.name)))
	);
}

/** Fewer completed groups than this cannot support the revert criterion's 10 %. */
export const MINIMUM_GROUPS = 20;

/**
 * The share of completed merge groups that a queue-tier job failed, apart from the groups only a
 * fast-tier job failed. A cancelled group was regrouped by the queue and says nothing.
 */
export function queueTierFailures(
	groups: readonly { conclusion: string; failedJobs: readonly string[] }[],
) {
	const completed = groups.filter(
		(group) => group.conclusion === "success" || group.conclusion === "failure",
	);
	const failed = completed.filter((group) => group.conclusion === "failure");
	const queueTier = failed.filter((group) =>
		group.failedJobs.some((name) => QUEUE_TIER_JOBS.some((pattern) => pattern.test(name))),
	).length;
	return {
		groups: completed.length,
		queueTierFailed: queueTier,
		fastTierFailed: failed.length - queueTier,
		queueTierShare: completed.length < MINIMUM_GROUPS ? null : queueTier / completed.length,
	};
}

export function latencyBudget(values: number[]) {
	if (values.some((value) => !Number.isFinite(value) || value < 0)) {
		throw new Error("Verdict durations must be finite and nonnegative");
	}
	const p50 = median(values);
	const p90 = percentile(values, 0.9);
	let status: "insufficient-data" | "within-budget" | "exceeded";
	if (values.length < 10) {
		status = "insufficient-data";
	} else if (p50 !== null && p90 !== null && p50 <= 360 && p90 <= 420) {
		status = "within-budget";
	} else {
		status = "exceeded";
	}
	return {
		count: values.length,
		p50Seconds: p50,
		p90Seconds: p90,
		p50LimitSeconds: 360,
		p90LimitSeconds: 420,
		status,
	};
}

function seconds(value: number | null): string {
	return value === null ? "Not available" : `${value.toFixed(1)}s`;
}

export function renderLatencyBudget(
	budget: ReturnType<typeof latencyBudget>,
	pullRequests: readonly number[] = [],
	failures: ReturnType<typeof queueTierFailures> = queueTierFailures([]),
): string {
	const share =
		failures.queueTierShare === null
			? `Not available (fewer than ${MINIMUM_GROUPS} groups)`
			: `${(failures.queueTierShare * 100).toFixed(1)} %`;
	return [
		"## Full-verification latency (advisory)",
		"",
		`Status: **${budget.status}** (${budget.count}/10 qualifying runs).`,
		"",
		"| Metric | Observed | Target |",
		"|---|---:|---:|",
		`| Median | ${seconds(budget.p50Seconds)} | ${budget.p50LimitSeconds}s |`,
		`| p90 | ${seconds(budget.p90Seconds)} | ${budget.p90LimitSeconds}s |`,
		"",
		"## Pull-request latency (fast tier, observed)",
		"",
		`Median ${seconds(median([...pullRequests]))}, p90 ${seconds(percentile([...pullRequests], 0.9))}, over ${pullRequests.length} server pull-request runs that ran only the fast tier.`,
		"",
		`## Merge groups failed by the queue tier, last ${FAILURE_WINDOW_DAYS} days`,
		"",
		`${share} of ${failures.groups} completed groups failed a queue-tier job; ${failures.fastTierFailed} more failed only fast-tier jobs. Revert criterion: above 10 %.`,
		"",
		"Historical timings include runner waiting and earlier commits; this is not a verdict on the current change.",
		"Inspect the retained ci-latency.json run timelines to distinguish queue delays from execution before changing CI.",
		"",
	].join("\n");
}

async function listRuns(repository: string, event: string, since: number) {
	const created = encodeURIComponent(`>=${new Date(since).toISOString()}`);
	const pages = asArray(
		parseJson(
			await output("gh", [
				"api",
				"--paginate",
				"--slurp",
				`repos/${repository}/actions/workflows/cicd.yml/runs?event=${event}&status=completed&created=${created}&per_page=100`,
			]),
		),
		"run pages",
	);
	return {
		workflow_runs: pages.flatMap((page) =>
			asArray(asRecord(page, "run page").workflow_runs, "workflow_runs"),
		),
	};
}

async function jobsOf(repository: string, run: Record<string, unknown>, attempt: unknown) {
	const { id } = run;
	if (typeof id !== "number" || !Number.isSafeInteger(id) || id < 1) {
		throw new Error("Invalid run ID");
	}
	const pages = asArray(
		parseJson(
			await output("gh", [
				"api",
				"--paginate",
				"--slurp",
				`repos/${repository}/actions/runs/${id}/attempts/${String(attempt)}/jobs?per_page=100`,
			]),
		),
		"job pages",
	).map((page) => asRecord(page, "job page"));
	return {
		total_count: pages[0]?.total_count,
		jobs: pages.flatMap((page) => asArray(page.jobs, "jobs")),
	};
}

function failedJobNames(jobs: readonly unknown[]): string[] {
	return jobs
		.map((entry) => asRecord(entry, "job"))
		.filter((job) => job.conclusion === "failure" || job.conclusion === "cancelled")
		.map((job) => asString(job.name, "job name"));
}

/** The latest ten qualifying verdicts of one event, as run summaries. */
async function sample(
	repository: string,
	listing: unknown,
	now: number,
	event: "merge_group" | "pull_request",
	qualifies: (jobs: readonly { name: string }[]) => boolean,
) {
	const summaries = [];
	for (const run of selectLatencyRuns(listing, now, event)) {
		const summary = summarizeCiTimings(run, await jobsOf(repository, run, 1));
		if (isLatencySample(summary.jobs) && qualifies(summary.jobs)) {
			summaries.push(summary);
			if (summaries.length === 10) {
				break;
			}
		}
	}
	return summaries;
}

if (import.meta.main) {
	const repository = process.env.GITHUB_REPOSITORY;
	if (repository === undefined || !/^[\w.-]+\/[\w.-]+$/u.test(repository)) {
		throw new Error("GITHUB_REPOSITORY must be owner/repository");
	}
	const now = Date.now();
	const groups = await listRuns(repository, "merge_group", now - WINDOW_DAYS * DAY);
	const pullRequests = await listRuns(repository, "pull_request", now - WINDOW_DAYS * DAY);
	const full = await sample(repository, groups, now, "merge_group", isFullVerification);
	const fast = await sample(repository, pullRequests, now, "pull_request", isFastTier);
	const budget = latencyBudget(full.map((run) => run.verdictSeconds));

	const recent = groups.workflow_runs
		.map((run) => asRecord(run, "run"))
		.filter(
			(run) =>
				Date.parse(asString(run.created_at, "created_at")) >= now - FAILURE_WINDOW_DAYS * DAY,
		);
	const outcomes = [];
	for (const run of recent) {
		const conclusion = asString(run.conclusion, "conclusion");
		let failedJobs: string[] = [];
		if (conclusion === "failure") {
			const { jobs } = await jobsOf(repository, run, run.run_attempt);
			failedJobs = failedJobNames(jobs);
		}
		outcomes.push({ conclusion, failedJobs });
	}
	const failures = queueTierFailures(outcomes);

	const report = {
		observedAt: new Date(now).toISOString(),
		windowDays: WINDOW_DAYS,
		failureWindowDays: FAILURE_WINDOW_DAYS,
		budget,
		failures,
		fullVerification: full,
		pullRequests: fast,
	};
	await mkdir("tmp/ci-metrics", { recursive: true });
	await writeFile("tmp/ci-metrics/ci-latency.json", `${JSON.stringify(report, null, 2)}\n`);
	const rendered = renderLatencyBudget(
		budget,
		fast.map((run) => run.verdictSeconds),
		failures,
	);
	process.stdout.write(rendered);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	}
	if (budget.status === "exceeded") {
		process.stdout.write(
			"::warning title=Full-verification latency target exceeded::Historical full-verification latency exceeds the target; inspect the job summary and retained run timelines.\n",
		);
	} else if (budget.status === "insufficient-data") {
		process.stdout.write(
			"::notice title=Full-verification latency baseline incomplete::Fewer than ten qualifying runs; no performance verdict yet.\n",
		);
	}
	if (failures.queueTierShare !== null && failures.queueTierShare > 0.1) {
		process.stdout.write(
			"::warning title=Queue tier catches too much::More than 10 % of merge groups in 14 days failed a queue-tier job; ci-cd.mdx § Two verification tiers names the revert.\n",
		);
	}
}
