import { appendFile, mkdir, writeFile } from "node:fs/promises";

import { canonicalCiJobName } from "./lib/ci-job-name.ts";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";
import { median, percentile } from "./lib/statistics.ts";
import { summarizeCiTimings } from "./report-ci-timings.ts";

const WINDOW_DAYS = 28;

/** The window of the queue-tier failure share, the tier split's revert criterion. */
const FAILURE_WINDOW_DAYS = 14;
const CANCELLATION_WINDOW_DAYS = 7;
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
// The numeric legacy layout had eight shards, independently of the current partition.
const historicalNumericShards = 8;
const INTEGRATION_LAYOUTS = [
	["providers-and-startup", "application"],
	["providers", "runtime-and-startup", "application"],
	["providers", "runtime-and-startup", "practices-and-agent", "application"],
	["providers", "runtime-and-startup", "practices-and-agent", "application", "review"],
	["review", "1", "2", "3", "4", "5", "6", "7", "8"],
];

export function isFullVerification(jobs: readonly { name: string }[]) {
	const names = new Set(jobs.map((job) => canonicalCiJobName(job.name)));
	const integrationJobs = [...names].filter((name) =>
		name.startsWith("Server tests / Integration ("),
	);
	return (
		(names.has("Server tests / Unit and architecture") ||
			(names.has("Server tests / Unit") && names.has("Server tests / Architecture"))) &&
		[
			"Quality / Webapp stories",
			"Browser tests / Webapp",
			"Build / Database migrations",
			"Build / Generated API",
		].every((name) => names.has(name)) &&
		(names.has("Quality / Webapp") || names.has("Quality / Webapp / Gates")) &&
		INTEGRATION_LAYOUTS.some(
			(shards) =>
				shards.length === integrationJobs.length &&
				shards.every((shard) =>
					names.has(
						`Server tests / Integration (${/^\d+$/u.test(shard) ? `${shard} of ${historicalNumericShards}` : shard})`,
					),
				),
		)
	);
}

/** A release candidate's run also generates release evidence, which is not verification latency. */
export function isLatencySample(jobs: readonly { name: string }[]) {
	return !jobs.some((job) => job.name === "Release evidence preflight");
}

// Database and PostgreSQL drills can also run in the PR fast tier. These jobs cannot.
const QUEUE_ONLY_JOBS = [
	/^Server tests \/ Integration \((?!review\))/u,
	/^Browser tests \/ Webapp$/u,
	/^Browser tests \/ Extension$/u,
];

// The merge group's queue-tier failure classes, including database checks that also run on a
// migration PR. ci-contract.test.ts ties this list to the jobs that carry the tier guard.
export const QUEUE_TIER_JOBS = [
	...QUEUE_ONLY_JOBS,
	/^Build \/ Database migrations$/u,
	/^Build \/ PostgreSQL drill/u,
];

/**
 * A PR that exercised server tests without numbered integration shards or browser suites.
 * Migration-only PRs exercise review instead of Unit; their database checks stay in this cohort.
 */
export function isFastTier(jobs: readonly { name: string }[]) {
	const names = jobs.map((job) => canonicalCiJobName(job.name));
	return (
		names.some((name) =>
			[
				"Server tests / Unit",
				"Server tests / Unit and architecture",
				"Server tests / Integration (review)",
			].includes(name),
		) && !names.some((name) => QUEUE_ONLY_JOBS.some((pattern) => pattern.test(name)))
	);
}

/** Fewer completed groups than this cannot support the revert criterion's 10 %. */
export const MINIMUM_GROUPS = 20;

// Since the cancel job became one called workflow, its check name ends with " / Cancel this run".
const verdictJob =
	/^(?:CI Status Gate|Report unsuccessful merge group|Fail fast: )|(?:^| \/ )Stop the merge group early(?: \/ Cancel this run)?$/u;

/**
 * The share of completed merge groups that a queue-tier job failed, apart from the groups only a
 * fast-tier job failed. A cancelled group counts only when a worker recorded a failure.
 */
