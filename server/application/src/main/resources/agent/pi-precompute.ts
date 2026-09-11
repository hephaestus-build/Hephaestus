import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { resolve } from "node:path";

import { SUPPORTED_SCHEMA_VERSION, resolveTaskPaths } from "./pi-task-paths.ts";

const root = resolve(process.argv[2] ?? "/workspace");
const envelope: unknown = JSON.parse(readFileSync(resolve(root, "task.json"), "utf8"));
if (typeof envelope !== "object" || envelope === null) {
	throw new Error("task.json: expected an envelope");
}
if (Reflect.get(envelope, "schemaVersion") !== SUPPORTED_SCHEMA_VERSION) {
	throw new Error("task.json: unsupported schemaVersion");
}
const paths = resolveTaskPaths(root, Reflect.get(envelope, "paths"));
const stage = resolve(root, "work/precompute-stage");
const output = resolve(root, "work/precompute-out");
rmSync(resolve(stage, "practices"), { recursive: true, force: true });
mkdirSync(resolve(stage, "practices"), { recursive: true });
mkdirSync(output, { recursive: true });
if (existsSync(paths.precomputeScripts)) {
	for (const entry of readdirSync(paths.precomputeScripts, { withFileTypes: true })) {
		if (entry.isFile() && entry.name.endsWith(".ts")) {
			copyFileSync(
				resolve(paths.precomputeScripts, entry.name),
				resolve(stage, "practices", entry.name),
			);
		}
	}
}
const child = spawnSync(
	process.execPath,
	[
		"--permission",
		`--allow-fs-read=${root}`,
		"--allow-fs-read=/opt/precompute",
		`--allow-fs-write=${output}*`,
		"--allow-child-process",
		"/opt/precompute/runner.ts",
		"--repo",
		paths.repositoryRoot,
		"--diff",
		resolve(paths.contextRoot, "diff.patch"),
		"--metadata",
		resolve(paths.contextRoot, "metadata.json"),
		"--context",
		paths.contextRoot,
		"--practices",
		resolve(stage, "practices"),
		"--output",
		output,
	],
	{ stdio: "inherit" },
);
if (child.error) throw child.error;
process.exitCode = child.status ?? 1;
