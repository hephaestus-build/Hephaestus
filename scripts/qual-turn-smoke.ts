/**
 * The conversation-turn smoke: asks one Ask Hephaestus question as a synthetic member and passes only when the turn ran
 * in a conversation sandbox and completed. CI proves turns against the packaged JAR alone, so this is the check that a
 * deployed release answers at all.
 *
 *   node scripts/qual-staging-hold.ts -- node scripts/qual-turn-smoke.ts --target staging \
 *     --mentor-url https://<host>/api/workspaces/<slug>/mentor --token-file <file> [--minutes 5]
 *   node scripts/qual-staging-hold.ts -- node scripts/qual-turn-smoke.ts --target staging --await-workspace <id>
 *
 * The token file holds a member's bearer token; it is read, never printed. Where no token may leave a browser, the
 * member posts the question in the signed-in app and --await-workspace judges the first turn that the workspace gets
 * after the smoke starts. The turn is judged from its rows:
 * the mentor_turn ends DONE, its TURN run ends COMPLETED, a conversation sandbox held the conversation while it ran, at
 * least one model call was recorded for it, its holder renewed the run's lease at least once, and the server's longest
 * context read stayed within 20 s. The last two catch a holder that loses its lease even when a later attempt ends.
 */
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { parseArgs } from "node:util";

import { submitTurn } from "./lib/capacity-chat.ts";
import { COMMON, commitOf, commonFrom, sleep } from "./lib/qual-args.ts";
import { Evidence, RULES } from "./lib/qual-evidence.ts";
import { type RowOf, scrapeAll, type Shape, sqlRows } from "./lib/qual-state.ts";

const USAGE =
	"node scripts/qual-turn-smoke.ts --target k3d|staging (--mentor-url <https://host/api/workspaces/<slug>/mentor> --token-file <file> [--question <text>] | --await-workspace <id> [--await-minutes 10]) [--minutes 5]";
const { values } = parseArgs({
	options: {
		...COMMON,
		"mentor-url": { type: "string" },
		"token-file": { type: "string" },
		question: {
			type: "string",
			default: "In one sentence: what does this workspace's practice profile show?",
		},
		minutes: { type: "string", default: "5" },
		"await-workspace": { type: "string" },
		"await-minutes": { type: "string", default: "10" },
	},
});
const common = commonFrom(values, USAGE);
const { cluster } = common;
const mentorUrl = values["mentor-url"];
const tokenFile = values["token-file"];
const awaitWorkspace =
	values["await-workspace"] === undefined ? null : Number(values["await-workspace"]);
if (
	awaitWorkspace === null
		? mentorUrl === undefined || tokenFile === undefined
		: !Number.isSafeInteger(awaitWorkspace)
) {
	throw new Error(`give --mentor-url and --token-file, or --await-workspace <id>\n${USAGE}`);
}

const TURN = {
	id: "string",
	conversation_id: "string",
	status: "string",
	attempt: "number",
	run_id: "string?",
	failure_cause: "string?",
	failed_call: "string?",
	started_at: "string?",
	finished_at: "string?",
} as const satisfies Shape;
type TurnRow = RowOf<typeof TURN>;

const RUN = {
	id: "string",
	kind: "string",
	state: "string",
	attempt: "number",
	lease_epoch: "number",
	leased_until: "string?",
	stop_reason: "string?",
} as const satisfies Shape;
type RunRow = RowOf<typeof RUN>;

/** The context route of the sandbox gateway, as the server's HTTP timer names it. */
const CONTEXT_URI = "/internal/agent/runs/{runId}/context";
/** A turn's context read answers within this, or the holder's lease renewals wait behind it. */
const CONTEXT_BOUND_SECONDS = 20;

const TITLE = "one conversation turn runs in a conversation sandbox and completes";
const evidence = new Evidence(common.out, `turn-smoke-${common.target}`);

interface Watched {
	turn: TurnRow | undefined;
	runs: RunRow[];
	sandboxes: string[];
	/** Whether a TURN run's leased_until moved within one epoch: its holder renewed the lease at least once. */
	renewed: boolean;
	/** The longest context read that the server timed during the watch, in seconds; null when it timed none. */
	contextMaxSeconds: number | null;
}

