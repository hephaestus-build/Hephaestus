import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, mock, test } from "node:test";

import type { TemplateToken } from "@actions/workflow-parser/templates/tokens/template-token";

import { checkRepository, runScript } from "./check-workflows.ts";
import { openActionsLanguage } from "./lib/actions-language.ts";

const mask = (expression: string) => "_".repeat(expression.length);
const language = await openActionsLanguage();
const roots: string[] = [];
after(async () => {
	await language.close();
	await Promise.all(roots.map(async (root) => rm(root, { recursive: true, force: true })));
});

const ACTION = `name: Fixture
description: Fixture
inputs:
  required-input:
    description: Required without a default
    required: true
  defaulted:
    description: Required with a default
    required: true
    default: x
  optional:
    description: Optional
outputs:
  result:
    description: Declared output
runs:
  using: node24
  main: index.js
`;

const REUSABLE = `on:
  workflow_call:
    inputs:
      known:
        type: string
        required: true
permissions: {}
jobs:
  run:
    runs-on: ubuntu-latest
    steps:
      - uses: ./.github/actions/fixture
        with:
          required-input: \${{ inputs.known }}
`;

async function check(workflow: string, extra: Record<string, string> = {}) {
	const root = await mkdtemp(path.join(tmpdir(), "check-workflows-"));
	roots.push(root);
	const files = {
		".github/actions/fixture/action.yml": ACTION,
		".github/workflows/test.yml": workflow,
		...extra,
	};
	for (const [name, content] of Object.entries(files)) {
		await mkdir(path.dirname(path.join(root, name)), { recursive: true });
		await writeFile(path.join(root, name), content);
	}
	return { root, ...(await checkRepository(language, root)) };
}

const job = (steps: string, on = "push") =>
	`on: ${on}\npermissions: {}\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n${steps}`;
const fixture = (extra = "") =>
	`      - uses: ./.github/actions/fixture\n        with:\n          required-input: y\n${extra}`;
const guarded = (condition: string) =>
	fixture().replace("      - uses:", `      - if: ${condition}\n        uses:`);

void test("accepts background steps joined by wait-all and a local action's declared output", async () => {
	const result = await check(
		job(
			`${
				fixture("        background: true\n") +
				fixture("        id: fx\n        background: true\n")
			}      - wait-all: true\n${fixture().replace(
				"required-input: y",
				`required-input: \${{ steps.fx.outputs.result }}`,
			)}`,
		),
		{ ".github/workflows/legacy.yml": job(fixture()) },
	);
	assert.deepEqual(result.problems, []);
	assert.deepEqual(result.compatible, [".github/workflows/legacy.yml"]);
});

void test("fails on GitHub's own diagnostics, warnings included", async () => {
	const cases: Record<string, [string, Record<string, string>?]> = {
		"unknown key": [job(fixture("        bogus: true\n"))],
		"unknown function": [job(guarded(`\${{ nosuch() }}`))],
		"wrong arity": [job(guarded(`\${{ contains('a') }}`))],
		"unknown step": [job(fixture(`        env:\n          X: \${{ steps.missing.outputs.x }}\n`))],
		"undeclared local output": [
			job(
				fixture("        id: fx\n") +
					fixture(`        env:\n          X: \${{ steps.fx.outputs.nope }}\n`),
			),
		],
		"unknown needs": [
			`${job(fixture())}  other:\n    needs: [nope]\n    runs-on: ubuntu-latest\n    steps:\n${fixture()}`,
		],
		"cycle beside an independent job": [
			`on: push\npermissions: {}\njobs:\n${[
				["a", "[b]"],
				["b", "[a]"],
				["c", "[]"],
			]
				.map(
					([id, needs]) =>
						`  ${String(id)}:\n    needs: ${String(needs)}\n    runs-on: ubuntu-latest\n    steps:\n${fixture()}`,
				)
				.join("")}`,
		],
		"unknown merge_group field": [
			job(
				fixture(`        env:\n          X: \${{ github.event.merge_group.nope }}\n`),
				"merge_group",
			),
		],
		"unknown reusable workflow input": [
			"on: push\npermissions: {}\njobs:\n  call:\n    uses: ./.github/workflows/reusable.yml\n    with:\n      known: a\n      unknown: b\n",
			{ ".github/workflows/reusable.yml": REUSABLE },
		],
		"missing reusable workflow": [
			"on: push\npermissions: {}\njobs:\n  call:\n    uses: ./.github/workflows/absent.yml\n",
		],
	};
	for (const [name, [workflow, extra]] of Object.entries(cases)) {
		const { problems } = await check(workflow, extra);
		assert.ok(
			problems.some((problem) => problem.startsWith(".github/workflows/test.yml")),
			name,
		);
	}
	const known = await check(
		job(
			fixture(`        env:\n          X: \${{ github.event.merge_group.base_sha }}\n`),
			"merge_group",
		),
	);
	assert.deepEqual(known.problems, []);
});