export function queueTierFailures(
	groups: readonly { conclusion: string; failedJobs: readonly string[] }[],
) {
	const completed = groups.filter(
		(group) =>
			group.conclusion === "success" ||
			group.conclusion === "failure" ||
			group.failedJobs.some((name) => !verdictJob.test(canonicalCiJobName(name))),
	);
	const failed = completed.filter((group) => group.conclusion !== "success");
	const queueTier = failed.filter((group) =>
		group.failedJobs.some((name) =>
			QUEUE_TIER_JOBS.some((pattern) => pattern.test(canonicalCiJobName(name))),
		),
	).length;
	return {
		groups: completed.length,
		queueTierFailed: queueTier,
		fastTierFailed: failed.length - queueTier,
		queueTierShare: completed.length < MINIMUM_GROUPS ? null : queueTier / completed.length,
	};
}

interface QueueRemoval {
	head: string;
	reason: string | null;
	at: string;
	pullRequest: number;
}
type CancellationCause = "worker_failure" | "predecessor_drop" | "manual" | "timeout" | "unknown";
interface Cancellation {
	cause: CancellationCause;
	failedJobs?: string[];
	removal?: QueueRemoval;
}

/** Queue branch suffixes identify the exact predecessor tree, not the PR's mutable head. */
export function queueAncestors(
	run: Record<string, unknown>,
	runs: readonly Record<string, unknown>[],
) {
	const chain = [run];
	const seen = new Set<unknown>([run.head_sha]);
	for (let current = run; ;) {
		const parent = /\/pr-\d+-(?<parent>[a-f0-9]{40})$/u.exec(
			asString(current.head_branch, "queue branch"),
		)?.groups?.parent;
		const ancestor = runs.find(
			(candidate) => candidate.head_sha === parent && parent !== undefined,
		);
		if (!ancestor || seen.has(parent)) {
			return chain;
		}
		seen.add(parent);
		chain.push(ancestor);
		current = ancestor;
	}
}

/** Absence of a failed job alone cannot establish a queue reset or manual cancellation. */
export function classifyCancellation(
	run: Record<string, unknown>,
	jobs: readonly Record<string, unknown>[],
	ancestors: readonly Record<string, unknown>[],
	removals: readonly QueueRemoval[],
): Cancellation {
	const failedJobs = failedJobNames(jobs).filter(
		(name) => !verdictJob.test(canonicalCiJobName(name)),
	);
	if (failedJobs.length > 0) {
		return { cause: "worker_failure", failedJobs };
	}
	if (jobs.some((job) => job.conclusion === "timed_out")) {
		return { cause: "timeout" };
	}
	const start = Date.parse(asString(run.created_at, "created_at"));
	const cancelledEnds = jobs
		.filter(
			(job) =>
				job.conclusion === "cancelled" &&
				job.completed_at !== null &&
				job.completed_at !== undefined,
		)
		.map((job) => Date.parse(asString(job.completed_at, "completed_at")));
	const end =
		cancelledEnds.length > 0
			? Math.min(...cancelledEnds)
			: Date.parse(asString(run.updated_at, "updated_at"));
	if (![start, end, ...cancelledEnds].every(Number.isFinite)) {
		throw new Error("Invalid cancellation timestamp");
	}
	const matching = (head: unknown) =>
		removals.find(
			(removal) =>
				removal.head === head && Date.parse(removal.at) >= start && Date.parse(removal.at) <= end,
		);
	const own = matching(run.head_sha);
	if (own?.reason === "manual") {
		return { cause: "manual", removal: own };
	}
	if (own?.reason === "stack_invalidated") {
		return { cause: "predecessor_drop", removal: own };
	}
	for (const ancestor of ancestors.slice(1)) {
		const removal = matching(ancestor.head_sha);
		if (removal && removal.reason !== null && removal.reason !== "merged") {
			return { cause: "predecessor_drop", removal };
		}
	}
	return { cause: "unknown" };
}