/** The server Pods' `http_server_requests_seconds_max` of the context route: the longest read of the recent window. */
async function contextMax(): Promise<number | null> {
	const scrape = await scrapeAll(cluster, /^http_server_requests_seconds_max$/u);
	let max: number | null = null;
	for (const samples of Object.values(scrape)) {
		if (!Array.isArray(samples)) {
			continue;
		}
		for (const sample of samples) {
			if (sample.labels.uri === CONTEXT_URI && sample.labels.method === "GET" && sample.value > 0) {
				max = Math.max(max ?? 0, sample.value);
			}
		}
	}
	return max;
}

/** Reads the turn's rows every 5 s until it leaves QUEUED and RUNNING, or the time is up. */
async function watchTurn(turnId: string, errors: string[]): Promise<Watched> {
	const deadline = Date.now() + Number(values.minutes) * 60_000;
	const seen = new Set<string>();
	const leases = new Map<number, string>();
	let renewed = false;
	let contextMaxSeconds: number | null = null;
	let turn: TurnRow | undefined;
	let runs: RunRow[] = [];
	while (Date.now() < deadline) {
		[turn] = await sqlRows(
			cluster,
			`SELECT id, conversation_id, status, attempt, run_id, failure_cause, failed_call, started_at, finished_at FROM mentor_turn WHERE id = '${turnId}'`,
			errors,
			"mentor_turn",
			TURN,
		);
		if (turn !== undefined) {
			const subject = `conversation:${turn.conversation_id}`;
			const sandboxes = await sqlRows(
				cluster,
				`SELECT id, state FROM agent_sandbox WHERE kind = 'CONVERSATION' AND subject_key = '${subject}'`,
				errors,
				"agent_sandbox",
				{ id: "string", state: "string" },
			);
			for (const sandbox of sandboxes) {
				seen.add(`${sandbox.id}:${sandbox.state}`);
			}
			runs = await sqlRows(
				cluster,
				`SELECT id, kind, state, attempt, lease_epoch, leased_until, stop_reason FROM agent_run WHERE concurrency_key = '${subject}' AND created_at >= '${evidence.started.toISOString()}'::timestamptz - interval '1 minute'`,
				errors,
				"agent_run",
				RUN,
			);
			for (const run of runs.filter(
				(entry) => entry.kind === "TURN" && entry.leased_until !== null,
			)) {
				const first = leases.get(run.lease_epoch);
				if (first === undefined) {
					leases.set(run.lease_epoch, run.leased_until ?? "");
				} else if (first !== run.leased_until) {
					renewed = true;
				}
			}
			const read = await contextMax();
			if (read !== null) {
				contextMaxSeconds = Math.max(contextMaxSeconds ?? 0, read);
			}
			evidence.line("turn.jsonl", { turn, runs, sandboxes, contextMaxSeconds });
			if (turn.status !== "QUEUED" && turn.status !== "RUNNING") {
				break;
			}
		}
		await sleep(5000);
	}
	return { turn, runs, sandboxes: [...seen], renewed, contextMaxSeconds };
}

/** What the turn lacks to pass; empty when it passed. */
function missing(watched: Watched, run: RunRow | undefined, calls: number): string[] {
	const { turn } = watched;
	const misses: string[] = [];
	if (turn?.status !== "DONE") {
		misses.push(
			`turn ${turn?.status ?? "missing"}${turn?.failure_cause == null ? "" : ` (${turn.failure_cause})`}`,
		);
	}
	if (run?.state !== "ENDED" || run.stop_reason !== "COMPLETED") {
		misses.push(
			`TURN run ${run === undefined ? "missing" : `${run.state} ${run.stop_reason ?? ""}`}`,
		);
	}
	if (watched.sandboxes.length === 0) {
		misses.push("no conversation sandbox held the conversation");
	}
	if (calls === 0) {
		misses.push("no model call recorded");
	}
	misses.push(...leaseMisses(watched));
	return misses;
}

/** Lease and context-read misses: a holder that loses its lease, even when a later attempt ends the turn. */
function leaseMisses(watched: Watched): string[] {
	const { turn } = watched;
	const misses: string[] = [];
	// A retried attempt means its holder lost the lease. The driver renews every 25 s, so a turn that ran 30 s or longer
	// on one attempt must have moved its lease at least once.
	if ((turn?.attempt ?? 0) > 1) {
		misses.push(
			`the turn needed attempt ${turn?.attempt ?? "?"}: an earlier holder lost its lease`,
		);
	}
	const ranSeconds =
		turn?.started_at == null || turn.finished_at == null
			? null
			: (Date.parse(turn.finished_at) - Date.parse(turn.started_at)) / 1000;
	if (!watched.renewed && (ranSeconds === null || ranSeconds >= 30)) {
		misses.push("the run's lease was never renewed");
	}
	if (watched.contextMaxSeconds === null) {
		misses.push("the server timed no context read");
	} else if (watched.contextMaxSeconds > CONTEXT_BOUND_SECONDS) {
		misses.push(
			`a context read took ${watched.contextMaxSeconds.toFixed(1)} s (bound ${CONTEXT_BOUND_SECONDS} s)`,
		);
	}
	return misses;
}

