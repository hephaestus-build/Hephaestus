import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { isSeq, parseDocument } from "yaml";

import { asRecord, readJsonFile } from "./lib/json.ts";

const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const repository = "hephaestus-build/Hephaestus";

function context(event: string, branch = "changeset-release/main", owner = repository) {
	return {
		github: {
			workflow: "CI/CD",
			repository,
			event_name: event,
			head_ref: event === "pull_request" ? branch : "",
			ref_name: event === "pull_request" ? "42/merge" : branch,
			ref: event === "pull_request" ? "refs/pull/42/merge" : `refs/heads/${branch}`,
			event: {
				repository: { default_branch: "main" },
				...(event === "pull_request" && {
					pull_request: { head: { repo: { full_name: owner } } },
				}),
			},
		},
	};
}

function evaluate(template: unknown, value: ReturnType<typeof context>, cancelled = false) {
	assert.equal(typeof template, "string");
	assert.ok(typeof template === "string");
	const dictionary: unknown = JSON.parse(JSON.stringify(value), data.reviver);
	assert.ok(dictionary instanceof data.Dictionary);
	return template.replaceAll(/\$\{\{([\s\S]*?)}}/g, (_, expression: string) => {
		const functions = new Map([
			[
				"cancelled",
				{ name: "cancelled", minArgs: 0, maxArgs: 0, call: () => new data.BooleanData(cancelled) },
			],
		]);
		const parsed = new Parser(
			new Lexer(expression).lex().tokens,
			["github"],
			[...functions.values()],
		).parse();
		return new Evaluator(parsed, dictionary, functions).evaluate().coerceString();
	});
}

void test("only same-repository Version PR triggers share a cancelable CI lane", () => {
	const group = workflow.getIn(["concurrency", "group"]);
	const lane = (event: string, branch?: string, owner?: string) =>
		evaluate(group, context(event, branch, owner)).toLowerCase();
	const versionPr = lane("pull_request");
	assert.equal(versionPr, lane("workflow_dispatch"));
	assert.notEqual(versionPr, lane("pull_request", undefined, "fork/Hephaestus"));
	assert.notEqual(lane("pull_request", "feature"), lane("workflow_dispatch", "feature"));
	assert.notEqual(versionPr, lane("workflow_dispatch", "main"));
	assert.notEqual(lane("push", "main"), lane("workflow_dispatch", "main"));
	assert.notEqual(lane("merge_group", "gh-readonly-queue/main/pr-42"), versionPr);
	assert.notEqual(
		lane("merge_group", "gh-readonly-queue/main/pr-42"),
		lane("merge_group", "gh-readonly-queue/main/pr-43"),
	);
	assert.notEqual(versionPr, lane("pull_request", "changeset-release/main-extra"));
	const cancel = workflow.getIn(["concurrency", "cancel-in-progress"]);
	for (const event of ["pull_request", "workflow_dispatch", "push", "merge_group"]) {
		assert.equal(
			evaluate(cancel, context(event)),
			String(["pull_request", "workflow_dispatch"].includes(event)),
		);
	}
});

void test("the release branch and main push trigger use the same base", async () => {
	const config = asRecord(await readJsonFile(".changeset/config.json"), "changesets config");
	const branches = workflow.getIn(["on", "push", "branches"]);
	assert.ok(isSeq(branches));
	assert.deepEqual(branches.toJSON(), [config.baseBranch]);
	assert.equal(config.baseBranch, "main");
});

void test("a cancelled CI run cannot overwrite the surviving run's commit status", () => {
	const sequence = workflow.getIn(["jobs", "all-ci-passed", "steps"]);
	assert.ok(isSeq(sequence));
	const steps: unknown = sequence.toJSON();
	assert.ok(Array.isArray(steps));
	const status = steps
		.map((step: unknown) => asRecord(step, "step"))
		.find((step) => step.name === "Create commit status");
	assert.ok(status);
	assert.equal(evaluate(status.if, context("pull_request"), false), "true");
	assert.equal(evaluate(status.if, context("pull_request"), true), "false");
});
