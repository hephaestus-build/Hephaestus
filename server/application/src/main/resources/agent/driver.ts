// driver.ts — the one loop of a sandbox: it pulls its subject's next run from the gateway, runs the run's kind, keeps
// the run's lease while the kind works, and ends the run (ADR 0079 § Gateway API).
//
// The sandbox is the run's only writer. A kind runner reads its context and makes its calls with the run's headers;
// it never renews the lease and never ends the run. The driver aborts the runner's signal when the lease is lost or
// the sandbox is stopped, and then sends no end: the server's reaper owns a run whose lease ran out.

import { setTimeout as sleep } from "node:timers/promises";

import { KINDS } from "./driver-kinds.ts";
import { isJsonObject } from "./work/practice-scripts/lib/core/json.ts";

/** A run that the gateway leased to this sandbox (`POST /internal/agent/next`). */
export interface Assignment {
	runId: string;
	epoch: number;
	kind: string;
	/** When the lease ends unless the driver renews it, in epoch milliseconds. */
	leasedUntil: number;
	/** When the run ends whatever its lease, in epoch milliseconds; null for none. */
	deadline: number | null;
}

/** What a kind runner gets for one run. */
export interface RunContext {
	assignment: Assignment;
	gateway: {
		/** The gateway's base URL, such as `http://10.43.0.12:8081`. */
		url: string;
		/** The sandbox token and the run's claim: `Authorization`, `X-Agent-Run` and `X-Agent-Epoch`. */
		headers: Readonly<Record<string, string>>;
	};
	/** Aborted when the lease is lost or the sandbox stops; the runner stops at its next await. */
	signal: AbortSignal;
}

/** How a run ended for its runner: the lease was lost, or the runner finished or handed the run on. */
export type RunOutcome =
	| { leaseLost: true }
	| { leaseLost: false; outcome: "completed" | "handed_on" };

export type KindRunner = (run: RunContext) => Promise<RunOutcome>;

export interface DriverOptions {
	gatewayUrl: string;
	token: string;
	/** The runner of each run kind that this sandbox's kind drives, by the kind's name (`REVIEW_COMPOSE`). */
	kinds: Readonly<Record<string, KindRunner>>;
	/** Aborted on SIGTERM: the driver stops the current run and pulls no more. */
	signal: AbortSignal;
	/** How often the lease is renewed; the server's lease lasts at least twice as long. */
	renewEveryMs?: number;
	/** How long the driver waits after the gateway had no run for it. */
	idleWaitMs?: number;
	fetch?: typeof globalThis.fetch;
	log?: (line: string) => void;
}

const RENEW_EVERY_MS = 25_000;
const IDLE_WAIT_MS = 2000;

/** Runs the sandbox's runs one after another until `signal` aborts. */
export async function drive(options: DriverOptions): Promise<void> {
	const { signal } = options;
	const fetch = options.fetch ?? globalThis.fetch;
	const log = options.log ?? ((line: string) => process.stderr.write(`${line}\n`));
	const auth = { authorization: `Bearer ${options.token}` };
	// A function: the loop condition would narrow the property, and the signal aborts during an await.
	const stopped = () => signal.aborted;
	while (!stopped()) {
		let assignment: Assignment | null;
		try {
			assignment = await next(fetch, options.gatewayUrl, auth, signal);
		} catch (error) {
			if (stopped()) {
				return;
			}
			log(`driver: the gateway did not answer next: ${errorName(error)}`);
			assignment = null;
		}
		if (assignment === null) {
			await sleep(options.idleWaitMs ?? IDLE_WAIT_MS, undefined, { signal }).catch(() => undefined);
			continue;
		}
		await runOne(assignment, { ...options, fetch, log, auth });
	}
}

/** The subject's next run, or null when none is due. */
export async function next(
	fetch: typeof globalThis.fetch,
	gatewayUrl: string,
	auth: Readonly<Record<string, string>>,
	signal: AbortSignal,
): Promise<Assignment | null> {
	const response = await fetch(`${gatewayUrl}/internal/agent/next`, {
		method: "POST",
		headers: auth,
		signal,
	});
	if (response.status === 204) {
		return null;
	}
	if (!response.ok) {
		throw new Error(`next answered ${String(response.status)}`);
	}
	return parseAssignment(await response.json());
}

/** The gateway's answer, checked: a run that the driver cannot read is no run to start. */
export function parseAssignment(value: unknown): Assignment {
	if (!isJsonObject(value)) {
		throw new Error("the assignment is not an object");
	}
	const { runId, epoch, kind, leasedUntil, deadline } = value;
	const until = typeof leasedUntil === "string" ? Date.parse(leasedUntil) : Number.NaN;
	const end = typeof deadline === "string" ? Date.parse(deadline) : null;
	if (
		typeof runId !== "string" ||
		!/^[0-9a-f-]{36}$/u.test(runId) ||
		typeof epoch !== "number" ||
		!Number.isInteger(epoch) ||
		epoch < 1 ||
		typeof kind !== "string" ||
		kind === "" ||
		Number.isNaN(until) ||
		(deadline !== null && deadline !== undefined && (end === null || Number.isNaN(end)))
	) {
		throw new Error("the assignment does not name a run, its epoch, its kind and its lease");
	}
	return { runId, epoch, kind, leasedUntil: until, deadline: end };
}

