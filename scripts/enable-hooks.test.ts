import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { namesAnotherDispatcher } from "./enable-hooks.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES, repositoryCli } from "./lib/process.ts";

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

void test("an install enables the dispatcher and is idempotent", () => {
	const { repository, git } = clone();
	const result = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		maxBuffer: CAPTURE_LIMIT_BYTES,
		env: hookFreeEnv(),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks/_");
	const again = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		env: hookFreeEnv(),
	});
	assert.equal(again.status, 0, `${again.stdout}${again.stderr}`);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks/_");
});

void test("an install preserves disabled hooks until explicitly re-enabled", () => {
	const { repository, git } = clone();
	const run = (...args: string[]) => {
		const result = spawnSync(process.execPath, [repositoryCli(), ...args], {
			cwd: repository,
			encoding: "utf8",
			env: hookFreeEnv(),
		});
		assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	};
	run("hooks", "enable");
	run("hooks", "disable");
	const install = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		env: hookFreeEnv(),
	});
	assert.equal(install.status, 0, `${install.stdout}${install.stderr}`);
	assert.equal(git("config", "--type=bool", "vp.hooks.disabled"), "true");
	assert.ok(!git("config", "--local", "--list").includes("core.hookspath="));
	run("hooks", "enable");
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks/_");
});

void test("an install leaves a contributor's custom hooks directory intact", () => {
	const { repository, git } = clone();
	git("config", "core.hooksPath", ".custom-hooks");
	const result = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		env: hookFreeEnv(),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.equal(git("config", "core.hooksPath"), ".custom-hooks");
});

void test("an install replaces an absolute dispatcher path with the one each worktree resolves", () => {
	const { repository, git } = clone();
	git("config", "core.hooksPath", "/elsewhere/main-checkout/.vite-hooks/_");
	const result = spawnSync("node", [SCRIPT], {
		cwd: repository,
		encoding: "utf8",
		env: hookFreeEnv(),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks/_");
});

function installIn(directory: string): void {
	const result = spawnSync("node", [SCRIPT], {
		cwd: directory,
		encoding: "utf8",
		env: hookFreeEnv(),
	});
	assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
}

void test("an install keeps hooks disabled when it drops an absolute dispatcher path", () => {
	const { repository, git } = clone();
	git("config", "vp.hooks.disabled", "true");
	git("config", "core.hooksPath", "/elsewhere/main-checkout/.vite-hooks/_");
	installIn(repository);
	assert.ok(!git("config", "--local", "--list").includes("core.hookspath="));
	assert.equal(git("config", "--type=bool", "vp.hooks.disabled"), "true");
});

void test("every worktree's install creates the dispatcher that its own hooks run through", () => {
	const { repository, git } = clone();
	git("add", "--all");
	git("config", "user.name", "Hooks Test");
	git("config", "user.email", "test@example.invalid");
	git("-c", "commit.gpgsign=false", "commit", "--quiet", "--message", "hooks");
	installIn(repository);
	const worktree = path.join(mkdtempSync(path.join(tmpdir(), "enable-hooks-worktree-")), "second");
	temporaries.push(path.dirname(worktree));
	git("worktree", "add", "--quiet", worktree);
	// Before its own install, the second worktree has no dispatcher, so Git runs no hook there.
	assert.ok(!existsSync(path.join(worktree, ".vite-hooks", "_", "h")));
	installIn(worktree);
	assert.ok(existsSync(path.join(worktree, ".vite-hooks", "_", "h")));
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks/_");
});

void test("only an absolute path to a dispatcher counts as another checkout's", () => {
	assert.ok(namesAnotherDispatcher("/Users/someone/Hephaestus/.vite-hooks/_"));
	assert.ok(namesAnotherDispatcher(String.raw`C:\work\Hephaestus\.vite-hooks\_`));
	assert.ok(!namesAnotherDispatcher(".vite-hooks/_"));
	assert.ok(!namesAnotherDispatcher("/Users/someone/.custom-hooks"));
	assert.ok(!namesAnotherDispatcher(""));
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
	assert.equal(git("config", "core.hooksPath"), ".vite-hooks/_");
	assert.doesNotMatch(result.stderr, /commit\.gpgsign/u);
});
