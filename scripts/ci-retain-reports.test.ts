import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";

import { parse } from "yaml";

import { isRecord } from "./lib/json.ts";

const ACTION = "./.github/actions/retain-reports";

/** Every step of every workflow, with its file and job. */
function workflowSteps(): { file: string; job: string; step: Record<string, unknown> }[] {
	return readdirSync(".github/workflows")
		.filter((file) => file.endsWith(".yml"))
		.flatMap((file) => {
			const workflow: unknown = parse(readFileSync(`.github/workflows/${file}`, "utf8"));
			const jobs = isRecord(workflow) && isRecord(workflow.jobs) ? workflow.jobs : {};
			return Object.entries(jobs).flatMap(([job, definition]) =>
				isRecord(definition) && Array.isArray(definition.steps)
					? definition.steps.filter(isRecord).map((step) => ({ file, job, step }))
					: [],
			);
		});
}

void test("a job's own reports upload in three attempts, and a lost upload warns without changing the verdict", () => {
	const action: unknown = parse(readFileSync(`${ACTION}/action.yml`, "utf8"));
	assert.ok(isRecord(action) && isRecord(action.runs) && Array.isArray(action.runs.steps));
	const steps = action.runs.steps.filter(isRecord);
	const uploads = steps.filter((step) => String(step.uses).startsWith("actions/upload-artifact@"));
	assert.equal(uploads.length, 3);
	for (const upload of uploads) {
		// A failed attempt must not fail the job, and a failed finalize must not block the next attempt.
		assert.equal(upload["continue-on-error"], true);
		assert.ok(isRecord(upload.with));
		assert.equal(upload.with.overwrite, true);
	}
	assert.deepEqual(
		uploads.map((upload) => upload.id),
		["first", "second", "third"],
	);
	const waits = steps
		.map((step) => /sleep (?<seconds>\d+)/u.exec(String(step.run))?.groups?.seconds)
		.filter(Boolean);
	assert.deepEqual(waits, ["15", "45"]);
	// Each later attempt and its wait run only after the attempt before it failed.
	assert.deepEqual(
		steps.slice(1).map((step) => step.if),
		[
			"steps.first.outcome == 'failure'",
			"steps.first.outcome == 'failure'",
			"steps.second.outcome == 'failure'",
			"steps.second.outcome == 'failure'",
			"steps.third.outcome == 'failure'",
		],
	);
	const last = steps.at(-1);
	assert.ok(last !== undefined);
	assert.equal(last.if, "steps.third.outcome == 'failure'");
	assert.match(String(last.run), /::warning title=Reports not retained::/u);
	assert.match(String(last.run), /GITHUB_STEP_SUMMARY/u);
});

void test("every report step uses the action, and an artifact that a later job or a release reads does not", () => {
	const steps = workflowSteps();
	const reports = steps.filter(({ step }) =>
		[
			"Retain raw test reports",
			"Retain dependency verification reports",
			"Retain full database test reports",
			"Retain application test reports",
			"Retain the PMD report",
			"Retain full Storybook and visual test diagnostics",
			"Retain complete buildpack diagnostics",
			"Upload diagnostics",
		].includes(String(step.name)),
	);
	assert.ok(reports.length >= 10, `found ${String(reports.length)} report steps`);
	for (const { file, job, step } of reports) {
		assert.equal(step.uses, ACTION, `${file}#${job}: ${String(step.name)}`);
		assert.ok(isRecord(step.with));
		assert.notEqual(
			step.with["if-no-files-found"],
			"error",
			`${file}#${job}: a report is never required`,
		);
	}
	// These artifacts carry the build, the image digests or the release evidence: losing one must fail the job.
	for (const name of [
		"Upload the packaged server",
		"Upload digests",
		"Upload the evidence bundle",
	]) {
		const found = steps.filter(({ step }) => step.name === name);
		assert.ok(found.length > 0, name);
		for (const { step } of found) {
			assert.match(String(step.uses), /^actions\/upload-artifact@/u, name);
			assert.notEqual(step["continue-on-error"], true, name);
		}
	}
});
