import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

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
