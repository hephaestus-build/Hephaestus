import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { parse } from "yaml";

import { ISSUE_TITLE, reportNight, type GitHub, type Night } from "./feedback-eval-night.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

interface EarlierNight {
	id: number;
	sha: string;
	panel: string;
}

const SHA = "c".repeat(40);
const repo = { owner: "owner", repo: "repo" };

/** A client that serves earlier nights page by page, as the REST API does, and records each write. */
function client(
	earlier: EarlierNight[],
	open: { number: number; title: string }[] = [],
	artifacts: object[] = [],
) {
	const writes: { kind: string; input: Record<string, unknown> }[] = [];
	const jobLookups: unknown[] = [];
	const route = (name: string) => {
		const call = async (input: Record<string, unknown>) => {
			writes.push({ kind: name, input });
			return {};
		};
		return Object.assign(call, { route: name });
	};
	const listForRepo = route("listForRepo");
	const listWorkflowRuns = route("listWorkflowRuns");
	const listJobsForWorkflowRun = route("listJobsForWorkflowRun");
	const listWorkflowRunArtifacts = route("listWorkflowRunArtifacts");
	const github: GitHub = {
		paginate: Object.assign(
			async (endpoint: unknown, input: Record<string, unknown>) => {
				if (endpoint === listForRepo) {
					return open.map((issue) => ({ ...issue, body: "" }));
				}
				if (endpoint === listWorkflowRunArtifacts) {
					return artifacts;
				}
				assert.equal(endpoint, listJobsForWorkflowRun);
				jobLookups.push(input.run_id);
				const run = earlier.find((each) => each.id === input.run_id);
				return [{ name: "Model panel", conclusion: run?.panel ?? "failure" }];
			},
			{
				iterator: (endpoint: unknown, input: Record<string, unknown>) => {
					assert.equal(endpoint, listWorkflowRuns);
					assert.equal(input.workflow_id, "feedback-eval-panel.yml");
					assert.equal(input.event, "schedule");
					const runs = [
						{ id: 99, head_sha: SHA },
						...earlier.map((run) => ({ id: run.id, head_sha: run.sha })),
					];
					return (async function* pages() {
						for (let start = 0; start < runs.length; start += 30) {
							yield { data: runs.slice(start, start + 30) };
						}
					})();
				},
			},
		),
		rest: {
			issues: {
				listForRepo,
				create: route("create"),
				createComment: route("comment"),
				update: route("update"),
			},
			actions: { listWorkflowRuns, listJobsForWorkflowRun, listWorkflowRunArtifacts },
		},
	};
	const written = () =>
		writes.filter((write) => ["create", "comment", "update"].includes(write.kind));
	return { github, written, jobLookups };
}

const night = (
	result: string,
	status: string,
	artifactUrl = "https://example.org/artifact",
): Night => ({
	runId: 99,
	sha: SHA,
	runUrl: "https://github.com/owner/repo/actions/runs/99",
	serverUrl: "https://github.com",
	result,
	status,
	artifactUrl,
});

void test("a night without a provider never counts as the last green night", async () => {
	const { github, written } = client([
		// A newer night ran without a provider: the run succeeded, but its panel was skipped.
		{ id: 98, sha: "b".repeat(40), panel: "skipped" },
		{ id: 97, sha: "a".repeat(40), panel: "success" },
	]);
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "created");
	const [created] = written();
	assert.ok(created);
	assert.equal(created.input.title, ISSUE_TITLE);
	const body = asString(created.input.body, "body");
	assert.match(body, /fell below a floor/u);
	assert.ok(body.includes(`compare/${"a".repeat(40)}...${SHA}`), body);
	assert.match(body, /https:\/\/example\.org\/artifact/u);
});

void test("the search pages past 30 runs to an older measured green night", async () => {
	const red = Array.from({ length: 45 }, (_, index) => ({
		id: 90 - index,
		sha: "d".repeat(40),
		panel: "failure",
	}));
	const { github, written, jobLookups } = client([
		...red,
		{ id: 1, sha: "a".repeat(40), panel: "success" },
	]);
	await reportNight(github, repo, night("failure", "failed"));
	assert.equal(jobLookups.length, 46);
	assert.ok(
		asString(written()[0]?.input.body, "body").includes(`compare/${"a".repeat(40)}...${SHA}`),
	);
});

void test("with no measured green night, the report says so and still names the night's commit", async () => {
	const { github, written } = client([{ id: 98, sha: "b".repeat(40), panel: "skipped" }]);
	await reportNight(github, repo, night("cancelled", ""));
	const body = asString(written()[0]?.input.body, "body");
	assert.match(body, /did not finish \(cancelled\)/u);
	assert.ok(
		body.includes(`No night has measured and passed yet. This night ran on \`${SHA}\`.`),
		body,
	);
});

void test("the fallback finds the kept result on the run when it was not told it", async () => {
	const { github, written } = client([], [], [{ id: 5, name: "feedback-eval-panel" }]);
	await reportNight(github, repo, night("timed_out", "", ""));
	assert.match(
		asString(written()[0]?.input.body, "body"),
		/Result: https:\/\/github\.com\/owner\/repo\/actions\/runs\/99\/artifacts\/5/u,
	);
});

void test("a red night comments on the open issue instead of opening another", async () => {
	const { github, written } = client([], [{ number: 7, title: ISSUE_TITLE }]);
	assert.equal(await reportNight(github, repo, night("failure", "ceiling-reached")), "commented");
	assert.deepEqual(
		written().map((write) => [write.kind, write.input.issue_number]),
		[["comment", 7]],
	);
	assert.match(asString(written()[0]?.input.body, "body"), /stopped at its call ceiling/u);
});

void test("a night that measured and passed closes the open issue", async () => {
	const { github, written } = client([], [{ number: 7, title: ISSUE_TITLE }]);
	assert.equal(await reportNight(github, repo, night("success", "passed")), "closed");
	assert.deepEqual(
		written().map((write) => [write.kind, write.input.state ?? null]),
		[
			["comment", null],
			["update", "closed"],
		],
	);
	const quiet = client([]);
	assert.equal(await reportNight(quiet.github, repo, night("success", "passed")), "unchanged");
	assert.deepEqual(quiet.written(), []);
});

void test("the panel's report job imports the shared report script", async () => {
	const workflow = asRecord(
		parse(await readFile(".github/workflows/feedback-eval-panel.yml", "utf8")),
		"panel",
	);
	const report = asRecord(asRecord(workflow.jobs, "jobs").report, "report");
	const steps = asArray(report.steps, "steps").map((step) => asRecord(step, "step"));
	assert.equal(
		asRecord(steps[0]?.with, "checkout")["sparse-checkout"],
		"scripts/feedback-eval-night.ts",
	);
	assert.match(
		asString(asRecord(steps[1]?.with, "with").script, "script"),
		/await import\(`\$\{process\.env\.GITHUB_WORKSPACE\}\/scripts\/feedback-eval-night\.ts`\)/u,
	);
});
