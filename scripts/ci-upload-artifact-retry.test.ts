import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { test } from "node:test";

import { parse } from "yaml";

import { isRecord } from "./lib/json.ts";

const ACTION = "./.github/actions/upload-artifact-retry";
/** This workflow's own copy, for a job that checks out a ref that may predate the action. */
const OWN_ACTION = "./workflow-actions/.github/actions/upload-artifact-retry";

/**
 * The reports, by workflow, step and artifact: diagnostics for a person, next to a verdict that the job's own steps decide. Their
 * loss only warns. Every other upload is required: a later job, a later run, a release or a gate reads it, or it is
 * the product of a report run, so its loss fails the job after the retries.
 */
const REPORTS = [
	"ci-browser-tests.yml: Upload diagnostics (extension-e2e-results)",
	"ci-browser-tests.yml: Upload diagnostics (webapp-e2e-results)",
	"ci-build.yml: Retain full database test reports (database-test-reports-${{ github.run_attempt }})",
	"ci-quality-gates.yml: Retain application test reports (${{ env.QUALITY_LEG == 'extension' && 'extension' || 'webapp' }}-unit-reports-${{ github.run_attempt }})",
	"ci-quality-gates.yml: Retain full Storybook and visual test diagnostics (storybook-reports-${{ github.run_attempt }})",
	"ci-quality-gates.yml: Retain the PMD report (server-pmd-report-${{ github.run_attempt }})",
	"ci-quality-gates.yml: Upload application test metrics (test-metrics-${{ env.QUALITY_LEG == 'extension' && 'extension' || 'webapp' }}-unit)",
	"ci-quality-gates.yml: Upload the extension package (extension-chrome-${{ github.run_attempt }})",
	"ci-tests.yml: Keep the generated context map (server-context-map-${{ github.run_attempt }})",
	"ci-tests.yml: Retain dependency verification reports (server-integration-${{ env.INTEGRATION_SHARD }}-dependency-verification-${{ github.run_attempt }})",
	"ci-tests.yml: Retain raw test reports (server-${{ env.TEST_TIER }}-reports-${{ github.run_attempt }})",
	"ci-tests.yml: Retain raw test reports (server-integration-${{ env.INTEGRATION_SHARD }}-reports-${{ github.run_attempt }})",
	"cicd.yml: Keep the gate result (feedback-eval-gate)",
	"cicd.yml: Retain sandbox qualification summary (sandbox-security-preflight-summary)",
	"extension-provider-slots.yml: Keep each provider result (extension-provider-slots)",
	"feedback-eval-panel.yml: Keep the panel result (feedback-eval-panel)",
	"kubernetes-nightly.yml: Keep the report, the logs and the samples (sandbox-footprint)",
	"release.yml: Keep the ratchet results (stable-release-ratchets)",
	"release.yml: Retain sandbox qualification summary (sandbox-security-summary)",
	"rescan-images.yml: Upload diagnostic reports (supported-release-rescan-${{ github.run_id }})",
	"reusable-docker-build.yml: Retain complete buildpack diagnostics (buildpacks-log-${{ steps.prep.outputs.platform_pair }}-${{ github.run_attempt }})",
	"scorecard-ratchet.yml: Preserve ratchet evidence (scorecard-ratchet-${{ github.sha }})",
	"security-mutation.yml: Upload mutation report (security-mutation-report-${{ github.run_id }}-${{ github.run_attempt }})",
];

/**
 * The one upload outside the retries: OpenSSF Scorecard publishes results only from a workflow whose steps are all
 * allowlisted actions, and a local action is not one of them.
 */
const DIRECT = ["scorecard.yml: Preserve the assessment"];

interface Step {
	file: string;
	job: string;
	step: Record<string, unknown>;
	/** The step before it in its job. */
	previous: Record<string, unknown> | undefined;
	/** Whether the job's workspace checkout takes a `ref`, which can predate the action. */
	otherRef: boolean;
}

