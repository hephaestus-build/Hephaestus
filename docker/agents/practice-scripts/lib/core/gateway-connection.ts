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

export function gatewayConnectionFailed(error: unknown): boolean {
	let cause = error;
	for (let depth = 0; depth < 5 && isRecord(cause); depth += 1) {
		if (typeof cause.code === "string" && NETWORK_CODES.has(cause.code)) {
			return true;
		}
		cause = cause.cause;
	}
	return false;
}
