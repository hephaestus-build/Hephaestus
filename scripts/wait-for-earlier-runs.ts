/**
 * Holds a workflow run until the earlier runs of the same workflow have ended. The runs that pass it
 * go one at a time, in the order they were queued, and none is cancelled: a concurrency group keeps
 * one pending run and cancels the others. docs/contributor/ci-cd.mdx § One run at a time, in order.
 *
 * Usage, in a job before the work: `node scripts/wait-for-earlier-runs.ts`, with `GH_TOKEN`.
 * It writes `turn=true` when the run may go, or `turn=false` when it waited too long.
 */
import { appendFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";

import { requiredEnv } from "./lib/env.ts";
import { retryTransient } from "./lib/github.ts";
import { asArray, asRecord, asString, isRecord, parseJson } from "./lib/json.ts";
import { output } from "./lib/process.ts";

export interface Run {
	id: number;
	/** When the run's latest attempt was queued. A re-run keeps its creation time, so this orders re-runs too. */
	queuedAt: string;
	status: string;
	headSha: string;
	/** The pull request of a pull-request run. GitHub names none for a fork's run, and the run then counts as current. */
	pullRequest?: number;
}

// `waiting` is a run that waits for an environment approval, and `action_required` one that waits for
// a maintainer to approve a fork's workflow. Neither may block the queue, so only these statuses do.
const ACTIVE = new Set(["requested", "pending", "queued", "in_progress"]);

const isEarlier = (run: Run, me: Run) => {
	const difference = Date.parse(run.queuedAt) - Date.parse(me.queuedAt);
	return difference < 0 || (difference === 0 && run.id < me.id);
};

/** The active runs queued before `me`, oldest first. */
const earlierActive = (me: Run, runs: readonly Run[]) =>
	runs
		.filter((run) => run.id !== me.id && ACTIVE.has(run.status) && isEarlier(run, me))
		.toSorted((a, b) => (isEarlier(a, b) ? -1 : 1));

/**
 * A pull-request run is superseded when its pull request's head moved on. `head` is that head, or
 * `undefined` when it is unknown; an unknown head never supersedes a run.
 */
const isCurrent = (run: Run, head: string | undefined) =>
	head === undefined || head === run.headSha;

/** Every earlier run that `me` waits for: active, queued before it, and not superseded. */
export function runsAhead(
	me: Run,
	runs: readonly Run[],
	headOf: (pullRequest: number) => string | undefined,
): Run[] {
	return earlierActive(me, runs).filter((run) =>
		isCurrent(run, run.pullRequest === undefined ? undefined : headOf(run.pullRequest)),
	);
}

export interface Waiting {
	me: Run;
	/** The runs of this workflow that could still be active. */
	listRuns: () => Promise<Run[]>;
	headOf: (pullRequest: number) => Promise<string | undefined>;
	limitMs: number;
	pollMs: number;
	now?: () => number;
	sleep?: (milliseconds: number) => Promise<unknown>;
	log?: (line: string) => void;
}

/**
 * Waits until no earlier run is ahead, or until `limitMs` passes. Returns the runs still ahead.
 * Each poll reads the run list once and the pull-request heads only up to the first run that blocks.
 */
export async function waitForTurn(waiting: Waiting): Promise<Run[]> {
	const { me, listRuns, headOf, limitMs, pollMs } = waiting;
	const now = waiting.now ?? Date.now;
	const sleep = waiting.sleep ?? delay;
	const log = waiting.log ?? console.info;
	const deadline = now() + limitMs;
	for (;;) {
		const runs = await listRuns();
		const candidates = earlierActive(me, runs);
		const heads = new Map<number, string | undefined>();
		const headOfRun = async (run: Run) => {
			if (run.pullRequest === undefined) {
				return;
			}
			if (!heads.has(run.pullRequest)) {
				heads.set(run.pullRequest, await headOf(run.pullRequest));
			}
			return heads.get(run.pullRequest);
		};
		let blocking: Run | undefined;
		for (const run of candidates) {
			if (isCurrent(run, await headOfRun(run))) {
				blocking = run;
				break;
			}
		}
		if (blocking === undefined) {
			return [];
		}
		if (now() >= deadline) {
			const ahead: Run[] = [];
			for (const run of candidates) {
				if (isCurrent(run, await headOfRun(run))) {
					ahead.push(run);
				}
			}
			return ahead;
		}
		log(`Waiting for run ${String(blocking.id)}, queued at ${blocking.queuedAt}`);
		await sleep(pollMs);
	}
}

const api = async (path: string): Promise<unknown> =>
	parseJson(await retryTransient(async () => output("gh", ["api", path])));

function toRun(value: unknown): Run {
	const run = asRecord(value, "workflow run");
	const { id } = run;
	if (typeof id !== "number") {
		throw new TypeError("workflow run id must be a number");
	}
	// A run of several pull requests, as a push to a branch that several pull requests share, has no
	// single head to compare, so it is never superseded.
	const pulls = asArray(run.pull_requests ?? [], "workflow run pull requests");
	const pullRequest = pulls.length === 1 ? asRecord(pulls[0], "pull request").number : undefined;
	return {
		id,
		queuedAt: asString(run.run_started_at ?? run.created_at, "workflow run run_started_at"),
		status: asString(run.status, "workflow run status"),
		headSha: asString(run.head_sha, "workflow run head_sha"),
		...(typeof pullRequest === "number" ? { pullRequest } : {}),
	};
}

async function main() {
	const repository = requiredEnv(process.env, "GITHUB_REPOSITORY");
	const runId = requiredEnv(process.env, "GITHUB_RUN_ID");
	const limitMinutes = Number(process.env.WAIT_LIMIT_MINUTES ?? "300");
	if (!Number.isFinite(limitMinutes) || limitMinutes < 0) {
		throw new Error("WAIT_LIMIT_MINUTES must be a number of minutes");
	}
	const current = asRecord(await api(`repos/${repository}/actions/runs/${runId}`), "this run");
	const me = toRun(current);
	const workflow = String(current.workflow_id);
	// The list filters on creation time, which a re-run keeps. A day of runs covers every run that
	// can still be active, unless someone re-runs a run older than that.
	const since = new Date(Date.parse(me.queuedAt) - 24 * 3_600_000)
		.toISOString()
		.replace(/\.\d{3}Z$/u, "Z");
	const listRuns = async () => {
		const runs: Run[] = [];
		for (let page = 1; ; page += 1) {
			const answer = asRecord(
				await api(
					`repos/${repository}/actions/workflows/${workflow}/runs?created=%3E%3D${since}&per_page=100&page=${String(page)}`,
				),
				"workflow runs",
			);
			const listed = asArray(answer.workflow_runs, "workflow_runs").map(toRun);
			runs.push(...listed);
			if (listed.length < 100) {
				return runs;
			}
		}
	};
	const headOf = async (pullRequest: number) => {
		try {
			const pull = asRecord(
				await api(`repos/${repository}/pulls/${String(pullRequest)}`),
				"pull request",
			);
			return asString(asRecord(pull.head, "pull request head").sha, "pull request head sha");
		} catch (error) {
			// A deleted pull request has no head to compare; its run then counts as current.
			if (isRecord(error) && typeof error.stderr === "string" && /HTTP 404/u.test(error.stderr)) {
				return;
			}
			throw error;
		}
	};
	const ahead = await waitForTurn({
		me,
		listRuns,
		headOf,
		limitMs: limitMinutes * 60_000,
		pollMs: 60_000,
	});
	const turn = ahead.length === 0;
	const summary = turn
		? "No earlier run of this workflow is active, so this run goes now.\n"
		: `Queued too long: after ${String(limitMinutes)} minutes, ${String(ahead.length)} earlier run(s) were still ahead (${ahead.map((run) => String(run.id)).join(", ")}). This run did not do its work. Re-run it to queue again.\n`;
	if (!turn) {
		console.info(`::warning::${summary.trim()}`);
	}
	await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `turn=${String(turn)}\n`);
	const step = process.env.GITHUB_STEP_SUMMARY;
	if (step !== undefined && step !== "") {
		await appendFile(step, `## Queue\n\n${summary}`);
	}
	console.info(summary.trim());
}

if (import.meta.main) {
	await main();
}
