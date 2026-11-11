import { setTimeout as sleep } from "node:timers/promises";

export interface GatewayCapabilities {
	workspaceByteBudget: number;
}

const PROTOCOL_VERSION = 3;

/** A sandbox start waits out a limit of the gateway, instead of failing a review that never began. */
const LIMIT_ATTEMPTS = 3;
const LIMIT_WAIT_MS = 1000;

/** How long the gateway asks a limited caller to wait. */
export function retryAfterMs(response: Response): number {
	const seconds = Number(response.headers.get("retry-after"));
	return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : LIMIT_WAIT_MS;
}

function isByteBudget(value: unknown): value is number {
	return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

/** What the gateway advertises for this session, refused whole when any field is not as declared. */
export async function discoverCapabilities(
	endpoint: string,
	headers: Record<string, string>,
): Promise<GatewayCapabilities> {
	let discovery = await fetch(endpoint, { headers });
	for (let attempt = 1; discovery.status === 429 && attempt < LIMIT_ATTEMPTS; attempt += 1) {
		await discovery.body?.cancel();
		await sleep(retryAfterMs(discovery));
		discovery = await fetch(endpoint, { headers });
	}
	if (!discovery.ok) {
		throw new Error(`Gateway discovery refused: ${discovery.status}`);
	}
	const capabilities: unknown = await discovery.json();
	if (
		typeof capabilities !== "object" ||
		capabilities === null ||
		!("protocolVersion" in capabilities) ||
		capabilities.protocolVersion !== PROTOCOL_VERSION ||
		!("workspaceByteBudget" in capabilities) ||
		!isByteBudget(capabilities.workspaceByteBudget)
	) {
		throw new Error("Invalid gateway capabilities");
	}
	return { workspaceByteBudget: capabilities.workspaceByteBudget };
}

/** Whether an environment value is set and not empty: the gateway refuses to start on either. */
export function present(value: string | undefined): value is string {
	return value !== undefined && value !== "";
}

/** The headers of a run's claim; pi-provider.ts parses the same variable for the runner. */
const CLAIM_HEADERS = new Set(["x-agent-run", "x-agent-epoch"]);

/**
 * The claim of the run that a sandbox's driver started this program for, from `LLM_PROXY_CLAIM`: its headers go with
 * every gateway call. Empty in a review Job, which has no run claim of its own.
 */
export function claimHeaders(value: string | undefined): Record<string, string> {
	if (!present(value)) {
		return {};
	}
	const parsed: unknown = JSON.parse(value);
	if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
		throw new Error("LLM_PROXY_CLAIM is not an object of the run's claim headers");
	}
	const claim: Record<string, string> = {};
	for (const [name, header] of Object.entries(parsed)) {
		if (!CLAIM_HEADERS.has(name) || typeof header !== "string" || header === "") {
			throw new Error("LLM_PROXY_CLAIM names only the run's claim headers, each with a value");
		}
		claim[name] = header;
	}
	return claim;
}
