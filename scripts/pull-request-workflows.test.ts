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
		"validate-pr",
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

void test("PR metadata shares a lightweight runner without skipping policies after a failure", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/pull-request.yml", "utf8"));
	assert.equal(workflow.getIn(["jobs", "validate-pr", "runs-on"]), "ubuntu-slim");
	assert.equal(workflow.getIn(["jobs", "verify-commit-identity"]), undefined);
	const steps = await stepsIn(".github/workflows/pull-request.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	for (const id of ["identity", "attribution"]) {
		assert.equal(steps.find((step) => step.get("id") === id)?.get("if"), `\${{ !cancelled() }}`);
	}
});

void test("body validation uses the current description rather than a stale rerun payload", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	const script = steps.find((step) => step.get("id") === "attribution")?.getIn(["with", "script"]);
	assert.equal(typeof script, "string");
	const directory = await mkdtemp(join(tmpdir(), "pr-body-"));
	t.after(() => rm(directory, { recursive: true, force: true }));
	for (const [body, staleBody, valid] of [
		["A clear description", "Generated with a tool", true],
		["Generated with a tool", "A clear description", false],
		[null, "Generated with a tool", true],
		["Both API artifacts were regenerated with zero diff.", null, true],
		["The client was REGENERATED WITH the updated spec.", null, true],
	] as const) {
		const result = spawnSync(
			process.execPath,
			[
				"-e",
				`
			const context = { repo: { owner: "owner", repo: "repo" }, payload: { pull_request: { number: 7, body: ${JSON.stringify(staleBody)} } } };
			const github = { rest: { pulls: { get: async () => ({ data: { body: ${JSON.stringify(body)} } }) } } };
			const core = { setFailed() { process.exitCode = 1; } };
			(async () => { ${String(script)} })().catch(error => { console.error(error); process.exitCode = 2; });
		`,
			],
			{ encoding: "utf8", env: { ...process.env, RUNNER_TEMP: directory } },
		);
		assert.equal(result.status, valid ? 0 : 1, result.stderr);
		assert.equal(result.stderr, "");
	}
});
