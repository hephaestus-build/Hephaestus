import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import {
	hasSchemaMigrations,
	planRelease,
	type ReleaseRef,
	releaseOutputs,
} from "./plan-release.ts";

const SHA = "1111111111111111111111111111111111111111";
const OTHER_SHA = "2222222222222222222222222222222222222222";

const published = (tag: string): ReleaseRef => ({
	isDraft: false,
	isPrerelease: false,
	tag,
	targetCommitish: "main",
});

const draft = (tag: string, targetCommitish: string): ReleaseRef => ({
	isDraft: true,
	isPrerelease: false,
	tag,
	targetCommitish,
});

/** What main looks like today: everything up to 0.74.0 released, 0.75.0 never published. */
const RELEASED = [published("v0.73.2"), published("v0.74.0")];

void test("cuts nothing on an ordinary feature merge", () => {
	// The non-deferrable case: every push to main carries a version that is already published, and a
	// planner that cut one here would cut a release on every merge.
	const plan = planRelease(SHA, "0.74.0", RELEASED);
	assert.equal(plan.kind, "skip");
	assert.match(plan.reason, /v0\.74\.0 is already published/u);
	assert.deepEqual(releaseOutputs(plan), { released: "false" });
});

void test("cuts the version the Version PR merge introduced", () => {
	const plan = planRelease(SHA, "0.75.0", RELEASED);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
	assert.equal(plan.resumesDraft, false);
	assert.deepEqual(releaseOutputs(plan, true), {
		major: "0",
		migrations: "true",
		minor: "75",
		previous_version: "0.74.0",
		released: "true",
		sha: SHA,
		tag_name: "v0.75.0",
		version: "0.75.0",
	});
});

void test("re-cuts an unpublished version from a later commit that did not change it", () => {
	// The wedge this exists to remove: the version commit is consumed, the fix lands on top, and the
	// same version cuts again from the commit that carries it.
	const plan = planRelease(OTHER_SHA, "0.75.0", RELEASED);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.sha, OTHER_SHA);
	assert.equal(plan.tag, "v0.75.0");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("follows the latest published release, not the version the parent commit carried", () => {
	// On a re-cut the parent carries the same unpublished version; comparing against it would refuse
	// the retry. 0.75.0 having failed, 0.75.1 follows 0.74.0.
	const plan = planRelease(SHA, "0.75.1", RELEASED);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("resumes a draft that targets this commit", () => {
	const plan = planRelease(SHA, "0.75.0", [...RELEASED, draft("v0.75.0", SHA)]);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.resumesDraft, true);
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("refuses a draft that targets another commit", () => {
	const plan = planRelease(SHA, "0.75.0", [...RELEASED, draft("v0.75.0", OTHER_SHA)]);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /delete the draft to re-cut v0\.75\.0 here/u);
});

void test("cuts nothing once the release publishes, whatever it targets", () => {
	// A re-run at the same commit after publication, and every merge after it.
	const plan = planRelease(SHA, "0.75.0", [
		...RELEASED,
		{ isDraft: false, isPrerelease: false, tag: "v0.75.0", targetCommitish: OTHER_SHA },
	]);
	assert.equal(plan.kind, "skip");
});

void test("refuses a version that is not newer than the latest published release", () => {
	// A rollback of package.json cannot promote X.Y and latest back onto older code.
	const plan = planRelease(SHA, "0.73.5", RELEASED);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /not newer than the latest published release v0\.74\.0/u);
});

void test("refuses when nothing published exists to follow", () => {
	const plan = planRelease(SHA, "0.75.0", [draft("v0.75.0", SHA)]);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /no published release to follow/u);
});

void test("refuses a version that is not a released version shape", () => {
	const plan = planRelease(SHA, "0.75.0-rc.1", RELEASED);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /is not major\.minor\.patch/u);
});

void test("ignores prereleases and drafts when picking the release to follow", () => {
	const plan = planRelease(SHA, "0.75.0", [
		published("v0.74.0"),
		{ isDraft: false, isPrerelease: true, tag: "v0.74.1-rc.1", targetCommitish: "main" },
		draft("v0.74.2", "main"),
		published("not-a-version"),
	]);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("orders the published releases by version, not by listing order", () => {
	const plan = planRelease(SHA, "0.75.0", [
		published("v0.9.1"),
		published("v0.74.0"),
		published("v0.10.6"),
	]);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
});

const repositories: string[] = [];

after(() => {
	for (const repo of repositories) {
		rmSync(repo, { recursive: true, force: true });
	}
});

void test("reads schema migrations from the diff between the two releases", async () => {
	const repo = mkdtempSync(path.join(tmpdir(), "plan-release-"));
	repositories.push(repo);
	const git = (...args: string[]): string =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			env: environmentForGitFixture(),
			stdio: ["ignore", "pipe", "pipe"],
		}).trim();
	const changelog = "server/application/src/main/resources/db/changelog";
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	mkdirSync(path.join(repo, changelog), { recursive: true });
	writeFileSync(path.join(repo, changelog, "master.xml"), "<databaseChangeLog/>\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "release");
	git("tag", "v0.74.0");
	writeFileSync(path.join(repo, "README.md"), "# Nothing schema-shaped\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "docs");
	// A git hook exports GIT_DIR at the repository being pushed. Leaking it here would read that
	// repository's history instead of this one's, and git fails outright when it names nothing.
	process.env.GIT_DIR = path.join(repo, "not-a-git-directory");
	try {
		assert.equal(await hasSchemaMigrations("v0.74.0", "HEAD", repo), false);
	} finally {
		delete process.env.GIT_DIR;
	}

	writeFileSync(path.join(repo, changelog, "0.75.0.xml"), "<databaseChangeLog/>\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "feat: a migration");
	assert.equal(await hasSchemaMigrations("v0.74.0", "HEAD", repo), true);
	// A git failure is an error, never a verdict: neither true nor false is stamped by accident.
	await assert.rejects(hasSchemaMigrations("v0.99.0", "HEAD", repo));
});
