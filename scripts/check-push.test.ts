import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { existsSync, readdirSync, realpathSync } from "node:fs";
import { cp, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { setTimeout } from "node:timers/promises";
import { pathToFileURL } from "node:url";

import {
	focusedTests,
	frontendCommands,
	pushScopes,
	pushTasks,
	skipsImportedTests,
	toolingScopes,
} from "./check-push.ts";
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

void test("a tooling change runs what pull request CI runs for it, not every frontend suite", () => {
	// Before, any scripts/ path selected every scope, and the hook ran both complete frontend unit
	// suites on one worker.
	assert.deepEqual(pushScopes(["scripts/reorder-migrations.ts"]), ["agents"]);
	assert.deepEqual(frontendCommands(["scripts/reorder-migrations.ts"]), []);
	// CI's webapp and extension filters name the story prose gate, so both trees' checks run.
	const story = pushScopes(["scripts/check-story-prose.ts"]);
	assert.ok(story.includes("webapp") && story.includes("extension"), story.join(", "));
	assert.ok(pushScopes(["scripts/run-gradlew.ts"]).includes("server"));
	// Without readable filters, the hook cannot know, so it runs every scope.
	assert.equal(toolingScopes("scripts/reorder-migrations.ts", undefined), undefined);
	assert.deepEqual(
		toolingScopes("scripts/x.ts", { webapp: ["scripts/x.ts"], docs: ["!scripts/**"] }),
		["agents", "webapp"],
	);
});

void test("a server change compiles the tests and runs Spotless, PMD and the architecture tier in one Gradle call", async () => {
	const selected = pushTasks(["server/application/src/test/java/Example.java"]);
	const tasks = await loadTasks();
	const commands = selected
		.flatMap((name) => commandsOf(tasks[name]))
		.filter((command) => command.includes("gradlew"));
	assert.equal(commands.length, 1, commands.join("\n"));
	for (const task of [
		":spotlessCheck",
		":application:spotlessCheck",
		":application:pmdMain",
		":application:architectureTest",
	]) {
		assert.ok(commands[0]?.includes(task) === true, task);
	}
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

void test("a source change runs its changed and colocated tests, and resources run the whole unit project", () => {
	// Before, Vitest's related selection reached 101 test files through the router for one component.
	const component = frontendCommands([
		"webapp/src/components/product-feedback/ProductFeedbackDialog.tsx",
		".changeset/a.md",
	]);
	assert.deepEqual(component, [
		[
			"-C",
			"webapp",
			"test",
			"--run",
			"--maxWorkers=1",
			"src/components/product-feedback/ProductFeedbackDialog.test.tsx",
		],
	]);
	// A source without a colocated test leaves its tests to CI, and the hook says so.
	assert.deepEqual(frontendCommands(["webapp/src/components/ui/button.tsx"]), []);
	assert.equal(skipsImportedTests(["webapp/src/components/ui/button.tsx"]), true);
	assert.equal(skipsImportedTests(["webapp/package.json"]), false);
	assert.equal(skipsImportedTests(["scripts/reorder-migrations.ts"]), false);
	for (const files of [
		["webapp/public/a.svg"],
		["webapp/src/deleted.ts"],
		["webapp/package.json"],
		["webapp/src/test/async.ts"],
		["webapp/src/mocks/handlers.ts"],
	]) {
		assert.deepEqual(
			frontendCommands(files),
			[["-C", "webapp", "test", "--run", "--maxWorkers=1"]],
			"the whole unit project",
		);
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
		"enable-hooks.ts",
		"reorder-migrations.ts",
		"lib/changelog.ts",
		"lib/env.ts",
		"lib/git-environment.ts",
		"lib/json.ts",
		"lib/process.ts",
		"lib/push-gates.ts",
		"release-note-rules.ts",
		"verify-revert.ts",
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

void test("a migration that sorts before the base's newest blocks the push before any check runs", async () => {
	const f = await fixture();
	try {
		const next = path.join(f.directory, "server/application/src/main/resources/db/changelog/next");
		await mkdir(next, { recursive: true });
		await writeFile(path.join(next, "20261103000000-merged.xml"), "<databaseChangeLog/>\n");
		f.git("add", ".");
		f.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "main's migration");
		await writeFile(path.join(next, "20261102000000-mine.xml"), "<databaseChangeLog/>\n");
		const result = f.run();
		assert.equal(result.status, 1, result.stdout + result.stderr);
		assert.match(result.stderr, /20261102000000-mine\.xml/u);
		assert.match(result.stderr, /vp run db:reorder/u);
		await assert.rejects(readFile(f.calls, "utf8"), { code: "ENOENT" });
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
		assert.match(result.stdout, /Complete Node suites, Java unit and integration tests/u);
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

void test("a webapp change runs the build that enforces the bundle budget", () => {
	assert.ok(
		pushTasks(["webapp/src/components/ui/button.tsx"]).includes("verification:webapp-build"),
	);
	assert.ok(
		!pushTasks(["server/application/build.gradle.kts"]).includes("verification:webapp-build"),
	);
});

/** Runs `runUnlessStalled` on `script` in its own process, so its output stays out of this report. */
function watched(script: string, stallLimitMs: number, command = [process.execPath, "-e", script]) {
	const module = JSON.stringify(
		pathToFileURL(path.join(import.meta.dirname, "check-push.ts")).href,
	);
	const [program, ...args] = command;
	return spawnSync(
		process.execPath,
		[
			"--input-type=module",
			"--eval",
			`import { runUnlessStalled } from ${module};
process.exitCode = await runUnlessStalled(${JSON.stringify(program)}, ${JSON.stringify(args)}, ${stallLimitMs});`,
		],
		{ encoding: "utf8", timeout: 60_000 },
	);
}

void test("a frontend run that stops printing is stopped with its whole process tree, and fails", async (t) => {
	if (process.platform === "win32") {
		t.skip("Windows has no process groups or report signal");
		return;
	}
	// A parent that waits forever on a silent child: what a hung Vitest run looks like from outside.
	const hung = `const { spawn } = require("node:child_process");
const child = spawn(process.execPath, ["-e", "setInterval(() => {}, 1e6)"], { stdio: "ignore" });
console.log("child " + child.pid);
setInterval(() => {}, 1e6);`;
	const result = watched(hung, 1500);
	assert.equal(result.status, 1, result.stderr);
	assert.match(result.stderr, /printed nothing for 2 s, so the push check stopped it and failed/u);
	const reports = /waited on: (?<directory>\S+)/u.exec(result.stderr)?.groups?.directory ?? "";
	assert.equal(readdirSync(reports).filter((file) => file.endsWith(".json")).length, 2);
	const grandchild = Number(/child (?<pid>\d+)/u.exec(result.stdout)?.groups?.pid);
	assert.ok(grandchild > 0);
	await setTimeout(6000);
	assert.throws(() => process.kill(grandchild, 0), "the silent child is stopped too");
});

void test("a stalled run whose leader ends on the report signal leaves no worker behind", async (t) => {
	if (process.platform === "win32") {
		t.skip("Windows has no process groups or report signal");
		return;
	}
	// A shell leader ends on SIGUSR2; its Node worker writes a report and would keep running.
	const leader = `"${process.execPath}" -e "setInterval(() => {}, 1e6)" & echo "child $!"; wait`;
	const result = watched("", 1500, ["sh", "-c", leader]);
	assert.equal(result.status, 1, result.stderr);
	const grandchild = Number(/child (?<pid>\d+)/u.exec(result.stdout)?.groups?.pid);
	assert.ok(grandchild > 0);
	await setTimeout(500);
	assert.throws(() => process.kill(grandchild, 0), "the worker is stopped before the hook returns");
});

void test("a frontend run that keeps printing may run longer than the stall limit", () => {
	const steady = `let n = 0; const t = setInterval(() => { console.log(n); if (++n === 6) clearInterval(t); }, 400);`;
	assert.equal(watched(steady, 1500).status, 0);
	assert.equal(watched("process.exit(3)", 1500).status, 3);
});

void test("a push of shipped code without a changeset stops before any check runs", async () => {
	const f = await fixture();
	try {
		const base = execFileSync("git", ["rev-parse", "HEAD"], {
			cwd: f.directory,
			encoding: "utf8",
		}).trim();
		await mkdir(path.join(f.directory, "webapp/src"), { recursive: true });
		await writeFile(path.join(f.directory, "webapp/src/feature.ts"), "export const feature = 1;\n");
		f.git("add", ".");
		f.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "shipped change");
		const missing = f.run(f.directory, base);
		assert.equal(missing.status, 1, missing.stdout + missing.stderr);
		assert.match(
			missing.stderr,
			/changes shipped code but carries no changeset: webapp\/src\/feature\.ts/u,
		);
		assert.ok(!existsSync(f.calls), "no gate ran");

		await mkdir(path.join(f.directory, ".changeset"));
		await writeFile(
			path.join(f.directory, ".changeset/feature.md"),
			'---\n"hephaestus": patch\n---\n\nA feature.\n',
		);
		f.git("add", ".");
		f.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "release note");
		assert.doesNotMatch(f.run(f.directory, base).stderr, /carries no changeset/u);
	} finally {
		await rm(f.directory, { recursive: true, force: true });
	}
});
