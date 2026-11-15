import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir, userInfo } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { availableBytes, KEEP_SDKS_FREE_BYTES } from "./lib/runner-disk.ts";
import { roomiest, scanVolume, type Volume } from "./prepare-image-scan-runner.ts";

void test("the scans' temporary files go to the volume with the most free space", () => {
	const runnerTemp = { mount: "/home/runner/work/_temp", available: 9 * 1024 ** 3 };
	const second = { mount: "/mnt", available: 60 * 1024 ** 3 };
	assert.deepEqual(roomiest([runnerTemp, second]), second);
	// An arm64 runner without a second disk keeps the runner's temporary directory.
	assert.deepEqual(roomiest([runnerTemp]), runnerTemp);
	assert.deepEqual(
		roomiest([runnerTemp, { ...second, available: runnerTemp.available }]),
		runnerTemp,
	);
	assert.throws(() => roomiest([]), /No volume/u);
});

/** The volume scanVolume picks, and the order of its measurements and deletion. */
const choose = async (before: Volume[], after: Volume[]) => {
	const events: string[] = [];
	let removed = false;
	const chosen = await scanVolume(
		async () => {
			events.push("measure");
			return removed ? after : before;
		},
		async () => {
			events.push("remove");
			removed = true;
		},
	);
	return { chosen, events };
};

void test("the scans remove the SDKs only when the roomiest volume is short, then measure again", async () => {
	const gib = 1024 ** 3;
	const root = { mount: "/home/runner/work/_temp", available: 10 * gib };
	const second = { mount: "/mnt", available: 30 * gib };
	const roomy = { ...root, available: KEEP_SDKS_FREE_BYTES };
	assert.deepEqual(await choose([roomy, second], []), { chosen: roomy, events: ["measure"] });
	// The deletion frees the root volume, which then wins over the second disk.
	const freed = { ...root, available: 60 * gib };
	assert.deepEqual(await choose([root, second], [freed, second]), {
		chosen: freed,
		events: ["measure", "remove", "measure"],
	});
});

void test("disk preparation for the scans refuses a contributor's machine", () => {
	const result = spawnSync(
		process.execPath,
		[path.resolve("scripts/prepare-image-scan-runner.ts")],
		{
			encoding: "utf8",
			env: { ...process.env, GITHUB_ACTIONS: "false", RUNNER_ENVIRONMENT: "self-hosted" },
		},
	);
	assert.equal(result.status, 1);
	assert.match(result.stderr, /requires a GitHub-hosted Linux runner/u);
});

void test(
	"preparation logs the disk, removes the SDKs only when short and hands the scans a directory it owns",
	{ skip: process.platform !== "linux" },
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "image-scan-disk-"));
		try {
			const calls = path.join(directory, "calls");
			for (const tool of ["sudo", "df"]) {
				await writeFile(
					path.join(directory, tool),
					`#!/bin/sh\nprintf '%s %s\\n' ${tool} "$*" >> "${calls}"\n`,
				);
				await chmod(path.join(directory, tool), 0o755);
			}
			const output = path.join(directory, "output");
			const result = spawnSync(
				process.execPath,
				[path.resolve("scripts/prepare-image-scan-runner.ts")],
				{
					encoding: "utf8",
					env: {
						...process.env,
						PATH: `${directory}:${process.env.PATH ?? ""}`,
						GITHUB_ACTIONS: "true",
						RUNNER_ENVIRONMENT: "github-hosted",
						RUNNER_TEMP: directory,
						GITHUB_OUTPUT: output,
					},
				},
			);
			assert.equal(result.status, 0, result.stderr);
			const written = await readFile(output, "utf8");
			const chosen = /^directory=(?<path>.+)\/image-scan-tmp$/mu.exec(written)?.groups?.path;
			assert.ok(chosen === directory || chosen === "/mnt", written);
			const { uid, gid } = userInfo();
			const recorded = await readFile(calls, "utf8");
			const log = recorded.trim().split("\n");
			const short = (await availableBytes(chosen)) < KEEP_SDKS_FREE_BYTES;
			assert.deepEqual(log, [
				"df -h",
				...(short
					? [
							"sudo rm -rf -- /usr/local/lib/android /usr/share/dotnet /usr/local/.ghcup /opt/ghc /opt/hostedtoolcache/CodeQL",
						]
					: []),
				`sudo install -d -o ${uid} -g ${gid} ${chosen}/image-scan-tmp`,
				"df -h",
			]);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);
