import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";
import { setTimeout } from "node:timers/promises";

import { annotation, deadlineReport, descendants, exitStatus, innermost } from "./ci-deadline.ts";

const directory = mkdtempSync(path.join(tmpdir(), "ci-deadline-"));
after(() => rmSync(directory, { recursive: true, force: true }));
const script = path.join(import.meta.dirname, "ci-deadline.ts");

const row = (pid: number, ppid: number) => ({ pid, ppid, elapsed: "01:00", command: `p${pid}` });

/** Whether any process on the host has `marker` in its command line. */
const running = (marker: string) =>
	spawnSync("ps", ["-A", "-o", "args="], { encoding: "utf8" }).stdout.includes(marker);

void test("the processes of a task are its root and everything below it, not its neighbours", () => {
	const table = [row(1, 0), row(10, 1), row(11, 10), row(12, 11), row(20, 1), row(13, 10)];
	assert.deepEqual(
		descendants(table, 10).map(({ pid }) => pid),
		[10, 11, 12, 13],
	);
	assert.match(deadlineReport(900, [row(11, 10)]), /deadline of 900 s[^]*\n\s+11\s+01:00\s+p11$/u);
});

void test("a task that hangs past its deadline is named in the step's output and summary, and stopped", async (t) => {
	if (process.platform === "win32") {
		t.skip("the process listing is checked on the POSIX runners");
		return;
	}
	// Unique to this run, so that no other command line on the host contains it.
	const marker = `hung-task-${randomUUID()}`;
	const summary = path.join(directory, "summary.md");
	const started = Date.now();
	const result = spawnSync(
		process.execPath,
		[script, "2", "vp", "exec", "node", "-e", `setInterval(() => {}, 1e6) // ${marker}`],
		{ encoding: "utf8", timeout: 60_000, env: { ...process.env, GITHUB_STEP_SUMMARY: summary } },
	);
	assert.equal(result.status, 124, result.stderr);
	assert.ok(Date.now() - started < 30_000);
	assert.match(
		result.stderr,
		/::error title=Quality gates hit their deadline::The step reached its deadline of 2 s/u,
	);
	assert.ok(result.stderr.includes(marker), result.stderr);
	const written = readFileSync(summary, "utf8");
	assert.match(written, /### Quality gates hit their deadline/u);
	assert.ok(written.includes(marker), written);
	// A stopped process can still be leaving the process table for a moment.
	for (let waited = 0; waited < 5000 && running(marker); waited += 250) {
		await setTimeout(250);
	}
	assert.ok(!running(marker), "the hung task is stopped");
});

void test("a task that ends before its deadline keeps its own exit code", () => {
	const result = spawnSync(
		process.execPath,
		[script, "60", "vp", "exec", "node", "-e", "process.exit(3)"],
		{
			encoding: "utf8",
			timeout: 60_000,
		},
	);
	assert.equal(result.status, 3, result.stderr);
	assert.doesNotMatch(result.stderr, /deadline/u);
});

void test("a task killed by a signal reports it as a shell does", () => {
	assert.equal(exitStatus(0, null), 0);
	assert.equal(exitStatus(3, null), 3);
	assert.equal(exitStatus(null, "SIGKILL"), 137);
	assert.equal(exitStatus(null, "SIGTERM"), 143);
});

void test("the report's first line names the commands the task waits on, and is never empty", () => {
	// What run 41988545096 listed: the vp task runner, the gate, and the promtool container it waited on.
	const listed = [
		{
			pid: 2750,
			ppid: 2700,
			elapsed: "15:00",
			command: "node vite-plus/dist/bin.js run ci:tooling",
		},
		{ pid: 9114, ppid: 2750, elapsed: "13:31", command: "node scripts/check-alert-rules.ts" },
		{
			pid: 9598,
			ppid: 9114,
			elapsed: "13:26",
			command: "docker run --entrypoint promtool prom/prometheus test rules",
		},
	];
	assert.deepEqual(
		innermost(listed).map(({ pid }) => pid),
		[9598],
	);
	const [headline = ""] = deadlineReport(900, listed).split("\n");
	assert.match(
		headline,
		/Still running: docker run --entrypoint promtool prom\/prometheus test rules \(13:26\)$/u,
	);
	assert.match(deadlineReport(900, []).split("\n")[0] ?? "", /no process of its task was found/u);
});

void test("an annotation carries the whole multi-line report", () => {
	assert.equal(annotation("50% done\nnext\r\nline"), "50%25 done%0Anext%0D%0Aline");
});
