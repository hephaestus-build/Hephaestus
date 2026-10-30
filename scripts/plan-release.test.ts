import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import {
	assertCurrentStableRelease,
	hasPendingChangesets,
	hasSchemaMigrations,
	planRelease,
	type ReleaseRef,
	releaseOutputs,
	releaseSourceState,
	renderReleaseNotes,
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
	const plan = planRelease(SHA, "0.74.0", RELEASED, true);
	assert.equal(plan.kind, "skip");
	assert.match(plan.reason, /v0\.74\.0 is already published/u);
	assert.deepEqual(releaseOutputs(plan), { released: "false" });
});

void test("cuts the version the Version PR merge introduced", () => {
	const plan = planRelease(SHA, "0.75.0", RELEASED, false);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
	assert.equal(plan.resumesDraft, false);
	assert.deepEqual(releaseOutputs(plan, true), {
		candidate: "",
		prerelease: "false",
		notes_version: "0.75.0",
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

void test("re-cuts an unpublished version from a later commit without pending changesets", () => {
	// A source with no new release notes can retry an unpublished version.
	const plan = planRelease(OTHER_SHA, "0.75.0", RELEASED, false);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.sha, OTHER_SHA);
	assert.equal(plan.tag, "v0.75.0");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("follows the latest published release, not the version the parent commit carried", () => {
	// On a re-cut the parent carries the same unpublished version; comparing against it would refuse
	// the retry. 0.75.0 having failed, 0.75.1 follows 0.74.0.
	const plan = planRelease(SHA, "0.75.1", RELEASED, false);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("resumes a draft that targets this commit", () => {
	const plan = planRelease(SHA, "0.75.0", [...RELEASED, draft("v0.75.0", SHA)], false);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.resumesDraft, true);
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("refuses a draft that targets another commit", () => {
	const plan = planRelease(SHA, "0.75.0", [...RELEASED, draft("v0.75.0", OTHER_SHA)], false);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /delete the draft to re-cut v0\.75\.0 here/u);
});

void test("cuts nothing once the release publishes, whatever it targets", () => {
	// A re-run at the same commit after publication, and every merge after it.
	const plan = planRelease(
		SHA,
		"0.75.0",
		[
			...RELEASED,
			{ isDraft: false, isPrerelease: false, tag: "v0.75.0", targetCommitish: OTHER_SHA },
		],
		false,
	);
	assert.equal(plan.kind, "skip");
});

void test("refuses a version that is not newer than the latest published release", () => {
	// A rollback of package.json cannot promote X.Y and latest back onto older code.
	const plan = planRelease(SHA, "0.73.5", RELEASED, false);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /not newer than the latest published release v0\.74\.0/u);
});

void test("refuses when nothing published exists to follow", () => {
	const plan = planRelease(SHA, "0.75.0", [draft("v0.75.0", SHA)], false);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /no published release to follow/u);
});

void test("refuses a version that is not a released version shape", () => {
	const plan = planRelease(SHA, "0.75.0-beta.1", RELEASED, false);
	assert.equal(plan.kind, "refuse");
	assert.match(plan.reason, /is not major\.minor\.patch/u);
});

void test("ignores prereleases and drafts when picking the release to follow", () => {
	const plan = planRelease(
		SHA,
		"0.75.0",
		[
			published("v0.74.0"),
			{ isDraft: false, isPrerelease: true, tag: "v0.74.1-rc.1", targetCommitish: "main" },
			draft("v0.74.2", "main"),
			published("not-a-version"),
		],
		false,
	);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("orders the published releases by version, not by listing order", () => {
	const plan = planRelease(
		SHA,
		"0.75.0",
		[published("v0.9.1"), published("v0.74.0"), published("v0.10.6")],
		false,
	);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.74.0");
});

void test("promotion accepts the same stable version that seeds the next release", () => {
	const releases = [
		published("v0.93.0"),
		published("v0.100.0"),
		draft("v0.101.0", SHA),
		{ ...published("v99.0.0"), isPrerelease: true },
		published("v0.93.1"),
	];
	const plan = planRelease(SHA, "0.101.0", releases, false);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "0.100.0");
	assert.doesNotThrow(() => assertCurrentStableRelease(plan.previousVersion, releases));
	for (const version of ["0.93.0", "0.93.1", "0.101.0", "99.0.0", "0.100.0-rc.1"]) {
		assert.throws(
			() => assertCurrentStableRelease(version, releases),
			/Only (?:the current stable release|a published immutable candidate)/u,
		);
	}
});

void test("promotion refuses when no published stable release exists", () => {
	assert.throws(() => assertCurrentStableRelease("0.75.0", []), /Only the current stable release/u);
	assert.throws(
		() => assertCurrentStableRelease("0.75.0", [draft("v0.75.0", SHA)]),
		/Only the current stable release/u,
	);
});

void test("holds an unpublished release until pending changesets are consumed", () => {
	const plan = planRelease(SHA, "0.75.0", RELEASED, true);
	assert.equal(plan.kind, "skip");
	assert.match(plan.reason, /Version PR must consume/u);
	assert.deepEqual(releaseOutputs(plan), { released: "false" });
});

void test("holds a same-source draft but preserves a different-source draft refusal", () => {
	const sameSource = planRelease(SHA, "0.75.0", [...RELEASED, draft("v0.75.0", SHA)], true);
	assert.equal(sameSource.kind, "skip");
	assert.deepEqual(releaseOutputs(sameSource), { released: "false" });
	const otherSource = planRelease(SHA, "0.75.0", [...RELEASED, draft("v0.75.0", OTHER_SHA)], true);
	assert.equal(otherSource.kind, "refuse");
	assert.match(otherSource.reason, /delete the draft to re-cut/u);
});

void test("a locked writer accepts only an absent release or this commit's own draft and tag", () => {
	const absent = { release: undefined, tagCommit: undefined };
	assert.equal(releaseSourceState("v0.75.0", SHA, absent), "absent");
	assert.equal(releaseSourceState("v0.75.0", SHA, { ...absent, tagCommit: SHA }), "absent");
	const ownDraft = { release: { isDraft: true, targetCommitish: SHA }, tagCommit: undefined };
	assert.equal(releaseSourceState("v0.75.0", SHA, ownDraft), "draft");
	assert.throws(
		() => releaseSourceState("v0.75.0", OTHER_SHA, ownDraft),
		/draft v0\.75\.0 targets 1{40}, not 2{40}/u,
	);
	// A draft's target does not move a tag that already exists.
	assert.throws(
		() => releaseSourceState("v0.75.0", SHA, { ...ownDraft, tagCommit: OTHER_SHA }),
		/tag v0\.75\.0 points to 2{40}, not 1{40}/u,
	);
	assert.throws(
		() => releaseSourceState("v0.75.0", SHA, { ...absent, tagCommit: OTHER_SHA }),
		/tag v0\.75\.0 points to 2{40}, not 1{40}/u,
	);
});

void test("a published release binds to the commit its tag names, not to its target", () => {
	const release = { isDraft: false, targetCommitish: "main" };
	assert.equal(releaseSourceState("v0.75.0", SHA, { release, tagCommit: SHA }), "published");
	assert.throws(
		() => releaseSourceState("v0.75.0", OTHER_SHA, { release, tagCommit: SHA }),
		/tag v0\.75\.0 points to 1{40}, not 2{40}/u,
	);
	assert.throws(
		() => releaseSourceState("v0.75.0", SHA, { release, tagCommit: undefined }),
		/published v0\.75\.0 has no tag/u,
	);
});

const section = (version: string, body: string): string =>
	`## ${version}\n\n### Patch Changes\n\n- ${body}\n\n`;

const CHANGELOG = [
	"# hephaestus\n\n",
	section("0.89.1", "Fixed the review queue."),
	// A heading inside a fenced example is text, not a section boundary.
	"## 0.89.0\n\n### Minor Changes\n\n- Added a setting:\n\n  ```md\n  ## 0.88.0\n  ```\n\n",
	section("0.88.0", "Published already."),
	section("0.87.0", "Older history."),
].join("");

void test("release notes carry every section since the published release, as the source has them", async () => {
	// 0.89.0 never published, so 0.89.1 is the first release operators see after 0.88.0.
	const notes = await renderReleaseNotes(CHANGELOG, "0.88.0", "0.89.1");
	assert.equal(
		notes,
		CHANGELOG.slice(CHANGELOG.indexOf("## 0.89.1"), CHANGELOG.indexOf("## 0.88.0\n\n### Patch")),
	);
	assert.match(
		notes,
		/Fixed the review queue[\s\S]*Added a setting[\s\S]*```md\n {2}## 0\.88\.0\n {2}```/u,
	);
	assert.doesNotMatch(notes, /Published already|Older history/u);

	assert.equal(
		await renderReleaseNotes(CHANGELOG, "0.89.0", "0.89.1"),
		section("0.89.1", "Fixed the review queue."),
	);
	assert.match(
		await renderReleaseNotes(CHANGELOG, "0.87.0", "0.89.1"),
		/Published already\.\n\n$/u,
	);
});

const refused = async (changelog: string, previous: string, version: string, reason: RegExp) =>
	assert.rejects(renderReleaseNotes(changelog, previous, version), reason);

void test("release notes refuse boundaries they cannot place exactly", async () => {
	await refused(CHANGELOG, "0.86.0", "0.89.1", /0 "## 0\.86\.0" sections/u);
	await refused(CHANGELOG, "0.88.0", "0.89.2", /0 "## 0\.89\.2" sections/u);
	// The CHANGELOG of an older commit: its newest section is not the version being released.
	await refused(
		section("0.89.2", "Later.") + CHANGELOG,
		"0.88.0",
		"0.89.1",
		/newest CHANGELOG section is "## 0\.89\.2"/u,
	);
	await refused(
		CHANGELOG + section("0.88.0", "Again."),
		"0.88.0",
		"0.89.1",
		/2 "## 0\.88\.0" sections/u,
	);
	await refused(
		CHANGELOG.replace("## 0.89.0", "## 0.89.5"),
		"0.88.0",
		"0.89.1",
		/"## 0\.89\.5" is not a release older than 0\.89\.1/u,
	);
	await refused(
		CHANGELOG.replace("## 0.89.0", "## 0.89.1-beta.1"),
		"0.88.0",
		"0.89.1",
		/"## 0\.89\.1-beta\.1" is not a release/u,
	);
	await refused(CHANGELOG, "0.88.0", "0.89.1-beta.1", /must both be major\.minor\.patch/u);
	await refused(CHANGELOG, "0.89.1", "0.89.1", /not newer than 0\.89\.1/u);
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

void test("reads pending changesets from the exact commit through native git", async () => {
	const repo = mkdtempSync(path.join(tmpdir(), "plan-release-pending-"));
	repositories.push(repo);
	const git = (...args: string[]): string =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			env: environmentForGitFixture(),
			stdio: ["ignore", "pipe", "pipe"],
		}).trim();
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	const directory = path.join(repo, ".changeset");
	mkdirSync(path.join(directory, "nested"), { recursive: true });
	writeFileSync(path.join(directory, "README.md"), "# Changesets\n");
	writeFileSync(path.join(directory, "config.json"), "{}\n");
	writeFileSync(path.join(directory, "nested", "ignored.md"), "Not a direct changeset\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "consumed release");
	const consumed = git("rev-parse", "HEAD");
	assert.equal(await hasPendingChangesets(consumed, repo), false);

	writeFileSync(
		path.join(directory, "release-note.md"),
		'---\n"hephaestus": patch\n---\n\nFixed behavior.\n',
	);
	git("add", "-A");
	git("commit", "--quiet", "-m", "pending release note");
	assert.equal(await hasPendingChangesets("HEAD", repo), true);
	assert.equal(await hasPendingChangesets(consumed, repo), false);

	rmSync(path.join(directory, "release-note.md"));
	writeFileSync(path.join(directory, "tooling.md"), "---\n---\n\nNo shipped change.\n");
	git("add", "-A");
	git("commit", "--quiet", "-m", "pending empty changeset");
	assert.equal(await hasPendingChangesets("HEAD", repo), true);

	rmSync(path.join(directory, "tooling.md"));
	git("add", "-A");
	git("commit", "--quiet", "-m", "consume pending changeset");
	process.env.GIT_DIR = path.join(repo, "not-a-git-directory");
	try {
		assert.equal(await hasPendingChangesets("HEAD", repo), false);
	} finally {
		delete process.env.GIT_DIR;
	}
	await assert.rejects(hasPendingChangesets("missing-revision", repo));
});
