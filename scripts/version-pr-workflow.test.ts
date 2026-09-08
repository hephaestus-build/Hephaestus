import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { environmentForGitFixture } from "./lib/git-environment.ts";

const workflow = parseDocument(await readFile(".github/workflows/version-pr.yml", "utf8"));
const job = workflow.getIn(["jobs", "version-pr"]);
assert.ok(isMap(job));
const steps = job.get("steps");
assert.ok(isSeq(steps));
const selection = steps.items.find((step) => isMap(step) && step.get("id") === "current");
assert.ok(isMap(selection));

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
	assert.match(String(guard), /github\.event\.workflow_run\.conclusion == 'success'/);
	assert.match(String(guard), /&& github\.event\.workflow_run\.event == 'push'/);
	assert.match(
		String(guard),
		/&& github\.event\.workflow_run\.head_repository\.full_name == github\.repository/,
	);
	const checkout = steps.items[0];
	assert.ok(isMap(checkout));
	assert.equal(checkout.getIn(["with", "ref"]), `\${{ github.event.repository.default_branch }}`);
	assert.equal(
		selection.getIn(["env", "VALIDATED_SHA"]),
		`\${{ github.event.workflow_run.head_sha }}`,
	);
	const work = steps.items.slice(2);
	assert.equal(work.length, 3);
	for (const step of work) {
		assert.ok(isMap(step));
		assert.equal(step.get("if"), "steps.current.outputs.validated == 'true'");
	}
});

void test(
	"overtaken completions do no work, but a successful latest-tip rerun can proceed",
	{
		skip: process.platform === "win32",
	},
	async (context) => {
		const directory = await mkdtemp(path.join(tmpdir(), "version-pr-tip-"));
		context.after(() => rm(directory, { recursive: true, force: true }));
		function git(...args: string[]): string {
			const result = spawnSync("git", args, {
				cwd: directory,
				encoding: "utf8",
				env: environmentForGitFixture(),
			});
			assert.equal(result.status, 0, result.stderr);
			return result.stdout.trim();
		}
		git("init", "-q");
		git(
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.com",
			"commit",
			"-qm",
			"first",
			"--allow-empty",
		);
		const first = git("rev-parse", "HEAD");
		const command = selection.get("run");
		assert.equal(typeof command, "string");
		assert.equal(selection.get("shell"), "bash");
		let attempt = 0;
		async function selected(sha: string): Promise<boolean> {
			const output = path.join(directory, `output-${attempt++}`);
			const result = spawnSync(
				"bash",
				["--noprofile", "--norc", "-eo", "pipefail", "-c", String(command)],
				{
					cwd: directory,
					encoding: "utf8",
					env: environmentForGitFixture({ VALIDATED_SHA: sha, GITHUB_OUTPUT: output }),
				},
			);
			assert.equal(result.status, 0, result.stderr);
			return (
				await readFile(output, "utf8").catch((error: unknown) => {
					if (error instanceof Error && "code" in error && error.code === "ENOENT") return "";
					throw error;
				})
			).includes("validated=true");
		}
		assert.equal(await selected(first), true);
		git(
			"-c",
			"user.name=Test",
			"-c",
			"user.email=test@example.com",
			"commit",
			"-qm",
			"second",
			"--allow-empty",
		);
		assert.equal(await selected(first), false);
		assert.equal(await selected(git("rev-parse", "HEAD")), true);
	},
);
