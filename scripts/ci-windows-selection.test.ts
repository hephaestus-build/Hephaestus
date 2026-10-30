import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { minimatch } from "minimatch";
import { isMap, isSeq, parseDocument } from "yaml";

import { asArray, asString } from "./lib/json.ts";

const cicd = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
const quality = parseDocument(await readFile(".github/workflows/ci-quality-gates.yml", "utf8"));
const steps = cicd.getIn(["jobs", "detect-changes", "steps"]);
assert.ok(isSeq(steps));
const filter = steps.items.find((step) => isMap(step) && step.get("id") === "filter");
assert.ok(isMap(filter));
assert.equal(filter.getIn(["with", "predicate-quantifier"]), "some-with-excludes");
const filters = parseDocument(asString(filter.getIn(["with", "filters"]), "path filters"));
const windowsFilter = filters.get("windows", true);
assert.ok(isSeq(windowsFilter));
const patterns = asArray(windowsFilter.toJSON(), "Windows paths").map((pattern) =>
	asString(pattern, "Windows path"),
);

function evaluate(expression: unknown, context: unknown): string {
	const source = asString(expression, "selection expression").replaceAll(
		/^\$\{\{\s*|\s*\}\}$/gu,
		"",
	);
	const parsed = new Parser(
		new Lexer(source).lex().tokens,
		["github", "needs", "inputs", "steps"],
		[],
	).parse();
	const dictionary: unknown = JSON.parse(JSON.stringify(context), data.reviver);
	assert.ok(dictionary instanceof data.Dictionary);
	return new Evaluator(parsed, dictionary).evaluate().coerceString();
}

function windows(paths: string[], event = "pull_request", queueTier = "false", selection?: string) {
	// paths-filter uses picomatch; these literal paths and ** globs share minimatch's dotfile semantics.
	const matches =
		selection ??
		String(
			paths.some(
				(path) =>
					patterns
						.filter((pattern) => !pattern.startsWith("!"))
						.some((pattern) => minimatch(path, pattern, { dot: true })) &&
					!patterns
						.filter((pattern) => pattern.startsWith("!"))
						.some((pattern) => minimatch(path, pattern.slice(1), { dot: true })),
			),
		);
	const output = evaluate(cicd.getIn(["jobs", "detect-changes", "outputs", "windows"]), {
		steps: { filter: { outputs: { windows: matches } } },
	});
	const changed = evaluate(cicd.getIn(["jobs", "Quality", "with", "windows_changed"]), {
		needs: { "detect-changes": { outputs: { windows: output, "queue-tier": queueTier } } },
	});
	return evaluate(quality.getIn(["jobs", "windows", "if"]), {
		github: { event_name: event },
		inputs: { should_skip: "false", tooling_changed: "true", windows_changed: changed },
	});
}

void test("server, documentation, release notes and instructions keep Windows verification in the queue", () => {
	const paths = [
		"server/application/src/main/java/Example.java",
		"server/application/src/test/resources/fixture.json",
		"docs/contributor/ci-cd.mdx",
		".changeset/example.md",
		"AGENTS.md",
		"webapp/AGENTS.md",
		"server/CLAUDE.md",
	];
	assert.equal(windows(paths), "false");
	for (const event of ["merge_group", "push", "workflow_dispatch"]) {
		assert.equal(windows(paths, event), "true", event);
	}
	assert.equal(windows(paths, "pull_request", "true"), "true");
	assert.equal(windows(paths, "pull_request", ""), "true");
	assert.equal(windows(paths, "pull_request", "false", ""), "true");
	assert.equal(
		quality.getIn(["on", "workflow_call", "inputs", "windows_changed", "default"]),
		"true",
	);
});

void test("Windows inputs and unknown paths also select the PR leg in a mixed change", () => {
	for (const path of [
		"scripts/check-toolchain.ts",
		"docker/agents/pi/tools.ts",
		"webapp/src/component.tsx",
		"extension/src/component.tsx",
		"deploy/chart/hephaestus/values.yaml",
		".github/workflows/ci-quality-gates.yml",
		".github/actions/setup-toolchain/action.yml",
		".vite-hooks/pre-push",
		".vale/words.json",
		"package.json",
		"pnpm-lock.yaml",
		"pnpm-workspace.yaml",
		"vite.config.ts",
		"new-tool.config.ts",
	]) {
		assert.equal(windows(["docs/contributor/ci-cd.mdx", path]), "true", path);
	}
});
