import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { minimatch } from "minimatch";
import { isSeq } from "yaml";

import { jobsOf, parseDocument, render, runScript, runStep, step } from "./ci-contract-support.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

// docs/contributor/ci-cd.mdx § Release checks on the change owns the reasons for these tests.
const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const filters = asRecord(
	parseDocument(
		String(step(cicd, ["jobs", "detect-changes"], "dorny/paths-filter").get("filters")),
	).toJSON(),
	"filters",
);

/** Whether `files` select `name`, as `dorny/paths-filter` with `some-with-excludes` decides. */
function selects(name: string, files: readonly string[]): boolean {
	const patterns = asArray(filters[name], name).map((pattern) => asString(pattern, "path pattern"));
	return files.some(
		(file) =>
			patterns
				.filter((pattern) => !pattern.startsWith("!"))
				.some((pattern) => minimatch(file, pattern, { dot: true })) &&
			patterns
				.filter((pattern) => pattern.startsWith("!"))
				.every((pattern) => !minimatch(file, pattern.slice(1), { dot: true })),
	);
}

// Changes that broke a release-only check on the release-candidate night of 2026-11-22, with paths
// from their diffs. Each one must select the check it broke.
const BROKE = {
	// #4764 changed PostgreSQL's memory, and the chart upgrade drill refused the upgrade (#4845).
	"chart-upgrade": [
		"deploy/chart/hephaestus/templates/postgresql.yaml",
		"deploy/chart/hephaestus/values.yaml",
		"deploy/chart/hephaestus/values.schema.json",
		"scripts/kubernetes-chart-render-support.ts",
	],
	// #4637 moved the web app into the server, and the evidence capture failed on Syft's `dir:` root
	// entry; #4848 then changed only the SBOM check.
	"release-evidence": [
		"docker/docker-bake.hcl",
		".github/workflows/reusable-docker-build.yml",
		"scripts/check-release-sbom.ts",
		"scripts/generate-release-evidence.ts",
	],
	// #4841 confined the file tools to the workspace, and the qualification did not know the new
	// refusal (#4849).
	"sandbox-qualification": [
		"server/application/src/main/resources/agent/pi-agent-sandbox.ts",
		"server/application/src/main/resources/agent/turn.ts",
		"server/application/src/test/resources/agent/tool-environment.spec.ts",
	],
} as const;

const CHECKS = ["chart-upgrade", "release-evidence", "sandbox-qualification"] as const;

void test("each release-only check is selected by the change that broke it", () => {
	assert.ok(selects("chart-upgrade", BROKE["chart-upgrade"]));
	assert.ok(selects("release-evidence", BROKE["release-evidence"]), "#4637");
	assert.ok(selects("release-evidence", ["scripts/check-release-sbom.ts"]), "#4848");
	assert.ok(selects("sandbox-qualification", BROKE["sandbox-qualification"]));
	// The qualification's own corpus and script select it too, and so does the drill for the upgrade.
	assert.ok(selects("sandbox-qualification", ["scripts/sandbox-security.ts"]));
	assert.ok(selects("chart-upgrade", ["scripts/chart-upgrade-drill.ts"]));
});

void test("an unrelated change selects none of the release checks", () => {
	for (const files of [
		["docs/user/feedback.mdx"],
		["webapp/src/components/feedback/FeedbackCard.tsx"],
		["server/application/src/main/java/de/tum/cit/aet/hephaestus/feedback/FeedbackService.java"],
		["scripts/report-duplication.ts"],
	]) {
		for (const check of CHECKS) {
			assert.equal(selects(check, files), false, `${files.join(", ")} selects ${check}`);
		}
	}
});

