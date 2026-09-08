import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { mock, test } from "node:test";
import { fileURLToPath } from "node:url";

const imageRoot = fileURLToPath(
	new URL("../../../../../../docker/agents/precompute", import.meta.url),
);

const scenarioRoot = process.env.PRECOMPUTE_SCENARIO_ROOT;
if (scenarioRoot) {
	mock.module("node:child_process", {
		namedExports: {
			spawnSync(command: string, args: string[]) {
				assert.equal(command, process.execPath);
				// Substitute only the image installation prefix; execute the real runner and permissions.
				return spawnSync(
					command,
					args.map((arg) => arg.replace("/opt/precompute", imageRoot)),
					{ stdio: "inherit" },
				);
			},
		},
	});
	process.argv[2] = scenarioRoot;
	await import("../../../main/resources/agent/pi-precompute.ts");
} else {
	void test("precompute stages only regular scripts and executes the image runner with task-declared locations", () => {
		const root = mkdtempSync(join(tmpdir(), "task-precompute-#"));
		try {
			const context = "areas/changed work";
			const scripts = "catalog/scripts";
			mkdirSync(join(root, context), { recursive: true });
			mkdirSync(join(root, scripts), { recursive: true });
			writeFileSync(
				join(root, context, "diff.patch"),
				"diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -0,0 +1 @@\n[L1] +line\n",
			);
			writeFileSync(
				join(root, scripts, "example.ts"),
				`import { readFileSync } from "node:fs";
import { parseDiff } from "../lib/diff-parser.ts";
export default (repo, diff, metadata, context) => ({
 hints: [], directions: [], metrics: {
  files: diff.size,
  addedLine: Number(diff.get("a").addedLines.get(1) === "line"),
  parsed: parseDiff(readFileSync(context + "/diff.patch", "utf8")).size,
  context: Number(readFileSync(context + "/marker", "utf8")),
  repo: Number(readFileSync(repo + "/marker", "utf8")),
  metadata: metadata.marker
 }
});
`,
			);
			for (const [name, source] of Object.entries({
				"missing-export": "export const value = 1;",
				"invalid-result":
					"export default () => ({ hints: [], directions: [], metrics: { count: Infinity } });",
				throws: "export default () => { throw new Error('broken practice'); };",
			})) {
				writeFileSync(join(root, scripts, `${name}.ts`), source);
			}
			mkdirSync(join(root, "repos/project with spaces"), { recursive: true });
			writeFileSync(join(root, "repos/project with spaces/marker"), "7");
			writeFileSync(join(root, context, "marker"), "11");
			writeFileSync(join(root, context, "metadata.json"), JSON.stringify({ marker: 13 }));
			symlinkSync("example.ts", join(root, scripts, "linked.ts"));
			writeFileSync(
				join(root, "task.json"),
				JSON.stringify({
					schemaVersion: 2,
					paths: {
						contextRoot: context,
						repositoryRoot: "repos/project with spaces",
						manifest: "manifest.json",
						practiceIndex: "catalog/index.json",
						compositionRequest: "composition.json",
						preparedFeedback: "history/prepared.json",
						precomputeScripts: scripts,
					},
				}),
			);
			mkdirSync(join(root, "work/precompute-stage"), { recursive: true });
			mkdirSync(join(root, "work/precompute-out"), { recursive: true });
			symlinkSync(join(imageRoot, "lib"), join(root, "work/precompute-stage/lib"));
			const child = spawnSync(
				process.execPath,
				[
					"--experimental-test-module-mocks",
					"--permission",
					"--allow-worker",
					`--allow-fs-read=${fileURLToPath(new URL("../../../", import.meta.url))}`,
					`--allow-fs-read=${root}`,
					`--allow-fs-read=${imageRoot}`,
					"--allow-child-process",
					`--allow-fs-write=${root}/work/precompute-stage*`,
					`--allow-fs-write=${root}/work/precompute-out*`,
					import.meta.filename,
				],
				{
					env: { ...process.env, PRECOMPUTE_SCENARIO_ROOT: root },
					encoding: "utf8",
					timeout: 10_000,
				},
			);
			assert.equal(child.status, 0, child.stderr);
			const result: unknown = JSON.parse(
				readFileSync(join(root, "work/precompute-out/example.json"), "utf8"),
			);
			assert.ok(typeof result === "object" && result !== null);
			assert.equal(Reflect.get(result, "status"), "ok");
			assert.deepEqual(Reflect.get(result, "metrics"), {
				files: 1,
				addedLine: 1,
				metadata: 13,
				parsed: 1,
				context: 11,
				repo: 7,
			});
			assert.ok(readFileSync(join(root, "work/precompute-out/.complete"), "utf8"));
			for (const slug of ["missing-export", "invalid-result", "throws"]) {
				const failure: unknown = JSON.parse(
					readFileSync(join(root, `work/precompute-out/${slug}.json`), "utf8"),
				);
				assert.ok(typeof failure === "object" && failure !== null);
				assert.equal(Reflect.get(failure, "status"), "error");
				assert.deepEqual(Reflect.get(failure, "hints"), []);
			}
			assert.match(
				readFileSync(join(root, "work/precompute-out/summary.md"), "utf8"),
				/3 script\(s\) failed/,
			);

			assert.throws(
				() => readFileSync(join(root, "work/precompute-stage/practices/linked.ts")),
				/ENOENT/,
			);
		} finally {
			rmSync(root, { recursive: true, force: true });
		}
	});
}
