import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { once } from "node:events";
import { existsSync, readdirSync, realpathSync } from "node:fs";
import { cp, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { createServer, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";

import {
	focusedTests,
	frontendCommands,
	parsePushArguments,
	pushFailures,
	pushScopeReason,
	pushScopes,
	pushTasks,
	runUnlessStalled,
	skipsImportedTests,
	toolingScopes,
} from "./check-push.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { asStringArray } from "./lib/json.ts";
import { pushStaticTasks } from "./lib/push-gates.ts";
import { commandsOf, loadTasks } from "./lib/task-graph.ts";

void test("workflow and docs edits run their static checks without complete suites or builds", async () => {
	const selected = pushTasks([".github/workflows/cicd.yml", "docs/contributor/ci-cd.mdx"]);
	assert.equal(selected.length, 14);
	for (const owner of [
		"gate:agents-lint",
		"gate:scripts-typecheck",
		"gate:docs-lint",
		"gate:prose",
		"gate:docs-pages",
		"gate:adr-confirmation",
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

/**
 * The gates that `check:affected` runs for a scope and the hook does not, each with its reason. Every
 * other gate of `affected:<scope>` is in the hook's list for that scope.
 */
const notInHook: Readonly<Record<string, Readonly<Record<string, string>>>> = {
	agents: {
		"test:tooling":
			"A complete Node suite. The hook runs the changed and companion test files; PR CI runs the suite.",
		"test:agents":
			"A complete agent-runtime suite. The hook runs the changed spec files; PR CI runs the suite.",
	},
	server: {
		"gate:server":
			"check:push:server runs each of its Gradle tasks in the hook's one Gradle invocation.",
	},
};

/** A task's leaf tasks: a group (no command of its own) stands for the tasks it depends on. */
function leafTasks(tasks: Record<string, unknown>, name: string): string[] {
	const task = tasks[name];
	if (
		typeof task === "object" &&
		task !== null &&
		"dependsOn" in task &&
		Array.isArray(task.dependsOn) &&
		commandsOf(task).length === 0
	) {
		return task.dependsOn.flatMap((dependency: unknown) => leafTasks(tasks, String(dependency)));
	}
	return [name];
}

/** Where the hook and `check:affected` disagree, as one line each. */
function hookGaps(
	tasks: Record<string, unknown>,
	hook: Readonly<Record<string, readonly string[]>>,
	exclusions: typeof notInHook,
): string[] {
	return Object.keys(hook).flatMap((scope) => {
		const affected = new Set(leafTasks(tasks, `affected:${scope}`));
		const pushed = new Set((hook[scope] ?? []).flatMap((name) => leafTasks(tasks, name)));
		const excluded = exclusions[scope] ?? {};
		return [
			...[...affected]
				.filter((gate) => !pushed.has(gate) && !Object.hasOwn(excluded, gate))
				.map((gate) => `${scope}: check:affected runs ${gate}, the hook does not`),
			...Object.keys(excluded)
				.filter((gate) => !affected.has(gate) || pushed.has(gate))
				.map((gate) => `${scope}: the exclusion of ${gate} is stale`),
		];
	});
}

void test("the hook runs every gate that check:affected selects for a scope, or names why not", async () => {
	const tasks = await loadTasks();
	assert.deepEqual(hookGaps(tasks, pushStaticTasks, notInHook), []);

	// A gate added to one list only, or an exclusion that no longer excludes anything, fails here.
	const affectedDocs = tasks["affected:docs"];
	assert.ok(
		typeof affectedDocs === "object" && affectedDocs !== null && "dependsOn" in affectedDocs,
	);
	const widened = {
		...tasks,
		"affected:docs": {
			...affectedDocs,
			dependsOn: [...asStringArray(affectedDocs.dependsOn, "affected:docs"), "gate:new"],
		},
	};
	assert.deepEqual(hookGaps(widened, pushStaticTasks, notInHook), [
		"docs: check:affected runs gate:new, the hook does not",
	]);
	assert.deepEqual(
		hookGaps(
			tasks,
			{ ...pushStaticTasks, agents: [...pushStaticTasks.agents, "test:agents"] },
			notInHook,
		),
		["agents: the exclusion of test:agents is stale"],
	);

	// The server exclusion holds only while the hook's Gradle call runs every task of gate:server.
	const gradleTasks = (name: string) =>
		new Set(commandsOf(tasks[name]).flatMap((line) => line.match(/(?<=\s):[\w:-]+/gu) ?? []));
	const hookGradle = gradleTasks("check:push:server");
	assert.ok(hookGradle.size > 0);
	for (const gradleTask of gradleTasks("gate:server")) {
		assert.ok(hookGradle.has(gradleTask), `check:push:server does not run ${gradleTask}`);
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

void test("the hook runs the changed and colocated frontend tests, never the whole unit project", () => {
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
	assert.equal(skipsImportedTests(["scripts/reorder-migrations.ts"]), false);
	// Helpers, mocks, setup files, resources and shared inputs reach every test: CI runs them all.
	for (const files of [
		["webapp/public/a.svg"],
		["webapp/src/deleted.ts"],
		["webapp/package.json"],
		["webapp/src/test/async.ts"],
		["webapp/src/test/setup-msw.ts"],
		["webapp/src/mocks/handlers.ts"],
	]) {
		assert.deepEqual(frontendCommands(files), [], String(files));
		assert.equal(skipsImportedTests(files), true, String(files));
	}
	// A helper changed with the tests that use it runs those tests.
	assert.deepEqual(
		frontendCommands(["webapp/src/test/async.ts", "webapp/src/test/router-harness.test.tsx"]),
		[["-C", "webapp", "test", "--run", "--maxWorkers=1", "src/test/router-harness.test.tsx"]],
	);
	// A changed test alone leaves nothing out.
	assert.equal(skipsImportedTests(["webapp/src/test/router-harness.test.tsx"]), false);
	assert.deepEqual(frontendCommands(["extension/src/deleted.ts"]), []);
	// Without a merge base the hook runs both whole unit projects, the extension's without Storybook.
	assert.deepEqual(frontendCommands(undefined), [
		["-C", "webapp", "test", "--run", "--maxWorkers=1"],
		["-C", "extension", "test", "--run", "--maxWorkers=1", "--project", "unit"],
	]);
});

void test("a hook change runs every test that runs a hook as a process", () => {
	const selected = focusedTests([".vite-hooks/pre-push"]);
	// These run the hook through Git or directly, so no import ties them to it.
	for (const file of ["scripts/enable-hooks.test.ts", "scripts/git-environment.test.ts"]) {
		assert.ok(selected.includes(file), file);
	}
	assert.ok(
		!focusedTests(["scripts/reorder-migrations.ts"]).includes("scripts/enable-hooks.test.ts"),
	);
});

void test("a schema change pushes with the server and docs checks only", () => {
	const schema = [
		"server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/model/Practice.java",
		"server/application/src/main/resources/db/changelog/next/20261115000000-practice.xml",
		"docs/contributor/erd/practices.mmd",
		"docs/contributor/erd/_modules.mdx",
	];
	assert.deepEqual(pushScopes(schema), ["docs", "server"]);
	assert.deepEqual(pushScopes(["docs/contributor/erd/practices.mmd"]), ["docs"]);
	assert.deepEqual(frontendCommands(schema), []);
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
		assert.match(
			result.stdout,
			/Complete frontend unit projects and Node suites, Java unit and integration tests/u,
		);
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

void test("resource inputs that select a frontend tree leave its tests to CI", () => {
	for (const input of [
		"server/application/src/main/resources/practices/default-catalog.json",
		"server/application/src/main/resources/practices/guidance/a.md",
		"docs/images/readme/a.png",
	]) {
		assert.deepEqual(frontendCommands(["webapp/src/components/ui/button.tsx", input]), [], input);
		assert.equal(skipsImportedTests(["webapp/src/components/ui/button.tsx", input]), true, input);
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
	// The docs scope runs it too, as check:affected does; a server-only change does not.
	assert.ok(!pushTasks(["server/application/build.gradle.kts"]).includes("gate:instructions"));
});

void test("a webapp change runs the build that enforces the bundle budget", () => {
	assert.ok(
		pushTasks(["webapp/src/components/ui/button.tsx"]).includes("verification:webapp-build"),
	);
	assert.ok(
		!pushTasks(["server/application/build.gradle.kts"]).includes("verification:webapp-build"),
	);
});

/** Captures forwarded output while only the watchdog's clock advances under test control. */
function watched(t: TestContext, program: string, args: string[]) {
	const output = { stdout: "", stderr: "" };
	const changed = new EventTarget();
	for (const stream of ["stdout", "stderr"] as const) {
		const write = process[stream].write.bind(process[stream]);
		t.mock.method(process[stream], "write", (...writeArgs: Parameters<typeof write>) => {
			output[stream] += String(writeArgs[0]);
			changed.dispatchEvent(new Event("output"));
			return write(...writeArgs);
		});
	}
	t.mock.timers.enable({ apis: ["Date", "setInterval", "setTimeout"], now: 10_000 });
	const result = runUnlessStalled(program, args, 1500);
	t.after(async () => {
		t.mock.timers.tick(6500);
		await result;
		const reports = /waited on: (?<directory>\S+)/u.exec(output.stderr)?.groups?.directory;
		if (reports !== undefined) {
			await rm(reports, { recursive: true, force: true });
		}
	});
	return {
		result,
		output,
		async until(condition: (seen: typeof output) => boolean) {
			while (!condition(output)) {
				await once(changed, "output");
			}
		},
	};
}

/** A connection held by the actual worker, whose close proves that the worker stopped. */
async function workerConnection(t: TestContext) {
	const connected = Promise.withResolvers<Socket>();
	let socket: Socket | undefined;
	const server = createServer((connection) => {
		socket = connection;
		connected.resolve(connection);
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	t.after(() => {
		socket?.destroy();
		server.close();
	});
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("Missing fixture address");
	}
	return { port: address.port, connected: connected.promise };
}

void test("a frontend run that stops printing is stopped with its whole process tree, and fails", async (t) => {
	if (process.platform === "win32") {
		t.skip("Windows has no process groups or report signal");
		return;
	}
	const worker = await workerConnection(t);
	// A parent that waits forever on a silent child: what a hung Vitest run looks like from outside.
	const hung = `const { spawn } = require("node:child_process");
spawn(process.execPath, ["-e", "const connection = require('node:net').connect(${worker.port}, '127.0.0.1'); connection.on('connect', () => console.log('ready'));"], { stdio: "inherit" });
setInterval(() => {}, 1e6);`;
	const run = watched(t, process.execPath, ["-e", hung]);
	const connection = await worker.connected;
	const stopped = once(connection, "close");
	await run.until(({ stdout }) => stdout.includes("ready"));
	t.mock.timers.tick(1500);
	await run.until(({ stderr }) => stderr.split("Node.js report completed").length === 3);
	t.mock.timers.tick(5000);
	assert.equal(await run.result, 1, run.output.stderr);
	await stopped;
	assert.match(
		run.output.stderr,
		/printed nothing for 2 s, so the push check stopped it and failed/u,
	);
	const reports = /waited on: (?<directory>\S+)/u.exec(run.output.stderr)?.groups?.directory ?? "";
	assert.equal(readdirSync(reports).filter((file) => file.endsWith(".json")).length, 2);
});

void test("a stalled run whose leader ends on the report signal leaves no worker behind", async (t) => {
	if (process.platform === "win32") {
		t.skip("Windows has no process groups or report signal");
		return;
	}
	const worker = await workerConnection(t);
	// A shell leader ends on SIGUSR2; its Node worker writes a report and would keep running.
	const leader = `"${process.execPath}" -e "const connection = require('node:net').connect(${worker.port}, '127.0.0.1'); connection.on('connect', () => console.log('ready'));" & wait`;
	const run = watched(t, "sh", ["-c", leader]);
	const connection = await worker.connected;
	const stopped = once(connection, "close");
	await run.until(({ stdout }) => stdout.includes("ready"));
	t.mock.timers.tick(1500);
	await run.until(({ stderr }) => stderr.includes("Node.js report completed"));
	t.mock.timers.tick(5000);
	assert.equal(await run.result, 1, run.output.stderr);
	await stopped;
});

void test(
	"a stalled run closes its output even when a detached worker retains the pipe",
	{ timeout: 10_000 },
	async (t) => {
		if (process.platform === "win32") {
			t.skip("Windows has no detached POSIX process groups");
			return;
		}
		const worker = await workerConnection(t);
		const detached = `const { spawn } = require('node:child_process');
const worker = spawn(process.execPath, ['-e', "const connection = require('node:net').connect(${worker.port}, '127.0.0.1'); connection.on('connect', () => console.log('ready')); connection.on('close', () => process.exit(0));"], { detached: true, stdio: 'inherit' });
worker.unref();`;
		const run = watched(t, process.execPath, ["-e", detached]);
		await worker.connected;
		await run.until(({ stdout }) => stdout.includes("ready"));
		t.mock.timers.tick(1500);
		t.mock.timers.tick(5000);
		assert.equal(await run.result, 1);
	},
);

void test("a frontend run that keeps printing may run longer than the stall limit", async (t) => {
	const worker = await workerConnection(t);
	const steady = `const connection = require('node:net').connect(${worker.port}, '127.0.0.1');
connection.on('connect', () => console.log('ready'));
let beats = 0;
connection.on('data', () => console.log('heartbeat ' + (++beats)));
connection.on('end', () => connection.end());`;
	const run = watched(t, process.execPath, ["-e", steady]);
	const connection = await worker.connected;
	await run.until(({ stdout }) => stdout.includes("ready"));
	for (const beat of [1, 2, 3]) {
		t.mock.timers.tick(1000);
		connection.write("heartbeat");
		await run.until(({ stdout }) => stdout.includes(`heartbeat ${beat}`));
	}
	connection.end();
	assert.equal(await run.result, 0, run.output.stderr);
});

void test("a frontend run that ends before stalling keeps its own exit code", async (t) => {
	const run = watched(t, process.execPath, ["-e", "process.exit(3)"]);
	assert.equal(await run.result, 3, run.output.stderr);
});

void test("a push of shipped code without a changeset stops before any check runs", async () => {
	const f = await fixture();
	try {
		const base = execFileSync("git", ["rev-parse", "HEAD"], {
			cwd: f.directory,
			encoding: "utf8",
		}).trim();
		await mkdir(path.join(f.directory, "server/application"), { recursive: true });
		await writeFile(
			path.join(f.directory, "server/application/integration-test-durations.json"),
			"{}\n",
		);
		f.git("add", ".");
		f.git("-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "test durations");
		// Test data that no image holds needs no release note, so the gates run.
		assert.doesNotMatch(f.run(f.directory, base).stderr, /carries no changeset/u);
		await rm(f.calls, { force: true });

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

void test("a migration fragment selects the changeset checks, and the first line names what escalated", () => {
	// Docs, tooling and a fragment: no file selects every scope.
	const docsDrift = [
		".migration/durable-compose-claim.md",
		"docs/admin/alert-rules.mdx",
		"scripts/check-alert-rules.ts",
		"scripts/check-docs-pages.test.ts",
	];
	assert.deepEqual(pushScopes([".migration/durable-compose-claim.md"]), ["changesets", "docs"]);
	assert.equal(pushScopeReason(docsDrift), "scopes agents, changesets, docs");
	assert.match(
		pushScopeReason([...docsDrift, "README.md"]),
		/^all scopes \(.*\), because README\.md selects them$/u,
	);
	assert.match(pushScopeReason(undefined), /because no merge base was found$/u);
});

void test("--keep-going stands beside --base in any order, and anything else is a usage error", () => {
	assert.deepEqual(parsePushArguments([]), { base: "origin/main", keepGoing: false });
	assert.deepEqual(parsePushArguments(["--keep-going"]), { base: "origin/main", keepGoing: true });
	assert.deepEqual(parsePushArguments(["--base", "HEAD~2", "--keep-going"]), {
		base: "HEAD~2",
		keepGoing: true,
	});
	assert.deepEqual(parsePushArguments(["--keep-going", "--base", "HEAD~2"]), {
		base: "HEAD~2",
		keepGoing: true,
	});
	assert.throws(() => parsePushArguments(["--keep-gong"]), /\[--keep-going\]/u);
});

/** Stands in for process.exit, so the test sees each exit as an error with its status. */
const exitStub = (status: number): never => {
	throw new Error(`exit ${status}`);
};

void test("the hook stops at the first failed check; --keep-going runs them all, lists each, and exits like the first", (t: TestContext) => {
	const errors = t.mock.method(console, "error", () => undefined);
	const failFast = pushFailures(false, exitStub);
	failFast.record("gate:prose", 0);
	assert.throws(() => failFast.record("gate:webapp", 3), { message: "exit 3" });

	const keepGoing = pushFailures(true, exitStub);
	keepGoing.record("gate:webapp", 3);
	keepGoing.record("gate:prose", 0);
	keepGoing.record("focused Node tests", 1);
	assert.throws(() => keepGoing.finish(), { message: "exit 3" });
	const summary = String(errors.mock.calls.at(-1)?.arguments[0]);
	assert.match(
		summary,
		/^2 push checks failed:\n- gate:webapp \(exit 3\)\n- focused Node tests \(exit 1\)$/u,
	);

	// Nothing failed, so the run goes on to say what it did.
	const passing = pushFailures(true, exitStub);
	passing.record("gate:prose", 0);
	assert.doesNotThrow(() => passing.finish());
});
