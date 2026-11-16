/**
 * The repository being pushed has to survive the gate the push runs. Git hands every hook the
 * location of that repository, and anything below the hook that lets those variables through
 * addresses it instead of the throwaway repository it built for itself — `git config`, `git commit`
 * and `git tag` have all landed on a contributor's checkout that way.
 *
 * Three things keep them apart and all are asserted here: the hook drops the variables before it
 * starts the gate, every repository test that runs `git` builds its environment in one place, and
 * that environment stops a `git` outside its fixture from finding the checkout that holds the
 * temporary directory.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import {
	chmodSync,
	cpSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";
import { pathToFileURL } from "node:url";

import { environmentForGitFixture, GIT_REPOSITORY_VARIABLES } from "./lib/git-environment.ts";

const REPO_ROOT = path.resolve(import.meta.dirname, "..");
const HOOKS = path.join(REPO_ROOT, ".vite-hooks");
const SCRIPTS = path.join(REPO_ROOT, "scripts");

const temporaries: string[] = [];

after(() => {
	for (const temporary of temporaries) {
		rmSync(temporary, { recursive: true, force: true });
	}
});

function temporaryDirectory(prefix: string): string {
	const directory = mkdtempSync(path.join(tmpdir(), prefix));
	temporaries.push(directory);
	return directory;
}

/** The hooks the dispatcher runs that start a repository task, which is what carries the risk. */
function gateHooks(): string[] {
	return readdirSync(HOOKS, { withFileTypes: true })
		.filter((entry) => entry.isFile())
		.map((entry) => entry.name)
		.filter((name) => readFileSync(path.join(HOOKS, name), "utf8").includes("vp run"));
}

void test("the hook drops every repository variable before it starts the gate", () => {
	const hooks = gateHooks();
	assert.deepEqual(hooks, ["pre-push"]);
	for (const hook of hooks) {
		const source = readFileSync(path.join(HOOKS, hook), "utf8");
		const unset = source.split("\n").find((line) => line.startsWith("unset "));
		assert.ok(
			unset !== undefined,
			`.vite-hooks/${hook} must unset the variables git exports to it`,
		);
		assert.deepEqual(
			unset.split(/\s+/u).slice(1).toSorted(),
			[...GIT_REPOSITORY_VARIABLES],
			`.vite-hooks/${hook} must unset exactly the variables lib/git-environment.ts names`,
		);
		assert.ok(
			source.indexOf(unset) < source.indexOf("vp run"),
			`.vite-hooks/${hook} must unset them before it runs the gate`,
		);
	}
});

const git = (cwd: string, ...args: string[]): string =>
	execFileSync("git", args, { cwd, encoding: "utf8", env: environmentForGitFixture() }).trim();

void test("a gate that runs git the wrong way cannot reach the repository being pushed", () => {
	const fingerprint = (repository: string): string[] => [
		git(repository, "show-ref"),
		git(repository, "tag", "--list"),
		git(repository, "worktree", "list"),
		readFileSync(path.join(repository, ".git", "config"), "utf8"),
	];

	const decoy = temporaryDirectory("git-environment-decoy-");
	git(decoy, "init", "--quiet", "--initial-branch=main");
	git(decoy, "config", "user.email", "decoy@example.invalid");
	git(decoy, "config", "user.name", "Decoy");
	writeFileSync(path.join(decoy, "tracked"), "decoy\n");
	git(decoy, "add", "-A");
	git(decoy, "commit", "--quiet", "-m", "decoy");
	const before = fingerprint(decoy);
	const intruder = `intruder-${randomUUID()}`;

	// Stands in for `vp run check`: a task that runs git in whatever environment it was handed, and
	// records that it ran so a hook that never reached it cannot pass this test. The hooks run the `vp`
	// of their own checkout, so the stub is that checkout's install.
	const checkout = temporaryDirectory("git-environment-hooks-");
	cpSync(HOOKS, path.join(checkout, ".vite-hooks"), { recursive: true });
	const bin = path.join(checkout, "node_modules", ".bin");
	mkdirSync(bin, { recursive: true });
	const marker = path.join(checkout, "ran");
	const stub = path.join(bin, "vp");
	writeFileSync(
		stub,
		`#!/bin/sh\nprintf ran > "${marker}"\ngit config user.name ${intruder}\ngit tag ${intruder}\nexit 0\n`,
	);
	chmodSync(stub, 0o755);

	const environment = environmentForGitFixture({
		GIT_DIR: path.join(decoy, ".git"),
		GIT_INDEX_FILE: path.join(decoy, ".git", "index"),
		GIT_WORK_TREE: decoy,
	});
	// Git passes the remote's name and URL; the hook runs its checks only for GitHub.
	execFileSync(
		"sh",
		[
			"-e",
			path.join(checkout, ".vite-hooks", "pre-push"),
			"origin",
			"https://github.com/hephaestus-build/Hephaestus",
		],
		{
			cwd: temporaryDirectory("git-environment-worktree-"),
			encoding: "utf8",
			env: environment,
			stdio: ["ignore", "pipe", "pipe"],
		},
	);

	assert.equal(readFileSync(marker, "utf8"), "ran");
	assert.deepEqual(fingerprint(decoy), before);
	// The checkout this test runs in is the other repository the gate could have reached.
	assert.ok(!git(REPO_ROOT, "config", "--list").includes(intruder));
	assert.equal(git(REPO_ROOT, "tag", "--list", intruder), "");
});

