import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { access, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

const workspace = asString(process.env.GITHUB_WORKSPACE, "GITHUB_WORKSPACE");
const runnerTemp = asString(process.env.RUNNER_TEMP, "RUNNER_TEMP");
const gradleHome = asString(process.env.GRADLE_USER_HOME, "GRADLE_USER_HOME");
assert.ok(workspace.length > 0 && runnerTemp.length > 0 && gradleHome.length > 0);
const proof = path.join(runnerTemp, "r-proof");
const snapshotFile = path.join(proof, "cache-inventory.json");
async function fileExists(file: string): Promise<boolean> {
	try {
		await access(file);
		return true;
	} catch (error) {
		if (error instanceof Error && "code" in error && error.code === "ENOENT") {
			return false;
		}
		throw error;
	}
}
if (await fileExists(snapshotFile)) {
	const snapshot = asRecord(parseJson(await readFile(snapshotFile, "utf8")), "cache snapshot");
	const removed: string[] = [];
	for (const value of asArray(snapshot.coordinates, "coordinates")) {
		const coordinate = asRecord(value, "coordinate");
		for (const artifact of asArray(coordinate.artifacts, "artifacts")) {
			const relative = asString(asRecord(artifact, "artifact").file, "artifact path");
			assert.match(relative, /^caches\/modules-[^/]+\/files-[^/]+\/[^.]/u);
			assert.equal(relative.split("/").includes(".."), false);
			if (!(await fileExists(path.join(gradleHome, relative)))) {
				removed.push(relative);
			}
		}
	}
	console.log(
		"R_POST_SAVE_REMOVED",
		JSON.stringify({ resolution: process.env.R_RESOLUTION, removed }),
	);
	await writeFile(path.join(proof, "removed-after-cleanup.json"), JSON.stringify(removed, null, 2));
	const result = spawnSync(
		"./gradlew",
		[
			":application:liquibaseUpdate",
			":application:liquibaseDiff",
			"-PpostgresPort=5432",
			"-PpackagedServer=true",
			"--offline",
			"--no-configuration-cache",
			"--stacktrace",
			"--info",
		],
		{
			cwd: path.join(workspace, "candidate/server"),
			encoding: "utf8",
			maxBuffer: 64 * 1024 * 1024,
		},
	);
	if (result.error !== undefined) {
		throw result.error;
	}
	process.stdout.write(result.stdout);
	process.stderr.write(result.stderr);
	console.log(
		"R_POST_SAVE_OFFLINE",
		JSON.stringify({
			resolution: process.env.R_RESOLUTION,
			status: result.status,
			signal: result.signal,
		}),
	);
	const jars = spawnSync(process.execPath, [path.join(proof, "snapshot.mts"), "verify"], {
		cwd: path.join(workspace, "candidate"),
		stdio: "inherit",
	});
	assert.equal(jars.status, 0, "the offline consumer must not change the producer's JARs");
	assert.equal(
		result.status,
		0,
		"the saved producer cache must support the original schema invocation offline",
	);
} else {
	console.log(
		"R post-save proof is inconclusive: the producer did not reach its pre-cleanup snapshot.",
	);
}
