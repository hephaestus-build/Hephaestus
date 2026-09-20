import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";

const CHECKER = path.join(import.meta.dirname, "check-artifact-source-contract-immutability.ts");

const CONTRACTS = "server/application/src/main/resources/contracts/artifact-source";

type Git = (...args: string[]) => string;

/** A throwaway repo with one published contract version on `main`, and a branch checked out. */
function repoWithPublishedContract(): { repo: string; git: Git } {
	const repo = mkdtempSync(path.join(tmpdir(), "contract-immutability-"));
	const git: Git = (...args) =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			stdio: ["ignore", "pipe", "pipe"],
			env: environmentForGitFixture(),
		});
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	mkdirSync(path.join(repo, CONTRACTS, "1.0.0"), { recursive: true });
	writeFileSync(path.join(repo, CONTRACTS, "1.0.0", "catalog.json"), '{"version":"1.0.0"}\n');
	git("add", "-A");
	git("commit", "--quiet", "-m", "publish 1.0.0");
	git("checkout", "--quiet", "-b", "feature");
	return { repo, git };
}

function runChecker(repo: string): string {
	// Two of these tests expect the checker to throw; without capturing stderr its stack trace
	// prints on every successful run.
	return execFileSync(process.execPath, [CHECKER], {
		cwd: repo,
		encoding: "utf8",
		stdio: ["ignore", "pipe", "pipe"],
		env: environmentForGitFixture({ CONTRACT_BASE_REF: "main", GITHUB_BASE_REF: "" }),
	});
}

await test("passes and says what it verified when published contracts are untouched", (t) => {
	const { repo, git } = repoWithPublishedContract();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(path.join(repo, "unrelated.txt"), "change something else\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "unrelated change");

	assert.match(runChecker(repo), /1 published version\(s\) unchanged/u);
});

await test("fails when a published contract file is edited", (t) => {
	const { repo, git } = repoWithPublishedContract();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(
		path.join(repo, CONTRACTS, "1.0.0", "catalog.json"),
		'{"version":"1.0.0","sneaked":true}\n',
	);
	git("add", "-A");
	git("commit", "--quiet", "-m", "mutate published contract");

	assert.throws(() => runChecker(repo), /1\.0\.0 is immutable/u);
});

await test("fails when a published contract version is deleted", (t) => {
	const { repo, git } = repoWithPublishedContract();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	git("rm", "--quiet", "-r", path.join(CONTRACTS, "1.0.0"));
	git("commit", "--quiet", "-m", "delete published contract");

	assert.throws(() => runChecker(repo), /1\.0\.0 is immutable/u);
});

await test("adding a new version alongside a published one is allowed", (t) => {
	const { repo, git } = repoWithPublishedContract();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	mkdirSync(path.join(repo, CONTRACTS, "1.1.0"), { recursive: true });
	writeFileSync(path.join(repo, CONTRACTS, "1.1.0", "catalog.json"), '{"version":"1.1.0"}\n');
	git("add", "-A");
	git("commit", "--quiet", "-m", "publish 1.1.0");

	assert.match(runChecker(repo), /1 published version\(s\) unchanged/u);
});

await test("reports honestly when nothing is published at the merge base", (t) => {
	const repo = mkdtempSync(path.join(tmpdir(), "contract-immutability-empty-"));
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	const git: Git = (...args) =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			stdio: ["ignore", "pipe", "pipe"],
			env: environmentForGitFixture(),
		});
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	writeFileSync(path.join(repo, "readme.md"), "no contracts yet\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "initial");
	git("checkout", "--quiet", "-b", "feature");
	mkdirSync(path.join(repo, CONTRACTS, "1.0.0"), { recursive: true });
	writeFileSync(path.join(repo, CONTRACTS, "1.0.0", "catalog.json"), '{"version":"1.0.0"}\n');
	git("add", "-A");
	git("commit", "--quiet", "-m", "introduce the contract");

	// The introducing PR has nothing to protect yet, and the check must say so rather than print
	// nothing — silence is what let it pass vacuously from the wrong directory.
	assert.match(runChecker(repo), /no version is published/u);
});

await test("runs from a subdirectory", (t) => {
	const { repo, git } = repoWithPublishedContract();
	t.after(() => rmSync(repo, { recursive: true, force: true }));
	writeFileSync(
		path.join(repo, CONTRACTS, "1.0.0", "catalog.json"),
		'{"version":"1.0.0","sneaked":true}\n',
	);
	git("add", "-A");
	git("commit", "--quiet", "-m", "mutate published contract");

	assert.throws(() => runChecker(path.join(repo, "server")), /1\.0\.0 is immutable/u);
});
