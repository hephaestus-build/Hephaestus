import { appendFile, mkdir, writeFile } from "node:fs/promises";

import { versionBranch } from "./dispatch-version-pr-ci.ts";
import { asArray, asRecord, asString, parseJson, readJsonFile } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { summarizeCiTimings } from "./report-ci-timings.ts";

const WINDOW_DAYS = 28;

export function selectLatencyRuns(value: unknown, releaseBranch: string, now: number) {
	return asArray(asRecord(value, "runs response").workflow_runs, "workflow_runs")
		.map((run) => asRecord(run, "run"))
		.filter((run) => {
			const created = Date.parse(asString(run.created_at, "created_at"));
			if (!Number.isFinite(created)) throw new Error("Invalid run creation timestamp");
			return (
				run.event === "pull_request" &&
				run.status === "completed" &&
				run.conclusion === "success" &&
				run.run_attempt === 1 &&
				run.head_branch !== releaseBranch &&
				created <= now &&
				created >= now - WINDOW_DAYS * 24 * 60 * 60 * 1000
			);
		})
		.toSorted((a, b) =>
			asString(b.created_at, "created_at").localeCompare(asString(a.created_at, "created_at")),
		);
}

export function isFullVerification(jobs: readonly { name: string }[]) {
	const names = new Set(jobs.map((job) => job.name));
	return (
		[
			"Test / App Server: Unit and architecture",
			"Quality / Webapp: Stories",
			"Build / Webapp: E2E",
			"Build / App Server: Database",
			"Build / App Server: Generated artifacts",
		].every((name) => names.has(name)) &&
		(names.has("Quality / Webapp") || names.has("Quality / Webapp / Gates")) &&
		jobs.filter((job) => job.name.startsWith("Test / App Server: Integration (")).length === 2
	);
}

export function latencyBudget(values: number[]) {
	if (values.some((value) => !Number.isFinite(value) || value < 0))
		throw new Error("Verdict durations must be finite and nonnegative");
	const sorted = values.toSorted((a, b) => a - b);
	const middle = Math.floor(sorted.length / 2);
	const upper = sorted[middle] ?? 0;
	const p50 =
		sorted.length === 0
			? null
			: sorted.length % 2 === 0
				? ((sorted[middle - 1] ?? 0) + upper) / 2
				: upper;
	const p90 = sorted[Math.ceil(sorted.length * 0.9) - 1] ?? null;
	return {
		count: values.length,
		p50Seconds: p50,
		p90Seconds: p90,
		p50LimitSeconds: 360,
		p90LimitSeconds: 420,
		status:
			values.length < 10
				? "insufficient-data"
				: p50 !== null && p90 !== null && p50 <= 360 && p90 <= 420
					? "within-budget"
					: "exceeded",
	};
}

export function renderLatencyBudget(budget: ReturnType<typeof latencyBudget>): string {
	const seconds = (value: number | null) =>
		value === null ? "Not available" : `${value.toFixed(1)}s`;
	return [
		"## Pull-request latency (advisory)",
		"",
		`Status: **${budget.status}** (${budget.count}/10 qualifying runs).`,
		"",
		"| Metric | Observed | Target |",
		"|---|---:|---:|",
		`| Median | ${seconds(budget.p50Seconds)} | ${budget.p50LimitSeconds}s |`,
		`| p90 | ${seconds(budget.p90Seconds)} | ${budget.p90LimitSeconds}s |`,
		"",
		"Historical timings include runner waiting and earlier commits; this is not a verdict on the current change.",
		"Inspect the retained ci-latency.json run timelines to distinguish queue delays from execution before changing CI.",
		"",
	].join("\n");
}

if (import.meta.main) {
	const repository = process.env.GITHUB_REPOSITORY;
	if (!repository || !/^[\w.-]+\/[\w.-]+$/.test(repository))
		throw new Error("GITHUB_REPOSITORY must be owner/repository");
	const releaseBranch = versionBranch(await readJsonFile(".changeset/config.json"));
	const now = Date.now();
	const since = new Date(now - WINDOW_DAYS * 24 * 60 * 60 * 1000).toISOString();
	const listing = asRecord(
		parseJson(
			await output("gh", [
				"api",
				`repos/${repository}/actions/workflows/cicd.yml/runs?event=pull_request&status=completed&created=${encodeURIComponent(`>=${since}`)}&per_page=100`,
			]),
		),
		"runs response",
	);
	const listedRuns = asArray(listing.workflow_runs, "workflow_runs").map((run) =>
		asRecord(run, "run"),
	);
	const summaries = [];
	let fullCount = 0;
	for (const run of selectLatencyRuns(listing, releaseBranch, now)) {
		const id = run.id;
		if (typeof id !== "number" || !Number.isSafeInteger(id) || id < 1)
			throw new Error("Invalid run ID");
		const pages = asArray(
			parseJson(
				await output("gh", [
					"api",
					"--paginate",
					"--slurp",
					`repos/${repository}/actions/runs/${id}/attempts/1/jobs?per_page=100`,
				]),
			),
			"job pages",
		).map((page) => asRecord(page, "job page"));
		const jobs = pages.flatMap((page) => asArray(page.jobs, "jobs"));
		const summary = summarizeCiTimings(run, { total_count: pages[0]?.total_count, jobs });
		const cohort = isFullVerification(summary.jobs) ? "full-verification" : "partial-verification";
		summaries.push({ ...summary, cohort });
		if (cohort === "full-verification" && ++fullCount === 10) break;
	}
	const budget = latencyBudget(
		summaries.filter((run) => run.cohort === "full-verification").map((run) => run.verdictSeconds),
	);
	// Counts describe the bounded listing, not a repository-wide reliability rate.
	const report = {
		observedAt: new Date(now).toISOString(),
		windowDays: WINDOW_DAYS,
		listingLimit: 100,
		listed: listedRuns.length,
		failed: listedRuns.filter((run) => run.conclusion === "failure").length,
		cancelled: listedRuns.filter((run) => run.conclusion === "cancelled").length,
		budget,
		runs: summaries,
	};
	await mkdir("tmp/ci-metrics", { recursive: true });
	await writeFile("tmp/ci-metrics/ci-latency.json", `${JSON.stringify(report, null, 2)}\n`);
	process.stdout.write(`${JSON.stringify(budget, null, 2)}\n`);
	const rendered = renderLatencyBudget(budget);
	process.stdout.write(rendered);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined)
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	if (budget.status === "exceeded")
		process.stdout.write(
			"::warning title=PR latency target exceeded::Historical PR latency exceeds the target; inspect the job summary and retained run timelines.\n",
		);
	else if (budget.status === "insufficient-data")
		process.stdout.write(
			"::notice title=PR latency baseline incomplete::Fewer than ten qualifying runs; no performance verdict yet.\n",
		);
}
