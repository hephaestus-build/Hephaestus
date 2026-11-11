import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
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

const identityCommit = (login: string | null) => ({
	commit: {
		abbreviatedOid: "abcdef0",
		message: "fix(ci): check policy",
		authors: {
			nodes: [
				{
					email: "maintainer@example.com",
					name: "Maintainer",
					user: login === null ? null : { login },
				},
			],
		},
	},
});

void test("title preflight loads without dependencies and validates the current title on reruns", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request-policy.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	assert.ok(steps.every((step) => !/setup-toolchain|setup-node/u.test(String(step.get("uses")))));
	const checkout = steps.find((step) => String(step.get("uses")).startsWith("actions/checkout@"));
	assert.ok(checkout);
	assert.equal(checkout.getIn(["with", "persist-credentials"]), false);
	assert.equal(checkout.getIn(["with", "ref"]), `\${{ github.event.repository.default_branch }}`);

	// The workspace the script runs in is the sparse checkout, and nothing else: a file the workflow
	// stops fetching has to break this test rather than the pull request that reruns without it.
	const workspace = await mkdtemp(path.join(tmpdir(), "title-policy-"));
	t.after(async () => rm(workspace, { recursive: true, force: true }));
	for (const entry of String(checkout.getIn(["with", "sparse-checkout"]))
		.trim()
		.split("\n")) {
		const file = entry.trim();
		await mkdir(path.join(workspace, path.dirname(file)), { recursive: true });
		await copyFile(file, path.join(workspace, file));
	}

	const policy = steps.find((step) => step.get("id") === "title");
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
			const assert = (await import("node:assert/strict")).default;
			const context = { repo: { owner: "owner", repo: "repo" }, payload: { pull_request: { number: 99, title: ${JSON.stringify(valid ? "fix!: stale invalid title" : "fix: stale valid title")} } } };
			const github = { rest: { pulls: { get: async ({pull_number}) => { assert.equal(pull_number, 7); return { data: { title: ${JSON.stringify(title)} } }; } } } };
			const outputs = {};
			const core = { setOutput(name, value) { outputs[name] = value; }, setFailed() { process.exitCode = 1; } };
			${String(script)}
		`,
			],
			{ encoding: "utf8", env: { ...process.env, GITHUB_WORKSPACE: workspace, PR_NUMBER: "7" } },
		);
		assert.equal(result.status, valid ? 0 : 1, `${title}: ${result.stderr}`);
		assert.equal(result.stderr, "", title);
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
	assert.match(String(explanation.get("run")), /> "\$RUNNER_TEMP\/title-error\.md"/u);
	assert.equal(comment.getIn(["with", "path"]), `\${{ runner.temp }}/title-error.md`);
});

void test("the trusted policy checks every rule even after another rule fails", async () => {
	const workflow = parseDocument(
		await readFile(".github/workflows/pull-request-policy.yml", "utf8"),
	);
	assert.equal(workflow.getIn(["jobs", "validate-pr", "runs-on"]), "ubuntu-slim");
	assert.equal(workflow.getIn(["jobs", "verify-commit-identity"]), undefined);
	const steps = await stepsIn(".github/workflows/pull-request-policy.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	for (const id of ["identity", "attribution"]) {
		assert.equal(steps.find((step) => step.get("id") === id)?.get("if"), `\${{ !cancelled() }}`);
	}
});

void test("commit identity accepts only the current author or a resolved listed maintainer", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request-policy.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	const identity = steps.find((step) => step.get("id") === "identity");
	assert.ok(identity);
	assert.equal(identity.getIn(["env", "MAINTAINERS"]), `\${{ vars.REVIEW_POLICY_MAINTAINERS }}`);
	const script = identity.getIn(["with", "script"]);
	assert.equal(typeof script, "string");
	const checkout = steps.find((step) => String(step.get("uses")).startsWith("actions/checkout@"));
	assert.ok(checkout);
	assert.equal(checkout.getIn(["with", "ref"]), `\${{ github.event.repository.default_branch }}`);
	assert.equal(checkout.getIn(["with", "persist-credentials"]), false);
	const workspace = await mkdtemp(path.join(tmpdir(), "commit-identity-"));
	t.after(async () => rm(workspace, { recursive: true, force: true }));
	for (const entry of String(checkout.getIn(["with", "sparse-checkout"]))
		.trim()
		.split("\n")) {
		const file = entry.trim();
		await mkdir(path.join(workspace, path.dirname(file)), { recursive: true });
		await copyFile(file, path.join(workspace, file));
	}
	const coauthored = identityCommit("unlisted");
	coauthored.commit.authors.nodes.push({
		email: "listed@example.com",
		name: "Listed",
		user: { login: "maintainer" },
	});
	const cases = [
		{
			name: "current PR author",
			author: "contributor",
			type: "User",
			maintainers: "",
			pages: [[identityCommit("contributor")]],
			valid: true,
		},
		{
			name: "listed maintainer and case normalization",
			author: "contributor",
			type: "User",
			maintainers: " MaIntAiner, ",
			pages: [[identityCommit("MAINTAINER")]],
			valid: true,
		},
		{
			name: "mixed contributor and maintainer commits",
			author: "contributor",
			type: "User",
			maintainers: "maintainer",
			pages: [[identityCommit("contributor"), identityCommit("maintainer")]],
			valid: true,
		},
		{
			name: "unresolved name and email",
			author: "contributor",
			type: "User",
			maintainers: "maintainer",
			pages: [[identityCommit(null)]],
			valid: false,
		},
		{
			name: "unlisted collaborator",
			author: "contributor",
			type: "User",
			maintainers: "maintainer",
			pages: [[identityCommit("other")]],
			valid: false,
		},
		{
			name: "co-author cannot replace primary author",
			author: "contributor",
			type: "User",
			maintainers: "maintainer",
			pages: [[coauthored]],
			valid: false,
		},
		{
			name: "empty maintainer list",
			author: "contributor",
			type: "User",
			maintainers: "",
			pages: [[identityCommit("maintainer")]],
			valid: false,
		},
		{
			name: "bot's resolved account",
			author: "renovate",
			type: "Bot",
			maintainers: "",
			pages: [[identityCommit("Renovate[bot]")]],
			valid: true,
		},
		{
			name: "bot login without resolved suffix",
			author: "renovate",
			type: "Bot",
			maintainers: "",
			pages: [[identityCommit("renovate")]],
			valid: false,
		},
		{
			name: "offender after a full page",
			author: "contributor",
			type: "User",
			maintainers: "maintainer",
			pages: [
				Array.from({ length: 100 }, () => identityCommit("maintainer")),
				[identityCommit("other")],
			],
			valid: false,
		},
	];
	for (const entry of cases) {
		const result = spawnSync(
			process.execPath,
			[
				"-e",
				`
			const assert = require("node:assert/strict");
			const pages = ${JSON.stringify(entry.pages)};
			let calls = 0;
			const context = { repo: { owner: "owner", repo: "repo" }, payload: { pull_request: { number: 99, user: { login: "stale-payload" }, base: { ref: "main" } } } };
			const github = { graphql: async (query, variables) => {
				assert.ok(query.includes("authors(first:1)"));
				assert.equal(variables.number, 7);
				assert.equal(variables.after, calls === 0 ? null : "page-" + calls);
				const nodes = pages[calls++];
				return { repository: { pullRequest: { author: { __typename: ${JSON.stringify(entry.type)}, login: ${JSON.stringify(entry.author)} }, commits: { nodes, pageInfo: { hasNextPage: calls < pages.length, endCursor: "page-" + calls } } } } };
			} };
			const outputs = {};
			const core = { setOutput(name, value) { outputs[name] = value; }, setFailed() { process.exitCode = 1; } };
			(async () => { ${String(script)} })().then(() => { assert.equal(calls, pages.length); assert.equal(JSON.parse(require("node:fs").readFileSync(process.env.RUNNER_TEMP + "/commit-messages.json", "utf8")).length, pages.flat().length); assert.deepEqual(outputs, ${JSON.stringify(entry.valid ? {} : { violations: "true" })}); }).catch(error => { console.error(error); process.exitCode = 2; });
		`,
			],
			{
				encoding: "utf8",
				env: {
					...process.env,
					GITHUB_WORKSPACE: workspace,
					RUNNER_TEMP: workspace,
					MAINTAINERS: entry.maintainers,
					PR_NUMBER: "7",
				},
			},
		);
		assert.equal(result.status, entry.valid ? 0 : 1, `${entry.name}: ${result.stderr}`);
		assert.equal(result.stderr, "", entry.name);
	}
});

void test("attribution checks current metadata and the complete commit list beyond the REST limit", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request-policy.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	const script = steps.find((step) => step.get("id") === "attribution")?.getIn(["with", "script"]);
	assert.equal(typeof script, "string");
	const directory = await mkdtemp(path.join(tmpdir(), "pr-attribution-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const clean = "fix(ci): check pull requests";
	const toolTrailer = "Claude-Session: https://claude.ai/code/session_abc";
	const modelCoauthor = "Co-Authored-By: Codex <tool@example.com>";
	for (const [title, body, messages, valid] of [
		[clean, "A clear description", [clean], true],
		[clean, null, [clean], true],
		[clean, "Both API artifacts were regenerated with zero diff.", [clean], true],
		[clean, "Generated with a tool", [clean], false],
		["Generated with a tool", null, [clean], false],
		[clean, null, [`${clean}\n\n${toolTrailer}`], false],
		[clean, null, [`${clean}\n\nCo-authored-by: Contributor <human@example.com>`], true],
		[clean, null, [`${clean}\n\n${modelCoauthor}`], true],
		[clean, modelCoauthor, [clean], true],
		[modelCoauthor, null, [clean], true],
		[clean, null, [...Array.from({ length: 300 }, () => clean), toolTrailer], false],
	] as const) {
		await writeFile(
			path.join(directory, "commit-messages.json"),
			JSON.stringify(messages.map((message) => ({ sha: "a".repeat(40), message }))),
		);
		const result = spawnSync(
			process.execPath,
			[
				"-e",
				`
			const assert = require("node:assert/strict");
			const context = { repo: { owner: "owner", repo: "repo" }, payload: { pull_request: { number: 99, title: "Generated with stale text", body: "Generated with stale text" } } };
			const github = { rest: { pulls: {
				get: async ({pull_number}) => { assert.equal(pull_number, 7); return { data: { title: ${JSON.stringify(title)}, body: ${JSON.stringify(body)} } }; },
			} } };
			const outputs = {};
			const core = { setOutput(name, value) { outputs[name] = value; }, setFailed() { process.exitCode = 1; } };
			(async () => { ${String(script)} })().then(() => assert.deepEqual(outputs, ${JSON.stringify(valid ? {} : { violations: "true" })})).catch(error => { console.error(error); process.exitCode = 2; });
		`,
			],
			{
				encoding: "utf8",
				env: {
					...process.env,
					GITHUB_WORKSPACE: process.cwd(),
					RUNNER_TEMP: directory,
					PR_NUMBER: "7",
				},
			},
		);
		assert.equal(result.status, valid ? 0 : 1, result.stderr);
		assert.equal(result.stderr, "");
	}
});

void test("policy comments require a recorded violation, even when the API or another policy fails", async () => {
	const steps = await stepsIn(".github/workflows/pull-request-policy.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	for (const [id, header] of [
		["identity", "pr-commit-identity-error"],
		["attribution", "pr-attribution-error"],
	] as const) {
		const step = steps.find((entry) => entry.getIn(["with", "header"]) === header);
		assert.ok(step);
		const condition = String(step.get("if")).replaceAll(/^\$\{\{\s*|\s*\}\}$/gu, "");
		const expression = new Parser(
			new Lexer(condition).lex().tokens,
			["steps"],
			[{ name: "cancelled", minArgs: 0, maxArgs: 0 }],
		).parse();
		for (const [name, outcome, reported, cancelled, expected] of [
			["API failure without a report", "failure", false, false, false],
			["actual violation", "failure", true, false, true],
			["successful check", "success", false, false, false],
			["cancelled run", "failure", true, true, false],
		] as const) {
			const context: unknown = JSON.parse(
				JSON.stringify({
					steps: { [id]: { outcome, outputs: reported ? { violations: "true" } : {} } },
				}),
				data.reviver,
			);
			assert.ok(context instanceof data.Dictionary);
			const functions = new Map([
				[
					"cancelled",
					{
						name: "cancelled",
						minArgs: 0,
						maxArgs: 0,
						call: () => new data.BooleanData(cancelled),
					},
				],
			]);
			assert.equal(
				new Evaluator(expression, context, functions).evaluate().coerceString() === "true",
				expected,
				`${id}: ${name}`,
			);
		}
	}
});

void test("unavailable PR metadata fails without claiming a policy violation", async (t) => {
	const steps = await stepsIn(".github/workflows/pull-request-policy.yml", [
		"jobs",
		"validate-pr",
		"steps",
	]);
	const directory = await mkdtemp(path.join(tmpdir(), "pr-metadata-unavailable-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	for (const id of ["identity", "attribution"]) {
		const script = steps.find((step) => step.get("id") === id)?.getIn(["with", "script"]);
		assert.equal(typeof script, "string");
		const result = spawnSync(
			process.execPath,
			[
				"-e",
				`
			const assert = require("node:assert/strict");
			const { existsSync } = require("node:fs");
			const outputs = {};
			const failures = [];
			const core = { setOutput(name, value) { outputs[name] = value; }, setFailed(reason) { failures.push(reason); } };
			const unavailable = async () => { throw Object.assign(new Error("Service Unavailable"), {status: 503}); };
			const github = {graphql: unavailable, rest: {pulls: {get: unavailable}}};
			const context = {repo: {owner: "owner", repo: "repo"}, payload: {pull_request: {number: 7}}};
			(async () => {
				await assert.rejects(async () => { ${String(script)} }, {status: 503});
				assert.deepEqual(outputs, {});
				assert.deepEqual(failures, []);
				assert.equal(existsSync(process.env.RUNNER_TEMP + "/${id === "identity" ? "identity" : "attribution"}-comment.md"), false);
			})().catch(error => {console.error(error); process.exitCode = 1;});
		`,
			],
			{
				encoding: "utf8",
				env: {
					...process.env,
					GITHUB_WORKSPACE: process.cwd(),
					RUNNER_TEMP: directory,
					PR_NUMBER: "7",
				},
			},
		);
		assert.equal(result.status, 0, `${id}: ${result.stderr}`);
		assert.equal(result.stderr, "", id);
	}
});

void test("a new commit cancels old policy work while same-head metadata edits complete", async () => {
	const document = parseDocument(await readFile(".github/workflows/pull-request.yml", "utf8"));
	const expression = new Parser(
		new Lexer(
			String(document.getIn(["concurrency", "cancel-in-progress"])).replaceAll(
				/^\$\{\{\s*|\s*\}\}$/gu,
				"",
			),
		).lex().tokens,
		["github"],
		[],
	).parse();
	for (const action of ["synchronize", "edited", "opened", "reopened"]) {
		const context = new data.Dictionary({
			key: "github",
			value: new data.Dictionary({
				key: "event",
				value: new data.Dictionary({ key: "action", value: new data.StringData(action) }),
			}),
		});
		assert.equal(
			new Evaluator(expression, context).evaluate().coerceString(),
			String(action === "synchronize"),
		);
	}
});
