// pi-verified-search.ts — whether a search that the tools side ran can stand as proof.
//
// A receipt for a search proves an absence: a result may say that the work has no such thing. The tools side is not
// trusted, and no hash shows a match that it left out, so the loop searches the same files itself. Anything that it
// cannot settle in time proves nothing, and the result then rests on what it read. A command in the sandbox can change
// the files, so the proof also names the digest of the bytes it searched, and admission compares that with the capture.

import { once } from "node:events";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { Worker } from "node:worker_threads";

import type { SearchClaim, SearchJob, SearchProof } from "./pi-search-worker.ts";

/** How long the loop gives one proof: a pattern that backtracks is ended here. */
const PROOF_TIMEOUT_MS = 20_000;

/** The most bytes of a checkout that a proof reads. */
const PROOF_BYTES = 512 * 1024 * 1024;

// Each proof loads this file again, so the commands that a session runs must not be able to write it: a replaced worker
// could name the captured digest for any files. The runtime's workspace is read-only here; a sandbox whose commands
// share the loop's user loads it from the image.
const WORKER = new URL(`pi-search-worker${path.extname(import.meta.filename)}`, import.meta.url);

/** What a worker answers within `timeoutMs`, or null when it fails or runs out of time; it ends either way. */
export async function onWorker(
	file: URL,
	workerData: unknown,
	timeoutMs: number,
): Promise<unknown> {
	const worker = new Worker(file, { workerData });
	const stop = new AbortController();
	try {
		const answer = await Promise.race([
			once(worker, "message", { signal: stop.signal }),
			sleep(timeoutMs, null, { signal: stop.signal }),
		]);
		return answer?.[0] ?? null;
	} catch {
		return null;
	} finally {
		stop.abort();
		await worker.terminate();
	}
}

/**
 * For each claim, whether it stands, with the checkout digest of what it searched. A worker that fails or runs out of
 * time proves none.
 */
export async function proveSearches(
	root: string,
	claims: readonly SearchClaim[],
	{ timeoutMs = PROOF_TIMEOUT_MS, maxBytes = PROOF_BYTES } = {},
): Promise<SearchProof> {
	const none: SearchProof = { proved: claims.map(() => false), checkoutDigest: null };
	if (claims.length === 0) {
		return none;
	}
	const answer = await onWorker(WORKER, { root, maxBytes, claims } satisfies SearchJob, timeoutMs);
	if (
		typeof answer !== "object" ||
		answer === null ||
		!("proved" in answer) ||
		!("checkoutDigest" in answer)
	) {
		return none;
	}
	const { proved, checkoutDigest } = answer;
	return Array.isArray(proved) &&
		proved.length === claims.length &&
		typeof checkoutDigest === "string" &&
		/^[0-9a-f]{64}$/u.test(checkoutDigest)
		? { proved: proved.map((value) => value === true), checkoutDigest }
		: none;
}
