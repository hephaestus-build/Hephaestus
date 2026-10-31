import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { realpathSync } from "node:fs";
import { cp, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { focusedTests, frontendCommands, pushTasks } from "./check-push.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { commandsOf, loadTasks } from "./lib/task-graph.ts";

void test("workflow and docs edits retain eight static checks without complete suites or builds", async () => {
	const selected = pushTasks([".github/workflows/cicd.yml", "docs/contributor/ci-cd.mdx"]);
	assert.equal(selected.length, 8);
	for (const owner of [
		"gate:agents-lint",
		"gate:scripts-typecheck",
		"gate:docs-lint",
		"gate:prose",
	]) {
		assert.ok(selected.includes(owner));
	}
	const tasks = await loadTasks();
	for (const name of selected) {
		assert.doesNotMatch(
			commandsOf(tasks[name]).join("\n"),
			/test:tooling|test:agents|gradlew|\bbuild\b|(?:^|[;&|] )docker |curl/u,
		);
	}
	assert.deepEqual(frontendCommands([".github/workflows/cicd.yml"]), []);
});

void test("unknown inputs and unavailable bases retain every static scope", () => {
	const all = pushTasks(undefined);
	assert.deepEqual(pushTasks(["unknown-input.txt"]), all);
	for (const owner of [
		"gate:webapp",
		"gate:extension",
		"gate:changesets",
		"gate:prose",
		"gate:java-nullness",
	]) {
		assert.ok(all.includes(owner));
	}
	assert.equal(frontendCommands(undefined).length, 2);
});

void test("Vitest owns source selection, with complete unit projects for resources and deletions", () => {
	const related = frontendCommands(["webapp/src/components/ui/button.tsx", ".changeset/a.md"]);
	assert.equal(related.length, 1);
	assert.ok(related.some((command) => command.includes("related")));
	for (const files of [
		["webapp/public/a.svg"],
		["webapp/src/deleted.ts"],
		["webapp/package.json"],
	]) {
		assert.ok(frontendCommands(files).every((command) => !command.includes("related")));
	}
	const extension = frontendCommands(["extension/src/deleted.ts"])[0];
	assert.ok(extension !== undefined);
	assert.ok(extension.includes("unit"));
	assert.ok(!extension.includes("storybook"));
});

void test("focused Node tests include changed tests and existing source companions", () => {
	assert.deepEqual(
		focusedTests([
			"scripts/check-affected.ts",
			"scripts/check-push.test.ts",
			"scripts/deleted.test.ts",
		]),
		["scripts/check-affected.test.ts", "scripts/check-push.test.ts"],
	);
});

const repository = path.resolve(import.meta.dirname, "..");

async function installCli(directory: string): Promise<void> {
	await mkdir(path.join(directory, "node_modules/vite-plus"), { recursive: true });
	await symlink(
		path.join(repository, "node_modules/yaml"),
		path.join(directory, "node_modules/yaml"),
		process.platform === "win32" ? "junction" : "dir",
	);
	await writeFile(
		path.join(directory, "node_modules/vite-plus/package.json"),
		JSON.stringify({ type: "module", exports: { "./bin": "./cli.mjs" } }),
	);
	await writeFile(
		path.join(directory, "node_modules/vite-plus/cli.mjs"),
		`import { appendFileSync } from 'node:fs';
appendFileSync(process.env.CALLS, JSON.stringify({cwd:process.cwd(),args:process.argv.slice(2),workers:process.env.VP_RUN_CONCURRENCY_LIMIT,offline:process.env.HEPHAESTUS_PUSH_CHECK})+'\\n');
if (process.argv[2] === 'run' && process.argv.length !== 4) process.exit(22);
if (process.argv[3] === process.env.FAIL_TASK) process.exit(42);
`,
	);
}

async function fixture() {
	const directory = await mkdtemp(path.join(tmpdir(), "check-push-"));
	await mkdir(path.join(directory, "scripts/lib"), { recursive: true });
	for (const file of [
		"check-push.ts",
		"check-affected.ts",
		"lib/env.ts",
		"lib/git-environment.ts",
		"lib/json.ts",
		"lib/process.ts",
		"lib/push-gates.ts",
	]) {
		await cp(path.join(repository, "scripts", file), path.join(directory, "scripts", file));
	}
	await writeFile(path.join(directory, "package.json"), '{"type":"module"}');
	await writeFile(path.join(directory, ".gitignore"), "node_modules/\n");
	const git = (...args: string[]) =>
		execFileSync("git", args, { cwd: directory, env: environmentForGitFixture(), stdio: "pipe" });
	git("init", "--quiet");
	git("config", "user.name", "Test");
	git("config", "user.email", "test@example.invalid");
	git("add", ".");
	git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "fixture");
	await installCli(directory);
	const calls = path.join(directory, ".git/calls");
	const run = (cwd = directory, base = "HEAD", failTask?: string) =>
		spawnSync(process.execPath, [path.join(cwd, "scripts/check-push.ts"), "--base", base], {
			cwd,
			encoding: "utf8",
			env: environmentForGitFixture({
				CALLS: calls,
				FAIL_TASK: failTask,
				NODE_TEST_CONTEXT: undefined,
				GIT_DIR: path.join(directory, ".git"),
				GIT_WORK_TREE: directory,
			}),
		});
	return { directory, git, run, calls };
}

