import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { resolveTaskPaths, taskPaths } from "../../../main/resources/agent/pi-task-paths.ts";

const paths = {
	contextRoot: "areas/scm",
	repositoryRoot: "repos/project",
	manifest: "manifest.json",
	practiceIndex: "practices/index.json",
	compositionRequest: "composition/request.json",
	preparedFeedback: "history/prepared.json",
	precomputeScripts: "scripts/practices",
};

void test("resolves a completely relocated layout without any captured-input prefix", () => {
	assert.deepEqual(taskPaths(paths), paths);
	const resolved = resolveTaskPaths("/workspace", paths);
	for (const [key, value] of Object.entries(paths)) {
		assert.equal(Reflect.get(resolved, key), `/workspace/${value}`);
	}
});

for (const invalid of [undefined, null, [], "paths", {}]) {
	void test(`rejects a missing or invalid path map: ${JSON.stringify(invalid)}`, () => {
		assert.throws(() => taskPaths(invalid), /task.json/);
	});
}
for (const invalid of [
	"",
	" ",
	"\u00a0",
	"\u2007",
	"\u202f",
	"/etc/passwd",
	"../secret",
	"area/../secret",
	"area/./file",
	"area//file",
	"area/",
	"C:/secret",
	"area\\file",
	"area\u0000file",
	"area\nfile",
	"area\u0085file",
]) {
	void test(`rejects non-normalized or unsafe task paths: ${JSON.stringify(invalid)}`, () => {
		for (const key of Object.keys(paths)) {
			assert.throws(
				() => taskPaths({ ...paths, [key]: invalid }),
				/normalized workspace-relative path/,
			);
		}
	});
}
void test("ignores additive metadata without relaxing required path validation", () => {
	assert.deepEqual(taskPaths({ ...paths, interactiveFrames: { url: "/frames" } }), paths);
	assert.throws(() => taskPaths({ ...paths, manifest: 42 }), /paths.manifest/);
});

void test("accepts the envelope produced by the Java task writer", () => {
	const envelope: unknown = JSON.parse(
		readFileSync(new URL("../task-fixtures/v2/practice-review.json", import.meta.url), "utf8"),
	);
	assert.ok(typeof envelope === "object" && envelope !== null);
	assert.deepEqual(taskPaths(Reflect.get(envelope, "paths")), Reflect.get(envelope, "paths"));
});

for (const [name, envelope] of Object.entries({
	"old schema": { schemaVersion: 1, paths, task: { kind: "practice_review", prompt: "Review" } },

	"missing paths": { schemaVersion: 2, task: { kind: "practice_review", prompt: "Review" } },
	traversal: {
		schemaVersion: 2,
		paths: { ...paths, manifest: "../secret" },
		task: { kind: "practice_review", prompt: "Review" },
	},
})) {
	void test(`runner exits with contract drift before execution for ${name}`, () => {
		const root = mkdtempSync(join(tmpdir(), "invalid-task-"));
		try {
			writeFileSync(join(root, "task.json"), JSON.stringify(envelope));
			const child = spawnSync(
				process.execPath,
				[fileURLToPath(new URL("../../../main/resources/agent/pi-runner.ts", import.meta.url))],
				{
					env: { ...process.env, PI_RUNNER_CWD: root },
					encoding: "utf8",
					timeout: 10_000,
				},
			);
			assert.equal(child.status, 42, child.stderr);
		} finally {
			rmSync(root, { recursive: true, force: true });
		}
	});
}