export function cancellationBudget(
	groups: readonly { conclusion: string; cancellation?: Cancellation }[],
) {
	const causes = { worker_failure: 0, predecessor_drop: 0, manual: 0, timeout: 0, unknown: 0 };
	for (const group of groups) {
		if (group.conclusion === "cancelled") {
			causes[group.cancellation?.cause ?? "unknown"] += 1;
		}
	}
	const cancelled = Object.values(causes).reduce((sum, count) => sum + count, 0);
	const nonOwnFailure = cancelled - causes.worker_failure;
	const share = groups.length === 0 ? null : nonOwnFailure / groups.length;
	let status = "within-budget";
	if (share === null) {
		status = "insufficient-data";
	} else if (share > 0.1) {
		status = "exceeded";
	} else if (causes.unknown > 0) {
		status = "incomplete";
	}
	return {
		groups: groups.length,
		cancelled,
		causes,
		nonOwnFailure,
		share,
		limit: 0.1,
		status,
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
	cancellations: ReturnType<typeof cancellationBudget> = cancellationBudget([]),
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
		`## Merge-group cancellations, last ${CANCELLATION_WINDOW_DAYS} days (first attempts)`,
		"",
		`Status: **${cancellations.status}**. ${cancellations.cancelled} of ${cancellations.groups} completed groups were cancelled.`,
		"",
		"| Cause | Cancelled groups |",
		"|---|---:|",
		`| Native worker failure | ${cancellations.causes.worker_failure} |`,
		`| Predecessor drop | ${cancellations.causes.predecessor_drop} |`,
		`| Manual removal | ${cancellations.causes.manual} |`,
		`| Timeout | ${cancellations.causes.timeout} |`,
		`| Unknown | ${cancellations.causes.unknown} |`,
		"",
		`Non-own-failure cancellations: ${cancellations.nonOwnFailure}/${cancellations.groups} (${cancellations.share === null ? "Not available" : `${(100 * cancellations.share).toFixed(1)} %`}). Target: ≤ 10 %. Unknown causes count against the target.`,
		"",
		"Historical timings include runner waiting and earlier commits; this is not a verdict on the current change.",
		"Inspect the retained ci-latency.json run timelines to distinguish queue delays from execution before changing CI.",
		"",
	].join("\n");
}

async function batches<T, U>(values: readonly T[], read: (value: T) => Promise<U>) {
	const result: U[] = [];
	for (let start = 0; start < values.length; start += 8) {
		result.push(...(await Promise.all(values.slice(start, start + 8).map(read))));
	}
	return result;
}

function runId(run: Record<string, unknown>) {
	const { id } = run;
	if (typeof id !== "number" || !Number.isSafeInteger(id) || id < 1) {
		throw new Error("Invalid run ID");
	}
	return id;
}

async function listRuns(repository: string, event: string, since: number, now: number) {
	const days = [];
	for (let day = Math.floor(since / DAY) * DAY; day <= now; day += DAY) {
		days.push(day);
	}
	const daily = await batches(days, async (day) => {
		const created = encodeURIComponent(
			`${new Date(day).toISOString()}..${new Date(day + DAY - 1).toISOString()}`,
		);
		// A rerun can be in progress even though its first attempt is already complete.
		const pages = asArray(
			parseJson(
				await output("gh", [
					"api",
					"--paginate",
					"--slurp",
					`repos/${repository}/actions/workflows/cicd.yml/runs?event=${event}&created=${created}&per_page=100`,
				]),
			),
			"run pages",
		).map((page) => asRecord(page, "run page"));
		const runs = pages.flatMap((page) =>
			asArray(page.workflow_runs, "workflow_runs").map((run) => asRecord(run, "run")),
		);
		if (
			runs.length >= 1000 ||
			pages.some((page) => typeof page.total_count === "number" && page.total_count >= 1000)
		) {
			throw new Error(
				"Run listing reached the 1,000-run daily API cap; no complete-window verdict",
			);
		}
		return runs;
	});
	return {
		workflow_runs: [
			...new Map(
				daily
					.flat()
					.filter((run) => {
						const created = Date.parse(asString(run.created_at, "created_at"));
						if (!Number.isFinite(created)) {
							throw new Error("Invalid run creation timestamp");
						}
						return created >= since && created <= now;
					})
					.map((run) => [runId(run), run]),
			).values(),
		],
	};
}

