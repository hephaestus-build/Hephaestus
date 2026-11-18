import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { environmentForGitFixture } from "./lib/git-environment.ts";
import {
	assertCurrentStableRelease,
	findVersionSource,
	hasPendingChangesets,
	isHistoryChange,
	nameStatusLines,
	hasSchemaMigrations,
	planRelease,
	pendingChangesetFiles,
	planVersionRelease,
	type ReleaseRef,
	releaseOutputs,
	releaseSourceState,
	majorChangesetSummaries,
	staleVersionPullRequest,
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

const HISTORICAL =
	"### Major Changes\n\n- The rc.1 wording.\n\n  **Operators:** The old first step.\n\n";
const MAJOR_CHANGELOG = [
	"# hephaestus\n\n",
	section("1.0.0-rc.3", "Third candidate."),
	section("1.0.0-rc.2", "Second candidate."),
	`## 1.0.0-rc.1\n\n${HISTORICAL}### Minor Changes\n\n- First candidate.\n\n`,
	section("0.95.1", "The last 0.x release."),
].join("");
const CHANGESETS = [
	'---\n"hephaestus": minor\n---\n\nA minor change.\n',
	'---\n"hephaestus": major\n---\n\nThe 1.0 promise.\n\n**Operators:** Follow the upgrade guide.\n',
];

void test("a stable major promotion leads with its consumed major changeset", async () => {
	const notes = await renderReleaseNotes(
		MAJOR_CHANGELOG,
		"0.95.1",
		"1.0.0-rc.3",
		"1.0.0",
		majorChangesetSummaries(CHANGESETS),
	);
	const lead =
		"### Major Changes\n\n- The 1.0 promise.\n\n  **Operators:** Follow the upgrade guide.\n\n";
	assert.equal(
		notes,
		lead +
			MAJOR_CHANGELOG.slice(
				MAJOR_CHANGELOG.indexOf("## 1.0.0-rc.3"),
				MAJOR_CHANGELOG.indexOf("## 0.95.1"),
			),
	);
	// Released text stays: the candidate keeps its historical entry in its own section.
	assert.match(notes, /## 1\.0\.0-rc\.1\n\n### Major Changes\n\n- The rc\.1 wording\./u);
	assert.doesNotMatch(notes, /A minor change|The last 0\.x release/u);
	await assert.rejects(
		renderReleaseNotes(MAJOR_CHANGELOG, "0.95.1", "1.0.0-rc.3", "1.0.0", []),
		/1\.0\.0 starts a major, but \.changeset\/pre\/ has no consumed major changeset/u,
	);
});

void test("candidate cuts and minor promotions keep the source order", async () => {
	const inOrder = MAJOR_CHANGELOG.slice(
		MAJOR_CHANGELOG.indexOf("## 1.0.0-rc.3"),
		MAJOR_CHANGELOG.indexOf("## 0.95.1"),
	);
	assert.equal(await renderReleaseNotes(MAJOR_CHANGELOG, "0.95.1", "1.0.0-rc.3"), inOrder);
	const minor = [
		"# hephaestus\n\n",
		section("1.1.0-rc.1", "Next."),
		section("1.0.0", "Stable."),
	].join("");
	assert.equal(
		await renderReleaseNotes(
			minor,
			"1.0.0",
			"1.1.0-rc.1",
			"1.1.0",
			majorChangesetSummaries(CHANGESETS),
		),
		section("1.1.0-rc.1", "Next."),
	);
	await assert.rejects(
		renderReleaseNotes(MAJOR_CHANGELOG, "0.95.1", "1.0.0-rc.3", "1.0.1"),
		/1\.0\.1 does not promote the candidate 1\.0\.0-rc\.3/u,
	);
	await assert.rejects(
		renderReleaseNotes(MAJOR_CHANGELOG, "0.95.1", "1.0.0-rc.3", "1.0.0-rc.4"),
		/must be a stable major\.minor\.patch release/u,
	);
});

void test("only changesets that bump hephaestus by a major count as its promise", () => {
	assert.deepEqual(majorChangesetSummaries(CHANGESETS), [
		"The 1.0 promise.\n\n**Operators:** Follow the upgrade guide.",
	]);
	assert.deepEqual(majorChangesetSummaries(["---\n---\n\nNo release.\n", "no front matter"]), []);
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

void test("a 1.0 candidate upgrades from the release of the baseline, not from 0.x", () => {
	const rc1 = { ...published("v1.0.0-rc.1"), isPrerelease: true };
	const plan = planRelease(SHA, "1.0.0-rc.2", [published("v0.95.1"), rc1], false);
	assert.equal(plan.kind, "cut");
	assert.equal(plan.previousVersion, "1.0.0-rc.1");
	// Before the baseline's release exists, and after 1.0.0, the previous stable release is right.
	const first = planRelease(SHA, "1.0.0-rc.1", [published("v0.95.1")], false);
	assert.equal(first.kind, "cut");
	assert.equal(first.previousVersion, "0.95.1");
	const patch = planRelease(SHA, "0.95.2", [published("v0.95.1"), rc1], false);
	assert.equal(patch.kind, "cut");
	assert.equal(patch.previousVersion, "0.95.1");
	const later = planRelease(SHA, "1.0.1", [published("v0.95.1"), rc1, published("v1.0.0")], false);
	assert.equal(later.kind, "cut");
	assert.equal(later.previousVersion, "1.0.0");
});

/** Tonight's shape (2026-11-18): rc.10 published, the Version PR set rc.11, later merges added changesets. */
const CANDIDATES = [
	published("v0.95.1"),
	{ ...published("v1.0.0-rc.1"), isPrerelease: true },
	{ ...published("v1.0.0-rc.10"), isPrerelease: true },
];
const VERSION_COMMIT = "3333333333333333333333333333333333333333";
const source = (overrides: Partial<Parameters<typeof planVersionRelease>[4] & object> = {}) => ({
	sha: VERSION_COMMIT,
	pendingChangesets: false,
	validated: true,
	historyChanges: [],
	plannedBefore: false,
	...overrides,
});

void test("a Version PR folded into a merge group cuts its unpublished version at the commit that set it", () => {
	// main's tip carries newer changesets, so the tip itself is not a release source.
	const plan = planVersionRelease(SHA, "1.0.0-rc.11", CANDIDATES, true, source());
	assert.equal(plan.kind, "cut");
	assert.equal(plan.sha, VERSION_COMMIT);
	assert.equal(plan.tag, "v1.0.0-rc.11");
	assert.deepEqual(releaseOutputs(plan).sha, VERSION_COMMIT);
});

void test("a tip without pending changesets still plans itself, so a forward fix can recut", () => {
	const plan = planVersionRelease(SHA, "1.0.0-rc.11", CANDIDATES, false, source());
	assert.equal(plan.kind, "cut");
	assert.equal(plan.sha, SHA);
});

void test("the version commit cuts nothing once its version is published or still has changesets", () => {
	const released = [...CANDIDATES, { ...published("v1.0.0-rc.11"), isPrerelease: true }];
	assert.equal(planVersionRelease(SHA, "1.0.0-rc.11", released, true, source()).kind, "skip");
	const unversioned = planVersionRelease(
		SHA,
		"1.0.0-rc.11",
		CANDIDATES,
		true,
		source({ pendingChangesets: true }),
	);
	assert.equal(unversioned.kind, "skip");
	assert.match(unversioned.reason, /pending changesets remain at 3{40}/u);
	// Without a version commit the tip's own plan stands: it waits for the Version PR.
	assert.equal(planVersionRelease(SHA, "1.0.0-rc.11", CANDIDATES, true, undefined).kind, "skip");
});

void test("the version commit is refused unless CI/CD validated it and only changesets followed it", () => {
	const unvalidated = planVersionRelease(
		SHA,
		"1.0.0-rc.11",
		CANDIDATES,
		true,
		source({ validated: false }),
	);
	assert.equal(unvalidated.kind, "refuse");
	assert.match(unvalidated.reason, /no successful CI\/CD/u);
	const rewritten = planVersionRelease(
		SHA,
		"1.0.0-rc.11",
		CANDIDATES,
		true,
		source({ historyChanges: ["M\tCHANGELOG.md"] }),
	);
	assert.equal(rewritten.kind, "refuse");
	assert.match(rewritten.reason, /M\tCHANGELOG\.md.*only new changesets/u);
	// A draft of the version at another commit belongs to that commit's images.
	const drafted = planVersionRelease(
		SHA,
		"1.0.0-rc.11",
		[...CANDIDATES, draft("v1.0.0-rc.11", OTHER_SHA)],
		true,
		source(),
	);
	assert.equal(drafted.kind, "refuse");
});

void test("only the first push that brings the version commit to main cuts it", () => {
	// A later push, or a forward fix after it, had its own push run: that run planned the version.
	const later = planVersionRelease(
		SHA,
		"1.0.0-rc.11",
		CANDIDATES,
		true,
		source({ plannedBefore: true }),
	);
	assert.equal(later.kind, "skip");
	assert.match(later.reason, /pending changesets remain at 1{40}/u);
});

void test("reads name-status output with any path unquoted", () => {
	assert.deepEqual(nameStatusLines("A\0.changeset/a b.md\0M\0CHANGELOG.md\0"), [
		"A\t.changeset/a b.md",
		"M\tCHANGELOG.md",
	]);
	assert.deepEqual(nameStatusLines(""), []);
});

void test("only a new changeset or migration fragment may follow the version commit", () => {
	assert.equal(isHistoryChange("M\t.migration/README.md"), false);
	assert.equal(isHistoryChange("A\t.changeset/next-fix.md"), false);
	assert.equal(isHistoryChange("A\t.migration/next-step.md"), false);
	assert.equal(isHistoryChange("M\t.changeset/README.md"), false);
	assert.equal(isHistoryChange("M\tCHANGELOG.md"), true);
	assert.equal(isHistoryChange("M\tMIGRATION.md"), true);
	assert.equal(isHistoryChange("D\t.changeset/consumed.md"), true);
	assert.equal(isHistoryChange("M\t.changeset/edited.md"), true);
	assert.equal(isHistoryChange("D\t.migration/consumed.md"), true);
	assert.equal(isHistoryChange("A\t.changeset/pre/new.md"), true);
	assert.equal(isHistoryChange("M\t.changeset/pre.json"), true);
});

void test("finds the commit that set the version through a tip with newer changesets", async () => {
	const repo = mkdtempSync(path.join(tmpdir(), "plan-release-source-"));
	repositories.push(repo);
	const git = (...args: string[]): string =>
		execFileSync("git", args, {
			cwd: repo,
			encoding: "utf8",
			env: environmentForGitFixture(),
			stdio: ["ignore", "pipe", "pipe"],
		}).trim();
	const write = (file: string, content: string) => {
		mkdirSync(path.dirname(path.join(repo, file)), { recursive: true });
		writeFileSync(path.join(repo, file), content);
	};
	const commit = (message: string) => {
		git("add", "-A");
		git("commit", "--quiet", "-m", message);
		return git("rev-parse", "HEAD");
	};
	git("init", "--quiet", "--initial-branch=main");
	git("config", "user.email", "test@example.invalid");
	git("config", "user.name", "Test");
	write("package.json", '{ "version": "1.0.0-rc.10" }\n');
	write("CHANGELOG.md", "## 1.0.0-rc.10\n");
	write(".changeset/README.md", "# Changesets\n");
	write(".changeset/mitigation.md", '---\n"hephaestus": patch\n---\n\nA fix.\n');
	commit("fix: the mitigation, with its changeset");
	// The Version PR consumes the changeset and sets rc.11.
	write("package.json", '{ "version": "1.0.0-rc.11" }\n');
	write("CHANGELOG.md", "## 1.0.0-rc.11\n\n- A fix.\n\n## 1.0.0-rc.10\n");
	rmSync(path.join(repo, ".changeset/mitigation.md"));
	const versioned = commit("chore(release): version packages (rc)");
	// The same merge group's later pull requests: code, and changesets for the next Version PR.
	write("server.txt", "later code\n");
	write(".changeset/later-fix.md", '---\n"hephaestus": patch\n---\n\nLater.\n');
	commit("fix: a later pull request in the same group");
	write(".migration/later-step.md", "#### Later\n");
	const tip = commit("fix: another later pull request");

	const found = await findVersionSource(tip, repo);
	assert.deepEqual(found, {
		sha: versioned,
		pendingChangesets: false,
		historyChanges: [],
		earlier: [git("rev-parse", "HEAD~1"), versioned],
	});
	assert.equal(await hasPendingChangesets(tip, repo), true);
	// The version commit itself is its own source.
	const own = await findVersionSource(versioned, repo);
	assert.equal(own?.sha, versioned);

	// A later commit that rewrites released history is reported, so the planner refuses it.
	write("CHANGELOG.md", "## 1.0.0-rc.11\n\n- A rewritten fix.\n\n## 1.0.0-rc.10\n");
	const rewritten = commit("docs: rewrite a released entry");
	const history = await findVersionSource(rewritten, repo);
	assert.deepEqual(history?.historyChanges, ["M\tCHANGELOG.md"]);
	// A history shorter than the walk never reaches another version, so it names no source.
	assert.equal(await findVersionSource(tip, repo, 2), undefined);
});

void test("a merge group refuses a Version PR that left changesets unconsumed ahead of it", async () => {
	assert.equal(staleVersionPullRequest({ sha: VERSION_COMMIT }, []), undefined);
	// Tonight's commit: six pull requests merged ahead of the Version PR after it was versioned.
	const reason = staleVersionPullRequest({ sha: VERSION_COMMIT }, [
		".changeset/delete-agent-image-pull-policy.md",
		".changeset/probes-notice-readiness-sooner.md",
	]);
	assert.match(
		reason ?? "",
		/at 3{40} leaves 2 changesets unconsumed .*delete-agent-image-pull-policy\.md.*queue it again/u,
	);
});

void test("lists the pending changesets of the exact commit", async () => {
	const repo = mkdtempSync(path.join(tmpdir(), "plan-release-list-"));
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
	mkdirSync(path.join(repo, ".changeset/pre"), { recursive: true });
	writeFileSync(path.join(repo, ".changeset/README.md"), "# Changesets\n");
	writeFileSync(path.join(repo, ".changeset/pre/consumed.md"), "Consumed.\n");
	writeFileSync(
		path.join(repo, ".changeset/later.md"),
		'---\n"hephaestus": patch\n---\n\nLater.\n',
	);
	git("add", "-A");
	git("commit", "--quiet", "-m", "a later changeset");
	assert.deepEqual(await pendingChangesetFiles("HEAD", repo), [".changeset/later.md"]);
});
