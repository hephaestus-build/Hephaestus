import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, copyFile, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

await test("uses the caller's Node runtime in Gradle child processes", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "gradlew-node-runtime-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const scripts = path.join(directory, "scripts");
	const server = path.join(directory, "server");
	const shims = path.join(directory, "shims");
	await Promise.all([scripts, server, shims].map(async (folder) => mkdir(folder)));
	const runner = path.join(scripts, "run-gradlew.ts");
	await copyFile(path.join(import.meta.dirname, "run-gradlew.ts"), runner);
	await writeFile(path.join(directory, "package.json"), '{"type":"module"}\n');

	const isWindows = process.platform === "win32";
	const gradlew = path.join(server, isWindows ? "gradlew.bat" : "gradlew");
	const shim = path.join(shims, isWindows ? "node.cmd" : "node");
	await writeFile(
		gradlew,
		isWindows
			? '@echo off\r\nnode -p "process.execPath"\r\n'
			: "#!/usr/bin/env node\nprocess.stdout.write(process.execPath);\n",
	);
	await writeFile(
		shim,
		isWindows ? "@echo off\r\necho shim-selected\r\n" : "#!/bin/sh\nprintf shim-selected\n",
	);
	if (!isWindows) {
		await Promise.all([gradlew, shim].map(async (file) => chmod(file, 0o755)));
	}

	const result = spawnSync(process.execPath, [runner], {
		cwd: directory,
		encoding: "utf8",
		env: {
			...process.env,
			PATH: `${shims}${path.delimiter}${process.env.PATH ?? ""}`,
		},
		timeout: 10_000,
	});
	assert.equal(result.status, 0, result.stderr);
	assert.equal(result.stdout.trim(), process.execPath);
});
