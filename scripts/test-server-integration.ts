// Runs the server integration tier as CI runs it: the review shard and each numbered shard, one after
// another, each in a test JVM of its own. One JVM for the whole tier caches more test contexts than
// its heap holds (docs/contributor/ci-cd.mdx § Integration shards).

import { spawnSync } from "node:child_process";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { committedShards } from "./lib/integration-durations.ts";

const TASK = ":application:integrationTest";

/** The shard of each Gradle run, or `undefined` for one run with the caller's own selection. */
export function integrationRuns(
	selectedShard: string | undefined,
	gradleArguments: readonly string[],
	shards: number,
): readonly (string | undefined)[] {
	// A CI job names its shard. A run with --tests selects a few classes, and a shard without them
	// would fail on no matching tests.
	if ((selectedShard ?? "") !== "" || gradleArguments.includes("--tests")) {
		return [undefined];
	}
	return ["review", ...Array.from({ length: shards }, (_, index) => String(index + 1))];
}

function main(): void {
	const gradleArguments = process.argv.slice(2);
	const runs = integrationRuns(
		process.env.HEPHAESTUS_INTEGRATION_SHARD,
		gradleArguments,
		committedShards(),
	);
	const failed: string[] = [];
	for (const shard of runs) {
		const result = spawnSync(
			process.execPath,
			[path.join(import.meta.dirname, "run-gradlew.ts"), TASK, ...gradleArguments],
			{
				stdio: "inherit",
				env:
					shard === undefined
						? process.env
						: { ...process.env, HEPHAESTUS_INTEGRATION_SHARD: shard },
			},
		);
		if (result.status !== 0) {
			failed.push(shard ?? TASK);
		}
	}
	if (failed.length > 0) {
		console.error(`Integration shards that failed: ${failed.join(", ")}`);
		process.exitCode = 1;
	}
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
	main();
}
