import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

const workflow = parseDocument(await readFile(".github/workflows/version-pr.yml", "utf8"));
const job = workflow.getIn(["jobs", "version-pr"]);
assert.ok(isMap(job));
const steps = job.get("steps");
assert.ok(isSeq(steps));

void test("Version PR maintenance follows trusted successful main CI without cancelling mutations", () => {
	const trigger = workflow.getIn(["on", "workflow_run"]);
	assert.ok(isMap(trigger));
	assert.deepEqual(trigger.toJSON(), {
		workflows: ["CI/CD"],
		types: ["completed"],
		branches: ["main"],
	});
	assert.equal(workflow.getIn(["on", "push"]), undefined);
	assert.equal(job.getIn(["concurrency", "cancel-in-progress"]), false);
	const guard = job.get("if");
	assert.equal(typeof guard, "string");
	assert.match(String(guard), /github\.event\.workflow_run\.conclusion == 'success'/u);
	assert.match(String(guard), /&& github\.event\.workflow_run\.event == 'push'/u);
	assert.match(
		String(guard),
		/&& github\.event\.workflow_run\.head_repository\.full_name == github\.repository/u,
	);
});

void test("the maintainer runs from main's tip and reads nothing else from the event", () => {
	// `scripts/maintain-version-pr.ts` selects the validated commit to version; its tests own that.
	const [checkout, toolchain, maintain] = steps.items;
	assert.ok(isMap(checkout) && isMap(toolchain) && isMap(maintain));
	assert.equal(checkout.getIn(["with", "ref"]), `\${{ github.event.repository.default_branch }}`);
	assert.equal(checkout.getIn(["with", "fetch-depth"]), 0);
	assert.equal(toolchain.get("uses"), "./.github/actions/setup-toolchain");
	assert.equal(maintain.get("run"), "node scripts/maintain-version-pr.ts");
	assert.doesNotMatch(
		JSON.stringify(steps.toJSON()),
		/github\.event\.workflow_run/u,
		"the selected commit comes from main's history, not from the event",
	);
});

const reportStep = workflow.getIn(["jobs", "report", "steps", 0, "run"]);
assert.ok(typeof reportStep === "string");
const reportScript = reportStep;

/**
 * Runs the report step against a stand-in `gh` that logs each call. `listed` is what the jobs API
 * lists for the maintenance job: undefined fails the call, and an empty string names no such job.
 */
async function report(
	context: TestContext,
	{ result, listed, openIssue = false }: { result: string; listed?: string; openIssue?: boolean },
) {
	const directory = await mkdtemp(path.join(tmpdir(), "version-pr-report-"));
	context.after(async () => rm(directory, { recursive: true, force: true }));
	const log = path.join(directory, "calls.log");
	const issues = openIssue ? '[{"number":7,"title":"Version PR maintenance failed"}]' : "[]";
	const gh = path.join(directory, "gh");
	await writeFile(
		gh,
		[
			"#!/bin/sh",
			`echo "$*" >> "${log}"`,
			'case "$1 $2" in',
			`  "issue list") echo '${issues}' ;;`,
			listed === undefined ? "  api*) exit 1 ;;" : `  api*) echo "${listed}" ;;`,
			"esac",
			"",
		].join("\n"),
	);
	await chmod(gh, 0o755);
	const run = spawnSync("bash", ["--noprofile", "--norc", "-c", reportScript], {
		encoding: "utf8",
		env: {
			...process.env,
			PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
			REPOSITORY: "owner/repository",
			RESULT: result,
			RUN_ATTEMPT: "2",
			RUN_ID: "11",
			RUN_URL: "https://example.invalid/run",
		},
	});
	const calls = await readFile(log, "utf8").catch(() => "");
	return { status: run.status, calls: calls.trim().split("\n").filter(Boolean) };
}

const issueCalls = (calls: readonly string[]) =>
	calls
		.filter((call) => /^issue (?:create|comment|close)/u.test(call))
		.map((call) => call.split(" ")[1]);

void test(
	"a replaced run stays silent, and a run that started and was cancelled raises the alert",
	{ skip: process.platform === "win32" },
	async (context) => {
		const replaced = await report(context, { result: "cancelled", listed: "0" });
		assert.equal(replaced.status, 0);
		assert.deepEqual(replaced.calls, [
			'api repos/owner/repository/actions/runs/11/attempts/2/jobs?per_page=100 --jq .jobs[] | select(.name == "version-pr") | .steps | length',
		]);

		const started = await report(context, { result: "cancelled", listed: "5" });
		assert.equal(started.status, 0);
		assert.deepEqual(issueCalls(started.calls), ["create"]);

		const again = await report(context, { result: "cancelled", listed: "5", openIssue: true });
		assert.deepEqual(issueCalls(again.calls), ["comment"]);

		// A listing without the job cannot prove that it never started, so the alert still fires.
		const unlisted = await report(context, { result: "cancelled", listed: "" });
		assert.deepEqual(issueCalls(unlisted.calls), ["create"]);

		// A failed lookup fails the report job, so the run is red, and it files no claim.
		const lookup = await report(context, { result: "cancelled" });
		assert.notEqual(lookup.status, 0);
		assert.deepEqual(issueCalls(lookup.calls), []);
	},
);

void test(
	"a failure raises the alert and a success closes it, without asking for the job's steps",
	{ skip: process.platform === "win32" },
	async (context) => {
		const failure = await report(context, { result: "failure" });
		assert.deepEqual(issueCalls(failure.calls), ["create"]);
		assert.equal(
			failure.calls.some((call) => call.startsWith("api")),
			false,
		);

		const recovered = await report(context, { result: "success", openIssue: true });
		assert.deepEqual(issueCalls(recovered.calls), ["close"]);

		const quiet = await report(context, { result: "success" });
		assert.equal(quiet.status, 0);
		assert.deepEqual(issueCalls(quiet.calls), []);
	},
);
