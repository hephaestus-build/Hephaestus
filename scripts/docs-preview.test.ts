import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";
import { pathToFileURL } from "node:url";

import { data, Evaluator, Lexer, Parser } from "@actions/expressions";
import { parse } from "yaml";

import {
	claimDocsPreview,
	DOCS_PREVIEW_CONTEXT,
	docsChanged,
	finishDocsPreview,
	previewTransition,
	shouldTeardownPreviews,
} from "./docs-preview.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

const head = "a".repeat(40);
const repo = { owner: "owner", repo: "repo" };
const runUrl = "https://github.com/owner/repo/actions/runs/123";
const previewUrl = "https://owner-docs-pr-7.surge.sh";

function api(statuses: unknown[] = []) {
	let state = "open";
	let currentHead = head;
	let failure = "";
	const written: Record<string, unknown>[] = [];
	const github = {
		paginate: async <T>(
			method: (params: Record<string, unknown>) => Promise<{ data: T[] }>,
			params: Record<string, unknown>,
		) => {
			const all: T[] = [];
			for (let page = 1; ; page += 1) {
				const response = await method({ ...params, page });
				all.push(...response.data);
				if (response.data.length < 100) {
					return all;
				}
			}
		},
		rest: {
			pulls: {
				get: async () => {
					if (failure === "pull") {
						throw new Error("Pull API unavailable");
					}
					return { data: { state, head: { sha: currentHead } } };
				},
			},
			repos: {
				listCommitStatusesForRef: async (params: Record<string, unknown>) => {
					if (failure === "statuses") {
						throw new Error("Status API unavailable");
					}
					assert.equal(params.ref, head);
					const page = Number(params.page);
					return { data: statuses.slice((page - 1) * 100, page * 100) };
				},
				createCommitStatus: async (params: Record<string, unknown>) => {
					if (failure === "write") {
						throw new Error("Status write unavailable");
					}
					written.push(params);
					statuses.unshift(params);
					return { data: { id: written.length } };
				},
			},
		},
	};
	return {
		input: { github, repo, pullNumber: 7, headSha: head, runUrl },
		written,
		close: () => {
			state = "closed";
		},
		reopen: () => {
			state = "open";
		},
		replaceHead: () => {
			currentHead = "b".repeat(40);
		},
		fail: (operation: string) => {
			failure = operation;
		},
	};
}

void test("a current head is claimed once, before an upload can start", async () => {
	const fixture = api();
	const claimed = await claimDocsPreview(fixture.input);
	assert.equal(claimed.kind, "claimed");
	assert.deepEqual(fixture.written, [
		{
			...repo,
			sha: head,
			context: DOCS_PREVIEW_CONTEXT,
			state: "pending",
			target_url: runUrl,
			description: "Docs preview deployment claimed for this head",
		},
	]);
	const repeated = await claimDocsPreview(fixture.input);
	assert.equal(repeated.kind, "existing");
	assert.match(repeated.summary, /pending/u);
	assert.equal(fixture.written.length, 1);
});

for (const state of ["pending", "success", "failure", "error"]) {
	void test(`a recorded ${state} status survives a repeated run without another claim`, async () => {
		const fixture = api([
			...Array.from({ length: 100 }, () => ({ context: "Other check", state: "success" })),
			{ context: DOCS_PREVIEW_CONTEXT, state },
		]);
		const result = await claimDocsPreview(fixture.input);
		assert.equal(result.kind, "existing");
		assert.ok(result.summary.includes(state));
		assert.deepEqual(fixture.written, []);
	});
}

void test("a replaced head and a closed pull request cannot claim a deployment", async () => {
	for (const close of [false, true]) {
		const fixture = api();
		if (close) {
			fixture.close();
		} else {
			fixture.replaceHead();
		}
		const decision = await claimDocsPreview(fixture.input);
		assert.equal(decision.kind, "superseded");
		assert.deepEqual(fixture.written, []);
	}
});

void test("a delayed teardown reads the reopened pull request inside its lock", async () => {
	const fixture = api();
	fixture.close();
	assert.equal(await shouldTeardownPreviews(fixture.input), true);
	fixture.reopen();
	assert.equal(await shouldTeardownPreviews(fixture.input), false);
	assert.deepEqual(fixture.written, []);
});

for (const operation of ["pull", "statuses", "write"]) {
	void test(`an unavailable ${operation} API fails the claim without publishing success`, async () => {
		const fixture = api();
		fixture.fail(operation);
		await assert.rejects(claimDocsPreview(fixture.input), /unavailable/u);
		assert.deepEqual(fixture.written, []);
	});
}

for (const outcome of ["success", "failure", "cancelled", "skipped"]) {
	void test(`the actual ${outcome} upload outcome decides the visible status`, async () => {
		const fixture = api();
		await claimDocsPreview(fixture.input);
		const result = await finishDocsPreview({ ...fixture.input, previewUrl, outcome });
		const expected = outcome === "success" ? "success" : "error";
		assert.equal(result.state, expected);
		const reported = fixture.written[1];
		assert.ok(reported);
		assert.equal(reported.state, expected);
		assert.equal(reported.target_url, outcome === "success" ? previewUrl : runUrl);
		const repeated = await claimDocsPreview(fixture.input);
		assert.equal(repeated.kind, "existing");
		assert.equal(fixture.written.length, 2);
	});
}

