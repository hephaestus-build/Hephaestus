import { setTimeout as sleep } from "node:timers/promises";

export class GatewayUnavailableError extends Error {
	override name = "GatewayUnavailableError";
}

function transient(error: unknown): boolean {
	return (
		error instanceof TypeError || (error instanceof DOMException && error.name === "TimeoutError")
	);
}

/** The trusted loop keeps its lease through execution and result upload, including a worker replacement. */
export async function withReviewLease<T>(
	endpoint: URL,
	token: string,
	startDeadline: number,
	run: (signal: AbortSignal) => Promise<T>,
): Promise<T> {
	const stopped = new AbortController();
	const isStopped = () => stopped.signal.aborted;
	// No review work starts without a receipt. Startup waits only within the existing work deadline.
	let expires = performance.now() + Math.max(0, startDeadline - Date.now());

	async function renew(): Promise<number> {
		const started = performance.now();
		const remaining = expires - started;
		if (remaining <= 0) {
			throw new Error("The review lease expired");
		}
		const response = await fetch(endpoint, {
			method: "POST",
			headers: { Authorization: `Bearer ${token}` },
			signal: AbortSignal.any([
				stopped.signal,
				AbortSignal.timeout(Math.max(1, Math.floor(Math.min(10_000, remaining)))),
			]),
		});
		if (!response.ok) {
			await response.body?.cancel();
			if ([429, 502, 503, 504].includes(response.status)) {
				throw new TypeError("The sandbox gateway is unavailable");
			}
			throw new Error(`The sandbox gateway refused lease renewal: ${response.status}`);
		}
		const receipt: unknown = await response.json();
		if (
			typeof receipt !== "object" ||
			receipt === null ||
			!("remainingMillis" in receipt) ||
			!Number.isSafeInteger(receipt.remainingMillis) ||
			typeof receipt.remainingMillis !== "number" ||
			receipt.remainingMillis <= 0 ||
			!("renewAfterMillis" in receipt) ||
			!Number.isSafeInteger(receipt.renewAfterMillis) ||
			typeof receipt.renewAfterMillis !== "number" ||
			receipt.renewAfterMillis <= 0 ||
			receipt.renewAfterMillis > receipt.remainingMillis
		) {
			throw new Error("The sandbox gateway returned an invalid lease receipt");
		}
		// Subtract the whole exchange, including the database work; clocks need not agree across nodes.
		expires = started + receipt.remainingMillis;
		if (expires <= performance.now()) {
			throw new Error("The review lease expired during renewal");
		}
		return Math.min(receipt.renewAfterMillis, expires - performance.now());
	}

	let wait: number;
	for (;;) {
		if (performance.now() >= expires) {
			throw new GatewayUnavailableError(
				"The sandbox gateway did not return before review startup timed out",
			);
		}
		try {
			wait = await renew();
			break;
		} catch (error) {
			if (!transient(error)) {
				throw error;
			}
			await sleep(Math.max(0, Math.min(1000, expires - performance.now())));
		}
	}
	const heartbeat = (async () => {
		try {
			while (!isStopped()) {
				await sleep(Math.max(0, Math.min(wait, expires - performance.now())), undefined, {
					signal: stopped.signal,
				});
				try {
					wait = await renew();
				} catch (error) {
					if (isStopped()) {
						return;
					}
					if (!transient(error)) {
						throw error;
					}
					if (performance.now() >= expires) {
						throw new Error("The review lease expired", { cause: error });
					}
					wait = 1000;
				}
			}
		} catch (error) {
			if (!isStopped()) {
				stopped.abort(error);
			}
		}
	})();
	try {
		return await run(stopped.signal);
	} finally {
		stopped.abort();
		await heartbeat;
	}
}
