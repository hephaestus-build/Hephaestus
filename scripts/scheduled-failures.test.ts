import assert from "node:assert/strict";
import { glob, readFile } from "node:fs/promises";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

import { parse } from "yaml";

import { asArray, asRecord, asString } from "./lib/json.ts";

const observer = asRecord(
	parse(await readFile(".github/workflows/scheduled-failures.yml", "utf8")),
	"observer",
);
const trigger = asRecord(asRecord(observer.on, "on").workflow_run, "workflow_run");
const jobs = asRecord(observer.jobs, "jobs");
const report = asRecord(jobs.report, "report");
const step = asRecord(asArray(report.steps, "steps")[0], "step");
const script = asString(asRecord(step.with, "with").script, "script");

void test("every scheduled workflow has a notification owner", async () => {
	const watched = asArray(trigger.workflows, "workflows").map((value) =>
		asString(value, "workflow name"),
	);
	const scheduled: string[] = [];
	for await (const file of glob(".github/workflows/*.yml")) {
		const workflow = asRecord(parse(await readFile(file, "utf8")), file);
		const on = asRecord(workflow.on, `${file}.on`);
		if (on.schedule === undefined) {
			continue;
		}
		const name = asString(workflow.name, "workflow.name");
		scheduled.push(name);
		assert.ok(watched.includes(name), name);
	}
	assert.deepEqual(
		watched.toSorted((a, b) => a.localeCompare(b)),
		scheduled.toSorted((a, b) => a.localeCompare(b)),
	);
});

async function notify(
	existing: { number: number; body: string; title?: string }[] = [],
	comments: { user: { login: string }; body: string }[] = [],
	fail = false,
	nativeJobs?: object[],
	name?: string,
) {
	const writes: { kind: string; input: Record<string, unknown> }[] = [];
	const list = Symbol("issues");
	const listComments = Symbol("comments");
	const listJobs = Symbol("jobs");
	const completed: unknown = runInNewContext(`(async () => { ${script} })()`, {
		context: {
			repo: { owner: "owner", repo: "repo" },
			payload: {
				workflow_run: {
					workflow_id: 7,
					id: 42,
					run_attempt: 2,
					name: name ?? (nativeJobs === undefined ? "Weekly check" : "Security / Rescan images"),
					conclusion: "failure",
					html_url: "https://github.com/owner/repo/actions/runs/42",
				},
			},
		},
		github: {
			rest: {
				actions: { listJobsForWorkflowRunAttempt: listJobs },
				issues: {
					listForRepo: list,
					listComments,
					create: async (input: Record<string, unknown>) => {
						writes.push({ kind: "create", input });
					},
					createComment: async (input: Record<string, unknown>) => {
						writes.push({ kind: "comment", input });
					},
				},
			},
			paginate: async (endpoint: unknown, input: Record<string, unknown>) => {
				if (fail) {
					throw new Error("HTTP 503");
				}
				if (endpoint === listJobs) {
					assert.equal(input.attempt_number, 2);
					return nativeJobs;
				}
				if (endpoint === list) {
					assert.equal(input.creator, "github-actions[bot]");
					return existing;
				}
				assert.equal(endpoint, listComments);
				return comments;
			},
		},
	});
	await completed;
	return writes;
}

void test("scheduled failures notify the maintainer team once per attempt", async () => {
	const [created] = await notify();
	assert.ok(created);
	assert.equal(created.kind, "create");
	const body = asString(created.input.body, "body");
	assert.match(body, /@hephaestus-build\/maintainer/u);
	assert.match(body, /attempt 2/u);
	assert.deepEqual(await notify([{ number: 1, body }]), []);
	const open = [{ number: 1, body: "<!-- hephaestus:scheduled-failure:7 -->" }];
	const [update] = await notify(open);
	assert.ok(update);
	assert.equal(update.kind, "comment");
	assert.equal(update.input.issue_number, 1);
	assert.deepEqual(await notify(open, [{ user: { login: "github-actions[bot]" }, body }]), []);
	const untrustedReplay = await notify(open, [{ user: { login: "contributor" }, body }]);
	assert.equal(untrustedReplay.length, 1);
	await assert.rejects(notify([], [], true), /HTTP 503/u);
});

void test("the shared reporter covers timeouts but leaves successful specific notifications alone", async () => {
	assert.deepEqual(
		await notify([], [], false, [
			{ conclusion: "failure", steps: [{ name: "Report a failed rescan", conclusion: "success" }] },
		]),
		[],
	);
	const timedOut = await notify([], [], false, [{ conclusion: "timed_out", steps: [] }]);
	assert.equal(timedOut.length, 1);
	const failedNotice = await notify([], [], false, [
		{ conclusion: "failure", steps: [{ name: "Report a failed rescan", conclusion: "failure" }] },
	]);
	assert.equal(failedNotice.length, 1);
});

const PANEL = "CI / Feedback eval panel";
const PANEL_TITLE = "The nightly feedback eval panel is not green";

void test("the panel's fallback report and its own report keep one issue", async () => {
	const panelWorkflow = await readFile(".github/workflows/feedback-eval-panel.yml", "utf8");
	assert.ok(panelWorkflow.includes(`const title = '${PANEL_TITLE}';`));
	assert.ok(script.includes(`'${PANEL_TITLE}'`));
	// The panel's own report succeeded: nothing to add.
	assert.deepEqual(
		await notify([], [], false, [{ name: "Report a failure", conclusion: "success" }], PANEL),
		[],
	);
	// The report job timed out: the fallback opens the panel's issue, which the next green night closes.
	const timedOut = [{ name: "Report a failure", conclusion: "timed_out", steps: [] }];
	const [created] = await notify([], [], false, timedOut, PANEL);
	assert.ok(created);
	assert.equal(created.kind, "create");
	assert.equal(created.input.title, PANEL_TITLE);
	// With the panel's issue open, the fallback comments on it and opens no second issue.
	const open = [{ number: 5, title: PANEL_TITLE, body: "the panel's own report" }];
	const writes = await notify(open, [], false, timedOut, PANEL);
	assert.deepEqual(
		writes.map((write) => [write.kind, write.input.issue_number]),
		[["comment", 5]],
	);
});
