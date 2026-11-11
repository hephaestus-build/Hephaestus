import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { createServer, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import {
	annotation,
	deadlineReport,
	descendants,
	exitStatus,
	innermost,
	runWithDeadline,
} from "./ci-deadline.ts";

const directory = mkdtempSync(path.join(tmpdir(), "ci-deadline-"));
after(() => rmSync(directory, { recursive: true, force: true }));

const row = (pid: number, ppid: number) => ({ pid, ppid, elapsed: "01:00", command: `p${pid}` });

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
	const previousSummary = process.env.GITHUB_STEP_SUMMARY;
	process.env.GITHUB_STEP_SUMMARY = summary;
	t.after(() => {
		if (previousSummary === undefined) {
			delete process.env.GITHUB_STEP_SUMMARY;
		} else {
			process.env.GITHUB_STEP_SUMMARY = previousSummary;
		}
	});
	let stderr = "";
	t.mock.method(process.stderr, "write", (chunk: unknown) => {
		stderr += String(chunk);
		return true;
	});
	const connected = Promise.withResolvers<Socket>();
	const server = createServer(connected.resolve);
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	t.after(() => {
		server.close();
	});
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("Missing fixture address");
	}
	t.mock.timers.enable({ apis: ["setTimeout"] });
	const result = runWithDeadline(2, [
		"exec",
		"node",
		"-e",
		`require('node:net').connect(${address.port}, '127.0.0.1'); // ${marker}`,
	]);
	t.after(async () => {
		t.mock.timers.tick(12_000);
		await result;
	});
	const socket = await connected.promise;
	const disconnected = once(socket, "close");
	t.mock.timers.tick(2000);
	assert.equal(await result, 124, stderr);
	await disconnected;
	assert.match(
		stderr,
		/::error title=Quality gates hit their deadline::The step reached its deadline of 2 s/u,
	);
	assert.ok(stderr.includes(marker), stderr);
	const written = readFileSync(summary, "utf8");
	assert.match(written, /### Quality gates hit their deadline/u);
	assert.ok(written.includes(marker), written);
});

void test("a task that ends before its deadline keeps its own exit code", async (t) => {
	t.mock.timers.enable({ apis: ["setTimeout"] });
	assert.equal(await runWithDeadline(60, ["exec", "node", "-e", "process.exit(3)"]), 3);
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
