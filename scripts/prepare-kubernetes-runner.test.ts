import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { clusterGlobs, touchesCluster } from "./check-affected.ts";
import { availableBytes, freeDiskFor, KEEP_SDKS_FREE_BYTES } from "./lib/runner-disk.ts";
import { checkFreeSpace, MINIMUM_FREE_BYTES } from "./prepare-kubernetes-runner.ts";

const script = path.resolve("scripts/prepare-kubernetes-runner.ts");

void test("disk cleanup refuses a contributor's machine", () => {
	const result = spawnSync(process.execPath, [script], {
		encoding: "utf8",
		env: { ...process.env, GITHUB_ACTIONS: "false", RUNNER_ENVIRONMENT: "self-hosted" },
	});
	assert.equal(result.status, 1);
	assert.match(result.stderr, /requires a GitHub-hosted Linux runner/u);
});

void test("a runner keeps its SDKs only with more room than a cluster needs", () => {
	assert.ok(KEEP_SDKS_FREE_BYTES > MINIMUM_FREE_BYTES);
});

void test("disk checks report sufficient space and fail early on low or unreadable space", () => {
	assert.match(checkFreeSpace(25 * 1024 ** 3), /25\.0.*these cluster tests require/u);
	assert.throws(() => checkFreeSpace(25 * 1024 ** 3 - 1), /26843545599 bytes.*require/u);
	assert.throws(() => checkFreeSpace(Number.NaN), /Could not read available/u);
	assert.throws(() => checkFreeSpace(-1), /Could not read available/u);
});

void test("a change to runner disk preparation selects cluster verification", async () => {
	const source = await readFile(".github/workflows/cicd.yml", "utf8");
	for (const file of ["scripts/prepare-kubernetes-runner.ts", "scripts/lib/runner-disk.ts"]) {
		assert.equal(touchesCluster([file], clusterGlobs(source)), true, file);
	}
});

// The stub is a POSIX shell script, which Windows cannot run from PATH; the cleanup runs only on Linux runners.
void test(
	"the SDKs are removed only when the volume is short of the space a job needs",
	{ skip: process.platform === "win32" },
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "runner-disk-"));
		const searchPath = process.env.PATH;
		try {
			const calls = path.join(directory, "calls");
			await writeFile(
				path.join(directory, "sudo"),
				`#!/bin/sh\nprintf '%s\\n' "$*" >> "${calls}"\n`,
			);
			await chmod(path.join(directory, "sudo"), 0o755);
			process.env.PATH = `${directory}:${searchPath ?? ""}`;
			await freeDiskFor(directory);
			const removed = await readFile(calls, "utf8").catch(() => "");
			const short = (await availableBytes(directory)) < KEEP_SDKS_FREE_BYTES;
			assert.equal(removed.startsWith("rm -rf -- /usr/local/lib/android "), short, removed);
		} finally {
			process.env.PATH = searchPath;
			await rm(directory, { recursive: true, force: true });
		}
	},
);

void test(
	"cleanup removes only the fixed SDK paths, and only when the disk is short",
	{ skip: process.platform !== "linux" },
	async () => {
		const directory = await mkdtemp(path.join(tmpdir(), "kubernetes-disk-"));
		try {
			await writeFile(path.join(directory, "docker"), '#!/bin/sh\nprintf "%s\\n" "$DISK_ROOT"\n');
			await writeFile(path.join(directory, "sudo"), '#!/bin/sh\nprintf "%s\\n" "$@" >> "$CALLS"\n');
			await Promise.all([
				chmod(path.join(directory, "docker"), 0o755),
				chmod(path.join(directory, "sudo"), 0o755),
			]);
			const result = spawnSync(process.execPath, [script], {
				encoding: "utf8",
				env: {
					...process.env,
					PATH: `${directory}:${process.env.PATH ?? ""}`,
					GITHUB_ACTIONS: "true",
					RUNNER_ENVIRONMENT: "github-hosted",
					GITHUB_STEP_SUMMARY: undefined,
					CALLS: path.join(directory, "calls"),
					DISK_ROOT: directory,
				},
			});
			assert.match(result.stdout + result.stderr, /Docker filesystem: \d+ bytes/u);
			if ((await availableBytes(directory)) >= KEEP_SDKS_FREE_BYTES) {
				await assert.rejects(readFile(path.join(directory, "calls"), "utf8"), { code: "ENOENT" });
				return;
			}
			const calls = await readFile(path.join(directory, "calls"), "utf8");
			assert.deepEqual(calls.trim().split("\n"), [
				"rm",
				"-rf",
				"--",
				"/usr/local/lib/android",
				"/usr/share/dotnet",
				"/usr/local/.ghcup",
				"/opt/ghc",
				"/opt/hostedtoolcache/CodeQL",
			]);
		} finally {
			await rm(directory, { recursive: true, force: true });
		}
	},
);

void test("each cluster frees disk before creating nodes", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/ci-kubernetes.yml", "utf8"));
	for (const job of ["k3d", "calico"]) {
		const steps = workflow.getIn(["jobs", job, "steps"]);
		assert.ok(isSeq(steps));
		const entries = steps.items.filter(isMap);
		const cleanup = entries.findIndex(
			(entry) => entry.get("name") === "Free disk space for the cluster",
		);
		const create = entries.findIndex((entry) => String(entry.get("name")).startsWith("Create "));
		assert.ok(cleanup !== -1 && cleanup < create);
	}
});