async function runOne(
	assignment: Assignment,
	options: DriverOptions & {
		fetch: typeof globalThis.fetch;
		log: (line: string) => void;
		auth: Readonly<Record<string, string>>;
	},
): Promise<void> {
	const { fetch, log, gatewayUrl } = options;
	const headers = {
		...options.auth,
		"x-agent-run": assignment.runId,
		"x-agent-epoch": String(assignment.epoch),
	};
	const runUrl = `${gatewayUrl}/internal/agent/runs/${assignment.runId}`;
	const lost = new AbortController();
	const signal = AbortSignal.any([options.signal, lost.signal]);
	const renewal = keepLease(fetch, runUrl, headers, assignment.leasedUntil, signal, lost, {
		everyMs: options.renewEveryMs ?? RENEW_EVERY_MS,
		log,
	});
	const runner = options.kinds[assignment.kind];
	let outcome: RunOutcome;
	if (runner === undefined) {
		// The server offers a sandbox only the kinds that its kind drives; another is a defect, not work to do.
		log(
			`driver: run ${assignment.runId} has kind ${assignment.kind}, which this sandbox does not run`,
		);
		outcome = { leaseLost: false, outcome: "handed_on" };
	} else {
		try {
			outcome = await runner({ assignment, gateway: { url: gatewayUrl, headers }, signal });
		} catch (error) {
			log(`driver: run ${assignment.runId} failed: ${errorName(error)}`);
			outcome = signal.aborted ? { leaseLost: true } : { leaseLost: false, outcome: "handed_on" };
		}
	}
	lost.abort();
	await renewal;
	if (outcome.leaseLost || options.signal.aborted) {
		return;
	}
	try {
		const ended = await fetch(`${runUrl}/end`, {
			method: "POST",
			headers: { ...headers, "content-type": "application/json" },
			body: JSON.stringify({ outcome: outcome.outcome }),
			signal: options.signal,
		});
		if (!ended.ok) {
			log(
				`driver: run ${assignment.runId} ended with ${String(ended.status)}; the server takes it back`,
			);
		}
	} catch (error) {
		log(
			`driver: run ${assignment.runId} could not end: ${errorName(error)}; the server takes it back`,
		);
	}
}

/**
 * Renews the lease until `signal` aborts. A renewal that the gateway refuses (409) ends the lease at once; one that
 * fails is tried again until the lease's own end, after which the claim is lost too.
 */
async function keepLease(
	fetch: typeof globalThis.fetch,
	runUrl: string,
	headers: Readonly<Record<string, string>>,
	initialUntil: number,
	signal: AbortSignal,
	lost: AbortController,
	{ everyMs, log }: { everyMs: number; log: (line: string) => void },
): Promise<void> {
	let until = initialUntil;
	const stopped = () => signal.aborted;
	while (!stopped()) {
		const wait = await sleep(Math.min(everyMs, Math.max(0, until - Date.now())), true, {
			signal,
		}).catch(() => false);
		if (!wait) {
			return;
		}
		try {
			const response = await fetch(`${runUrl}/lease`, { method: "POST", headers, signal });
			if (response.status === 409) {
				log(`driver: the lease of ${runUrl} is gone`);
				lost.abort();
				return;
			}
			if (response.ok) {
				const body: unknown = await response.json();
				const renewed =
					isJsonObject(body) && typeof body.leasedUntil === "string"
						? Date.parse(body.leasedUntil)
						: Number.NaN;
				if (!Number.isNaN(renewed)) {
					until = renewed;
				}
			}
		} catch (error) {
			if (stopped()) {
				return;
			}
			log(`driver: a lease renewal failed: ${errorName(error)}`);
		}
		if (Date.now() >= until) {
			lost.abort();
			return;
		}
	}
}

function errorName(error: unknown): string {
	return error instanceof Error ? error.name : "error";
}

/**
 * The sandbox's entry: the claim's environment names the gateway and the sandbox token. The loop is the container's
 * first process, so it stops on SIGTERM itself; otherwise a claim's delete waits out the grace period.
 */
async function main(): Promise<void> {
	const gatewayUrl = process.env.HEPHAESTUS_GATEWAY_URL;
	const token = process.env.HEPHAESTUS_SANDBOX_TOKEN;
	if (gatewayUrl === undefined || gatewayUrl === "" || token === undefined || token === "") {
		throw new Error("HEPHAESTUS_GATEWAY_URL and HEPHAESTUS_SANDBOX_TOKEN are required");
	}
	const stop = new AbortController();
	process.once("SIGTERM", () => stop.abort());
	await drive({ gatewayUrl, token, kinds: KINDS, signal: stop.signal });
}

if (import.meta.main) {
	await main();
}
