import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";

import { parse } from "yaml";

import { isRecord } from "./lib/json.ts";

const ACTION = "./.github/actions/upload-artifact-retry";

/**
 * The reports, by workflow and step: diagnostics for a person, next to a verdict that the job's own steps decide. Their
 * loss only warns. Every other upload is required: a later job, a later run, a release or a gate reads it, or it is
 * the product of a report run, so its loss fails the job after the retries.
 */
const REPORTS = [
	"cicd.yml: Keep the gate result",
	"cicd.yml: Retain sandbox qualification summary",
	"ci-browser-tests.yml: Upload diagnostics",
	"ci-build.yml: Retain full database test reports",
	"ci-quality-gates.yml: Retain application test reports",
	"ci-quality-gates.yml: Retain full Storybook and visual test diagnostics",
	"ci-quality-gates.yml: Retain the PMD report",
	"ci-quality-gates.yml: Upload application test metrics",
	"ci-quality-gates.yml: Upload the extension package",
	"ci-tests.yml: Keep the generated context map",
	"ci-tests.yml: Retain dependency verification reports",
	"ci-tests.yml: Retain raw test reports",
	"extension-provider-slots.yml: Keep each provider result",
	"feedback-eval-panel.yml: Keep the panel result",
	"kubernetes-nightly.yml: Keep the report, the logs and the samples",
	"release.yml: Keep the ratchet results",
	"release.yml: Retain sandbox qualification summary",
	"rescan-images.yml: Upload diagnostic reports",
	"reusable-docker-build.yml: Retain complete buildpack diagnostics",
	"scorecard-ratchet.yml: Preserve ratchet evidence",
	"security-mutation.yml: Upload mutation report",
];

/** OpenSSF Scorecard verifies that its publishing workflow runs only allowlisted actions, so it cannot use ours. */
const DIRECT = ["scorecard.yml"];

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
	// The image scans read the advisory snapshot by the ID of whichever attempt uploaded it.
	assert.ok(isRecord(action.outputs) && isRecord(action.outputs["artifact-id"]));
	assert.equal(
		action.outputs["artifact-id"].value,
		"${{ steps.first.outputs.artifact-id || steps.second.outputs.artifact-id || steps.third.outputs.artifact-id }}",
	);
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

void test("no workflow uploads an artifact around the retries, and only the listed reports are soft", () => {
	const steps = workflowSteps();
	const direct = steps.filter(({ step }) =>
		String(step.uses).startsWith("actions/upload-artifact@"),
	);
	assert.deepEqual([...new Set(direct.map(({ file }) => file))], DIRECT);
	const actions = readdirSync(".github/actions").filter(
		(action) =>
			action !== "upload-artifact-retry" &&
			readFileSync(`.github/actions/${action}/action.yml`, "utf8").includes(
				"actions/upload-artifact@",
			),
	);
	assert.deepEqual(actions, []);
	const uploads = steps.filter(({ step }) => step.uses === ACTION);
	assert.ok(uploads.length >= 40, `found ${String(uploads.length)} uploads`);
	for (const { file, job, step } of uploads) {
		const where = `${file}#${job}: ${String(step.name)}`;
		assert.notEqual(step["continue-on-error"], true, where);
		assert.ok(isRecord(step.with), where);
		// Required is the default, so `required: "true"` would only be a second way to write it.
		assert.ok(step.with.required === undefined || step.with.required === "false", where);
		if (step.with.required === "false") {
			assert.notEqual(
				step.with["if-no-files-found"],
				"error",
				`${where}: a report is never required`,
			);
		}
	}
	// A report missing from the list is required by default; a soft upload outside it would hide a lost artifact.
	const soft = uploads.filter(({ step }) => isRecord(step.with) && step.with.required === "false");
	assert.deepEqual(
		[...new Set(soft.map(({ file, step }) => `${file}: ${String(step.name)}`))].toSorted(),
		REPORTS.toSorted(),
	);
});
