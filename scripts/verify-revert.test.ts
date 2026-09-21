import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import { verifyRevert } from "./verify-revert.ts";

const SCRIPT = path.join(import.meta.dirname, "verify-revert.ts");

type Git = (...args: string[]) => string;

const repositories: string[] = [];

after(() => {
	for (const repo of repositories) {
		rmSync(repo, { recursive: true, force: true });
	}
});

/** A repository whose `main` carries a release-shaped commit, with a branch checked out from it. */
function fixture(): { repo: string; git: Git; write: (file: string, content: string) => void } {
	const repo = mkdtempSync(path.join(tmpdir(), "verify-revert-"));
	repositories.push(repo);
	const git: Git = (...args) =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			stdio: ["ignore", "pipe", "pipe"],
			env: environmentForGitFixture(),
		}).trim();
	const write = (file: string, content: string): void =>
		writeFileSync(path.join(repo, file), content);
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	// Byte-exact content is asserted below, whatever this host's line-ending conversion is.
	git("config", "core.autocrlf", "false");
	write("package.json", '{"version":"0.74.0"}\n');
	write("MIGRATION.md", "# Migration\n");
	write("changeset.md", "---\nhephaestus: minor\n---\n\nA pending note.\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "base");
	return { repo, git, write };
}

/** The shape the release reverts have: bump the version, stamp MIGRATION.md, consume changesets. */
function versionCommit(git: Git, write: (file: string, content: string) => void): string {
	write("package.json", '{"version":"0.75.0"}\n');
	write("MIGRATION.md", "# Migration\n\n## 0.75.0\n\nDo the thing.\n");
	git("rm", "--quiet", "changeset.md");
	git("add", "-A");
	git("commit", "--quiet", "-m", "chore(release): version packages");
	return git("rev-parse", "HEAD");
}

void test("verifies a git revert of a commit already on the base", () => {
	const { repo, git, write } = fixture();
	const released = versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	git("revert", "--no-edit", released);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, true);
	assert.deepEqual(
		verdict.commits.map((entry) => entry.reverted),
		[released],
	);
	// The revert restores exactly what the guard freezes.
	assert.equal(readFileSync(path.join(repo, "MIGRATION.md"), "utf8"), "# Migration\n");
});

void test("verifies a revert taken after unrelated work landed on the base", () => {
	const { repo, git, write } = fixture();
	const released = versionCommit(git, write);
	write("feature.ts", "export const feature = true;\n");
	write("changeset-later.md", "---\nhephaestus: patch\n---\n\nA later note.\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "feat: land more work");
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	git("revert", "--no-edit", released);

	assert.equal(verifyRevert(base, "HEAD", repo).verified, true);
	// The changesets that landed after the release are untouched by the revert.
	assert.equal(
		readFileSync(path.join(repo, "changeset-later.md"), "utf8").includes("later note"),
		true,
	);
});

void test("rejects a claimed revert that smuggles an extra change", () => {
	const { repo, git, write } = fixture();
	const released = versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	git("revert", "--no-edit", "--no-commit", released);
	write("backdoor.ts", "export const shipped = true;\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", `revert with a rider\n\nThis reverts commit ${released}.`);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /not the exact inverse/u);
});

void test("rejects a revert commit paired with an unrelated commit", () => {
	const { repo, git, write } = fixture();
	const released = versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	git("revert", "--no-edit", released);
	write("backdoor.ts", "export const shipped = true;\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "feat: ship something under cover of a revert");

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /does not record exactly one/u);
});

void test("rejects a trailer that names a commit which is not on the base", () => {
	const { repo, git, write } = fixture();
	versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	write("private.ts", "export const value = 1;\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "chore: a commit only this branch has");
	const branchOnly = git("rev-parse", "HEAD");
	git("revert", "--no-edit", branchOnly);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /is not an ancestor of the base/u);
});