void test("a failed status report fails instead of claiming that the preview succeeded", async () => {
	const fixture = api();
	fixture.fail("write");
	await assert.rejects(
		finishDocsPreview({ ...fixture.input, previewUrl, outcome: "failure" }),
		/unavailable/u,
	);
});

void test("the status lifecycle permits only a new claim and this attempt's outcome", () => {
	const states = ["unclaimed", "pending", "success", "error", "failure"] as const;
	const events = ["claim", "deployed", "unavailable"] as const;
	for (const state of states) {
		for (const event of events) {
			if (state === "unclaimed" && event === "claim") {
				assert.equal(previewTransition(state, event), "pending");
			} else if (state === "pending" && event === "deployed") {
				assert.equal(previewTransition(state, event), "success");
			} else if (state === "pending" && event === "unavailable") {
				assert.equal(previewTransition(state, event), "error");
			} else {
				assert.throws(() => previewTransition(state, event), /Illegal/u);
			}
		}
	}
});

function history(t: TestContext) {
	const cwd = mkdtempSync(path.join(tmpdir(), "docs-preview-"));
	t.after(() => rmSync(cwd, { recursive: true, force: true }));
	const environment = environmentForGitFixture();
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd, env: environment, encoding: "utf8" }).trim();
	git("init", "--quiet");
	git("config", "user.name", "Fixture");
	git("config", "user.email", "fixture@example.invalid");
	mkdirSync(path.join(cwd, "docs"));
	writeFileSync(path.join(cwd, "docs/guide.md"), "Initial docs\n");
	writeFileSync(path.join(cwd, "code.ts"), "export const value = 1;\n");
	const commit = () => {
		git("add", ".");
		git("commit", "--quiet", "-m", "fixture");
		return git("rev-parse", "HEAD");
	};
	const base = commit();
	writeFileSync(path.join(cwd, "docs/guide.md"), "Changed docs\n");
	const docs = commit();
	writeFileSync(path.join(cwd, "code.ts"), "export const value = 2;\n");
	const code = commit();
	return { cwd, base, docs, code, commit, git, environment };
}

void test("a later code-only push does not redeploy a PR that already changes docs", (t) => {
	const fixture = history(t);
	const pull_request = { base: { sha: fixture.base }, head: { sha: fixture.code } };
	assert.equal(docsChanged({ action: "opened", pull_request }, fixture.cwd), true);
	assert.equal(docsChanged({ action: "reopened", pull_request }, fixture.cwd), true);
	assert.equal(
		docsChanged({ action: "synchronize", before: fixture.docs, pull_request }, fixture.cwd),
		false,
	);
	assert.equal(
		docsChanged({ action: "synchronize", before: fixture.base, pull_request }, fixture.cwd),
		true,
	);
});

void test("a force push and a documentation deletion compare the actual docs trees", (t) => {
	const fixture = history(t);
	fixture.git("checkout", "--quiet", "--detach", fixture.base);
	writeFileSync(path.join(fixture.cwd, "code.ts"), "export const value = 3;\n");
	const rebased = fixture.commit();
	const pull_request = { base: { sha: fixture.base }, head: { sha: rebased } };
	assert.equal(docsChanged({ action: "opened", pull_request }, fixture.cwd), false);
	assert.equal(
		docsChanged({ action: "synchronize", before: fixture.docs, pull_request }, fixture.cwd),
		true,
	);
	rmSync(path.join(fixture.cwd, "docs/guide.md"));
	assert.equal(
		docsChanged(
			{
				action: "synchronize",
				before: rebased,
				pull_request: { ...pull_request, head: { sha: fixture.commit() } },
			},
			fixture.cwd,
		),
		true,
	);
});

void test("missing or malformed comparison evidence never claims that docs were unchanged", (t) => {
	const fixture = history(t);
	const pull_request = { base: { sha: fixture.base }, head: { sha: fixture.code } };
	assert.throws(
		() => docsChanged({ action: "synchronize", pull_request }, fixture.cwd),
		/head commit/u,
	);
	assert.throws(
		() => docsChanged({ action: "synchronize", before: "--output=bad", pull_request }, fixture.cwd),
		/commit SHA/u,
	);
	assert.throws(
		() => docsChanged({ action: "synchronize", before: "0".repeat(40), pull_request }, fixture.cwd),
		/comparison commit/u,
	);
});

