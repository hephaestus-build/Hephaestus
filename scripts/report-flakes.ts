import { appendFile, mkdir, mkdtemp, readdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { XMLParser } from "fast-xml-parser";

import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

// CI retains JUnit reports for 14 days, so older reruns cannot name their failed tests.
const WINDOW_DAYS = 14;
const DAY_MS = 24 * 60 * 60 * 1000;
// The step of a CI job that uploads its `*-reports-<attempt>` artifact. report-flakes.test.ts keeps
// every such step named this way.
export const REPORT_STEP = /^Retain\b.*\btest\b/u;
// The gate fails because another job failed; it never names a flaky test of its own.
const AGGREGATE_JOBS = new Set(["CI Status Gate"]);

export interface AttemptJob {
	name: string;
	conclusion: string | null;
	/** An attempt lists every job; one it did not rerun keeps its earlier start. */
	startedAt: string | null;
	url: string;
	failedSteps: string[];
	/** Whether the job uploaded its JUnit reports in this attempt. */
	reported: boolean;
}

export interface FlakyJob {
	attempt: number;
	job: AttemptJob;
}

export interface FlakyRun {
	runId: number;
	sha: string;
	event: string;
	createdAt: string;
	jobs: FlakyJob[];
	/** Test cases that failed in the attempts listed in `jobs`, or null when no report was retained. */
	failedTests: string[] | null;
}

/**
 * Jobs that failed in one attempt of a run and then passed in a later attempt of the same commit,
 * once per job at its first failed attempt.
 */
export function flakyJobs(attempts: ReadonlyMap<number, readonly AttemptJob[]>): FlakyJob[] {
	const flaky: FlakyJob[] = [];
	const counted = new Set<string>();
	const ordered = [...attempts.keys()].toSorted((a, b) => a - b);
	for (const [index, attempt] of ordered.entries()) {
		for (const job of attempts.get(attempt) ?? []) {
			if (job.conclusion !== "failure" || AGGREGATE_JOBS.has(job.name) || counted.has(job.name)) {
				continue;
			}
			const passedLater = ordered
				.slice(index + 1)
				.some((later) =>
					(attempts.get(later) ?? []).some(
						(retry) => retry.name === job.name && retry.conclusion === "success",
					),
				);
			if (passedLater) {
				counted.add(job.name);
				flaky.push({ attempt, job });
			}
		}
	}
	return flaky;
}

export function attemptJobs(value: unknown): AttemptJob[] {
	return asArray(value, "jobs").map((entry) => {
		const job = asRecord(entry, "job");
		const steps = job.steps === undefined ? [] : asArray(job.steps, "steps");
		return {
			name: asString(job.name, "job.name"),
			conclusion: job.conclusion === null ? null : asString(job.conclusion, "job.conclusion"),
			startedAt: job.started_at === null ? null : asString(job.started_at, "job.started_at"),
			url: asString(job.html_url, "job.html_url"),
			failedSteps: steps
				.map((step) => asRecord(step, "step"))
				.filter((step) => step.conclusion === "failure")
				.map((step) => asString(step.name, "step.name")),
			reported: steps
				.map((step) => asRecord(step, "step"))
				.some(
					(step) =>
						step.conclusion === "success" && REPORT_STEP.test(asString(step.name, "step.name")),
				),
		};
	});
}

const junit = new XMLParser({
	ignoreAttributes: false,
	attributeNamePrefix: "",
	isArray: (name) => name === "testsuite" || name === "testcase",
});

/** `Class > test` for every failed or errored test case in one JUnit XML report. */
export function failedTestCases(xml: string): string[] {
	const document: unknown = junit.parse(xml);
	if (!isRecord(document)) {
		throw new Error("JUnit report is not an XML document");
	}
	const root = isRecord(document.testsuites) ? document.testsuites : document;
	const suites = root.testsuite === undefined ? [] : asArray(root.testsuite, "testsuite");
	return suites.flatMap((suite) => {
		const record = asRecord(suite, "testsuite");
		const cases = record.testcase === undefined ? [] : asArray(record.testcase, "testcase");
		return cases
			.map((entry) => asRecord(entry, "testcase"))
			.filter((testCase) => testCase.failure !== undefined || testCase.error !== undefined)
			.map((testCase) => {
				const owner = typeof testCase.classname === "string" ? testCase.classname : record.name;
				return `${asString(owner, "testcase classname")} > ${asString(testCase.name, "testcase name")}`;
			});
	});
}

/**
 * The failed tests of the flaky attempts that did not fail again in the run's last attempt. A
 * rerun repeats every failed job, so a test that fails there belongs to a job that never passed.
 */
export function flakyTests(
	failedEarlier: readonly string[],
	failedLast: readonly string[],
): string[] {
	const real = new Set(failedLast);
	return [...new Set(failedEarlier)].filter((name) => !real.has(name));
}

/**
 * Whether the failed tests of the flaky attempts can be told apart from those of jobs that never
 * passed. Each such job that left reports must have run again in the last attempt, failed there and
 * left its reports there too, so that its tests can be subtracted. A job that the last attempt did
 * not rerun keeps the start it had in the attempt before. Otherwise the run's tests stay unknown.
 */
export function attributable(
	attempts: ReadonlyMap<number, readonly AttemptJob[]>,
	flaky: readonly FlakyJob[],
	lastAttempt: number,
): boolean {
	const flakyNames = new Set(flaky.map(({ job }) => job.name));
	const last = attempts.get(lastAttempt) ?? [];
	const previous = attempts.get(lastAttempt - 1) ?? [];
	return [...new Set(flaky.map(({ attempt }) => attempt))].every((attempt) =>
		(attempts.get(attempt) ?? [])
			.filter(
				(job) =>
					job.conclusion === "failure" &&
					job.reported &&
					!AGGREGATE_JOBS.has(job.name) &&
					!flakyNames.has(job.name),
			)
			.every(({ name }) => {
				const rerun = last.find((job) => job.name === name);
				const before = previous.find((job) => job.name === name);
				return (
					rerun?.conclusion === "failure" && rerun.reported && rerun.startedAt !== before?.startedAt
				);
			}),
	);
}

/** Completed runs that needed another attempt, once each: adjacent day windows share their bound. */
export function rerunsOf(pages: readonly unknown[]): Record<string, unknown>[] {
	const seen = new Set<unknown>();
	return pages
		.flatMap((page) => asArray(asRecord(page, "runs page").workflow_runs, "workflow_runs"))
		.map((run) => asRecord(run, "run"))
		.filter((run) => {
			if (typeof run.run_attempt !== "number" || run.run_attempt < 2 || seen.has(run.id)) {
				return false;
			}
			seen.add(run.id);
			return true;
		});
}

/** Report artifacts of one attempt: CI names them `<suite>-reports-<attempt>`. */
export function reportArtifacts(value: unknown, attempt: number): string[] {
	return asArray(value, "artifacts")
		.map((entry) => asRecord(entry, "artifact"))
		.filter(
			(artifact) =>
				artifact.expired === false &&
				asString(artifact.name, "artifact.name").endsWith(`-reports-${attempt}`),
		)
		.map((artifact) => asString(artifact.name, "artifact.name"));
}

function byCount<T extends { count: number; latest: string }>(entries: Map<string, T>) {
	return [...entries].toSorted(
		([, a], [, b]) => b.count - a.count || b.latest.localeCompare(a.latest),
	);
}

function cell(text: string): string {
	return text.replaceAll("|", String.raw`\|`);
}

export function renderFlakes(runs: readonly FlakyRun[], windowDays: number): string {
	const tests = new Map<string, { count: number; latest: string; runs: Set<number> }>();
	const jobs = new Map<string, { count: number; latest: string; steps: Set<string> }>();
	for (const run of runs) {
		for (const name of new Set(run.failedTests)) {
			const entry = tests.get(name) ?? { count: 0, latest: run.createdAt, runs: new Set() };
			entry.count += 1;
			entry.latest = entry.latest > run.createdAt ? entry.latest : run.createdAt;
			entry.runs.add(run.runId);
			tests.set(name, entry);
		}
		for (const { job } of run.jobs) {
			const entry = jobs.get(job.name) ?? { count: 0, latest: run.createdAt, steps: new Set() };
			entry.count += 1;
			entry.latest = entry.latest > run.createdAt ? entry.latest : run.createdAt;
			for (const step of job.failedSteps) {
				entry.steps.add(step);
			}
			jobs.set(job.name, entry);
		}
	}
	return [
		`## Flaky CI (last ${windowDays} days)`,
		"",
		`${runs.length} runs failed and then passed on a rerun of the same commit.`,
		"",
		"### Tests",
		"",
		...(tests.size === 0
			? ["No retained test report names a flaky test."]
			: [
					"| Failures | Latest | Test | Runs |",
					"|---:|---|---|---|",
					...byCount(tests).map(
						([name, entry]) =>
							`| ${entry.count} | ${entry.latest.slice(0, 10)} | ${cell(name)} | ${[...entry.runs].join(", ")} |`,
					),
				]),
		"",
		"### Jobs",
		"",
		"| Failures | Latest | Job | Failed steps |",
		"|---:|---|---|---|",
		...byCount(jobs).map(
			([name, entry]) =>
				`| ${entry.count} | ${entry.latest.slice(0, 10)} | ${cell(name)} | ${cell([...entry.steps].join("; "))} |`,
		),
		"",
	].join("\n");
}

async function api(route: string): Promise<readonly unknown[]> {
	return asArray(parseJson(await output("gh", ["api", "--paginate", "--slurp", route])), "pages");
}

async function failedTestsIn(
	repository: string,
	runId: number,
	names: readonly string[],
	directory: string,
): Promise<string[]> {
	const failed: string[] = [];
	for (const name of names) {
		const target = path.join(directory, name);
		await output("gh", [
			"run",
			"download",
			String(runId),
			"-R",
			repository,
			"-n",
			name,
			"-D",
			target,
		]);
		for (const file of await readdir(target, { recursive: true })) {
			if (file.endsWith(".xml")) {
				failed.push(...failedTestCases(await readFile(path.join(target, file), "utf8")));
			}
		}
	}
	return failed;
}

async function failedTestsOf(
	repository: string,
	runId: number,
	attempts: ReadonlyMap<number, readonly AttemptJob[]>,
	flaky: readonly FlakyJob[],
	lastAttempt: number,
): Promise<string[] | null> {
	const pages = await api(`repos/${repository}/actions/runs/${runId}/artifacts?per_page=100`);
	const artifacts = pages.flatMap((page) =>
		asArray(asRecord(page, "artifact page").artifacts, "artifacts"),
	);
	const earlier = [...new Set(flaky.map(({ attempt }) => attempt))].flatMap((attempt) =>
		reportArtifacts(artifacts, attempt),
	);
	const later = reportArtifacts(artifacts, lastAttempt);
	if (earlier.length === 0 || !attributable(attempts, flaky, lastAttempt)) {
		return null;
	}
	const directory = await mkdtemp(path.join(tmpdir(), "report-flakes-"));
	try {
		return flakyTests(
			await failedTestsIn(repository, runId, earlier, directory),
			await failedTestsIn(repository, runId, later, directory),
		);
	} catch (error) {
		// An expired or deleted report leaves this run's tests unknown, not the whole report.
		process.stderr.write(`Run ${runId}: test reports unreadable (${String(error)})\n`);
		return null;
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

if (import.meta.main) {
	const repository = process.env.GITHUB_REPOSITORY;
	if (repository === undefined || !/^[\w.-]+\/[\w.-]+$/u.test(repository)) {
		throw new Error("GITHUB_REPOSITORY must be owner/repository");
	}
	const now = Date.now();
	const flakyRuns: FlakyRun[] = [];
	// A filtered run listing stops at 1,000 results, so each day is listed on its own.
	const pages: unknown[] = [];
	for (let day = WINDOW_DAYS; day > 0; day -= 1) {
		const from = new Date(now - day * DAY_MS).toISOString();
		const to = new Date(now - (day - 1) * DAY_MS).toISOString();
		pages.push(
			...(await api(
				`repos/${repository}/actions/workflows/cicd.yml/runs?status=completed&per_page=100&created=${encodeURIComponent(`${from}..${to}`)}`,
			)),
		);
	}
	for (const run of rerunsOf(pages)) {
		const runId = run.id;
		const lastAttempt = run.run_attempt;
		if (typeof runId !== "number" || typeof lastAttempt !== "number") {
			throw new Error("Invalid run identifier");
		}
		const attempts = new Map<number, AttemptJob[]>();
		for (let attempt = 1; attempt <= lastAttempt; attempt += 1) {
			const jobPages = await api(
				`repos/${repository}/actions/runs/${runId}/attempts/${attempt}/jobs?per_page=100`,
			);
			attempts.set(
				attempt,
				jobPages.flatMap((page) => attemptJobs(asRecord(page, "jobs page").jobs)),
			);
		}
		const jobs = flakyJobs(attempts);
		if (jobs.length === 0) {
			continue;
		}
		flakyRuns.push({
			runId,
			sha: asString(run.head_sha, "head_sha"),
			event: asString(run.event, "event"),
			createdAt: asString(run.created_at, "created_at"),
			jobs,
			failedTests: await failedTestsOf(repository, runId, attempts, jobs, lastAttempt),
		});
	}
	await mkdir("tmp/ci-metrics", { recursive: true });
	await writeFile(
		"tmp/ci-metrics/flakes.json",
		`${JSON.stringify({ observedAt: new Date(now).toISOString(), windowDays: WINDOW_DAYS, runs: flakyRuns }, null, 2)}\n`,
	);
	const rendered = renderFlakes(flakyRuns, WINDOW_DAYS);
	process.stdout.write(rendered);
	if (process.env.GITHUB_STEP_SUMMARY !== undefined) {
		await appendFile(process.env.GITHUB_STEP_SUMMARY, rendered);
	}
}
