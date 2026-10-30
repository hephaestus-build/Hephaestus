import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";
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

await test("two stop signals preserve a receipt while asynchronous cleanup runs", async () => {
	const root = await mkdtemp(path.join(os.tmpdir(), "capacity-signals-"));
	const receipt = path.join(root, "summary.json");
	const module = new URL("lib/capacity-child.ts", import.meta.url).href;
	const source = `import { once } from 'node:events'; import { writeFile } from 'node:fs/promises'; const { installCapacitySignals } = await import(${JSON.stringify(module)}); const abort = new AbortController(); installCapacitySignals(abort); const finish = once(process,'message'); process.stdout.write('ready'); await once(abort.signal,'abort'); process.stdout.write('cleaning'); await finish; await writeFile(${JSON.stringify(receipt)},'cleanup complete'); process.disconnect();`;
	const child = spawn(process.execPath, ["--input-type=module", "-e", source], {
		stdio: ["ignore", "pipe", "ignore", "ipc"],
	});
	const ended = once(child, "exit");
	const { stdout } = child;
	if (stdout === null) {
		throw new Error("Child stdout missing");
	}
	try {
		await once(stdout, "data");
		const cleaning = once(stdout, "data");
		child.kill("SIGTERM");
		await cleaning;
		child.kill("SIGTERM");
		await sleep(30);
		if (child.connected) {
			child.send("finish", () => undefined);
		}
		const result: unknown = await ended;
		assert.equal(asArray(result, "child exit")[0], 0);
		assert.equal(await readFile(receipt, "utf8"), "cleanup complete");
	} finally {
		child.kill("SIGKILL");
		await ended;
		await rm(root, { recursive: true });
	}
});

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
