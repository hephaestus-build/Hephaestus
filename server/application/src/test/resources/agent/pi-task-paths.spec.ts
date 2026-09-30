import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
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
		assert.throws(() => taskPaths(invalid), /task.json/u);
	});
}
for (const invalid of [
	"",
	" ",
	"\u00A0",
	"\u2007",
	"\u202F",
	"/etc/passwd",
	"../secret",
	"area/../secret",
	"area/./file",
	"area//file",
	"area/",
	"C:/secret",
	String.raw`area\file`,
	"area\u0000file",
	"area\nfile",
	"area\u0085file",
]) {
	void test(`rejects non-normalized or unsafe task paths: ${JSON.stringify(invalid)}`, () => {
		for (const key of Object.keys(paths)) {
			assert.throws(
				() => taskPaths({ ...paths, [key]: invalid }),
				/normalized workspace-relative path/u,
			);
		}
	});
}
void test("ignores additive metadata without relaxing required path validation", () => {
	assert.deepEqual(taskPaths({ ...paths, interactiveFrames: { url: "/frames" } }), paths);
	assert.throws(() => taskPaths({ ...paths, manifest: 42 }), /manifest/u);
});

void test("accepts the envelope produced by the Java task writer", () => {
	const envelope: unknown = JSON.parse(
		readFileSync(new URL("../task-fixtures/v3/practice-review.json", import.meta.url), "utf8"),
	);
	assert.ok(typeof envelope === "object" && envelope !== null);
	assert.equal(taskPaths(envelope).contextRoot, Reflect.get(envelope, "contextRoot"));
	assert.equal(Reflect.get(envelope, "schemaVersion"), 3);
	assert.equal(Reflect.has(envelope, "kind"), false);
	assert.equal(Reflect.has(envelope, "task"), false);
	assert.equal(Reflect.has(envelope, "paths"), false);
});

for (const [name, envelope] of Object.entries({
	"old schema": { schemaVersion: 2, ...paths, prompt: "Review" },

	"missing paths": { schemaVersion: 3, prompt: "Review" },
	traversal: {
		schemaVersion: 3,
		...paths,
		manifest: "../secret",
		prompt: "Review",
	},
})) {
	void test(`runner exits with contract drift before execution for ${name}`, () => {
		const root = mkdtempSync(path.join(tmpdir(), "invalid-task-"));
		try {
			writeFileSync(path.join(root, "task.json"), JSON.stringify(envelope));
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
