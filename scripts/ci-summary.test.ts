import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

import { isMap, isSeq, parseDocument } from "yaml";

const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const steps = workflow.getIn(["jobs", "all-ci-passed", "steps"]);
assert.ok(isSeq(steps));
const summary = steps.items.find(
	(item) => isMap(item) && item.get("name") === "Generate CI Summary",
);
assert.ok(isMap(summary));
const script = summary.getIn(["with", "script"]);
assert.equal(typeof script, "string");

async function report(needs: unknown, verdict = "success") {
	const rendered: unknown[] = [];
	const output = {
		addHeading: (text: string) => {
			rendered.push(text);
			return output;
		},
		addRaw: (text: string) => {
			rendered.push(text);
			return output;
		},
		addTable: (rows: unknown) => {
			rendered.push(rows);
			return output;
		},
		addLink: (label: string, url: string) => {
			rendered.push([label, url]);
			return output;
		},
		write: () => {
			rendered.push("written");
			return Promise.resolve();
		},
	};
	const completed: unknown = runInNewContext(`(async () => { ${String(script)} })()`, {
		process: { env: { NEEDS: JSON.stringify(needs), VERDICT: verdict } },
		core: { summary: output },
		context: {
			serverUrl: "https://github.com",
			repo: { owner: "hephaestus-build", repo: "Hephaestus" },
		},
	});
	await completed;
	return JSON.stringify(rendered);
}

void test("the status-writing summary remains checkout-free and uses native workflow data", () => {
	assert.match(String(summary.get("uses")), /^actions\/github-script@/);
	assert.equal(summary.getIn(["env", "NEEDS"]), `\${{ toJSON(needs) }}`);
	assert.equal(summary.getIn(["env", "VERDICT"]), `\${{ steps.evaluate.outputs.status }}`);
	assert.equal(summary.get("if"), "always()");
	for (const item of steps.items)
		if (isMap(item))
			assert.doesNotMatch(String(item.get("uses")), /checkout|setup-toolchain|^\.\//);
	assert.equal(workflow.getIn(["jobs", "all-ci-passed", "permissions", "contents"]), undefined);
});

void test("the summary includes every job and counts actual skips without inventing their reason", async () => {
	const jobs = workflow.getIn(["jobs", "all-ci-passed", "needs"]);
	assert.ok(isSeq(jobs));
	const needs = Object.fromEntries(jobs.items.map((name) => [String(name), { result: "skipped" }]));
	needs["future-job"] = { result: "success" };
	const text = await report(needs);
	for (const name of Object.keys(needs)) assert.ok(text.includes(name));
	assert.ok(text.includes(`${jobs.items.length} of ${jobs.items.length + 1} jobs skipped`));
	assert.ok(text.includes("Job conditions determine why"));
	assert.ok(!text.includes("All workflows ran"));
});

void test("selection flags report PostgreSQL and false values without labelling selections as file changes", async () => {
	const text = await report({
		"detect-changes": {
			result: "success",
			outputs: {
				"postgres-image": "true",
				webapp: "false",
				unfiltered: "true",
				"alias-base": "private-commit-value",
			},
		},
	});
	for (const expected of ["postgres-image", "webapp", "false", "unfiltered", "Selection flags"])
		assert.ok(text.includes(expected));
	assert.ok(!text.includes("private-commit-value"));
	assert.ok(!text.includes("Components Changed"));
});

void test("a missing verdict and a failure never render as success", async () => {
	assert.ok((await report({ Build: { result: "failure" } }, "failure")).includes("CI failed"));
	assert.ok(
		(await report({ Build: { result: "cancelled" } }, "")).includes("did not produce a verdict"),
	);
	assert.ok(
		(await report({ Build: { result: "success" } })).includes("All required checks passed"),
	);
});

void test("malformed job data fails instead of rendering a misleading report", async () => {
	for (const needs of [null, [], { Build: null }, { Build: { result: "unknown" } }])
		await assert.rejects(report(needs));
});

void test("a cancellation verdict is distinct from a missing verdict", async () => {
	const text = await report({ Build: { result: "cancelled" } }, "cancelled");
	assert.ok(text.includes("CI was cancelled before it finished"));
	assert.ok(!text.includes("did not produce a verdict"));
});

void test("missing selection data produces no empty flags table", async () => {
	const text = await report(
		{ "detect-changes": { result: "cancelled", outputs: {} } },
		"cancelled",
	);
	assert.ok(!text.includes("Selection flags"));
});