void test("a previous head outside the checkout is fetched before comparing docs", (t) => {
	const fixture = history(t);
	const shallow = path.join(fixture.cwd, "shallow");
	execFileSync("git", ["clone", "--quiet", "--depth=1", pathToFileURL(fixture.cwd).href, shallow], {
		env: fixture.environment,
	});
	assert.notEqual(
		spawnSync("git", ["cat-file", "-e", fixture.docs], {
			cwd: shallow,
			env: fixture.environment,
			stdio: "ignore",
		}).status,
		0,
	);
	assert.equal(
		docsChanged(
			{
				action: "synchronize",
				before: fixture.docs,
				pull_request: { base: { sha: fixture.base }, head: { sha: fixture.code } },
			},
			shallow,
		),
		false,
	);
	assert.equal(
		spawnSync("git", ["cat-file", "-e", fixture.docs], {
			cwd: shallow,
			env: fixture.environment,
			stdio: "ignore",
		}).status,
		0,
	);
});

function workflow(file: string) {
	return asRecord(
		parse(readFileSync(new URL(`../.github/workflows/${file}`, import.meta.url), "utf8")),
		file,
	);
}

void test("build cancellation cannot interrupt an upload, and queued teardown shares its lock", () => {
	const docs = workflow("cd-docs.yml");
	const jobs = asRecord(docs.jobs, "jobs");
	const teardown = asRecord(
		asRecord(workflow("cd-docs-teardown.yml").jobs, "jobs").teardown,
		"teardown",
	);
	assert.equal(docs.concurrency, undefined);
	for (const name of ["build-preview", "build-production"]) {
		assert.equal(
			asRecord(asRecord(jobs[name], name).concurrency, "concurrency")["cancel-in-progress"],
			true,
		);
	}
	const preview = asRecord(jobs.preview, "preview");
	assert.deepEqual(preview.concurrency, teardown.concurrency);
	assert.deepEqual(preview.concurrency, {
		group: `docs-preview-\${{ github.event.number }}`,
		"cancel-in-progress": false,
		queue: "max",
	});
	assert.match(
		asString(preview.if, "preview condition"),
		/needs\.build-preview\.outputs\.docs-changed == 'true'/u,
	);
	for (const [sourceRepo, changed, expected] of [
		["owner/repo", "true", true],
		["owner/repo", "false", false],
		["fork/repo", "true", false],
	] as const) {
		const expression = new Parser(
			new Lexer(asString(preview.if, "preview condition")).lex().tokens,
			["github", "needs"],
			[],
		).parse();
		const context: unknown = JSON.parse(
			JSON.stringify({
				github: {
					event_name: "pull_request",
					repository: "owner/repo",
					event: { pull_request: { head: { repo: { full_name: sourceRepo } } } },
				},
				needs: { "build-preview": { outputs: { "docs-changed": changed } } },
			}),
			data.reviver,
		);
		assert.ok(context instanceof data.Dictionary);
		assert.equal(new Evaluator(expression, context).evaluate().coerceString() === "true", expected);
	}
	const steps = asArray(preview.steps, "steps").map((step) => asRecord(step, "step"));
	assert.deepEqual(
		steps.filter((step) => step["continue-on-error"] === true).map((step) => step.id),
		["surge"],
	);
	assert.match(
		asString(steps.find((step) => step.id === "surge")?.if, "Surge condition"),
		/steps\.claim\.outputs\.deploy == 'true'/u,
	);
});

void test("a delayed closed-event teardown performs no mutation after reopening", () => {
	const teardown = asRecord(
		asRecord(workflow("cd-docs-teardown.yml").jobs, "jobs").teardown,
		"teardown",
	);
	const steps = asArray(teardown.steps, "steps").map((step) => asRecord(step, "step"));
	const guard = steps.find((step) => step.id === "state");
	const script = asString(asRecord(guard?.with, "guard inputs").script, "guard script");
	for (const state of ["open", "closed"]) {
		const result = spawnSync(
			process.execPath,
			[
				"-e",
				`
			const context = {repo: {owner: "owner", repo: "repo"}, issue: {number: 7}, payload: {action: "closed", pull_request: {state: "closed"}}};
			const github = {rest: {pulls: {get: async () => ({data: {state: ${JSON.stringify(state)}}})}}};
			const outputs = {};
			const core = {setOutput(key, value) {outputs[key] = String(value);}, notice() {}};
			(async () => { ${script} })().then(() => process.stdout.write(JSON.stringify(outputs))).catch(error => {console.error(error); process.exitCode = 1;});
		`,
			],
			{
				encoding: "utf8",
				env: environmentForGitFixture({
					GITHUB_WORKSPACE: path.resolve(import.meta.dirname, ".."),
				}),
			},
		);
		assert.equal(result.status, 0, result.stderr);
		const outputs = asRecord(parseJson(result.stdout), "guard output");
		const permitted = steps
			.filter(
				(step) =>
					step.run !== undefined ||
					step.name === "Retire preview comments and their continuation parts",
			)
			.filter((step) => {
				const expression = new Parser(
					new Lexer(asString(step.if, "mutation condition")).lex().tokens,
					["steps"],
					[],
				).parse();
				const context: unknown = JSON.parse(
					JSON.stringify({ steps: { state: { outputs } } }),
					data.reviver,
				);
				assert.ok(context instanceof data.Dictionary);
				return new Evaluator(expression, context).evaluate().coerceString() === "true";
			});
		assert.equal(permitted.length, state === "open" ? 0 : 3);
	}
});
