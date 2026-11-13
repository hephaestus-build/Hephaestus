import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { parse } from "yaml";

import {
	ISSUE_TITLE,
	LAST_GREEN_PREFIX,
	reportNight,
	type GitHub,
	type Night,
} from "./feedback-eval-night.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

const SHA = "c".repeat(40);
const GREEN = "a".repeat(40);
const repo = { owner: "owner", repo: "repo" };

interface Fixture {
	caches?: { key: string; created_at: string }[];
	open?: { number: number; title: string; body?: string }[];
	comments?: { user: { login: string }; body: string }[];
	artifacts?: object[];
}

/** A client over a fixed repository state that records each write. */
function client({ caches = [], open = [], comments = [], artifacts = [] }: Fixture) {
	const writes: { kind: string; input: Record<string, unknown> }[] = [];
	const cacheQueries: Record<string, unknown>[] = [];
	const route = (kind: string) => async (input: Record<string, unknown>) => {
		writes.push({ kind, input });
		return {};
	};
	const listForRepo = route("listForRepo");
	const listComments = route("listComments");
	const listWorkflowRunArtifacts = route("listWorkflowRunArtifacts");
	const github: GitHub = {
		paginate: async (endpoint: unknown) => {
			if (endpoint === listForRepo) {
				return open.map((issue) => ({ body: "", ...issue }));
			}
			if (endpoint === listComments) {
				return comments;
			}
			assert.equal(endpoint, listWorkflowRunArtifacts);
			return artifacts;
		},
		rest: {
			issues: {
				listForRepo,
				listComments,
				create: route("create"),
				createComment: route("comment"),
				update: route("update"),
			},
			actions: {
				getActionsCacheList: async (input: Record<string, unknown>) => {
					cacheQueries.push(input);
					return { data: { actions_caches: caches } };
				},
				listWorkflowRunArtifacts,
			},
		},
	};
	const written = () =>
		writes.filter((write) => ["create", "comment", "update"].includes(write.kind));
	return { github, written, cacheQueries };
}

const night = (
	result: string,
	status: string,
	artifactUrl = "https://example.org/artifact",
): Night => ({
	runId: 99,
	attempt: 1,
	sha: SHA,
	runUrl: "https://github.com/owner/repo/actions/runs/99",
	serverUrl: "https://github.com",
	date: "2026-11-20T03:41:00Z",
	result,
	status,
	artifactUrl,
});

void test("a red night takes its commit range from the newest last-green cache entry", async () => {
	const { github, written, cacheQueries } = client({
		caches: [
			{ key: `${LAST_GREEN_PREFIX}98-${GREEN}`, created_at: "2026-11-18T03:50:00Z" },
			{ key: `${LAST_GREEN_PREFIX}90-${"b".repeat(40)}`, created_at: "2026-11-10T03:50:00Z" },
		],
	});
	assert.equal(await reportNight(github, repo, night("failure", "failed")), "created");
	assert.deepEqual(cacheQueries[0], {
		...repo,
		key: LAST_GREEN_PREFIX,
		sort: "created_at",
		direction: "desc",
		per_page: 10,
	});
	const [created] = written();
	assert.ok(created);
	assert.equal(created.input.title, ISSUE_TITLE);
	const body = asString(created.input.body, "body");
	assert.match(body, /fell below a floor/u);
	assert.ok(
		body.includes(`(2026-11-18): https://github.com/owner/repo/compare/${GREEN}...${SHA}`),
		body,
	);
	assert.match(body, /https:\/\/example\.org\/artifact/u);
});

void test("without a last-green cache entry, the report says the range is unknown, never that no night passed", async () => {
	const { github, written } = client({
		caches: [{ key: `${LAST_GREEN_PREFIX}not-a-run`, created_at: "2026-11-18T03:50:00Z" }],
	});
	await reportNight(github, repo, night("cancelled", ""));
	const body = asString(written()[0]?.input.body, "body");
	assert.match(body, /did not finish \(cancelled\)/u);
	assert.ok(
		body.includes(
			"Last measured green night unknown (cache expired); range from 2026-11-13 not computed.",
		),
		body,
	);
	assert.doesNotMatch(body, /No night has measured and passed/u);
});

