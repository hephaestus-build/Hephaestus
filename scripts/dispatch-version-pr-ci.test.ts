import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test } from "node:test";
import { fileURLToPath } from "node:url";

import {
	disposition,
	parseBranchHead,
	parseRuns,
	versionBranch,
} from "./dispatch-version-pr-ci.ts";

const SHA = "a".repeat(40);
const OTHER = "b".repeat(40);
const REPOSITORY = "owner/repo";
const blocked = {
	headSha: SHA,
	conclusion: "action_required",
	event: "pull_request",
	headRepository: REPOSITORY,
};

void describe("the Version PR's CI branch", () => {
	void test("follows the base branch changesets is configured with", async () => {
		assert.equal(versionBranch({ baseBranch: "main" }), "changeset-release/main");
		assert.equal(versionBranch({ baseBranch: "release/2" }), "changeset-release/release/2");
		const config: unknown = JSON.parse(await readFile(".changeset/config.json", "utf8"));
		assert.equal(versionBranch(config), "changeset-release/main");
	});

	void test("refuses a configuration that names no base branch", () => {
		assert.throws(() => versionBranch({}), /baseBranch must be a string/u);
		assert.throws(() => versionBranch({ baseBranch: "" }), /declares no baseBranch/u);
	});
});

void describe("dispatching CI for a Version PR head", () => {
	void test("dispatches a head commit no run has covered", () => {
		assert.equal(disposition(SHA, REPOSITORY, []), "dispatch");
		assert.equal(
			disposition(SHA, REPOSITORY, [{ ...blocked, headSha: OTHER, conclusion: "success" }]),
			"dispatch",
		);
	});

	void test("stays quiet when the head already has a run", () => {
		assert.equal(
			disposition(SHA, REPOSITORY, [
				{ ...blocked, headSha: OTHER, conclusion: "success" },
				{ ...blocked, conclusion: "success" },
			]),
			"covered",
		);
	});

	void test("reads head commits out of the workflow runs API", () => {
		const run = {
			head_sha: SHA,
			id: 1,
			conclusion: "success",
			event: "pull_request",
			head_repository: REPOSITORY,
		};
		assert.deepEqual(parseRuns({ workflow_runs: [run] }), [
			{ headSha: SHA, conclusion: "success", event: "pull_request", headRepository: REPOSITORY },
		]);
		assert.deepEqual(parseRuns({ workflow_runs: [{ ...run, head_repository: null }] }), [
			{ headSha: SHA, conclusion: "success", event: "pull_request", headRepository: null },
		]);
		assert.throws(() => parseRuns({}), /workflow_runs must be an array/u);
		assert.throws(() => parseRuns({ workflow_runs: [{ id: 1 }] }), /head_sha must be a string/u);
		assert.throws(
			() => parseRuns({ workflow_runs: [{ ...run, event: undefined }] }),
			/run 0 event must be a string/u,
		);
		assert.throws(
			() => parseRuns({ workflow_runs: [{ ...run, head_repository: { full_name: REPOSITORY } }] }),
			/run 0 head_repository/u,
		);
	});
});

void describe("a Version PR head whose pull_request run awaits approval", () => {
	void test("waits for that approval instead of dispatching a competing run", () => {
		assert.equal(disposition(SHA, REPOSITORY, [blocked]), "awaiting-approval");
	});

	void test("dispatches when no authentic pull_request run for this head awaits approval", () => {
		for (const runs of [
			[],
			[{ ...blocked, event: "workflow_dispatch" }],
			[{ ...blocked, conclusion: "cancelled" }],
			[{ ...blocked, headSha: OTHER }],
			[{ ...blocked, headRepository: "fork/repo" }],
			[{ ...blocked, headRepository: null }],
		]) {
			assert.equal(disposition(SHA, REPOSITORY, runs), "dispatch", JSON.stringify(runs));
		}
	});

	void test("keeps a same-head verdict or running run, and never retries a real failure", () => {
		for (const conclusion of [null, "success", "failure"]) {
			const run = { ...blocked, event: "workflow_dispatch", conclusion };
			assert.equal(disposition(SHA, REPOSITORY, [run]), "covered");
			assert.equal(disposition(SHA, REPOSITORY, [blocked, run]), "covered");
		}
	});
});

void test("cancelled and approval-blocked dispatched validation recover without retrying real failures", () => {
	for (const conclusion of ["cancelled", "action_required"]) {
		assert.equal(
			disposition(SHA, REPOSITORY, [{ ...blocked, event: "workflow_dispatch", conclusion }]),
			"dispatch",
		);
	}
	assert.equal(
		disposition(SHA, REPOSITORY, [blocked, { ...blocked, conclusion: "success" }]),
		"covered",
	);
	for (const conclusion of [null, "success", "failure"]) {
		assert.equal(disposition(SHA, REPOSITORY, [{ ...blocked, conclusion }]), "covered");
	}
});

void test("a missing Version branch is distinct from a malformed API response", () => {
	assert.equal(parseBranchHead([], "changeset-release/main"), undefined);
	const ref = { ref: "refs/heads/changeset-release/main", object: { sha: SHA } };
	assert.equal(parseBranchHead([ref], "changeset-release/main"), SHA);
	assert.equal(parseBranchHead([ref], "changeset-release/ma"), undefined);
	assert.throws(() => parseBranchHead({ message: "API unavailable" }, "main"), /matching refs/u);
	assert.throws(
		() => parseBranchHead([{ ...ref, object: {} }], "changeset-release/main"),
		/branch sha/u,
	);
});

void test(
	"the CLI preserves reserved characters in branch lookup URLs",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "version-pr-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
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
			[fileURLToPath(new URL("dispatch-version-pr-ci.ts", import.meta.url))],
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
		assert.match(result.stdout, /No changeset-release\/release#2026% branch/u);
	},
);

void test(
	"the CLI asks for approval of a blocked pull_request run and dispatches nothing",
	{ skip: process.platform === "win32" },
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "version-pr-"));
		context.after(async () => rm(directory, { recursive: true, force: true }));
		await mkdir(path.join(directory, ".changeset"));
		await writeFile(path.join(directory, ".changeset/config.json"), '{"baseBranch":"main"}');
		const ref = JSON.stringify([
			{ ref: "refs/heads/changeset-release/main", object: { sha: SHA } },
		]);
		const runs = JSON.stringify({
			workflow_runs: [
				{
					head_sha: SHA,
					conclusion: "action_required",
					event: "pull_request",
					head_repository: REPOSITORY,
				},
			],
		});
		// Any other call, `gh workflow run` included, fails the CLI.
		await writeFile(
			path.join(directory, "gh"),
			`#!/bin/sh
case "$1 $2" in
'api repos/owner/repo/git/matching-refs/heads/changeset-release/main') printf '%s' '${ref}' ;;
'api repos/owner/repo/actions/workflows/cicd.yml/runs?branch=changeset-release%2Fmain&per_page=100') printf '%s' '${runs}' ;;
*) exit 9 ;;
esac
`,
			{ mode: 0o755 },
		);
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("dispatch-version-pr-ci.ts", import.meta.url))],
			{
				cwd: directory,
				env: {
					...process.env,
					GITHUB_REPOSITORY: REPOSITORY,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
				},
				encoding: "utf8",
			},
		);
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /awaits approval\. Approve its pull_request run/u);
		assert.doesNotMatch(result.stdout, /Dispatched/u);
	},
);