async function firstAttempt(repository: string, run: Record<string, unknown>) {
	const id = runId(run);
	if (run.run_attempt === 1) {
		return run;
	}
	if (
		typeof run.run_attempt !== "number" ||
		!Number.isSafeInteger(run.run_attempt) ||
		run.run_attempt < 2
	) {
		throw new Error("Invalid run attempt");
	}
	const first = asRecord(
		parseJson(await output("gh", ["api", `repos/${repository}/actions/runs/${id}/attempts/1`])),
		"first attempt",
	);
	if (first.id !== id || first.run_attempt !== 1 || first.event !== "merge_group") {
		throw new Error("Mismatched first attempt");
	}
	return first;
}

async function queueRemovals(repository: string, pullRequest: number, since: number) {
	const [owner, name] = repository.split("/");
	if (owner === undefined || name === undefined) {
		throw new Error("Repository must be owner/repository");
	}
	const removals: QueueRemoval[] = [];
	let cursor: string | undefined;
	for (;;) {
		const args = [
			"api",
			"graphql",
			"-f",
			`query=query($owner:String!,$name:String!,$number:Int!,$cursor:String) {
			repository(owner:$owner,name:$name) { pullRequest(number:$number) {
				timelineItems(last:100,before:$cursor,itemTypes:[REMOVED_FROM_MERGE_QUEUE_EVENT]) {
					nodes { ... on RemovedFromMergeQueueEvent { createdAt reason beforeCommit { oid } } }
					pageInfo { hasPreviousPage startCursor }
				}
			} }
		}`,
			"-f",
			`owner=${owner}`,
			"-f",
			`name=${name}`,
			"-F",
			`number=${pullRequest}`,
		];
		if (cursor !== undefined) {
			args.push("-f", `cursor=${cursor}`);
		}
		const response = asRecord(parseJson(await output("gh", args)), "queue events response");
		if (response.errors !== undefined) {
			throw new Error("Queue timeline API returned errors");
		}
		const repo = asRecord(asRecord(response.data, "data").repository, "repository");
		const timeline = asRecord(
			asRecord(repo.pullRequest, "pullRequest").timelineItems,
			"timelineItems",
		);
		const events = asArray(timeline.nodes, "queue events").map((value) => {
			const entry = asRecord(value, "queue removal");
			const at = asString(entry.createdAt, "removal time");
			if (!Number.isFinite(Date.parse(at))) {
				throw new Error("Invalid queue removal timestamp");
			}
			return {
				at,
				reason: entry.reason === null ? null : asString(entry.reason, "removal reason"),
				head:
					entry.beforeCommit === null
						? ""
						: asString(asRecord(entry.beforeCommit, "beforeCommit").oid, "beforeCommit oid"),
				pullRequest,
			};
		});
		removals.push(...events);
		const page = asRecord(timeline.pageInfo, "queue page info");
		if (typeof page.hasPreviousPage !== "boolean") {
			throw new Error("Invalid queue pagination");
		}
		if (!page.hasPreviousPage || events.some((entry) => Date.parse(entry.at) < since)) {
			return removals;
		}
		const previous = asString(page.startCursor, "queue cursor");
		if (previous === cursor) {
			throw new Error("Queue pagination did not advance");
		}
		cursor = previous;
	}
}

async function jobsOf(repository: string, run: Record<string, unknown>, attempt: unknown) {
	const id = runId(run);
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
	const jobs = pages.flatMap((page) =>
		asArray(page.jobs, "jobs").map((job) => asRecord(job, "job")),
	);
	if (pages[0]?.total_count !== jobs.length) {
		throw new Error("Incomplete attempt job list");
	}
	if (jobs.some((job) => job.run_id !== id || job.run_attempt !== attempt)) {
		throw new Error("Mismatched attempt jobs");
	}
	return { total_count: jobs.length, jobs };
}

