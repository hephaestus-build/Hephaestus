import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { STILL_RUNNING, start, wait } from "./job.ts";

const exitAfter = (milliseconds: number, code: number) => [
	process.execPath,
	"-e",
	`console.log("working"); setTimeout(() => process.exit(${code}), ${milliseconds});`,
];

void test("a job outlives the process that started it, and a bounded wait reports it in steps", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "job-"));
	t.after(async () => {
		await rm(root, { recursive: true, force: true });
	});
	// The starter exits at once, as an agent's command or a harness background task does.
	const module = pathToFileURL(path.join(import.meta.dirname, "job.ts")).href;
	const id = execFileSync(
		process.execPath,
		[
			"--input-type=module",
			"-e",
			`const { start } = await import(${JSON.stringify(module)}); console.log(start(${JSON.stringify(exitAfter(1500, 0))}, ${JSON.stringify(root)}));`,
		],
		{ encoding: "utf8" },
	).trim();
	const early = await wait(id, 0, root);
	assert.equal(early.code, STILL_RUNNING);
	assert.match(early.message, /job:wait/u);
	const done = await wait(id, 20, root);
	assert.equal(done.code, 0, done.message);
});

void test("a failed job reports its status and the end of its log", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "job-"));
	t.after(async () => {
		await rm(root, { recursive: true, force: true });
	});
	const id = start(exitAfter(10, 3), root);
	const outcome = await wait(id, 20, root);
	assert.equal(outcome.code, 1);
	assert.match(outcome.message, /working/u);
	assert.match(outcome.message, /failed \(3\)/u);
	await assert.rejects(wait("../escape", 0, root), /Not a job id/u);
	await assert.rejects(wait("missing", 0, root), /No job missing/u);
});

void test("a job whose supervisor was killed fails instead of waiting forever", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "job-"));
	t.after(async () => {
		await rm(root, { recursive: true, force: true });
	});
	const id = start(exitAfter(30_000, 0), root);
	const supervisor = Number(await readFile(path.join(root, id, "supervisor.pid"), "utf8"));
	process.kill(supervisor, "SIGKILL");
	const outcome = await wait(id, 20, root);
	assert.equal(outcome.code, 1);
	assert.match(outcome.message, /lost its supervisor/u);
});
