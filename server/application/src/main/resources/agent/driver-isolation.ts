// driver-isolation.ts — the policy-lag guard (isolation gate G6). A new sandbox can reach the Kubernetes API for its
// first moments, before the node applies its namespace's NetworkPolicy; the template has no init container that could
// wait for it. So the driver starts no run's kind, and no model-chosen code, until a connection to the API has failed.
//
// A connection that does not open within the probe's timeout counts as refused: a policy that drops packets answers
// nothing, and the API Service answers within milliseconds when nothing stands between. The isolation gate's hold
// command (scripts/sandbox-isolation.ts, G5) measures the same window with the same probe.

import { connect as tcpConnect } from "node:net";
import { setTimeout as sleep } from "node:timers/promises";

/** Whether the sandbox's policy held: the API refused it in time, or it did not. */
export type Isolation = { isolated: true } | { isolated: false; reason: string };

export interface GuardOptions {
	/** The API Service address that the kubelet gives every container (`KUBERNETES_SERVICE_HOST` and `_PORT`). */
	host: string | undefined;
	port: string | undefined;
	signal: AbortSignal;
	/** How long the policy may take to hold; the isolation gate fails a template that takes as long. */
	boundMs?: number;
	/** How long one probe waits for the connection to open. */
	probeTimeoutMs?: number;
	/** The pause between probes that got through. */
	pauseMs?: number;
	/** Opens one connection: true when it opened, false when it was refused or did not open in time. */
	reach?: (host: string, port: number, timeoutMs: number) => Promise<boolean>;
}

const BOUND_MS = 10_000;
const PROBE_TIMEOUT_MS = 300;
const PAUSE_MS = 50;

/** Probes the API until it refuses a connection, for at most the bound. */
export async function guardIsolation(options: GuardOptions): Promise<Isolation> {
	const port = Number(options.port);
	if (options.host === undefined || options.host === "" || !Number.isInteger(port) || port < 1) {
		// Without the address, the driver cannot see the policy hold: no Kubernetes container lacks it.
		return { isolated: false, reason: "the container names no Kubernetes API address" };
	}
	const reach = options.reach ?? reachable;
	const timeout = options.probeTimeoutMs ?? PROBE_TIMEOUT_MS;
	const until = Date.now() + (options.boundMs ?? BOUND_MS);
	const stopped = () => options.signal.aborted;
	while (!stopped()) {
		if (!(await reach(options.host, port, timeout))) {
			return { isolated: true };
		}
		if (Date.now() >= until) {
			return { isolated: false, reason: "the Kubernetes API still answered after the bound" };
		}
		await sleep(options.pauseMs ?? PAUSE_MS, undefined, { signal: options.signal }).catch(
			() => undefined,
		);
	}
	return { isolated: false, reason: "the sandbox stopped before its policy held" };
}

async function reachable(host: string, port: number, timeoutMs: number): Promise<boolean> {
	const { promise, resolve } = Promise.withResolvers<boolean>();
	const socket = tcpConnect({ host, port, timeout: timeoutMs });
	socket.once("connect", () => {
		socket.destroy();
		resolve(true);
	});
	socket.once("timeout", () => {
		socket.destroy();
		resolve(false);
	});
	socket.once("error", () => {
		resolve(false);
	});
	return promise;
}
