import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import {
	cpSync,
	existsSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";
import { pathToFileURL } from "node:url";

import { namesRepositoryHooks } from "./enable-hooks.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const SCRIPT = path.join(REPO_ROOT, "scripts", "enable-hooks.ts");
const temporaries: string[] = [];

function globalConfig(contents: string): string {
	const directory = mkdtempSync(path.join(tmpdir(), "enable-hooks-config-"));
	temporaries.push(directory);
	const file = path.join(directory, "gitconfig");
	writeFileSync(file, contents);
	return file;
}

// The install reads CI to decide whether to advise on signing; each test states its own answer.
function hookFreeEnv(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
	return environmentForGitFixture({ CI: undefined, ...overrides });
}

after(() => {
	for (const temporary of temporaries) {
		rmSync(temporary, { recursive: true, force: true });
	}
});

function clone(): { repository: string; git: (...args: string[]) => string } {
	const repository = mkdtempSync(path.join(tmpdir(), "enable-hooks-"));
	temporaries.push(repository);
	const git = (...args: string[]): string =>
		execFileSync("git", args, {
			cwd: repository,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			env: hookFreeEnv(),
		}).trim();
	git("init", "--quiet");
	cpSync(path.join(REPO_ROOT, ".vite-hooks"), path.join(repository, ".vite-hooks"), {
		recursive: true,
	});
	return { repository, git };
}

function install(directory: string, env: NodeJS.ProcessEnv = hookFreeEnv()) {
	const result = spawnSync("node", [SCRIPT], {
		cwd: directory,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env,
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	return result;
}

void test("an install points every worktree at the tracked hooks and is idempotent", () => {
	const { repository, git } = clone();
	install(repository);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks");
	install(repository);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks");
});

void test("an install replaces a dispatcher path, relative or another checkout's", () => {
	for (const dispatcher of [
		".vite-hooks/_",
		"/elsewhere/main-checkout/.vite-hooks/_",
		// One worktree's tracked hooks: they would run that worktree's `vp` in every other one.
		"/elsewhere/t3-d5d75adf/.vite-hooks",
	]) {
		const { repository, git } = clone();
		git("config", "core.hooksPath", dispatcher);
		install(repository);
		assert.equal(git("config", "core.hooksPath"), ".vite-hooks");
	}
});

void test("an install leaves a contributor's custom hooks directory intact", () => {
	const { repository, git } = clone();
	git("config", "core.hooksPath", ".custom-hooks");
	install(repository);
	assert.equal(git("config", "core.hooksPath"), ".custom-hooks");
});

void test("an install keeps a contributor's global hooks directory", () => {
	const { repository, git } = clone();
	const custom = mkdtempSync(path.join(tmpdir(), "enable-hooks-global-"));
	temporaries.push(custom);
	const env = hookFreeEnv({ GIT_CONFIG_GLOBAL: globalConfig(`[core]\n\thooksPath = ${custom}\n`) });
	const result = install(repository, env);
	assert.ok(!git("config", "--local", "--list").includes("core.hookspath="));
	assert.doesNotMatch(result.stderr, /Git runs no repository hook/u);
});

void test("disabled hooks stay disabled: the launcher runs nothing", () => {
	const { repository, git } = clone();
	git("config", "vp.hooks.disabled", "true");
	install(repository);
	const run = (env: NodeJS.ProcessEnv) =>
		// Git starts hooks through sh, also on Windows, where the file itself is not executable.
		spawnSync("sh", [path.join(repository, ".vite-hooks", "run"), "pre-push", "false"], {
			cwd: repository,
			encoding: "utf8",
			env,
		});
	assert.equal(run(hookFreeEnv()).status, 0);
	git("config", "--unset", "vp.hooks.disabled");
	assert.equal(run(hookFreeEnv({ VP_GIT_HOOKS: "0" })).status, 0);
	// Enabled again, with no install in this fixture: the hook refuses rather than skips.
	assert.equal(run(hookFreeEnv()).status, 1);
});

void test("a fresh worktree runs the repository's hooks without an install, and refuses loudly until it has one", () => {
	const { repository, git } = clone();
	git("add", "--all");
	git("config", "user.name", "Hooks Test");
	git("config", "user.email", "test@example.invalid");
	git("-c", "commit.gpgsign=false", "commit", "--quiet", "--message", "hooks");
	// What every clone had: the shared path names the dispatcher that only an install creates.
	git("config", "core.hooksPath", ".vite-hooks/_");
	const remote = mkdtempSync(path.join(tmpdir(), "enable-hooks-remote-"));
	temporaries.push(remote);
	execFileSync("git", ["init", "--quiet", "--bare", remote], { env: hookFreeEnv() });
	const worktree = path.join(mkdtempSync(path.join(tmpdir(), "enable-hooks-worktree-")), "fresh");
	temporaries.push(path.dirname(worktree));
	git("worktree", "add", "--quiet", "-b", "fresh", worktree);
	// No install in the new worktree: no dispatcher, no node_modules.
	assert.ok(!existsSync(path.join(worktree, "node_modules")));
	const push = () =>
		spawnSync("git", ["push", remote, "HEAD:refs/heads/fresh"], {
			cwd: worktree,
			encoding: "utf8",
			env: hookFreeEnv(),
		});
	const silent = push();
	assert.equal(silent.status, 0, "before: the push passes with no hook at all");
	assert.doesNotMatch(silent.stderr, /pre-push/u);

	install(repository);
	const refused = push();
	assert.notEqual(refused.status, 0, "a push without an install must not pass its hooks");
	assert.match(refused.stderr, /has no install, so its pre-push checks cannot run/u);

	// Once the worktree has its own `vp`, the hook runs it.
	const bin = path.join(worktree, "node_modules", ".bin");
	mkdirSync(bin, { recursive: true });
	const calls = path.join(worktree, "calls");
	writeFileSync(path.join(bin, "vp"), `#!/bin/sh\necho "$*" >> "${calls}"\n`, { mode: 0o755 });
	const pushed = push();
	assert.equal(pushed.status, 0, pushed.stderr);
	assert.equal(readFileSync(calls, "utf8").trim(), "run check:push");
});

void test("each worktree runs its own hooks, whichever worktree last ran an install", () => {
	const { git } = clone();
	git("add", "--all");
	git("config", "user.name", "Hooks Test");
	git("config", "user.email", "test@example.invalid");
	git("-c", "commit.gpgsign=false", "commit", "--quiet", "--message", "hooks");
	const parent = mkdtempSync(path.join(tmpdir(), "enable-hooks-worktrees-"));
	temporaries.push(parent);
	const calls = path.join(parent, "calls");
	const add = (name: string) => {
		const tree = path.join(parent, name);
		git("worktree", "add", "--quiet", "-b", name, tree);
		// Each worktree's own install: a `vp` that says which worktree it belongs to.
		const bin = path.join(tree, "node_modules", ".bin");
		mkdirSync(bin, { recursive: true });
		writeFileSync(path.join(bin, "vp"), `#!/bin/sh\necho ${name} >> "${calls}"\n`, { mode: 0o755 });
		return tree;
	};
	const first = add("first");
	const second = add("second");
	// What the clone had: the shared path names one worktree's hooks, and a per-worktree override
	// names another worktree's dispatcher, which does not exist.
	git("config", "core.hooksPath", path.join(first, ".vite-hooks"));
	git("config", "extensions.worktreeConfig", "true");
	execFileSync(
		"git",
		["config", "--worktree", "core.hooksPath", path.join(first, ".vite-hooks", "_")],
		{
			cwd: second,
			env: hookFreeEnv(),
		},
	);

	install(second);
	const fresh = add("fresh");
	const remote = mkdtempSync(path.join(tmpdir(), "enable-hooks-remote-"));
	temporaries.push(remote);
	execFileSync("git", ["init", "--quiet", "--bare", remote], { env: hookFreeEnv() });
	for (const tree of [first, second, fresh]) {
		const pushed = spawnSync("git", ["push", remote, `HEAD:refs/heads/${path.basename(tree)}`], {
			cwd: tree,
			encoding: "utf8",
			env: hookFreeEnv(),
		});
		assert.equal(pushed.status, 0, pushed.stderr);
	}
	assert.deepEqual(readFileSync(calls, "utf8").trim().split("\n"), ["first", "second", "fresh"]);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks");
});

void test("an install warns when this worktree's own hooks path has no hooks", () => {
	const { repository, git } = clone();
	git("config", "extensions.worktreeConfig", "true");
	git("config", "--worktree", "core.hooksPath", path.join(repository, "no-such-hooks"));
	assert.match(install(repository).stderr, /Git runs no repository hook in this worktree/u);
	// An override that names a dispatcher is this repository's own, so the install drops it.
	git("config", "--worktree", "core.hooksPath", path.join(repository, ".vite-hooks", "_"));
	assert.doesNotMatch(install(repository).stderr, /Git runs no repository hook/u);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks");
});

void test("check:affected warns in a clone whose hooks path is unset or missing", () => {
	const { repository, git } = clone();
	const warning = () =>
		execFileSync(
			"node",
			[
				"--input-type=module",
				"--eval",
				`import { missingHooksWarning } from ${JSON.stringify(pathToFileURL(SCRIPT).href)}; process.stdout.write(missingHooksWarning() ?? "");`,
			],
			{ cwd: repository, encoding: "utf8", env: hookFreeEnv() },
		);
	assert.match(warning(), /core\.hooksPath is not set\.\nRun `pnpm install/u);
	git("config", "core.hooksPath", ".vite-hooks/_");
	assert.match(warning(), /resolves to .*\.vite-hooks[\\/]_, which does not exist/u);
	git("config", "core.hooksPath", ".vite-hooks");
	assert.equal(warning(), "");
});

void test("only this repository's hooks count as such: relative, in a worktree, or in a removed checkout", () => {
	const checkout = mkdtempSync(path.join(tmpdir(), "enable-hooks-checkout-"));
	temporaries.push(checkout);
	const other = mkdtempSync(path.join(tmpdir(), "enable-hooks-other-project-"));
	temporaries.push(other);
	assert.ok(namesRepositoryHooks(".vite-hooks/_", []));
	assert.ok(namesRepositoryHooks(".vite-hooks", []));
	assert.ok(namesRepositoryHooks(path.join(checkout, ".vite-hooks"), [checkout]));
	assert.ok(namesRepositoryHooks(`${path.join(checkout, ".vite-hooks", "_")}/`, [checkout]));
	assert.ok(namesRepositoryHooks("/Users/someone/removed/t3-d5d75adf/.vite-hooks", []));
	assert.ok(namesRepositoryHooks(String.raw`C:\work\Hephaestus\.vite-hooks\_`, []));
	// Another project's hooks directory that happens to share the name stays the contributor's.
	assert.ok(!namesRepositoryHooks(path.join(other, ".vite-hooks"), [checkout]));
	assert.ok(!namesRepositoryHooks("tools/.vite-hooks", []));
	assert.ok(!namesRepositoryHooks("/Users/someone/.custom-hooks", []));
	assert.ok(!namesRepositoryHooks("/Users/someone/.vite-hooks-mine", []));
	assert.ok(!namesRepositoryHooks("", []));
});
void test("a source archive installs without Git configuration or signing advice", () => {
	const directory = mkdtempSync(path.join(tmpdir(), "enable-hooks-archive-"));
	temporaries.push(directory);
	const result = spawnSync("node", [SCRIPT], {
		cwd: directory,
		encoding: "utf8",
		env: hookFreeEnv(),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.equal(result.stderr, "");
});

void test("an install without commit signing configured warns and still succeeds", () => {
	const { repository } = clone();
	const result = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: hookFreeEnv(),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.match(result.stderr, /git config --global commit\.gpgsign true/u);
	assert.match(result.stderr, /gh ssh-key add .* --type signing/u);
});

void test("an install with commit signing configured says nothing about it", () => {
	const { repository } = clone();
	const signing = globalConfig(
		"[gpg]\n\tformat = ssh\n[user]\n\tsigningkey = ~/.ssh/id_ed25519.pub\n[commit]\n\tgpgsign = true\n",
	);
	const result = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: hookFreeEnv({ GIT_CONFIG_GLOBAL: signing }),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.doesNotMatch(result.stderr, /commit\.gpgsign/u);
});

void test("an install on a runner stays silent even with no commit signing configured", () => {
	const { repository, git } = clone();
	const result = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: hookFreeEnv({ CI: "true" }),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks");
	assert.doesNotMatch(result.stderr, /commit\.gpgsign/u);
});
