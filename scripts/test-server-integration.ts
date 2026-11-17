// Runs the server integration tier as CI runs it: the review shard and each numbered shard, one after
// another, each in a test JVM of its own. One JVM for the whole tier caches more test contexts than
// its heap holds (docs/contributor/ci-cd.mdx § Integration shards).

import { spawnSync } from "node:child_process";
import { existsSync, renameSync, rmSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { committedShards } from "./lib/integration-durations.ts";

const TASK = ":application:integrationTest";
const BUILD = path.resolve(import.meta.dirname, "../server/application/build");
// Gradle's default report locations of the task. Each shard's run replaces them.
const REPORTS = ["test-results/integrationTest", "reports/tests/integrationTest"];

/** The project properties that select tests. Gradle rejects `criteriaCases` together with a shard. */
const SELECTING_PROPERTIES = new Set(["criteriaCases"]);

/**
 * Whether the arguments select the tests themselves. Such a run holds few classes, and a shard
 * without them would fail on no matching tests, so it is one Gradle run.
 */
export function selectsTests(gradleArguments: readonly string[]): boolean {
	return gradleArguments.some((argument, index) => {
		if (argument === "--tests" || argument.startsWith("--tests=")) {
			return true;
		}
		const property =
			argument === "-P" || argument === "--project-prop"
				? gradleArguments[index + 1]
				: /^(?:-P|--project-prop=)(?<property>.+)$/u.exec(argument)?.groups?.property;
		return property !== undefined && SELECTING_PROPERTIES.has(property.split("=")[0] ?? "");
	});
}

/** The shard of each Gradle run, or `undefined` for one run with the caller's own selection. */
export function integrationRuns(
	selectedShard: string | undefined,
	gradleArguments: readonly string[],
	shards: number,
): readonly (string | undefined)[] {
	// A CI job names its shard, and its steps read the reports at Gradle's default locations.
	if ((selectedShard ?? "") !== "" || selectsTests(gradleArguments)) {
		return [undefined];
	}
	return ["review", ...Array.from({ length: shards }, (_, index) => String(index + 1))];
}

/** Moves a shard's reports out of the way of the next shard, and returns where they are now. */
export function keepShardReports(build: string, shard: string): readonly string[] {
	return REPORTS.flatMap((report) => {
		const source = path.join(build, report);
		if (!existsSync(source)) {
			return [];
		}
		const target = `${source}-${shard}`;
		rmSync(target, { recursive: true, force: true });
		renameSync(source, target);
		return [target];
	});
}

function main(): void {
	const gradleArguments = process.argv.slice(2);
	const runs = integrationRuns(
		process.env.HEPHAESTUS_INTEGRATION_SHARD,
		gradleArguments,
		committedShards(),
	);
	const failed: string[] = [];
	// Every shard runs, also after a failed one: the run reports all of the tier's failures at once.
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
		const reports = shard === undefined ? [] : keepShardReports(BUILD, shard);
		if (result.status !== 0) {
			failed.push(`${shard ?? TASK}${reports.length > 0 ? `: ${reports.join(", ")}` : ""}`);
		}
	}
	if (failed.length > 0) {
		console.error(`Failed integration runs and their reports:\n${failed.join("\n")}`);
		process.exitCode = 1;
	}
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
	main();
}