/** The turn to judge: posted here with the member's token, or the workspace's first turn after the smoke started. */
async function turnToJudge(errors: string[]): Promise<{ id: string } | { refused: string }> {
	if (awaitWorkspace === null) {
		const submitted = await submitTurn(
			mentorUrl ?? "",
			readFileSync(tokenFile ?? "", "utf8").trim(),
			{
				requestId: randomUUID(),
				text: values.question,
			},
		);
		return "id" in submitted
			? { id: submitted.id }
			: { refused: `the turn was refused: ${submitted.status}` };
	}
	evidence.log(
		`waiting for a turn in workspace ${awaitWorkspace}: post the question in the app now`,
	);
	const deadline = Date.now() + Number(values["await-minutes"]) * 60_000;
	while (Date.now() < deadline) {
		const [turn] = await sqlRows(
			cluster,
			`SELECT id FROM mentor_turn WHERE workspace_id = ${awaitWorkspace} AND created_at >= '${evidence.started.toISOString()}'::timestamptz ORDER BY created_at LIMIT 1`,
			errors,
			"mentor_turn",
			{ id: "string" },
		);
		if (turn !== undefined || errors.length > 0) {
			return turn ?? { refused: `the turn's rows were unreadable: ${errors.join("; ")}` };
		}
		await sleep(2000);
	}
	return {
		refused: `no turn reached workspace ${awaitWorkspace} within ${values["await-minutes"]} min`,
	};
}

async function smoke(): Promise<void> {
	const errors: string[] = [];
	const submitted = await turnToJudge(errors);
	if (!("id" in submitted)) {
		evidence.check({
			id: "turn-smoke",
			title: TITLE,
			status: "fail",
			rule: `${RULES} § Turn smoke`,
			detail: submitted.refused,
		});
		return;
	}
	evidence.log(`turn ${submitted.id} ${awaitWorkspace === null ? "submitted" : "found"}`);
	const watched = await watchTurn(submitted.id, errors);
	const [usage] = await sqlRows(
		cluster,
		`SELECT count(*)::int AS rows, coalesce(sum(total_calls), 0)::int AS calls FROM llm_usage_event WHERE source_type = 'MENTOR_TURN' AND source_id = '${submitted.id}'`,
		errors,
		"llm_usage_event",
		{ rows: "number", calls: "number" },
	);
	if (errors.length > 0) {
		evidence.check({
			id: "turn-smoke",
			title: TITLE,
			status: "fail",
			detail: `the turn's rows were unreadable: ${errors.join("; ")}`,
		});
		return;
	}
	const run = watched.runs.find((entry) => entry.kind === "TURN");
	const calls = usage?.calls ?? 0;
	const misses = missing(watched, run, calls);
	evidence.check({
		id: "turn-smoke",
		title: TITLE,
		status: misses.length === 0 ? "pass" : "fail",
		rule: `${RULES} § Turn smoke`,
		detail: `turn ${submitted.id}: ${watched.turn?.status ?? "missing"} after attempt ${watched.turn?.attempt ?? "?"}; run ${run?.state ?? "missing"} ${run?.stop_reason ?? ""}; sandboxes ${watched.sandboxes.join(", ") || "none"}; model calls ${calls}; lease renewed ${watched.renewed}; longest context read ${watched.contextMaxSeconds?.toFixed(1) ?? "none"} s${misses.length === 0 ? "" : `; missing: ${misses.join("; ")}`}`,
		data: { ...watched, usage },
	});
}

const description = await cluster.describe();
evidence.raw("cluster.json", description);
try {
	await smoke();
} catch (error) {
	evidence.check({
		id: "turn-smoke",
		title: TITLE,
		status: "fail",
		detail: error instanceof Error ? error.message : String(error),
	});
}
const verdict = evidence.finish({
	command: common.command,
	commit: await commitOf(),
	cluster: description,
});
process.exitCode = verdict === "PASS" ? 0 : 1;
