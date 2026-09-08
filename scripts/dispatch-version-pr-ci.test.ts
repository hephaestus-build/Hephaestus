import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { fileURLToPath } from "node:url";

import {
	needsDispatch,
	parseBranchHead,
	parseRuns,
	versionBranch,
} from "./dispatch-version-pr-ci.ts";

const SHA = "a".repeat(40);
const OTHER = "b".repeat(40);

void describe("the Version PR's CI branch", () => {
	void test("follows the base branch changesets is configured with", async () => {
		assert.equal(versionBranch({ baseBranch: "main" }), "changeset-release/main");
		assert.equal(versionBranch({ baseBranch: "release/2" }), "changeset-release/release/2");
		const config: unknown = JSON.parse(await readFile(".changeset/config.json", "utf8"));
		assert.equal(versionBranch(config), "changeset-release/main");
	});

	void test("refuses a configuration that names no base branch", () => {
		assert.throws(() => versionBranch({}), /baseBranch must be a string/);
		assert.throws(() => versionBranch({ baseBranch: "" }), /declares no baseBranch/);
	});
});

void describe("dispatching CI for a Version PR head", () => {
	void test("dispatches a head commit no run has covered", () => {
		assert.equal(needsDispatch(SHA, []), true);
		assert.equal(needsDispatch(SHA, [{ headSha: OTHER, conclusion: "success" }]), true);
	});

	void test("stays quiet when the head already has a run", () => {
		assert.equal(
			needsDispatch(SHA, [
				{ headSha: OTHER, conclusion: "success" },
				{ headSha: SHA, conclusion: "success" },
			]),
			false,
		);
	});

	void test("reads head commits out of the workflow runs API", () => {
		assert.deepEqual(
			parseRuns({ workflow_runs: [{ head_sha: SHA, id: 1, conclusion: "success" }] }),
			[{ headSha: SHA, conclusion: "success" }],
		);
		assert.throws(() => parseRuns({}), /workflow_runs must be an array/);
		assert.throws(() => parseRuns({ workflow_runs: [{ id: 1 }] }), /head_sha must be a string/);
	});
});

void test("cancelled validation is recoverable but failed validation is not retried blindly", () => {
	assert.equal(needsDispatch(SHA, [{ headSha: SHA, conclusion: "cancelled" }]), true);
	for (const conclusion of [null, "success", "failure"])
		assert.equal(needsDispatch(SHA, [{ headSha: SHA, conclusion }]), false);
});

void test("a missing Version branch is distinct from a malformed API response", () => {
	assert.equal(parseBranchHead([], "changeset-release/main"), undefined);
	const ref = { ref: "refs/heads/changeset-release/main", object: { sha: SHA } };
	assert.equal(parseBranchHead([ref], "changeset-release/main"), SHA);
	assert.equal(parseBranchHead([ref], "changeset-release/ma"), undefined);
	assert.throws(() => parseBranchHead({ message: "API unavailable" }, "main"), /matching refs/);
	assert.throws(
		() => parseBranchHead([{ ...ref, object: {} }], "changeset-release/main"),
		/branch sha/,
	);
});

void test(
	"the CLI preserves reserved characters in branch lookup URLs",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "version-pr-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		await mkdir(path.join(directory, ".changeset"));
		await writeFile(
			path.join(directory, ".changeset/config.json"),
			'{"baseBranch":"release#2026%"}',
		);
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh
[ "$1" = api ] || exit 1
[ "$2" = 'repos/owner/repo/git/matching-refs/heads/changeset-release/release%232026%25' ] || exit 2
printf '[]'
`,
			{ mode: 0o755 },
		);
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("./dispatch-version-pr-ci.ts", import.meta.url))],
			{
				cwd: directory,
				env: {
					...process.env,
					GITHUB_REPOSITORY: "owner/repo",
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				},
				encoding: "utf8",
			},
		);
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /No changeset-release\/release#2026% branch/);
	},
);
