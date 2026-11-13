import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

import { parse } from "yaml";

import { asArray, asRecord, asString } from "./lib/json.ts";

const workflow = asRecord(
	parse(await readFile(".github/workflows/feedback-eval-panel.yml", "utf8")),
	"panel",
);
const jobs = asRecord(workflow.jobs, "jobs");
const report = asRecord(jobs.report, "report");
const script = asString(
	asRecord(asRecord(asArray(report.steps, "steps")[0], "step").with, "with").script,
	"script",
);
const TITLE = "The nightly feedback eval panel is not green";

interface Night {
	id: number;
	sha: string;
	panel: string;
}

/** Runs the night's report with the given panel outcome, earlier nights and open issues. */
async function night(
	result: string,
	status: string,
	earlier: Night[],
	open: { number: number; title: string }[] = [],
) {
	const writes: { kind: string; input: Record<string, unknown> }[] = [];
	const listIssues = Symbol("issues");
	const listJobs = Symbol("jobs");
	await runInNewContext(`(async () => { ${script} })()`, {
		process: {
			env: { RESULT: result, STATUS: status, ARTIFACT_URL: "https://example.org/artifact" },
		},
		context: {
			repo: { owner: "owner", repo: "repo" },
			runId: 99,
			sha: "c".repeat(40),
			serverUrl: "https://github.com",
		},
		github: {
			rest: {
				issues: {
					listForRepo: listIssues,
					create: async (input: Record<string, unknown>) => writes.push({ kind: "create", input }),
					createComment: async (input: Record<string, unknown>) =>
						writes.push({ kind: "comment", input }),
					update: async (input: Record<string, unknown>) => writes.push({ kind: "update", input }),
				},
				actions: {
					listJobsForWorkflowRun: listJobs,
					listWorkflowRuns: async () => ({
						data: {
							workflow_runs: [
								{ id: 99, head_sha: "c".repeat(40) },
								...earlier.map((run) => ({ id: run.id, head_sha: run.sha })),
							],
						},
					}),
				},
			},
			paginate: async (endpoint: unknown, input: Record<string, unknown>) => {
				if (endpoint === listIssues) {
					return open.map((issue) => ({ ...issue, body: "" }));
				}
				assert.equal(endpoint, listJobs);
				const run = earlier.find((each) => each.id === input.run_id);
				return [
					{ name: "Model provider", conclusion: "success" },
					{ name: "Model panel", conclusion: run?.panel ?? "failure" },
				];
			},
		},
	});
	return writes;
}

void test("a night without a provider never counts as the last green night", async () => {
	const [created] = await night("failure", "failed", [
		// Newer nights ran without a provider: the run succeeded, but its panel was skipped.
		{ id: 98, sha: "b".repeat(40), panel: "skipped" },
		{ id: 97, sha: "a".repeat(40), panel: "success" },
	]);
	assert.ok(created);
	assert.equal(created.kind, "create");
	assert.equal(created.input.title, TITLE);
	const body = asString(created.input.body, "body");
	assert.match(body, /fell below a floor/u);
	assert.ok(body.includes(`compare/${"a".repeat(40)}...${"c".repeat(40)}`), body);
	assert.match(body, /https:\/\/example\.org\/artifact/u);
});

void test("a red night comments on the open issue instead of opening another", async () => {
	const writes = await night("failure", "ceiling-reached", [], [{ number: 7, title: TITLE }]);
	assert.deepEqual(
		writes.map((write) => [write.kind, write.input.issue_number]),
		[["comment", 7]],
	);
	assert.match(asString(writes[0]?.input.body, "body"), /stopped at its call ceiling/u);
	assert.match(
		asString(writes[0]?.input.body, "body"),
		/No night of the last 30 measured and passed/u,
	);
});

void test("a night that measured and passed closes the open issue", async () => {
	const writes = await night("success", "passed", [], [{ number: 7, title: TITLE }]);
	assert.deepEqual(
		writes.map((write) => [write.kind, write.input.state ?? null]),
		[
			["comment", null],
			["update", "closed"],
		],
	);
	assert.deepEqual(await night("success", "passed", []), []);
});
