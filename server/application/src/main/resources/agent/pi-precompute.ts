import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync } from "node:fs";
import path from "node:path";

import { CHANGE_ROOT } from "./pi-change.ts";
import { SUPPORTED_SCHEMA_VERSION, resolveTaskPaths } from "./pi-task-paths.ts";

const root = path.resolve(process.argv[2] ?? "/workspace");
const envelope: unknown = JSON.parse(readFileSync(path.resolve(root, "task.json"), "utf8"));
if (typeof envelope !== "object" || envelope === null) {
	throw new Error("task.json: expected an envelope");
}
if (Reflect.get(envelope, "schemaVersion") !== SUPPORTED_SCHEMA_VERSION) {
	throw new Error("task.json: unsupported schemaVersion");
}
const paths = resolveTaskPaths(root, Reflect.get(envelope, "paths"));
const stage = path.resolve(root, "work/precompute-stage");
const output = path.resolve(root, "work/precompute-out");
rmSync(path.resolve(stage, "practices"), { recursive: true, force: true });
mkdirSync(path.resolve(stage, "practices"), { recursive: true });
mkdirSync(output, { recursive: true });
if (existsSync(paths.precomputeScripts)) {
	for (const entry of readdirSync(paths.precomputeScripts, { withFileTypes: true })) {
		if (entry.isFile() && entry.name.endsWith(".ts")) {
			copyFileSync(
				path.resolve(paths.precomputeScripts, entry.name),
				path.resolve(stage, "practices", entry.name),
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
		path.resolve(root, CHANGE_ROOT, "diff.patch"),
		"--metadata",
		path.resolve(paths.contextRoot, "metadata.json"),
		"--context",
		paths.contextRoot,
		"--context-reference",
		path.relative(root, paths.contextRoot),
		"--change",
		path.resolve(root, CHANGE_ROOT),
		"--practices",
		path.resolve(stage, "practices"),
		"--output",
		output,
	],
	{ stdio: "inherit" },
);
if (child.error) {
	throw child.error;
}
process.exitCode = child.status ?? 1;
