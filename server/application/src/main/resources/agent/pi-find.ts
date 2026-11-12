import { existsSync } from "node:fs";
import path from "node:path";

import { createFindToolDefinition } from "@earendil-works/pi-coding-agent";

import type { GlobJob } from "./pi-glob-worker.ts";
import { onWorker } from "./pi-verified-search.ts";

const FIND_TIMEOUT_MS = 20_000;
const GLOB_WORKER = new URL(`pi-glob-worker${path.extname(import.meta.filename)}`, import.meta.url);

/** Matches on a worker: a pattern that backtracks for ever must not stop the loop. */
export async function globApart(job: GlobJob): Promise<string[]> {
	const found = await onWorker(GLOB_WORKER, job, FIND_TIMEOUT_MS);
	if (!Array.isArray(found)) {
		throw new TypeError("The pattern took too long to match. Try a simpler one.");
	}
	return found.filter((file) => typeof file === "string");
}

/** Matches globs on a worker so a pattern cannot block the sandbox loop. */
export function loopFindTool(root: string) {
	return createFindToolDefinition(root, {
		operations: {
			exists: (file) => existsSync(file),
			glob: async (pattern, cwd, { ignore, limit }) => globApart({ pattern, cwd, ignore, limit }),
		},
	});
}