export function failedJobNames(jobs: readonly unknown[]): string[] {
	return jobs
		.map((entry) => asRecord(entry, "job"))
		.filter((job) => job.conclusion === "failure")
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
	const listedGroups = await listRuns(repository, "merge_group", now - WINDOW_DAYS * DAY, now);
	const groups = {
		workflow_runs: await batches(listedGroups.workflow_runs, async (run) =>
			firstAttempt(repository, run),
		),
	};
	const pullRequests = await listRuns(repository, "pull_request", now - WINDOW_DAYS * DAY, now);
	const full = await sample(repository, groups, now, "merge_group", isFullVerification);
	const fast = await sample(repository, pullRequests, now, "pull_request", isFastTier);
	const budget = latencyBudget(full.map((run) => run.verdictSeconds));

	const recent = groups.workflow_runs.filter(
		(run) =>
			run.status === "completed" &&
			Date.parse(asString(run.created_at, "created_at")) >= now - FAILURE_WINDOW_DAYS * DAY,
	);
	const removalCache = new Map<number, Promise<QueueRemoval[]>>();
	const outcomes = await batches(recent, async (run) => {
		const conclusion = asString(run.conclusion, "conclusion");
		const attemptJobs =
			conclusion === "failure" || conclusion === "cancelled"
				? await jobsOf(repository, run, 1)
				: { jobs: [] };
		const { jobs } = attemptJobs;
		let cancellation: Cancellation | undefined;
		if (
			conclusion === "cancelled" &&
			Date.parse(asString(run.created_at, "created_at")) >= now - CANCELLATION_WINDOW_DAYS * DAY
		) {
			cancellation =
				jobs.length === 0 ? { cause: "unknown" } : classifyCancellation(run, jobs, [], []);
			if (cancellation.cause === "unknown" && jobs.length > 0) {
				const ancestors = queueAncestors(run, groups.workflow_runs);
				const removals: QueueRemoval[] = [];
				// Read nearest first. A merged predecessor is main history, not part of this queue.
				// Each of the eight outcome workers has at most one timeline request in flight.
				for (const ancestor of ancestors) {
					const number = /\/pr-(?<number>\d+)-[a-f0-9]{40}$/u.exec(
						asString(ancestor.head_branch, "queue branch"),
					)?.groups?.number;
					if (number === undefined) {
						break;
					}
					const pr = Number(number);
					let cached = removalCache.get(pr);
					if (cached === undefined) {
						cached = queueRemovals(repository, pr, now - CANCELLATION_WINDOW_DAYS * DAY);
						removalCache.set(pr, cached);
					}
					const events = await cached;
					removals.push(...events);
					cancellation = classifyCancellation(run, jobs, ancestors, removals);
					if (
						cancellation.cause !== "unknown" ||
						events.some(
							(event) =>
								event.head === ancestor.head_sha &&
								event.reason === "merged" &&
								Date.parse(event.at) <= Date.parse(asString(run.created_at, "created_at")),
						)
					) {
						break;
					}
				}
			}
		}
		return {
			runId: runId(run),
			attempt: 1,
			createdAt: asString(run.created_at, "created_at"),
			conclusion,
			failedJobs: failedJobNames(jobs),
			...(cancellation === undefined ? {} : { cancellation }),
		};
	});
	const failures = queueTierFailures(outcomes);
	const cancellationOutcomes = outcomes.filter(
		(run) => Date.parse(run.createdAt) >= now - CANCELLATION_WINDOW_DAYS * DAY,
	);
	const cancellations = cancellationBudget(cancellationOutcomes);

	const report = {
		observedAt: new Date(now).toISOString(),
		windowDays: WINDOW_DAYS,
		failureWindowDays: FAILURE_WINDOW_DAYS,
		budget,
		failures,
		cancellationWindowDays: CANCELLATION_WINDOW_DAYS,
		cancellations,
		groupOutcomes: outcomes,
		fullVerification: full,
		pullRequests: fast,
	};
	await mkdir("tmp/ci-metrics", { recursive: true });
	await writeFile("tmp/ci-metrics/ci-latency.json", `${JSON.stringify(report, null, 2)}\n`);
	const rendered = renderLatencyBudget(
		budget,
		fast.map((run) => run.verdictSeconds),
		failures,
		cancellations,
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
	if (cancellations.status === "exceeded" || cancellations.status === "incomplete") {
		process.stdout.write(
			"::warning title=Merge-group cancellation guard::Non-own-failure cancellations exceed 10 percent or have unknown causes; inspect the seven-day counts and retained evidence.\n",
		);
	}
	if (failures.queueTierShare !== null && failures.queueTierShare > 0.1) {
		process.stdout.write(
			"::warning title=Queue tier catches too much::More than 10 % of merge groups in 14 days failed a queue-tier job; ci-cd.mdx § Two verification tiers names the revert.\n",
		);
	}
}