void test("a fixture's git stops at the temporary directory, even inside a checkout", () => {
	const enclosing = temporaryDirectory("git-environment-enclosing-");
	git(enclosing, "init", "--quiet", "--initial-branch=main");
	const temporary = path.join(enclosing, "tmp");
	mkdirSync(temporary);
	const configuration = path.join(enclosing, ".git", "config");
	const before = readFileSync(configuration, "utf8");

	// The temporary directory is read when the process starts, so a child process takes the one
	// inside the checkout and runs git outside any fixture, as the gate above does.
	const library = pathToFileURL(path.join(SCRIPTS, "lib", "git-environment.ts")).href;
	const script = [
		'import { spawnSync } from "node:child_process";',
		'import { mkdtempSync } from "node:fs";',
		'import { tmpdir } from "node:os";',
		'import path from "node:path";',
		`import { environmentForGitFixture } from ${JSON.stringify(library)};`,
		'const cwd = mkdtempSync(path.join(tmpdir(), "outside-"));',
		'const result = spawnSync("git", ["config", "user.name", "intruder"], { cwd, env: environmentForGitFixture() });',
		"process.stdout.write(String(result.status));",
	].join("\n");
	const status = execFileSync(process.execPath, ["--input-type=module", "--eval", script], {
		encoding: "utf8",
		env: environmentForGitFixture({ TEMP: temporary, TMP: temporary, TMPDIR: temporary }),
	});

	assert.notEqual(status, "0", "git found a repository outside the temporary directory");
	assert.equal(readFileSync(configuration, "utf8"), before);
});

void test("a repository test that runs git builds its environment in one place", () => {
	const spawnsGit = /(?:execFile|execFileAsync|execFileSync|spawn|spawnSync|run|output)\(\s*"git"/u;
	// The naive form: an environment derived from this process, which under a hook names the
	// repository being pushed.
	const derivesFromProcess = /\.\.\.process\.env|process\.env\s*\)/u;
	const tests = readdirSync(SCRIPTS).filter((name) => name.endsWith(".test.ts"));
	const running = tests.filter((name) =>
		spawnsGit.test(readFileSync(path.join(SCRIPTS, name), "utf8")),
	);
	assert.ok(running.includes("reconcile-deployment.test.ts"));
	assert.ok(running.includes("check-java-nullness.test.ts"));
	for (const name of running) {
		const source = readFileSync(path.join(SCRIPTS, name), "utf8");
		assert.match(
			source,
			/from "\.\/lib\/git-environment\.ts"/u,
			`scripts/${name} runs git, so it takes its environment from lib/git-environment.ts`,
		);
		assert.doesNotMatch(
			source,
			derivesFromProcess,
			`scripts/${name} must build its git environment with environmentForGitFixture()`,
		);
	}
});
