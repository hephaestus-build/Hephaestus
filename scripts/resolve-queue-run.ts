/**
 * Writes `run-id`: the successful merge-queue CI/CD run of this push's exact commit, whose images
 * the push may reuse instead of rebuilding them. Writes nothing when there is none, as after a
 * direct push, and every image is then built.
 */
import { appendFile } from "node:fs/promises";

import { requiredEnv } from "./lib/env.ts";
import { ghApi, resolveQueueRun } from "./resolve-server-build.ts";

const runId = await resolveQueueRun(
	requiredEnv(process.env, "GITHUB_REPOSITORY"),
	requiredEnv(process.env, "GITHUB_SHA"),
	requiredEnv(process.env, "DEFAULT_BRANCH"),
	ghApi,
);
if (runId !== undefined) {
	await appendFile(requiredEnv(process.env, "GITHUB_OUTPUT"), `run-id=${runId}\n`);
}