void test("rejects a fabricated trailer naming an unknown commit", () => {
	const { repo, git, write } = fixture();
	versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	write("MIGRATION.md", "# Migration\n");
	git("add", "-A");
	const unknown = "0".repeat(40);
	git("commit", "--quiet", "-m", `revert: restore MIGRATION.md\n\nThis reverts commit ${unknown}.`);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /reverts unknown commit/u);
});

void test("rejects a revert whose title says revert but whose body does not", () => {
	const { repo, git, write } = fixture();
	versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	write("MIGRATION.md", "# Migration\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "revert(release): defer the release");

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /does not record exactly one/u);
});

void test("rejects a binary payload swapped under a revert trailer", () => {
	const { repo, git } = fixture();
	writeFileSync(path.join(repo, "logo.png"), Buffer.from([0, 1, 2, 65]));
	git("add", "-A");
	git("commit", "--quiet", "-m", "add a binary");
	writeFileSync(path.join(repo, "logo.png"), Buffer.from([0, 1, 2, 66, 66]));
	git("add", "-A");
	git("commit", "--quiet", "-m", "change the binary");
	const released = git("rev-parse", "HEAD");
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/binary");
	writeFileSync(path.join(repo, "logo.png"), Buffer.from([9, 9, 9, 67, 67, 67]));
	git("add", "-A");
	git("commit", "--quiet", "-m", `revert the binary\n\nThis reverts commit ${released}.`);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /not the exact inverse/u);
});

void test("rejects a branch that adds nothing over the base", () => {
	const { repo, git, write } = fixture();
	versionCommit(git, write);
	const base = git("rev-parse", "HEAD");

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /adds no commit/u);
});

void test("rejects a revert of a merge commit", () => {
	const { repo, git, write } = fixture();
	git("checkout", "--quiet", "-b", "side");
	write("side.ts", "export const side = true;\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "feat: side");
	git("checkout", "--quiet", "main");
	versionCommit(git, write);
	git("merge", "--quiet", "--no-ff", "-m", "merge side", "side");
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/merge");
	// `git revert -m 1` writes "…, reversing changes made to <sha>." instead, which the trailer
	// pattern already refuses; this is the hand-written form that reaches the parent check.
	git("revert", "--no-edit", "-m", "1", base);
	git("commit", "--quiet", "--amend", "-m", `revert the merge\n\nThis reverts commit ${base}.`);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /which has 2 parents/u);
});

void test("rejects the trailer git writes for a reverted merge", () => {
	const { repo, git, write } = fixture();
	git("checkout", "--quiet", "-b", "side");
	write("side.ts", "export const side = true;\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "feat: side");
	git("checkout", "--quiet", "main");
	versionCommit(git, write);
	git("merge", "--quiet", "--no-ff", "-m", "merge side", "side");
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/merge");
	git("revert", "--no-edit", "-m", "1", base);

	const verdict = verifyRevert(base, "HEAD", repo);
	assert.equal(verdict.verified, false);
	assert.match(verdict.reason, /does not record exactly one/u);
});

void test("reports the verdict to GITHUB_OUTPUT", () => {
	const { repo, git, write } = fixture();
	const released = versionCommit(git, write);
	const base = git("rev-parse", "HEAD");
	git("checkout", "--quiet", "-b", "revert/version");
	git("revert", "--no-edit", released);
	const output = path.join(repo, "github-output");
	writeFileSync(output, "");

	const stdout = execFileSync(process.execPath, [SCRIPT, base, "HEAD"], {
		cwd: repo,
		encoding: "utf8",
		env: environmentForGitFixture({ GITHUB_OUTPUT: output }),
	});
	assert.match(stdout, /::notice::Verified revert of/u);
	assert.equal(readFileSync(output, "utf8"), "verified-revert=true\n");

	git("reset", "--quiet", "--hard", base);
	const denied = execFileSync(process.execPath, [SCRIPT, base, "HEAD"], {
		cwd: repo,
		encoding: "utf8",
		env: environmentForGitFixture({ GITHUB_OUTPUT: output }),
	});
	assert.match(denied, /Not a verified revert/u);
	assert.equal(readFileSync(output, "utf8"), "verified-revert=true\nverified-revert=false\n");
});
