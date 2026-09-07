import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

const workflow = parseDocument(await readFile(".github/workflows/ci-profile.yml", "utf8"));
const steps = workflow.getIn(["jobs", "server-integration", "steps"]);
assert.ok(isSeq(steps));
const selection = steps.items.find((item) => isMap(item) && item.get("id") === "history");
assert.ok(isMap(selection));
const source = selection.getIn(["with", "script"]);
assert.equal(typeof source, "string");

function selectHistory(pages: unknown[][], artifacts: Record<number, unknown[]>): string {
	const result = spawnSync(process.execPath, ["--input-type=module"], {
		input: `
const context = {repo: {owner: 'owner', repo: 'repo'}, runId: 10, payload: {repository: {default_branch: 'main'}}};
const core = {setOutput: (name, value) => process.stdout.write(String(value)), info: () => {}};
const pages = ${JSON.stringify(pages)};
const artifacts = ${JSON.stringify(artifacts)};
const paginate = Object.assign(async (_, {run_id}) => artifacts[run_id] ?? [], {
  iterator: async function* (_, options) {
    if (options.workflow_id !== 'ci-profile.yml' || options.branch !== 'main' || options.status !== 'completed') throw Error('Untrusted query');
    for (const data of pages) yield {data};
  },
});
const github = {paginate, rest: {actions: {listWorkflowRuns: {}, listWorkflowRunArtifacts: {}}}};
await (async () => {${String(source)}})();`,
		encoding: "utf8",
	});
	assert.equal(result.status, 0, result.stderr);
	return result.stdout;
}

const run = (id: number, headBranch = "main", repository = "owner/repo") => ({
	id,
	head_branch: headBranch,
	head_repository: { full_name: repository },
});
const history = { name: "ci-profile-history-jfr", expired: false };

void test("history skips this rerun, foreign sources and incomplete profiles across pages", () => {
	assert.equal(
		selectHistory([[run(10), run(9, "feature"), run(8, "main", "fork/repo"), run(7)], [run(6)]], {
			10: [history],
			9: [history],
			8: [history],
			7: [],
			6: [history],
		}),
		"6",
	);
});

void test("a budget-failed run's completed profile remains usable", () => {
	assert.equal(selectHistory([[{ ...run(9), conclusion: "failure" }]], { 9: [history] }), "9");
});

void test("expired history cannot become a baseline", () => {
	assert.equal(selectHistory([[run(9)]], { 9: [{ ...history, expired: true }] }), "");
});

void test("uninstrumented history cannot become the JFR baseline", () => {
	assert.equal(
		selectHistory([[run(9)]], { 9: [{ name: "ci-profile-history", expired: false }] }),
		"",
	);
});

void test(
	"the profiling pipeline preserves Maven failures through tee",
	{ skip: process.platform !== "linux" },
	async (context) => {
		const profile = steps.items.find((item) => isMap(item) && item.get("id") === "profile");
		assert.ok(isMap(profile));
		assert.equal(profile.get("shell"), "bash");
		const command = profile.get("run");
		assert.equal(typeof command, "string");
		const directory = await mkdtemp(path.join(tmpdir(), "profile-pipeline-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		await writeFile(path.join(directory, "vp"), "#!/bin/sh\necho 'Maven failed' >&2\nexit 17\n", {
			mode: 0o755,
		});
		const result = spawnSync(
			"bash",
			["--noprofile", "--norc", "-eo", "pipefail", "-c", String(command)],
			{
				cwd: directory,
				env: { ...process.env, PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}` },
				encoding: "utf8",
			},
		);
		assert.equal(result.status, 17, result.stderr);
		assert.match(
			await readFile(path.join(directory, "ci-metrics/server-integration.log"), "utf8"),
			/Maven failed/,
		);
	},
);
