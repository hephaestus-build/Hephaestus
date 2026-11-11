import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { unpassedTasks } from "./report-task-run.ts";

const report = `
Statistics:   4 tasks • 1 cache hits • 1 cache misses • 2 cache disabled • 2 failed
  [1] hephaestus#gate:webapp: $ vp -C webapp check ✓
      → Cache hit - output replayed - 8.16s saved
  ·······················································
  [2] hephaestus#gate:stories: $ node scripts/check-story-prose.ts ✗ (exit code: 1)
      → Cache miss: 'webapp/src/a.stories.tsx' modified
  ·······················································
  [3] hephaestus#gate:server: $ node scripts/run-gradlew.ts :spotlessCheck :application:spotlessCheck :application:pmdMain --quiet ✗ (exit code: 137)
  ·······················································
  [4] hephaestus#gate:server: $ node scripts/run-gradlew.ts :spotlessCheck :application:spotlessCheck :application:pmdMain --quiet ✗ (exit code: 1)
`;

void test("names each task that did not pass once, in report order", () => {
	assert.deepEqual(unpassedTasks(report), ["gate:stories", "gate:server"]);
});

void test("a clean report names nothing", () => {
	assert.deepEqual(unpassedTasks("  [1] hephaestus#gate:webapp: $ vp -C webapp check ✓\n"), []);
});

void test("names a task the runner stopped, because the report does not distinguish it", () => {
	const stopped = `
  [1] hephaestus#gate:stories: $ node scripts/check-story-prose.ts ✗ (exit code: 1)
  [2] hephaestus#gate:runner-contract: $ node --test scripts/check-runner-contract.ts ✗ (exit code: 137)
`;
	assert.deepEqual(unpassedTasks(stopped), ["gate:stories", "gate:runner-contract"]);
});

void test("reads a coloured report, which would otherwise name nothing and exit clean", () => {
	const esc = "\u001B";
	const coloured =
		`  ${esc}[90m[1]${esc}[0m ${esc}[97;1mhephaestus#gate:stories${esc}[0m: ` +
		`${esc}[34m$ node scripts/check-story-prose.ts${esc}[0m ${esc}[31m\u2717${esc}[0m (exit code: 1)\n`;
	assert.deepEqual(unpassedTasks(coloured), ["gate:stories"]);
});

void test("the CLI emits unknown-cause notices and a run-attempt link without inferring a first failure", async (context) => {
	const directory = await mkdtemp(path.join(tmpdir(), "task-report-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const summary = path.join(directory, "summary");
	const result = spawnSync(
		process.execPath,
		[fileURLToPath(new URL("report-task-run.ts", import.meta.url))],
		{
			input: report,
			encoding: "utf8",
			env: {
				...process.env,
				GITHUB_STEP_SUMMARY: summary,
				GITHUB_SERVER_URL: "https://github.com",
				GITHUB_REPOSITORY: "example/repository",
				GITHUB_RUN_ID: "123",
				GITHUB_RUN_ATTEMPT: "2",
			},
		},
	);
	assert.equal(result.status, 1, result.stderr);
	assert.equal(result.stdout.split("\n").filter((line) => line.startsWith("::notice::")).length, 2);
	assert.doesNotMatch(result.stdout, /::error::|first failure|task failed/u);
	assert.deepEqual(result.stdout.trim().split("\n"), [
		"::notice::gate:stories: unpassed, cause unavailable. Run attempt: https://github.com/example/repository/actions/runs/123/attempts/2 | Reproduce with: vp run gate:stories",
		"::notice::gate:server: unpassed, cause unavailable. Run attempt: https://github.com/example/repository/actions/runs/123/attempts/2 | Reproduce with: vp run gate:server",
	]);
	const rendered = await readFile(summary, "utf8");
	assert.match(rendered, /\*\*unpassed, cause unavailable\*\*/u);
	assert.match(
		rendered,
		/\[Run attempt\]\(https:\/\/github.com\/example\/repository\/actions\/runs\/123\/attempts\/2\)/u,
	);
	assert.match(rendered, /Open the quality job’s \*\*Quality gates\*\* step for task output/u);
	assert.ok(rendered.includes(report.trim()));
});
