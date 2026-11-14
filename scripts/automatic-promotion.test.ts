import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { isMap, isSeq, parseDocument } from "yaml";

import { automaticPromotion, supersedingBuild, unchangedSummary } from "./automatic-promotion.ts";
import { parseChannel } from "./reconcile-deployment.ts";

const current = "a".repeat(40);
const next = "b".repeat(40);
const channel = parseChannel({
	commit: current,
	images: { HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/example/webapp@sha256:${"c".repeat(64)}` },
});

await test("a frozen channel holds an automatic request without consulting commit history", async () => {
	assert.deepEqual(
		await automaticPromotion({ ...channel, freeze: true }, next, () => {
			throw new Error("a frozen channel must not query history");
		}),
		{ outcome: "held", release: current },
	);
});

await test("a held or current request says what the channel stays on, and that nothing was deployed", () => {
	const held = unchangedSummary({ outcome: "held", release: current }, "Staging", next);
	assert.equal(held.title, "Staging is held");
	assert.match(
		held.text,
		new RegExp(`frozen on ${current}, so ${next.slice(0, 10)} was not promoted`, "u"),
	);
	assert.match(
		held.text,
		/Nothing was published or deployed\. A promotion without freeze lifts the hold\./u,
	);
	const unchanged = unchangedSummary({ outcome: "current", release: current }, "Staging", next);
	assert.equal(unchanged.title, "Staging already carries this build");
	assert.doesNotMatch(unchanged.text, /frozen|held/u);
});

await test("automatic promotion advances but never replays or rewinds a completed build", async () => {
	for (const [status, expected] of [
		["ahead", { outcome: "promote" }],
		["identical", { outcome: "current", release: current }],
		["behind", { outcome: "current", release: current }],
	] as const) {
		assert.deepEqual(
			await automaticPromotion(channel, next, async (base, head) => {
				assert.equal(base, current);
				assert.equal(head, next);
				return status;
			}),
			expected,
		);
	}
});

await test("unknown history and API failures cannot authorize promotion", async () => {
	for (const status of ["diverged", "unexpected"]) {
		await assert.rejects(
			automaticPromotion(channel, next, async () => status),
			/history/u,
		);
	}
	await assert.rejects(
		automaticPromotion(channel, next, async () => {
			throw new Error("unavailable");
		}),
		/unavailable/u,
	);
	await assert.rejects(
		automaticPromotion(channel, "main", async () => "ahead"),
		/full commit SHA/u,
	);
});

await test("switching from a release to main cannot replay or rewind the release's source", async () => {
	const release = parseChannel({ release: "v1.2.3" });
	for (const [status, expected] of [
		["ahead", { outcome: "promote" }],
		["identical", { outcome: "current", release: "v1.2.3" }],
		["behind", { outcome: "current", release: "v1.2.3" }],
	] as const) {
		assert.deepEqual(
			await automaticPromotion(release, next, async (base, head) => {
				assert.equal(base, "v1.2.3");
				assert.equal(head, next);
				return status;
			}),
			expected,
		);
	}
	await assert.rejects(
		automaticPromotion(release, next, async () => {
			throw new Error("unknown release");
		}),
		/unknown release/u,
	);
});

const later = "d".repeat(40);
const repository = "example/repo";

/** A completed, successful CI/CD run of a push to main, as the workflow-runs listing returns it. */
function mainBuild(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		event: "push",
		status: "completed",
		conclusion: "success",
		head_branch: "main",
		path: ".github/workflows/cicd.yml",
		head_sha: later,
		repository: { full_name: repository },
		head_repository: { full_name: repository },
		...overrides,
	};
}

const listing =
	(...runs: unknown[]) =>
	async (): Promise<unknown> => ({ workflow_runs: runs });

const unavailable = async (): Promise<unknown> => {
	throw new Error("unavailable");
};

await test("the latest qualified main build supersedes a request it descends from", async () => {
	assert.equal(
		await supersedingBuild(repository, next, listing(mainBuild()), async (base, head) => {
			assert.equal(base, next);
			assert.equal(head, later);
			return "ahead";
		}),
		later,
	);
	// Do not search an unqualified latest record for another candidate.
	assert.equal(
		await supersedingBuild(repository, next, listing(null, mainBuild()), async () => "ahead"),
		undefined,
	);
});

await test("only a completed successful CI/CD push to this repository's main can supersede", async () => {
	for (const overrides of [
		{ event: "pull_request" },
		{ event: "merge_group" },
		{ event: "workflow_dispatch" },
		{ status: "in_progress" },
		{ conclusion: "failure" },
		{ head_branch: "release" },
		{ path: ".github/workflows/promote.yml" },
		{ repository: { full_name: "fork/repo" } },
		{ head_repository: { full_name: "fork/repo" } },
		{ head_sha: later.slice(0, 7) },
		{ head_sha: later.toUpperCase() },
		{ head_sha: next },
	]) {
		// Every comparison would say "ahead", so only the record itself can keep the request.
		assert.equal(
			await supersedingBuild(repository, next, listing(mainBuild(overrides)), async () => "ahead"),
			undefined,
			JSON.stringify(overrides),
		);
	}
});

await test("a later build that does not strictly descend from the request leaves it in place", async () => {
	for (const status of ["identical", "behind", "diverged"]) {
		assert.equal(
			await supersedingBuild(repository, next, listing(mainBuild()), async () => status),
			undefined,
		);
	}
});

await test("an unavailable or malformed supersession lookup keeps the request", async () => {
	for (const runs of [
		unavailable,
		async (): Promise<unknown> => null,
		async (): Promise<unknown> => ({}),
		async (): Promise<unknown> => ({ workflow_runs: {} }),
	]) {
		assert.equal(await supersedingBuild(repository, next, runs, async () => "ahead"), undefined);
	}
	assert.equal(
		await supersedingBuild(repository, next, listing(mainBuild()), async () => {
			throw new Error("unavailable");
		}),
		undefined,
	);
});

await test(
	"the CLI supersession check preserves lookup failures and current-channel skips",
	{ skip: process.platform === "win32" },
	(t) => {
		const directory = mkdtempSync(path.join(tmpdir(), "automatic-promotion-"));
		t.after(() => rmSync(directory, { recursive: true, force: true }));
		mkdirSync(path.join(directory, "deploy-state/channels"), { recursive: true });
		writeFileSync(
			path.join(directory, "deploy-state/channels/staging.json"),
			JSON.stringify({
				commit: current,
				images: { HEPHAESTUS_IMAGE_WEBAPP: `ghcr.io/example/webapp@sha256:${"c".repeat(64)}` },
			}),
		);
		// `gh api <path>`: the channel is behind the request, the request is behind `later`, and the
		// listing holds one qualified run of `later` unless it is made to fail.
		writeFileSync(
			path.join(directory, "gh"),
			[
				"#!/usr/bin/env node",
				"const route = process.argv[3];",
				String.raw`require('node:fs').appendFileSync(process.env.TRACE, route + '\n');`,
				"if (route.includes('/compare/')) {",
				"  console.log(JSON.stringify({ status: process.env.COMPARE || 'ahead' }));",
				` } else if (route !== 'repos/${repository}/actions/workflows/cicd.yml/runs?event=push&branch=main&status=success&per_page=1' || process.env.RUNS === 'fail') {`,
				"  process.exit(1);",
				"} else {",
				`  console.log(${JSON.stringify(JSON.stringify({ workflow_runs: [mainBuild()] }))});`,
				"}",
			].join("\n"),
			{ mode: 0o755 },
		);
		for (const [runs, comparison, expected, reads] of [
			["list", "ahead", `apply=true\noutcome=promote\ncommit=${later}\n`, 3],
			["fail", "ahead", `apply=true\noutcome=promote\ncommit=${next}\n`, 2],
			["identical", "identical", "apply=false\noutcome=current\n", 1],
			["behind", "behind", "apply=false\noutcome=current\n", 1],
		] as const) {
			const outputFile = path.join(directory, `output-${runs}`);
			const traceFile = path.join(directory, `trace-${runs}`);
			const result = spawnSync(
				process.execPath,
				[fileURLToPath(new URL("automatic-promotion.ts", import.meta.url))],
				{
					cwd: directory,
					encoding: "utf8",
					env: {
						...process.env,
						PATH: [directory, path.dirname(process.execPath)].join(path.delimiter),
						RUNS: runs,
						COMPARE: comparison,
						TRACE: traceFile,
						CHANNEL: "Staging",
						COMMIT: next,
						GITHUB_REPOSITORY: repository,
						GITHUB_OUTPUT: outputFile,
					},
				},
			);
			assert.equal(result.status, 0, result.stderr);
			assert.equal(readFileSync(outputFile, "utf8"), expected);
			assert.equal(readFileSync(traceFile, "utf8").trim().split("\n").length, reads);
		}
	},
);

await test("the CLI fails closed for invalid channels but honors a valid hold without network access", (t) => {
	const directory = mkdtempSync(path.join(tmpdir(), "automatic-promotion-"));
	t.after(() => rmSync(directory, { recursive: true, force: true }));
	mkdirSync(path.join(directory, "deploy-state/channels"), { recursive: true });
	const outputFile = path.join(directory, "output");
	for (const contents of [undefined, "{", JSON.stringify({ release: "v1.2.3", freeze: "true" })]) {
		if (contents !== undefined) {
			writeFileSync(path.join(directory, "deploy-state/channels/staging.json"), contents);
		}
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("automatic-promotion.ts", import.meta.url))],
			{
				cwd: directory,
				encoding: "utf8",
				env: {
					...process.env,
					CHANNEL: "Staging",
					COMMIT: next,
					GITHUB_REPOSITORY: "example/repo",
					GITHUB_OUTPUT: outputFile,
				},
			},
		);
		assert.notEqual(result.status, 0, result.stderr);
		assert.equal(existsSync(outputFile), false);
	}
	writeFileSync(
		path.join(directory, "deploy-state/channels/staging.json"),
		JSON.stringify({ release: "v1.2.3", freeze: true }),
	);
	const summaryFile = path.join(directory, "summary");
	const held = spawnSync(
		process.execPath,
		[fileURLToPath(new URL("automatic-promotion.ts", import.meta.url))],
		{
			cwd: directory,
			encoding: "utf8",
			env: {
				...process.env,
				PATH: "",
				CHANNEL: "Staging",
				COMMIT: next,
				GITHUB_REPOSITORY: "example/repo",
				GITHUB_OUTPUT: outputFile,
				GITHUB_STEP_SUMMARY: summaryFile,
			},
		},
	);
	// A hold is a state, not a failure: the run ends green, says so, and publishes nothing.
	assert.equal(held.status, 0, held.stderr);
	assert.equal(readFileSync(outputFile, "utf8"), "apply=false\noutcome=held\n");
	assert.match(
		held.stdout,
		/^::notice title=Staging is held::The Staging channel is frozen on v1\.2\.3/mu,
	);
	assert.match(
		readFileSync(summaryFile, "utf8"),
		/^### Staging is held\n\nThe Staging channel is frozen on v1\.2\.3/u,
	);
});

await test("every step after the policy deploys the commit the policy chose", () => {
	const workflow = parseDocument(readFileSync(".github/workflows/promote.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "promote", "steps"]);
	assert.ok(isSeq(steps));
	const policy = steps.items.findIndex((item) => isMap(item) && item.get("id") === "policy");
	assert.ok(policy !== -1);
	const deploying = JSON.stringify(steps.toJSON().slice(policy + 1));
	const reads = deploying.match(/inputs\.commit/gu) ?? [];
	assert.ok(reads.length > 0);
	assert.equal(
		(deploying.match(/steps\.policy\.outputs\.commit \|\| inputs\.commit/gu) ?? []).length,
		reads.length,
		"a later step reads the requested commit instead of the promoted one",
	);
});

await test("a held request publishes, signs, deploys and reports nothing, and files no failure", () => {
	const workflow = parseDocument(readFileSync(".github/workflows/promote.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "promote", "steps"]);
	assert.ok(isSeq(steps));
	const policy = steps.items.findIndex((item) => isMap(item) && item.get("id") === "policy");
	assert.ok(policy !== -1);
	// The two closing reporters run only after a record or a published channel, which a hold never makes.
	const closing = new Map([
		["Finish the deployment record", "steps.deployment.outputs.id != ''"],
		["Report promotion verification scope", "steps.publish-channel.outcome == 'success'"],
	]);
	for (const item of steps.items.slice(policy + 1)) {
		assert.ok(isMap(item));
		const name = String(item.get("name") ?? item.get("uses"));
		const guard = closing.get(name) ?? "steps.policy.outputs.apply != 'false'";
		const condition = item.get("if");
		assert.ok(
			typeof condition === "string" && condition.includes(guard),
			`${name} runs on a held request`,
		);
	}
	assert.match(
		String(workflow.getIn(["jobs", "report-failure", "if"])),
		/^\$\{\{ failure\(\) && inputs\.automatic \}\}$/u,
	);
});
