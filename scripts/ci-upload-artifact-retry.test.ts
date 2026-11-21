import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";

import { parse } from "yaml";

import { isRecord } from "./lib/json.ts";

const ACTION = "./.github/actions/upload-artifact-retry";

/** A job's own reports: a later job or a release never reads them, so their loss must not change the verdict. */
const REPORTS = [
	"Retain raw test reports",
	"Retain dependency verification reports",
	"Retain full database test reports",
	"Retain application test reports",
	"Retain the PMD report",
	"Retain full Storybook and visual test diagnostics",
	"Retain complete buildpack diagnostics",
	"Upload diagnostics",
	"Keep the generated context map",
];

/** The build, the image digests and the release evidence: losing one must fail the job, after the same retries. */
const REQUIRED = [
	"Upload the packaged server",
	"Upload digests",
	"Upload the evidence bundle",
	"Upload the vulnerability policy results",
];

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

function actionSteps(): Record<string, unknown>[] {
	const action: unknown = parse(readFileSync(`${ACTION}/action.yml`, "utf8"));
	assert.ok(isRecord(action) && isRecord(action.runs) && Array.isArray(action.runs.steps));
	assert.ok(isRecord(action.inputs) && isRecord(action.inputs.required));
	// An upload that forgets the input is required: a missing report costs a re-run, a soft required artifact a release.
	assert.equal(action.inputs.required.default, "true");
	return action.runs.steps.filter(isRecord);
}

void test("an artifact uploads in three attempts, each after the attempt before it failed", () => {
	const steps = actionSteps();
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
	assert.deepEqual(
		steps.slice(1, 5).map((step) => step.if),
		[
			"steps.first.outcome == 'failure'",
			"steps.first.outcome == 'failure'",
			"steps.second.outcome == 'failure'",
			"steps.second.outcome == 'failure'",
		],
	);
});

void test("after the last attempt, a required artifact fails the job and a report only warns", () => {
	const [required, report, ...rest] = actionSteps().slice(5);
	assert.equal(rest.length, 0);
	assert.ok(required !== undefined && report !== undefined);
	assert.equal(required.if, "steps.third.outcome == 'failure' && inputs.required != 'false'");
	assert.match(String(required.run), /::error title=Artifact not uploaded::\$NAME/u);
	assert.match(String(required.run), /\nexit 1\n?$/u);
	assert.equal(report.if, "steps.third.outcome == 'failure' && inputs.required == 'false'");
	assert.match(String(report.run), /::warning title=Reports not retained::\$NAME/u);
	assert.match(String(report.run), /GITHUB_STEP_SUMMARY/u);
	assert.doesNotMatch(String(report.run), /exit 1/u);
});

void test("every artifact upload is a report or a required artifact, and both go through the retries", () => {
	const steps = workflowSteps();
	for (const [names, required] of [
		[REPORTS, false],
		[REQUIRED, true],
	] as const) {
		for (const name of names) {
			const found = steps.filter(({ step }) => step.name === name);
			assert.ok(found.length > 0, name);
			for (const { file, job, step } of found) {
				const where = `${file}#${job}: ${name}`;
				assert.equal(step.uses, ACTION, where);
				assert.notEqual(step["continue-on-error"], true, where);
				assert.ok(isRecord(step.with), where);
				if (required) {
					assert.equal(step.with.required, undefined, `${where} is required by default`);
				} else {
					assert.equal(step.with.required, "false", where);
					assert.notEqual(
						step.with["if-no-files-found"],
						"error",
						`${where}: a report is never required`,
					);
				}
			}
		}
	}
	// A report outside the list would be required by default; a soft upload outside it would hide a lost artifact.
	const soft = steps.filter(
		({ step }) => step.uses === ACTION && isRecord(step.with) && step.with.required === "false",
	);
	assert.deepEqual(
		[...new Set(soft.map(({ step }) => String(step.name)))].toSorted(),
		REPORTS.toSorted(),
	);
});
