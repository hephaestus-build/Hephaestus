import { setTimeout as sleep } from "node:timers/promises";

export interface GatewayCapabilities {
	workspaceByteBudget: number;
}

const PROTOCOL_VERSION = 3;

const LIMIT_WAIT_MS = 1000;

/** How long the gateway asks a limited caller to wait. */
export function retryAfterMs(response: Response): number {
	const retry = response.headers.get("retry-after");
	const seconds = retry === null ? Number.NaN : Number(retry);
	if (Number.isFinite(seconds) && seconds >= 0) {
		return seconds * 1000;
	}
	const date = retry === null ? Number.NaN : Date.parse(retry);
	return Number.isFinite(date) ? Math.max(0, date - Date.now()) : LIMIT_WAIT_MS;
}

function isByteBudget(value: unknown): value is number {
	return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

/** A prepared input stays on its claim while admission is busy, bounded by the work signal. */
export async function fetchGatewayInput(
	endpoint: string,
	headers: Record<string, string>,
	signal: AbortSignal,
): Promise<Response> {
	for (;;) {
		signal.throwIfAborted();
		try {
			const response = await fetch(endpoint, { headers, signal });
			if (response.status !== 503 && response.status !== 429) {
				return response;
			}
			await response.body?.cancel();
			await sleep(retryAfterMs(response), undefined, { signal });
		} catch (error) {
			if (signal.aborted || !(error instanceof TypeError)) {
				throw error;
			}
			await sleep(LIMIT_WAIT_MS, undefined, { signal });
		}
	}
}

/** What the gateway advertises for this session, refused whole when any field is not as declared. */
export async function discoverCapabilities(
	endpoint: string,
	headers: Record<string, string>,
	signal: AbortSignal,
): Promise<GatewayCapabilities> {
	const discovery = await fetchGatewayInput(endpoint, headers, signal);
	if (!discovery.ok) {
		await discovery.body?.cancel();
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
