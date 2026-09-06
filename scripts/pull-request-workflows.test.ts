import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument, type YAMLMap } from "yaml";

async function stepsIn(file: string, location: string[]): Promise<YAMLMap[]> {
	const document = parseDocument(await readFile(file, "utf8"));
	const steps = document.getIn(location);
	assert.ok(isSeq(steps));
	return steps.items.map((step) => {
		assert.ok(isMap(step));
		return step;
	});
}

void test("title preflight loads without dependencies and validates the current title on reruns", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request.yml", [
		"jobs",
		"validate-title",
		"steps",
	]);
	assert.ok(steps.every((step) => !/setup-toolchain|setup-node/.test(String(step.get("uses")))));
	const checkout = steps.find((step) => String(step.get("uses")).startsWith("actions/checkout@"));
	assert.ok(checkout);
	assert.equal(checkout.getIn(["with", "persist-credentials"]), false);
	assert.equal(checkout.getIn(["with", "ref"]), `\${{ github.event.repository.default_branch }}`);

	// The workspace the script runs in is the sparse checkout, and nothing else: a file the workflow
	// stops fetching has to break this test rather than the pull request that reruns without it.
	const workspace = await mkdtemp(join(tmpdir(), "title-policy-"));
	t.after(() => rm(workspace, { recursive: true, force: true }));
	for (const path of String(checkout.getIn(["with", "sparse-checkout"]))
		.trim()
		.split("\n")) {
		const file = path.trim();
		await mkdir(join(workspace, dirname(file)), { recursive: true });
		await copyFile(file, join(workspace, file));
	}

	const policy = steps.find((step) => step.get("id") === "policy");
	const script = policy?.getIn(["with", "script"]);
	assert.equal(typeof script, "string");
	const cases = [
		["fix(ci): recover from a registry blip", true],
		["fix: document !: syntax", true],
		[`fix: ${"a".repeat(95)}`, true],
		[`fix: ${"a".repeat(96)}`, false],
		["fix!: breaking change", false],
		["fix(ci)!: breaking change", false],
		["fix: first line\nsecond line", false],
		["fix: first line\rsecond line", false],
	] as const;
	for (const [title, valid] of cases) {
		const result = spawnSync(
			process.execPath,
			[
				"--input-type=module",
				"-e",
				`
			const context = { repo: { owner: "owner", repo: "repo" }, payload: { pull_request: { number: 7, title: ${JSON.stringify(valid ? "fix!: stale invalid title" : "fix: stale valid title")} } } };
			const github = { rest: { pulls: { get: async () => ({ data: { title: ${JSON.stringify(title)} } }) } } };
			const core = { setOutput() {}, setFailed() { process.exitCode = 1; } };
			${String(script)}
		`,
			],
			{ encoding: "utf8", env: { ...process.env, GITHUB_WORKSPACE: workspace } },
		);
		assert.equal(result.status, valid ? 0 : 1, `${title}: ${result.stderr}`);
		assert.equal(result.stderr, "", title);
	}

	const validator = steps.find((step) => step.get("id") === "title");
	assert.ok(validator);
	assert.match(
		String(validator.get("uses")),
		/^amannn\/action-semantic-pull-request@[a-f0-9]{40}$/,
	);
	for (const input of ["types", "scopes", "headerPattern", "subjectPattern"]) {
		assert.equal(validator.getIn(["with", input]), `\${{ steps.policy.outputs.${input} }}`);
	}
	// The comment carries the validator's own message; a link to the run is not the reason.
	const explanation = steps.find((step) => step.get("id") === "explain");
	assert.ok(explanation);
	assert.equal(
		explanation.getIn(["env", "TITLE_ERROR"]),
		`\${{ steps.title.outputs.error_message }}`,
	);
	const comment = steps.find((step) => step.getIn(["with", "path"]) !== undefined);
	assert.ok(comment);
	assert.equal(comment.getIn(["with", "header"]), "pr-title-lint-error");
	assert.match(String(explanation.get("run")), /> "\$RUNNER_TEMP\/title-error\.md"/);
	assert.equal(comment.getIn(["with", "path"]), `\${{ runner.temp }}/title-error.md`);
});

void test("the attribution gate reads the pull request body, not the commits", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request.yml", [
		"jobs",
		"verify-commit-identity",
		"steps",
	]);
	const attribution = steps.find((step) => step.get("id") === "attribution");
	assert.ok(attribution);
	const script = String(attribution.getIn(["with", "script"]));
	// `Co-authored-by:` is git's own way to record who or what worked on a commit, so only the
	// prose a reader weighs the change by is refused; a commit walk here would widen that back.
	assert.doesNotMatch(script, /\bcommits\b/);

	const temp = await mkdtemp(join(tmpdir(), "attribution-"));
	t.after(() => rm(temp, { recursive: true, force: true }));
	const cases = [
		// A pull request opened with no description at all: `body` is null, not an empty string.
		[null, true],
		["Removes the flaky wait.\n\nCo-authored-by: Jane Doe <jane@example.com>", true],
		["🤖 Generated with [Claude Code](https://claude.com/claude-code)", false],
		["Claude-Session: https://claude.ai/code/session_01", false],
	] as const;
	for (const [body, clean] of cases) {
		const result = spawnSync(
			process.execPath,
			[
				"--input-type=module",
				"-e",
				`
			const { createRequire } = await import("node:module");
			const require = createRequire(${JSON.stringify(join(temp, "harness.js"))});
			const context = { payload: { pull_request: { body: ${JSON.stringify(body)} } } };
			const core = { setFailed() { process.exitCode = 1; } };
			await (async () => {
			${script}
			})();
		`,
			],
			{ encoding: "utf8", env: { ...process.env, RUNNER_TEMP: temp } },
		);
		assert.equal(result.status, clean ? 0 : 1, `${String(body)}: ${result.stderr}`);
		assert.equal(result.stderr, "", String(body));
	}

	// The one remedy the gate has: a commit is never the thing to rewrite.
	const comment = await readFile(join(temp, "attribution-comment.md"), "utf8");
	assert.match(comment, /Edit the description/);
	assert.doesNotMatch(comment, /rebase|amend/);
});
