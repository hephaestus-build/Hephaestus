// driver.ts — the one loop of a sandbox: it pulls its subject's next run from the gateway, runs the run's kind, keeps
// the run's lease while the kind works, and ends the run (ADR 0079 § Gateway API).
//
// The sandbox is the run's only writer. A kind runner reads its context and makes its calls with the run's headers;
// it never renews the lease and never ends the run. The driver aborts the runner's signal when the lease is lost or
// the sandbox is stopped, and then sends no end: the server's reaper owns a run whose lease ran out.
//
// No kind starts before the sandbox's network policy holds (driver-isolation.ts). A sandbox whose policy does not hold
// in time ends its run as `unisolated`, which hands the run on and takes the sandbox back, and pulls no more.

import { setTimeout as sleep } from "node:timers/promises";

import { guardIsolation, type Isolation } from "./driver-isolation.ts";
import { KINDS } from "./driver-kinds.ts";

/**
 * Where a kind works: Pi's working directory and its agent directory. The image gives both to UID 1000, and they last
 * as long as the sandbox, so a conversation keeps its files from one turn to the next. The container's
 * ephemeral-storage limit bounds them.
 */
export const WORKSPACE = "/workspace/work";
export const AGENT_DIR = "/workspace/.pi";

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
	/** Settles once the sandbox's policy holds, or once it has not held in time. */
	isolation: Promise<Isolation>;
	/** How often the lease is renewed; the server's lease lasts at least twice as long. */
	renewEveryMs?: number;
	/** How long the driver waits after the gateway had no run for it. */
	idleWaitMs?: number;
	fetch?: typeof globalThis.fetch;
	log?: (line: string) => void;
}

const RENEW_EVERY_MS = 25_000;
const IDLE_WAIT_MS = 2000;
/** The longest wait for one gateway call: a gateway that answers later has failed the call. */
const CALL_TIMEOUT_MS = 30_000;
/** The longest wait after `next` failed again and again, as after the sandbox's token expired. */
const MAX_RETRY_WAIT_MS = 30_000;
/**
 * A lease counts as lost this long before its end on the gateway's clock: the `Date` header that maps that clock to
 * this one has whole seconds.
 */
const LEASE_MARGIN_MS = 2000;

/** Runs the sandbox's runs one after another until `signal` aborts. */
export async function drive(options: DriverOptions): Promise<void> {
	const { signal } = options;
	const fetch = options.fetch ?? globalThis.fetch;
	const log = options.log ?? ((line: string) => process.stderr.write(`${line}\n`));
	const auth = { authorization: `Bearer ${options.token}` };
	const idle = options.idleWaitMs ?? IDLE_WAIT_MS;
	// A function: the loop condition would narrow the property, and the signal aborts during an await.
	const stopped = () => signal.aborted;
	let failures = 0;
	while (!stopped()) {
		let offered: Offered | null;
		try {
			offered = await next(fetch, options.gatewayUrl, auth, signal);
			failures = 0;
		} catch (error) {
			if (stopped()) {
				return;
			}
			failures += 1;
			log(`driver: the gateway did not answer next: ${errorName(error)}`);
			offered = null;
		}
		if (offered === null) {
			const wait = Math.min(MAX_RETRY_WAIT_MS, idle * 2 ** Math.min(failures, 10));
			await sleep(wait, undefined, { signal }).catch(() => undefined);
			continue;
		}
		if ((await runOne(offered, { ...options, fetch, log, auth })) === "unisolated") {
			return;
		}
	}
}

/** A run that the gateway leased to this sandbox, with how far this clock runs ahead of the gateway's. */
export interface Offered {
	assignment: Assignment;
	clockOffsetMs: number;
}

