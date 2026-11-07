// pi-glob-worker.ts — the loop's `find`, on a thread that the loop can end.
//
// A pattern comes from a model, and matching a hostile one against a file name can run for ever.

import { globSync } from "node:fs";
import { parentPort, workerData } from "node:worker_threads";

export interface GlobJob {
	pattern: string;
	cwd: string;
	ignore: readonly string[];
	limit: number;
}

if (parentPort !== null) {
	// The loop that started this thread wrote the job.
	// oxlint-disable-next-line typescript/no-unsafe-assignment
	const job: GlobJob = workerData;
	const found = globSync(job.pattern, { cwd: job.cwd, exclude: [...job.ignore] });
	parentPort.postMessage(found.slice(0, job.limit), []);
}
