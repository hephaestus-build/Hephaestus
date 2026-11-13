import assert from "node:assert/strict";
import { glob, readFile } from "node:fs/promises";
import { test } from "node:test";
import { constants, runInNewContext } from "node:vm";

import { parse } from "yaml";

import { asArray, asRecord, asString } from "./lib/json.ts";

const observer = asRecord(
	parse(await readFile(".github/workflows/scheduled-failures.yml", "utf8")),
	"observer",
);
const trigger = asRecord(asRecord(observer.on, "on").workflow_run, "workflow_run");
const jobs = asRecord(observer.jobs, "jobs");
const report = asRecord(jobs.report, "report");
const step = asRecord(
	asArray(report.steps, "steps").find(
		(each) => asRecord(each, "step").name === "Notify maintainers of a failed scheduled run",
	),
	"step",
);
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

/** Runs the shared reporter on a panel night, with the real report script it imports. */
async function panelFallback(
	conclusion: string,
	runJobs: object[],
	open: object[] = [],
	comments: object[] = [],
) {
	const writes: { kind: string; input: Record<string, unknown> }[] = [];
	const marks = new Map<unknown, string>();
	const route = (name: string) => {
		const call = async (input: Record<string, unknown>) => {
			writes.push({ kind: name, input });
			return {};
		};
		marks.set(call, name);
		return call;
	};
	const rest = {
		actions: {
			listJobsForWorkflowRunAttempt: route("jobs"),
			listWorkflowRunArtifacts: route("artifacts"),
			getActionsCacheList: async () => ({
				data: {
					actions_caches: [
						{
							key: `feedback-eval-last-green-41-${"a".repeat(40)}`,
							created_at: "2026-11-18T03:50:00Z",
						},
					],
				},
			}),
		},
		issues: {
			listForRepo: route("issues"),
			listComments: route("comments"),
			create: route("create"),
			createComment: route("comment"),
			update: route("update"),
		},
	};
	await runInNewContext(
		`(async () => { ${script} })()`,
		{
			process: { env: { GITHUB_WORKSPACE: process.cwd() } },
			context: {
				repo: { owner: "owner", repo: "repo" },
				serverUrl: "https://github.com",
				payload: {
					workflow_run: {
						workflow_id: 9,
						id: 42,
						run_attempt: 1,
						name: PANEL,
						conclusion,
						head_sha: "c".repeat(40),
						run_started_at: "2026-11-20T03:41:00Z",
						html_url: "https://github.com/owner/repo/actions/runs/42",
					},
				},
			},
			github: {
				rest,
				paginate: async (endpoint: unknown) => {
					const name = marks.get(endpoint);
					if (name === "jobs") {
						return runJobs;
					}
					if (name === "issues") {
						return open;
					}
					return name === "comments" ? comments : [];
				},
			},
		},
		{ importModuleDynamically: constants.USE_MAIN_CONTEXT_DEFAULT_LOADER },
	);
	return writes.filter((write) => ["create", "comment", "update"].includes(write.kind));
}

/** A night whose panel ran, and whose own report died before it wrote. */
const reporterDied = [
	{
		name: "Model provider",
		conclusion: "success",
		steps: [{ name: "Check the provider settings" }],
	},
	{ name: "Model panel", conclusion: "cancelled", steps: [{ name: "Set up job" }] },
	{ name: "Report a failure", conclusion: "timed_out", steps: [] },
];

void test("a cancelled night reaches the shared reporter only for the panel", () => {
	const condition = asString(report.if, "report.if");
	assert.match(
		condition,
		/github\.event\.workflow_run\.name == 'CI \/ Feedback eval panel' && github\.event\.workflow_run\.conclusion == 'cancelled'/u,
	);
	assert.equal(condition.match(/'cancelled'/gu)?.length, 1);
});

void test("a cancelled panel night whose panel never started reports nothing", async () => {
	assert.deepEqual(await panelFallback("cancelled", []), []);
	assert.deepEqual(
		await panelFallback("cancelled", [{ name: "Model panel", conclusion: "cancelled", steps: [] }]),
		[],
	);
});

void test("the panel's fallback writes the panel's one issue with its commit range", async () => {
	// The panel's own report succeeded: nothing to add.
	assert.deepEqual(
		await panelFallback("failure", [{ name: "Report a failure", conclusion: "success" }]),
		[],
	);
	const [created] = await panelFallback("cancelled", reporterDied);
	assert.ok(created);
	assert.equal(created.kind, "create");
	assert.equal(created.input.title, PANEL_TITLE);
	const body = asString(created.input.body, "body");
	assert.ok(body.includes(`compare/${"a".repeat(40)}...${"c".repeat(40)}`), body);
	assert.match(body, /did not finish \(cancelled\)/u);
	// With the panel's issue open, the fallback comments on it and opens no second issue.
	const open = [{ number: 5, title: PANEL_TITLE, body: "" }];
	const writes = await panelFallback("failure", reporterDied, open);
	assert.deepEqual(
		writes.map((write) => [write.kind, write.input.issue_number]),
		[["comment", 5]],
	);
	// A replay of the same attempt mentions the maintainers once.
	const told = [
		{ user: { login: "github-actions[bot]" }, body: asString(writes[0]?.input.body, "body") },
	];
	assert.deepEqual(await panelFallback("failure", reporterDied, open, told), []);
});