void test("a selected failing test blocks the push after static checks, with one worker", async () => {
	const f = await fixture();
	try {
		await writeFile(
			path.join(f.directory, "scripts/selected.test.ts"),
			"import {test} from 'node:test'; test('selected failure',()=>{throw new Error('selected failure')});",
		);
		const result = f.run();
		assert.equal(result.status, 1, result.stdout + result.stderr);
		assert.match(result.stdout + result.stderr, /selected failure/u);
		const calls = await readFile(f.calls, "utf8");
		assert.match(calls, /"workers":"1","offline":"true"/u);
		assert.doesNotMatch(result.stdout, /Push checks passed/u);
	} finally {
		await rm(f.directory, { recursive: true, force: true });
	}
});

void test("a missing base falls back without fetching and reports omitted full suites", async () => {
	const f = await fixture();
	try {
		const result = f.run(f.directory, "missing-reference");
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /No usable merge base/u);
		assert.match(result.stdout, /Complete Node suites, Gradle/u);
		const calls = await readFile(f.calls, "utf8");
		assert.match(calls, /"-C","webapp","test","--run","--maxWorkers=1"/u);
		assert.match(calls, /"-C","extension".*"--project","unit"/u);
	} finally {
		await rm(f.directory, { recursive: true, force: true });
	}
});

void test("a worktree selects its own changes and uses its own installed CLI", async () => {
	const f = await fixture();
	const worktree = await mkdtemp(path.join(tmpdir(), "check-push-worktree-"));
	try {
		f.git("worktree", "add", "--detach", worktree);
		await installCli(worktree);
		await writeFile(path.join(f.directory, "unknown-parent-input.txt"), "parent");
		await mkdir(path.join(worktree, ".changeset"));
		await writeFile(path.join(worktree, ".changeset/child.md"), "---\n---\n");
		const result = f.run(worktree);
		assert.equal(result.status, 0, result.stderr);
		const calls = await readFile(f.calls, "utf8");
		assert.ok(calls.includes(JSON.stringify(realpathSync(worktree))));
		for (const task of ["gate:changesets", "gate:docs-lint", "gate:prose"]) {
			assert.ok(calls.includes(JSON.stringify(["run", task])));
		}
		assert.equal(calls.trim().split("\n").length, 3);
	} finally {
		f.git("worktree", "remove", "--force", worktree);
		await rm(f.directory, { recursive: true, force: true });
		await rm(worktree, { recursive: true, force: true });
	}
});

void test("a later selected static failure blocks the push", async () => {
	const f = await fixture();
	try {
		await mkdir(path.join(f.directory, ".changeset"));
		await writeFile(path.join(f.directory, ".changeset/note.md"), "---\n---\n");
		const result = f.run(f.directory, "HEAD", "gate:prose");
		assert.equal(result.status, 42, result.stdout + result.stderr);
		const calls = await readFile(f.calls, "utf8");
		assert.equal(calls.trim().split("\n").length, 3);
		assert.doesNotMatch(result.stdout, /Push checks passed/u);
	} finally {
		await rm(f.directory, { recursive: true, force: true });
	}
});

void test("resource inputs retain full frontend coverage in a mixed change", () => {
	for (const input of [
		"server/application/src/main/resources/practices/default-catalog.json",
		"server/application/src/main/resources/practices/guidance/a.md",
		"docs/images/readme/a.png",
	]) {
		const commands = frontendCommands(["webapp/src/components/ui/button.tsx", input]);
		assert.ok(commands.length > 0);
		assert.ok(
			commands.every((command) => !command.includes("related")),
			input,
		);
	}
});

void test("instruction changes keep their owning static gate", () => {
	for (const file of [
		"AGENTS.md",
		"webapp/CLAUDE.md",
		".agents/skills/a/SKILL.md",
		".claude/skills/a/SKILL.md",
	]) {
		assert.ok(pushTasks([file]).includes("gate:instructions"), file);
	}
	assert.ok(!pushTasks([".github/workflows/cicd.yml"]).includes("gate:instructions"));
});