void test("a native workflow reads a pinned public action's declared metadata once", async (t) => {
	const commit = "a".repeat(40);
	const pinned = `octo/fixture@${commit}`;
	const requested: string[] = [];
	let status = 200;
	mock.method(
		globalThis,
		"fetch",
		async (input: Parameters<typeof fetch>[0], init?: RequestInit) => {
			requested.push(input instanceof Request ? input.url : input.toString());
			assert.equal(init?.headers, undefined, "no credentials are sent");
			return requested.at(-1)?.endsWith("/action.yml") === true
				? new Response(ACTION, { status })
				: new Response("", { status: 404 });
		},
	);
	t.after(() => mock.restoreAll());
	const remote = (extra = "", input = "required-input: y") =>
		`      - id: r\n        uses: ${pinned}\n        background: true\n        with:\n          ${input}\n${extra}`;
	const native = (steps: string, afterJoin = "") =>
		job(`${steps}      - wait-all: true\n${afterJoin}`);

	const known = await check(
		native(remote(), fixture(`        env:\n          X: \${{ steps.r.outputs.result }}\n`)),
	);
	assert.deepEqual(known.problems, []);
	assert.deepEqual(requested, [
		`https://raw.githubusercontent.com/octo/fixture/${commit}/action.yml`,
	]);

	for (const [name, steps] of Object.entries({
		"unknown input": remote("", "required-input: y\n          bogus: z"),
		"missing required input": remote("", "optional: z"),
	})) {
		const { problems } = await check(native(steps));
		assert.ok(
			problems.some((problem) => problem.startsWith(".github/workflows/test.yml")),
			name,
		);
	}

	const badOutput = await check(
		native(remote(), fixture(`        env:\n          X: \${{ steps.r.outputs.nope }}\n`)),
	);
	assert.ok(
		badOutput.problems.some((problem) => problem.startsWith(".github/workflows/test.yml")),
		"undeclared output",
	);

	requested.length = 0;
	await check(job(`      - uses: ${pinned}\n        with:\n          required-input: y\n`));
	assert.deepEqual(requested, [], "a workflow Actionlint reads stays offline");

	status = 503;
	await assert.rejects(check(native(remote())), "unavailable metadata fails rather than passing");
});

void test("holds a local action's caller to its declared inputs, inside this repository", async () => {
	const outside = await mkdtemp(path.join(tmpdir(), "check-workflows-outside-"));
	roots.push(outside);
	await writeFile(path.join(outside, "action.yml"), ACTION);
	const cases = {
		"missing action": "      - uses: ./.github/actions/absent\n",
		"unknown input": fixture("          extra: z\n"),
		"required input without a default": "      - uses: ./.github/actions/fixture\n",
		"lexical escape": "      - uses: ./../outside\n",
		"link escape": "      - uses: ./.github/actions/link\n",
	};
	for (const [name, steps] of Object.entries(cases)) {
		const root = await mkdtemp(path.join(tmpdir(), "check-workflows-"));
		roots.push(root);
		await mkdir(path.join(root, ".github/actions/fixture"), { recursive: true });
		await mkdir(path.join(root, ".github/workflows"), { recursive: true });
		await writeFile(path.join(root, ".github/actions/fixture/action.yml"), ACTION);
		await writeFile(path.join(root, ".github/workflows/test.yml"), job(steps));
		if (name === "link escape") {
			// Discovery finds the linked action too, and refuses to read it rather than checking it.
			await symlink(outside, path.join(root, ".github/actions/link"));
			await assert.rejects(checkRepository(language, root), /outside the repository/u);
			continue;
		}
		const { problems } = await checkRepository(language, root);
		assert.ok(
			problems.some((problem) => problem.startsWith(".github/workflows/test.yml: build step 1:")),
			name,
		);
	}
});

void test("hands each script to its checker as the shell receives it, expressions masked", () => {
	const quoted = `\${{ format('}}{0}', github.sha) }}`;
	const sha = `\${{ github.sha }}`;
	// Each run scalar as written, and the script GitHub's YAML reading gives the shell.
	const cases: [string, string][] = [
		[
			`|\n          echo "${quoted}"\n          echo ${sha} done\n`,
			`echo "${mask(quoted)}"\necho ${mask(sha)} done\n`,
		],
		[`|2-\n          cat <<EOF\n          ${sha}\n          EOF\n`, `cat <<EOF\n${mask(sha)}\nEOF`],
		[`>-\n          echo ${sha}\n          done\n`, `echo ${mask(sha)} done`],
		[`"echo ${sha}\n          done"\n`, `echo ${mask(sha)} done`],
		[`'echo a: ${sha}'\n`, `echo a: ${mask(sha)}`],
		[`"${sha}"\n`, mask(sha)],
	];
	const parsed = language.parser.parseWorkflow(
		{ name: "test.yml", content: job(cases.map(([run]) => `      - run: ${run}`).join("")) },
		new language.parser.NoOperationTraceWriter(),
	);
	const { isMapping, isSequence } = language.parser;
	const jobs =
		parsed.value !== undefined && isMapping(parsed.value) ? parsed.value.find("jobs") : undefined;
	const build = jobs !== undefined && isMapping(jobs) ? jobs.find("build") : undefined;
	const steps = build !== undefined && isMapping(build) ? build.find("steps") : undefined;
	assert.ok(steps !== undefined && isSequence(steps));
	for (const [index, [written, expected]] of cases.entries()) {
		const step: TemplateToken = steps.get(index);
		const run: TemplateToken | undefined = isMapping(step) ? step.find("run") : undefined;
		assert.ok(run !== undefined, written);
		assert.equal(runScript(language, run), expected, written);
	}
});

void test(
	"checks a native workflow's scripts with ShellCheck",
	{ skip: spawnSync("shellcheck", ["--version"]).error !== undefined && "ShellCheck is absent" },
	async () => {
		const workflow = (run: string) =>
			job(`      - run: ${run}\n        background: true\n      - wait-all: true\n`);
		const broken = await check(workflow(`'echo "unterminated'`));
		assert.ok(broken.problems.some((problem) => /SC\d+/u.test(problem)));
		const masked = await check(workflow(`echo "\${{ format('}}{0}', github.sha) }}"`));
		assert.deepEqual(masked.problems, []);
	},
);
