import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

const workflow = parseDocument(await readFile(".github/workflows/continuous-staging.yml", "utf8"));
const steps = workflow.getIn(["jobs", "follow-main", "steps"]);
assert.ok(isSeq(steps));
const step = steps.items.find(
	(item) => isMap(item) && item.get("name") === "Follow this commit on staging",
);
assert.ok(isMap(step));
const run = step.get("run");
assert.ok(typeof run === "string");
const script = run;

const shellOnly = {
	skip:
		process.platform === "win32"
			? "The Ubuntu workflow uses Bash, and its fixture executables need POSIX shebangs."
			: false,
};

// Run the workflow's actual shell. Only GitHub is a fixture: no workflow is dispatched.
const transport = String.raw`#!/usr/bin/env node
const fs = require('node:fs');
fs.appendFileSync(process.env.CALLS, JSON.stringify(process.argv.slice(2)) + '\n');
if (process.env.REFUSED === 'true') {
  console.error('gh: Forbidden (HTTP 403)');
  process.exit(1);
}
console.log(process.env.NO_URL === 'true' ? 'null' : 'https://github.com/owner/repo/actions/runs/123');
`;

async function follow(overrides: Record<string, string> = {}) {
	const directory = await mkdtemp(path.join(tmpdir(), "continuous-staging-"));
	try {
		const bin = path.join(directory, "bin");
		await mkdir(bin);
		const calls = path.join(directory, "calls");
		const summary = path.join(directory, "summary");
		await writeFile(calls, "");
		await writeFile(summary, "");
		await writeFile(path.join(bin, "gh"), transport, { mode: 0o755 });
		const result = spawnSync("bash", ["--noprofile", "--norc", "-e", "-c", script], {
			encoding: "utf8",
			env: {
				...process.env,
				PATH: `${bin}${path.delimiter}${process.env.PATH ?? ""}`,
				GITHUB_REPOSITORY: "owner/repo",
				GITHUB_STEP_SUMMARY: summary,
				COMMIT: "c".repeat(40),
				CALLS: calls,
				...overrides,
			},
			timeout: 10_000,
		});
		assert.equal(result.error, undefined);
		const recorded = await readFile(calls, "utf8");
		return {
			status: result.status,
			output: result.stdout + result.stderr,
			summary: await readFile(summary, "utf8"),
			calls: recorded
				.trim()
				.split("\n")
				.filter(Boolean)
				.map((line): unknown => JSON.parse(line)),
		};
	} finally {
		await rm(directory, { recursive: true, force: true });
	}
}

void test("dispatches one automatic staging promotion of the built commit", shellOnly, async () => {
	const result = await follow();
	assert.equal(result.status, 0, result.output);
	assert.deepEqual(result.calls, [
		[
			"api",
			"--method",
			"POST",
			"repos/owner/repo/actions/workflows/promote.yml/dispatches",
			"-f",
			"ref=main",
			"-f",
			"inputs[environment]=Staging",
			"-f",
			`inputs[commit]=${"c".repeat(40)}`,
			"-f",
			"inputs[automatic]=true",
			"-F",
			"return_run_details=true",
			"--jq",
			".html_url",
		],
	]);
	assert.match(result.summary, /actions\/runs\/123/u);
});

void test("fails when GitHub refuses the dispatch", shellOnly, async () => {
	const result = await follow({ REFUSED: "true" });
	assert.equal(result.status, 1, result.output);
	assert.match(result.output, /HTTP 403/u);
	assert.equal(result.calls.length, 1);
});

void test("fails when the dispatch returns no run URL", shellOnly, async () => {
	const result = await follow({ NO_URL: "true" });
	assert.equal(result.status, 1, result.output);
	assert.match(result.output, /no run URL/u);
});
