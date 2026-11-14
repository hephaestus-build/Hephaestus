// gateway-connection.ts — how a sandbox program meets a gateway that is gone for a while: the server restarts, or
// its only replica is replaced. Every call that a program makes to the gateway waits out a refused connection with
// the same rule, so that no route gets a shorter budget than a server takes to come back.

import { setTimeout as sleep } from "node:timers/promises";

const NETWORK_CODES = new Set([
	"ECONNREFUSED",
	"ECONNRESET",
	"EHOSTUNREACH",
	"ENETUNREACH",
	"ETIMEDOUT",
	"EAI_AGAIN",
	"ENOTFOUND",
	"UND_ERR_CONNECT_TIMEOUT",
	"UND_ERR_SOCKET",
]);

export const MAX_GATEWAY_RESPONSE_REPLAYS = 2;

/** How long a call waits before it connects again to a gateway that refused it. */
export const GATEWAY_RECONNECT_MS = 1000;

export class GatewayUnavailableError extends Error {
	override name = "GatewayUnavailableError";
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * A `connection` failure reached no server: the call can be sent again. A reset or ambiguous timeout may follow a
 * billed or recorded request before response headers reach the caller, so it is a `response` failure, which only a
 * caller that can replay its request may send again.
 */
export function gatewayConnectionFailure(error: unknown): "connection" | "response" | undefined {
	let cause = error;
	for (let depth = 0; depth < 5 && isRecord(cause); depth += 1) {
		if (typeof cause.code === "string" && NETWORK_CODES.has(cause.code)) {
			return cause.code === "ECONNRESET" ||
				cause.code === "UND_ERR_SOCKET" ||
				(["ETIMEDOUT", "EHOSTUNREACH", "ENETUNREACH"].includes(cause.code) &&
					cause.syscall !== "connect")
				? "response"
				: "connection";
		}
		cause = cause.cause;
	}
	return undefined;
}

/**
 * Sends a gateway call until the gateway takes it. A refused connection reached no server, so the call connects again
 * every second until `patience` ends; then the last failure is thrown. Any other failure is the caller's to judge.
 *
 * @param patience ends with the work that the call serves: its deadline, its lease, or its cancellation
 * @param waiting called before each wait, with the number of waits so far
 */
export async function untilGatewayTakes<T>(
	send: () => Promise<T>,
	patience: AbortSignal,
	waiting: (waits: number) => void = () => undefined,
): Promise<T> {
	for (let waits = 1; ; waits += 1) {
		try {
			return await send();
		} catch (error) {
			if (patience.aborted || gatewayConnectionFailure(error) !== "connection") {
				throw error;
			}
			waiting(waits);
			try {
				await sleep(GATEWAY_RECONNECT_MS, undefined, { signal: patience });
			} catch {
				throw error;
			}
		}
	}
}
