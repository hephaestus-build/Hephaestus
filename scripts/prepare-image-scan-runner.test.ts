import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { test } from "node:test";

import { roomiest } from "./prepare-image-scan-runner.ts";

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
