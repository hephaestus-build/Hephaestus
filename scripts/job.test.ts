import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { pathToFileURL } from "node:url";

import { STILL_RUNNING, start, wait } from "./job.ts";

/** Keeps the job alive until this test releases its connection, independent of scheduling. */
async function completionGate() {
	const connected = Promise.withResolvers<Socket>();
	let connection: Socket | undefined;
	const server = createServer((socket) => {
		connection = socket;
		connected.resolve(socket);
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("Missing completion gate address");
	}
	return {
		command: [
			process.execPath,
			"-e",
			`const connection = require('node:net').connect(${address.port}, '127.0.0.1'); connection.on('connect', () => console.log('working')); connection.on('end', () => connection.end());`,
		],
		connected: connected.promise,
		release: () => connection?.end(),
		async close() {
			connection?.end();
			const closed = once(server, "close");
			server.close();
			await closed;
		},
	};
}

void test("a job outlives the process that started it, and a bounded wait reports it in steps", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "job-"));
	const gate = await completionGate();
	t.after(async () => {
		await gate.close();
		await rm(root, { recursive: true, force: true });
	});
	// The starter exits at once, as an agent's command or a harness background task does.
	const module = pathToFileURL(path.join(import.meta.dirname, "job.ts")).href;
	const id = execFileSync(
		process.execPath,
		[
			"--input-type=module",
			"-e",
			`const { start } = await import(${JSON.stringify(module)}); console.log(start(${JSON.stringify(gate.command)}, ${JSON.stringify(root)}));`,
		],
		{ encoding: "utf8" },
	).trim();
	await gate.connected;
	const early = await wait(id, 0, root);
	assert.equal(early.code, STILL_RUNNING);
	assert.match(early.message, /job:wait/u);
	gate.release();
	const done = await wait(id, 20, root);
	assert.equal(done.code, 0, done.message);
});

void test("a failed job reports its status and the end of its log", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "job-"));
	t.after(async () => {
		await rm(root, { recursive: true, force: true });
	});
	const id = start([process.execPath, "-e", 'console.log("working"); process.exit(3);'], root);
	const outcome = await wait(id, 20, root);
	assert.equal(outcome.code, 1);
	assert.match(outcome.message, /working/u);
	assert.match(outcome.message, /failed \(3\)/u);
	await assert.rejects(wait("../escape", 0, root), /Not a job id/u);
	await assert.rejects(wait("missing", 0, root), /No job missing/u);
});

void test("a job whose supervisor was killed fails instead of waiting forever", async (t) => {
	const root = await mkdtemp(path.join(tmpdir(), "job-"));
	const gate = await completionGate();
	t.after(async () => {
		await gate.close();
		await rm(root, { recursive: true, force: true });
	});
	const id = start(gate.command, root);
	await gate.connected;
	const supervisor = Number(await readFile(path.join(root, id, "supervisor.pid"), "utf8"));
	process.kill(supervisor, "SIGKILL");
	const outcome = await wait(id, 20, root);
	assert.equal(outcome.code, 1);
	assert.match(outcome.message, /lost its supervisor/u);
});