/** The subject's next run, or null when none is due. */
export async function next(
	fetch: typeof globalThis.fetch,
	gatewayUrl: string,
	auth: Readonly<Record<string, string>>,
	signal: AbortSignal,
): Promise<Offered | null> {
	const response = await fetch(`${gatewayUrl}/internal/agent/next`, {
		method: "POST",
		headers: auth,
		signal: bounded(signal, CALL_TIMEOUT_MS),
	});
	if (response.status === 204) {
		return null;
	}
	if (!response.ok) {
		await response.body?.cancel();
		throw new Error(`next answered ${String(response.status)}`);
	}
	return {
		assignment: parseAssignment(await response.json()),
		clockOffsetMs: clockOffset(response),
	};
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/u;

/** An ISO-8601 instant as the server writes one, in epoch milliseconds; NaN for anything else. */
function instant(value: unknown): number {
	return typeof value === "string" && INSTANT.test(value) ? Date.parse(value) : Number.NaN;
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** The gateway's answer, checked: a run that the driver cannot read is no run to start. */
export function parseAssignment(value: unknown): Assignment {
	if (!isJsonObject(value)) {
		throw new Error("the assignment is not an object");
	}
	const { runId, epoch, kind, leasedUntil, deadline } = value;
	const until = instant(leasedUntil);
	const end = deadline === null || deadline === undefined ? null : instant(deadline);
	if (
		typeof runId !== "string" ||
		!UUID.test(runId) ||
		typeof epoch !== "number" ||
		!Number.isInteger(epoch) ||
		epoch < 1 ||
		typeof kind !== "string" ||
		kind === "" ||
		Number.isNaN(until) ||
		(end !== null && Number.isNaN(end))
	) {
		throw new Error("the assignment does not name a run, its epoch, its kind and its lease");
	}
	return { runId, epoch, kind, leasedUntil: until, deadline: end };
}

/**
 * How far this clock runs ahead of the gateway's, from the response's `Date` header: the lease and the deadline are
 * on the database clock. Zero when the response names no time.
 */
function clockOffset(response: Response): number {
	const sent = Date.parse(response.headers.get("date") ?? "");
	return Number.isNaN(sent) ? 0 : Date.now() - sent;
}

/** `signal`, and also aborted after `ms`. */
function bounded(signal: AbortSignal, ms: number): AbortSignal {
	return AbortSignal.any([signal, AbortSignal.timeout(Math.max(1, ms))]);
}

/** How the driver ends a run: as its runner said, or as unisolated when the sandbox's policy did not hold. */
type EndOutcome = "completed" | "handed_on" | "unisolated";

/** Runs one run; `unisolated` when the sandbox must pull no more. */
async function runOne(
	{ assignment, clockOffsetMs }: Offered,
	options: DriverOptions & {
		fetch: typeof globalThis.fetch;
		log: (line: string) => void;
		auth: Readonly<Record<string, string>>;
	},
): Promise<EndOutcome | null> {
	const { fetch, log, gatewayUrl } = options;
	const headers = {
		...options.auth,
		"x-agent-run": assignment.runId,
		"x-agent-epoch": String(assignment.epoch),
	};
	const runUrl = `${gatewayUrl}/internal/agent/runs/${assignment.runId}`;
	const lost = new AbortController();
	// Past its deadline the server ends the run itself, so the driver stops it then and ends nothing.
	const deadline =
		assignment.deadline === null
			? null
			: AbortSignal.timeout(Math.max(0, assignment.deadline + clockOffsetMs - Date.now()));
	const signal = AbortSignal.any([
		options.signal,
		lost.signal,
		...(deadline === null ? [] : [deadline]),
	]);
	const renewal = keepLease(
		fetch,
		runUrl,
		headers,
		assignment.leasedUntil + clockOffsetMs,
		signal,
		lost,
		{
			everyMs: options.renewEveryMs ?? RENEW_EVERY_MS,
			log,
		},
	);
	const runner = options.kinds[assignment.kind];
	const isolation = await options.isolation;
	let outcome: RunOutcome | { leaseLost: false; outcome: "unisolated" };
	if (!isolation.isolated) {
		log(
			`driver: the sandbox's network policy did not hold (${isolation.reason}); no kind runs here`,
		);
		outcome = { leaseLost: false, outcome: "unisolated" };
	} else if (runner === undefined) {
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
			outcome = { leaseLost: false, outcome: "handed_on" };
		}
	}
	// Read before the renewal stops: a lost lease or a passed deadline leaves the run to the server.
	const overtaken = lost.signal.aborted || deadline?.aborted === true;
	lost.abort();
	await renewal;
	if (outcome.leaseLost || overtaken) {
		return null;
	}
	// Its own bound, not the stop signal: a run that finished as the sandbox stops is still ended, not redone.
	try {
		const ended = await fetch(`${runUrl}/end`, {
			method: "POST",
			headers: { ...headers, "content-type": "application/json" },
			body: JSON.stringify({ outcome: outcome.outcome }),
			signal: AbortSignal.timeout(CALL_TIMEOUT_MS),
		});
		await ended.body?.cancel();
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
	return outcome.outcome;
}

/** The answers of a renewal after which the claim cannot recover: the lease, the run or the sandbox is gone. */
const GONE = new Set([401, 404, 409]);

/**
 * Renews the lease until `signal` aborts. A renewal that the gateway refuses ends the lease at once; one that fails is
 * tried again until the lease's own end on this clock, after which the claim is lost too. Each call ends at the
 * lease's end at the latest, so a gateway that hangs cannot keep a runner working after its lease.
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
	let until = initialUntil - LEASE_MARGIN_MS;
	const stopped = () => signal.aborted;
	while (!stopped()) {
		const wait = await sleep(Math.min(everyMs, Math.max(0, until - Date.now())), true, {
			signal,
		}).catch(() => false);
		if (!wait) {
			return;
		}
		try {
			const response = await fetch(`${runUrl}/lease`, {
				method: "POST",
				headers,
				signal: bounded(signal, Math.min(CALL_TIMEOUT_MS, until - Date.now())),
			});
			if (GONE.has(response.status)) {
				await response.body?.cancel();
				log(
					`driver: the gateway answered ${String(response.status)} to a renewal of ${runUrl}; the lease is gone`,
				);
				lost.abort();
				return;
			}
			if (response.ok) {
				const body: unknown = await response.json();
				const renewed = isJsonObject(body) ? instant(body.leasedUntil) : Number.NaN;
				if (!Number.isNaN(renewed)) {
					until = renewed + clockOffset(response) - LEASE_MARGIN_MS;
				}
			} else {
				await response.body?.cancel();
				log(`driver: a lease renewal answered ${String(response.status)}`);
			}
		} catch (error) {
			if (stopped()) {
				return;
			}
			log(`driver: a lease renewal failed: ${errorName(error)}`);
		}
		if (Date.now() >= until) {
			log(`driver: the lease of ${runUrl} ran out without a renewal`);
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
	// From the start, while the first pull waits for the gateway.
	const isolation = guardIsolation({
		host: process.env.KUBERNETES_SERVICE_HOST,
		port: process.env.KUBERNETES_SERVICE_PORT,
		signal: stop.signal,
	});
	await drive({ gatewayUrl, token, kinds: KINDS, signal: stop.signal, isolation });
	// Open keep-alive sockets would hold the first process of the container after its last run.
	process.exit(0);
}

if (import.meta.main) {
	await main();
}
