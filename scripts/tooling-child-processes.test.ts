import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { commandsOf, loadTasks } from "./lib/task-graph.ts";

void test("tooling fixtures replace the child process module's launch methods", () => {
	const fixture = new URL("fixtures/stub-child-processes.ts", import.meta.url).href;
	const processTools = new URL("lib/process.ts", import.meta.url).href;
	const result = spawnSync(process.execPath, ["--input-type=module"], {
		input: `
import assert from "node:assert/strict";
import childProcess, * as named from "node:child_process";
import { stubChildProcesses } from ${JSON.stringify(fixture)};
const launch = Object.entries(childProcess).filter(
  ([name, value]) => /^(?:spawn|exec|fork)/u.test(name) && typeof value === "function"
);
const executable = "missing-tooling-fixture-command";
stubChildProcesses((command, args) => {
  assert.equal(command, executable);
  return { status: args.includes("fail") ? 7 : 0, stdout: "stubbed", stderr: "" };
});
for (const [name, original] of launch) {
  assert.notEqual(childProcess[name], original, name);
  assert.equal(named[name], childProcess[name], name);
  if (name !== "spawn" && name !== "spawnSync") {
    assert.throws(() => named[name](executable, []), /Unexpected child process/u, name);
  }
}
const { output, run } = await import(${JSON.stringify(processTools)});
assert.equal(named.spawnSync(executable, ["inspect"]).stdout, "stubbed");
await run(executable, ["cp"]);
await assert.rejects(run(executable, ["fail"]), /exited with code 7/u);
await assert.rejects(output(executable, ["inspect"]), /Unexpected child process: execFile/u);
`,
		encoding: "utf8",
	});
	assert.equal(result.status, 0, result.stderr);
});

void test(
	"an unstubbed Docker call fails the tooling run even if its exit code is ignored",
	{
		skip: process.platform === "win32",
	},
	async () => {
		const tasks = await loadTasks();
		const command = commandsOf(tasks["test:tooling"]).join(" ");
		const tripwire = /(?:^|\s)--import\s+(?<file>\S+)/u.exec(command)?.groups?.file;
		assert.ok(tripwire !== undefined, "test:tooling must preload the Docker tripwire");
		const directory = mkdtempSync(path.join(tmpdir(), "tooling-path-"));
		try {
			const file = path.join(directory, "unstubbed.test.mjs");
			writeFileSync(
				file,
				'import { spawnSync } from "node:child_process"; spawnSync("docker", ["version"]);',
			);
			const result = spawnSync(process.execPath, ["--import", tripwire, "--test", file], {
				encoding: "utf8",
				env: { ...process.env, PATH: directory, NODE_TEST_CONTEXT: undefined },
			});
			const output = result.stdout + result.stderr;
			assert.equal(result.status, 1, output);
			assert.match(output, /Unexpected Docker call in tooling tests/u);
		} finally {
			rmSync(directory, { recursive: true, force: true });
		}
	},
);
