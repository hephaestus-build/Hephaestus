import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { asArray } from "./lib/json.ts";

import {
	capacityStopRequested,
	recordCapacityChild,
	stopCapacityChild,
} from "./lib/capacity-child.ts";

await test("a stop persists before the driver has created its PID receipt", async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), "capacity-child-"));
	try {
		assert.equal(await capacityStopRequested(root, "test"), false);
		await stopCapacityChild(root, "test");
		assert.equal(await capacityStopRequested(root, "test"), true);
	} finally {
		await rm(root, { recursive: true });
	}
});

await test("a receipt for an ended child or a reused PID does not signal another process", async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), "capacity-child-"));
	try {
		await mkdir(path.join(root, "results", "test"), { recursive: true });
		const file = path.join(root, "results", "test", "child.json");
		await writeFile(
			file,
			JSON.stringify({ pid: process.pid, ticks: "does-not-match-this-process" }),
		);
		await stopCapacityChild(root, "test");
		assert.equal(await capacityStopRequested(root, "test"), true);
	} finally {
		await rm(root, { recursive: true });
	}
});

// Windows kill unconditionally terminates its target; catchable signal delivery is POSIX-only.
await test(
	"two stop signals preserve a receipt while asynchronous cleanup runs",
	{ skip: process.platform === "win32" },
	async () => {
		const root = await mkdtemp(path.join(os.tmpdir(), "capacity-signals-"));
		const receipt = path.join(root, "summary.json");
		const module = new URL("lib/capacity-child.ts", import.meta.url).href;
		const source = `import { once } from 'node:events';
import { writeFile } from 'node:fs/promises';
const { installCapacitySignals } = await import(${JSON.stringify(module)});
const abort = new AbortController();
const stop = abort.abort.bind(abort);
let signals = 0;
abort.abort = () => {
  signals += 1;
  stop();
  if (signals === 2) process.send('second-signal');
};
installCapacitySignals(abort);
const finish = once(process, 'message');
process.send('ready');
await once(abort.signal, 'abort');
process.send('cleaning');
await finish;
await writeFile(${JSON.stringify(receipt)}, 'cleanup complete');
process.disconnect();`;
		const child = spawn(process.execPath, ["--input-type=module", "-e", source], {
			stdio: ["ignore", "ignore", "ignore", "ipc"],
		});
		const ended = once(child, "exit");
		try {
			const ready: unknown = await once(child, "message");
			assert.equal(asArray(ready, "child ready")[0], "ready");
			const cleaning = once(child, "message");
			child.kill("SIGTERM");
			const cleaningMessage: unknown = await cleaning;
			assert.equal(asArray(cleaningMessage, "child cleaning")[0], "cleaning");
			const acknowledged = once(child, "message");
			child.kill("SIGTERM");
			const secondSignal: unknown = await acknowledged;
			assert.equal(asArray(secondSignal, "child second signal")[0], "second-signal");
			// A failed send emits `error`, which rejects `ended`.
			child.send("finish");
			const result: unknown = await ended;
			assert.equal(asArray(result, "child exit")[0], 0);
			assert.equal(await readFile(receipt, "utf8"), "cleanup complete");
		} finally {
			child.kill("SIGKILL");
			await ended;
			await rm(root, { recursive: true });
		}
	},
);

await test(
	"Linux start-time identity stops the recorded child and tolerates a repeated stop",
	{ skip: process.platform !== "linux" },
	async () => {
		const root = await mkdtemp(path.join(os.tmpdir(), "capacity-child-"));
		const child = spawn(
			process.execPath,
			["-e", "process.stdout.write('ready');setInterval(()=>{},1000)"],
			{ stdio: ["ignore", "pipe", "ignore"] },
		);
		const ended = once(child, "exit");
		try {
			await once(child.stdout, "data");
			assert.notEqual(child.pid, undefined);
			if (child.pid === undefined) {
				throw new Error("Child PID missing");
			}
			await mkdir(path.join(root, "results", "test"), { recursive: true });
			await recordCapacityChild(root, "test", child.pid);
			await stopCapacityChild(root, "test");
			const result: unknown = await ended;
			const [code, signal] = asArray(result, "child exit");
			assert.equal(code, null);
			assert.equal(signal, "SIGTERM");
			await stopCapacityChild(root, "test");
		} finally {
			child.kill("SIGKILL");
			await ended;
			await rm(root, { recursive: true });
		}
	},
);
