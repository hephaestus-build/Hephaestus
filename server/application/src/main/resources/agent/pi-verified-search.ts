// pi-verified-search.ts — whether a search that the tools side ran can stand as proof.
//
// A receipt for a search proves an absence: a result may say that the work has no such thing. The tools side is not
// trusted, and no hash shows a match that it left out, so the loop searches the same files itself. Anything that it
// cannot settle in time proves nothing, and the result then rests on what it read.

import { once } from "node:events";
import { setTimeout as sleep } from "node:timers/promises";
import { Worker } from "node:worker_threads";

import type { SearchClaim, SearchJob } from "./pi-search-worker.ts";

/** How long the loop gives one proof: a pattern that backtracks is ended here. */
const PROOF_TIMEOUT_MS = 20_000;

/** The most bytes of a checkout that a proof reads. */
const PROOF_BYTES = 512 * 1024 * 1024;

const WORKER = new URL("pi-search-worker.ts", import.meta.url);

/** For each claim, whether it stands. A worker that fails or runs out of time proves none. */
export async function proveSearches(
	root: string,
	claims: readonly SearchClaim[],
	{ timeoutMs = PROOF_TIMEOUT_MS, maxBytes = PROOF_BYTES } = {},
): Promise<boolean[]> {
	if (claims.length === 0) {
		return [];
	}
	const worker = new Worker(WORKER, {
		workerData: { root, maxBytes, claims } satisfies SearchJob,
	});
	const stop = new AbortController();
	try {
		const answer = await Promise.race([
			once(worker, "message", { signal: stop.signal }),
			sleep(timeoutMs, null, { signal: stop.signal }),
		]);
		const proved: unknown = answer?.[0];
		return Array.isArray(proved) && proved.length === claims.length
			? proved.map((value) => value === true)
			: claims.map(() => false);
	} catch {
		return claims.map(() => false);
	} finally {
		stop.abort();
		await worker.terminate();
	}
}