/** Every step of every workflow, with its file and job. */
function workflowSteps(): Step[] {
	return readdirSync(".github/workflows")
		.filter((file) => file.endsWith(".yml"))
		.flatMap((file) => {
			const workflow: unknown = parse(readFileSync(`.github/workflows/${file}`, "utf8"));
			const jobs = isRecord(workflow) && isRecord(workflow.jobs) ? workflow.jobs : {};
			return Object.entries(jobs).flatMap(([job, definition]) => {
				const steps =
					isRecord(definition) && Array.isArray(definition.steps)
						? definition.steps.filter(isRecord)
						: [];
				const otherRef = steps.some(
					(step) =>
						String(step.uses).startsWith("actions/checkout@") &&
						isRecord(step.with) &&
						step.with.path === undefined &&
						step.with.ref !== undefined &&
						step.with.ref !== "${{ github.event.pull_request.base.sha }}",
				);
				return steps.map((step, index) => ({
					file,
					job,
					step,
					previous: steps[index - 1],
					otherRef,
				}));
			});
		});
}

function actionSteps(): Record<string, unknown>[] {
	const action: unknown = parse(readFileSync(`${ACTION}/action.yml`, "utf8"));
	assert.ok(isRecord(action) && isRecord(action.runs) && Array.isArray(action.runs.steps));
	assert.ok(isRecord(action.inputs) && isRecord(action.inputs.required));
	// An upload that forgets the input is required: a missing report costs a re-run, a soft required artifact a release.
	assert.equal(action.inputs.required.default, "true");
	// The image scans read the advisory snapshot by its ID. A later attempt replaces an earlier one's artifact.
	assert.ok(isRecord(action.outputs) && isRecord(action.outputs["artifact-id"]));
	assert.equal(
		action.outputs["artifact-id"].value,
		"${{ steps.third.outputs.artifact-id || steps.second.outputs.artifact-id || steps.first.outputs.artifact-id }}",
	);
	return action.runs.steps.filter(isRecord);
}

void test("an artifact uploads in three attempts, each after the attempt before it failed", () => {
	const steps = actionSteps();
	const uploads = steps.filter((step) => String(step.uses).startsWith("actions/upload-artifact@"));
	assert.equal(uploads.length, 3);
	for (const upload of uploads) {
		// A failed attempt must not fail the job.
		assert.equal(upload["continue-on-error"], true);
	}
	// A failed finalize can leave a partial artifact, so a retry replaces it. The first attempt replaces one only
	// when its caller asks, so two uploads of one name still fail.
	assert.deepEqual(
		uploads.map((upload) => (isRecord(upload.with) ? upload.with.overwrite : undefined)),
		["${{ inputs.overwrite }}", true, true],
	);
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
	assert.deepEqual(
		direct.map(({ file, step }) => `${file}: ${String(step.name)}`),
		DIRECT,
	);
	const actions = readdirSync(".github/actions").filter(
		(action) =>
			action !== "upload-artifact-retry" &&
			readFileSync(`.github/actions/${action}/action.yml`, "utf8").includes(
				"actions/upload-artifact@",
			),
	);
	assert.deepEqual(actions, []);
	const uploads = steps.filter(({ step }) => step.uses === ACTION || step.uses === OWN_ACTION);
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
		[
			...new Set(
				soft.map(
					({ file, step }) =>
						`${file}: ${String(step.name)} (${String(isRecord(step.with) && step.with.name)})`,
				),
			),
		].toSorted(),
		REPORTS.toSorted(),
	);
});

void test("a job that checks out another ref uploads through this workflow's own copy of the action", () => {
	// A release tag or a pull request's head can predate the action, so the workspace may have no copy of it.
	const uploads = workflowSteps().filter(
		({ step }) => step.uses === ACTION || step.uses === OWN_ACTION,
	);
	assert.ok(uploads.some(({ otherRef }) => otherRef));
	for (const { file, job, step, previous, otherRef } of uploads) {
		const where = `${file}#${job}: ${String(step.name)}`;
		assert.equal(step.uses, otherRef ? OWN_ACTION : ACTION, where);
		if (!otherRef) {
			continue;
		}
		// The copy comes from the commit of the running workflow, never from the checked-out ref.
		assert.ok(
			previous !== undefined && String(previous.uses).startsWith("actions/checkout@"),
			where,
		);
		assert.deepEqual(previous.with, {
			ref: "${{ github.workflow_sha }}",
			path: "workflow-actions",
			"sparse-checkout": ".github/actions/upload-artifact-retry",
			"persist-credentials": false,
		});
		assert.equal(previous.if, step.if, `${where}: the copy is there whenever the upload runs`);
	}
	// pull-request.yml takes the action from the base commit; pull-request-policy.test.ts pins that checkout.
});
