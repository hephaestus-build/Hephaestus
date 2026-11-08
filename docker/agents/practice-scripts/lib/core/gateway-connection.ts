import { isJsonObject as isRecord } from "./json.ts";

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

export class GatewayUnavailableError extends Error {
	override name = "GatewayUnavailableError";
}

/** A reset or ambiguous timeout may follow a billed request before response headers reach the caller. */
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