void test("a selected check runs on the change's own run and feeds the gate", async () => {
	// A pull request builds this head's images for the chart upgrade and the qualification.
	for (const name of ["head-images", "images"]) {
		const expression = String(cicd.getIn(["jobs", "detect-changes", "outputs", name]));
		for (const check of ["chart-upgrade", "sandbox-qualification"]) {
			assert.ok(
				expression.includes(`steps.filter.outputs.${check} == 'true'`),
				`${name}: ${check}`,
			);
		}
	}
	// The evidence needs both platforms, which only a merge group builds.
	assert.match(
		String(cicd.getIn(["jobs", "detect-changes", "outputs", "release-preflight"])),
		/\(github\.event_name == 'merge_group' && steps\.filter\.outputs\.release-evidence == 'true'\)/u,
	);
	assert.match(String(cicd.getIn(["jobs", "Chart-upgrade", "uses"])), /chart-upgrade\.yml$/u);
	assert.match(
		String(cicd.getIn(["jobs", "Chart-upgrade", "if"])),
		/needs\.detect-changes\.outputs\.chart-upgrade == 'true'/u,
	);
	assert.match(
		String(cicd.getIn(["jobs", "Sandbox-qualification", "if"])),
		/needs\.detect-changes\.outputs\.sandbox-qualification == 'true'/u,
	);
	const needs = cicd.getIn(["jobs", "all-ci-passed", "needs"]);
	assert.ok(isSeq(needs));
	for (const name of ["Chart-upgrade", "Sandbox-qualification", "Release-preflight"]) {
		assert.ok(needs.items.map(String).includes(name), `the gate needs ${name}`);
	}
	// CI/CD is the one place a change runs the drill, so the drill's own pull request trigger is gone.
	const drill = parseDocument(await readFile(".github/workflows/chart-upgrade.yml", "utf8"));
	assert.equal(drill.getIn(["on", "pull_request"]), undefined);
});

void test("a selected release check that did not pass fails the gate, a skipped one only where intended", async () => {
	const gate = ["jobs", "all-ci-passed"];
	const needed = cicd.getIn([...gate, "needs"]);
	assert.ok(isSeq(needed));
	const green = Object.fromEntries(needed.items.map((item) => [String(item), "success"]));
	const evaluate = runScript(cicd, gate, "Evaluate CI results");
	const verdict = async (
		results: Record<string, string>,
		selected: Record<string, string>,
		mergeGroup = false,
	) => {
		const all = { ...green, ...results };
		const run = await runStep(
			render(evaluate, all, {
				"release-candidate": "false",
				"seeded-upgrade": "false",
				"queue-server-image": "false",
				"rename-only": "false",
				"release-preflight": "false",
				"chart-upgrade": "false",
				"sandbox-qualification": "false",
				publishable: "true",
				should_skip: "false",
				...selected,
			}),
			{ NEEDS: JSON.stringify(jobsOf(all)), MERGE_GROUP: String(mergeGroup) },
		);
		return run.failed;
	};
	for (const result of ["skipped", "failure", "cancelled"]) {
		assert.ok(
			await verdict({ "Database-upgrade": result }, { "seeded-upgrade": "true" }),
			`main seeded upgrade ${result}`,
		);
		assert.ok(
			await verdict({ "Chart-upgrade": result }, { "chart-upgrade": "true" }),
			`chart ${result}`,
		);
		assert.ok(
			await verdict({ "Sandbox-qualification": result }, { "sandbox-qualification": "true" }),
			`qualification ${result}`,
		);
		assert.ok(
			await verdict({ "Release-preflight": result }, { "release-preflight": "true" }, true),
			`merge-group preflight ${result}`,
		);
	}
	// A run that published no image, such as a draft or a fork, skips them on purpose.
	assert.equal(
		await verdict(
			{ Docker: "skipped", "Chart-upgrade": "skipped", "Sandbox-qualification": "skipped" },
			{ "chart-upgrade": "true", "sandbox-qualification": "true" },
		),
		false,
	);
	assert.equal(
		await verdict(
			{ "Chart-upgrade": "skipped" },
			{ "chart-upgrade": "true", publishable: "false" },
		),
		false,
	);
	// An unrelated change skips all three and passes.
	assert.equal(
		await verdict(
			{
				"Chart-upgrade": "skipped",
				"Sandbox-qualification": "skipped",
				"Release-preflight": "skipped",
			},
			{},
		),
		false,
	);
	// A run that generates release evidence qualifies the sandbox on the same images.
	assert.ok(
		await verdict({ "Sandbox-qualification": "skipped" }, { "release-preflight": "true" }, true),
	);
});

void test("a failed release evidence preflight names its cause", () => {
	const explain = runScript(cicd, ["jobs", "Release-preflight"], "Name the cause of the failure");
	for (const cause of [
		"the latest nightly Kubernetes run on main did not pass",
		"generating or verifying the release evidence failed",
		"the verifier of an earlier release refuses this ref's images",
	]) {
		assert.ok(explain.includes(cause), cause);
	}
	assert.match(explain, /it touches the image build, the SBOM or the evidence scripts/u);
});