void test("the fallback finds the kept result on the run when it was not told it", async () => {
	const { github, written } = client({ artifacts: [{ id: 5, name: "feedback-eval-panel" }] });
	await reportNight(github, repo, night("timed_out", "", ""));
	assert.match(
		asString(written()[0]?.input.body, "body"),
		/Result: https:\/\/github\.com\/owner\/repo\/actions\/runs\/99\/artifacts\/5/u,
	);
});

void test("a red night comments on the open issue, and a replay of the same attempt adds nothing", async () => {
	const open = [{ number: 7, title: ISSUE_TITLE }];
	const first = client({ open });
	assert.equal(
		await reportNight(first.github, repo, night("failure", "ceiling-reached")),
		"commented",
	);
	const [comment] = first.written();
	assert.ok(comment);
	assert.equal(comment.input.issue_number, 7);
	const body = asString(comment.input.body, "body");
	assert.match(body, /^<!-- run:99:1 -->/u);
	assert.match(body, /stopped at its call ceiling/u);

	const replay = client({ open, comments: [{ user: { login: "github-actions[bot]" }, body }] });
	assert.equal(
		await reportNight(replay.github, repo, night("failure", "ceiling-reached")),
		"unchanged",
	);
	assert.deepEqual(replay.written(), []);

	const quoted = client({ open, comments: [{ user: { login: "contributor" }, body }] });
	assert.equal(
		await reportNight(quoted.github, repo, night("failure", "ceiling-reached")),
		"commented",
	);
});

void test("a night that measured and passed closes the open issue", async () => {
	const { github, written } = client({ open: [{ number: 7, title: ISSUE_TITLE }] });
	assert.equal(await reportNight(github, repo, night("success", "passed")), "closed");
	assert.deepEqual(
		written().map((write) => [write.kind, write.input.state ?? null]),
		[
			["comment", null],
			["update", "closed"],
		],
	);
	const quiet = client({});
	assert.equal(await reportNight(quiet.github, repo, night("success", "passed")), "unchanged");
	assert.deepEqual(quiet.written(), []);
});

void test("only a passed scheduled panel saves the last-green entry, under the prefix the report reads", async () => {
	const workflow = asRecord(
		parse(await readFile(".github/workflows/feedback-eval-panel.yml", "utf8")),
		"panel",
	);
	const jobs = asRecord(workflow.jobs, "jobs");
	const steps = asArray(asRecord(jobs.panel, "panel").steps, "steps").map((step) =>
		asRecord(step, "step"),
	);
	const gate = steps.findIndex((step) => step.name === "Compose the panel");
	const save = steps.findIndex((step) => step.name === "Keep the measured green night");
	assert.ok(gate !== -1 && save > gate);
	const saved = steps[save];
	assert.ok(saved);
	// No status function, so the step runs only after the panel step passed.
	assert.equal(saved.if, "github.event_name == 'schedule'");
	assert.equal(
		asRecord(saved.with, "with").key,
		`${LAST_GREEN_PREFIX}\${{ github.run_id }}-\${{ github.sha }}`,
	);
	const report = asArray(asRecord(jobs.report, "report").steps, "steps").map((step) =>
		asRecord(step, "step"),
	);
	assert.equal(
		asRecord(report[0]?.with, "checkout")["sparse-checkout"],
		"scripts/feedback-eval-night.ts",
	);
	assert.match(
		asString(asRecord(report[1]?.with, "with").script, "script"),
		/await import\(`\$\{process\.env\.GITHUB_WORKSPACE\}\/scripts\/feedback-eval-night\.ts`\)/u,
	);
});

void test("the provider check names each missing setting, the key by name only", async () => {
	const workflow = await readFile(".github/workflows/feedback-eval-panel.yml", "utf8");
	assert.match(workflow, /\[ "\$KEY_SET" = true \] \|\| missing\+=\("FEEDBACK_EVAL_API_KEY"\)/u);
	assert.match(workflow, /KEY_SET: \$\{\{ secrets\.FEEDBACK_EVAL_API_KEY != '' \}\}/u);
	assert.doesNotMatch(workflow, /echo[^\n]*secrets\./u);
});
