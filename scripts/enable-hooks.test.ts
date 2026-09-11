import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { cpSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { after, test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { CAPTURE_LIMIT_BYTES, repositoryCli } from "./lib/process.ts";

const REPO_ROOT = resolve(import.meta.dirname, "..");
const SCRIPT = join(REPO_ROOT, "scripts", "enable-hooks.ts");
const temporaries: string[] = [];

function globalConfig(contents: string): string {
	const directory = mkdtempSync(join(tmpdir(), "enable-hooks-config-"));
	temporaries.push(directory);
	const path = join(directory, "gitconfig");
	writeFileSync(path, contents);
	return path;
}

// The install reads CI to decide whether to advise on signing; each test states its own answer.
function hookFreeEnv(overrides: NodeJS.ProcessEnv = {}): NodeJS.ProcessEnv {
	return environmentForGitFixture({ CI: undefined, ...overrides });
}

after(() => {
	for (const temporary of temporaries) rmSync(temporary, { recursive: true, force: true });
});

function clone(): { repository: string; git: (...args: string[]) => string } {
	const repository = mkdtempSync(join(tmpdir(), "enable-hooks-"));
	temporaries.push(repository);
	const git = (...args: string[]): string =>
		execFileSync("git", args, {
			cwd: repository,
			encoding: "utf8",
			maxBuffer: CAPTURE_LIMIT_BYTES,
			env: hookFreeEnv(),
		}).trim();
	git("init", "--quiet");
	cpSync(join(REPO_ROOT, ".vite-hooks"), join(repository, ".vite-hooks"), { recursive: true });
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

void test("a source archive installs without Git configuration or signing advice", () => {
	const directory = mkdtempSync(join(tmpdir(), "enable-hooks-archive-"));
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
	assert.match(result.stderr, /git config --global commit\.gpgsign true/);
	assert.match(result.stderr, /gh ssh-key add .* --type signing/);
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
	assert.doesNotMatch(result.stderr, /commit\.gpgsign/);
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
	assert.doesNotMatch(result.stderr, /commit\.gpgsign/);
});
