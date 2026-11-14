import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { violations } from "./check-changelog-immutability.ts";
import { NEXT_CHANGELOG_DIRECTORY } from "./lib/changelog.ts";
import { environmentForGitFixture } from "./lib/git-environment.ts";
import {
	githubRepository,
	orderProblem,
	queuedMigrations,
	refusals,
	reorder,
	reorderMigrations,
} from "./reorder-migrations.ts";

const next = (name: string): string => `${NEXT_CHANGELOG_DIRECTORY}/${name}`;
const at = (iso: string): number => Date.parse(iso);

void test("a branch file that sorts last stays where it is", () => {
	const base = [next("20261103100829-a.xml")];
	assert.deepEqual(
		reorder(base, [next("20261103110000-mine.xml")], at("2026-11-03T12:00:00Z")),
		[],
	);
	assert.deepEqual(reorder([], [next("20261101000000-mine.xml")], Date.now()), []);
});

void test("files that sort too early move after the newest, keep their slugs and their order", () => {
	const base = [next("20261103100829-a.xml"), next("20261103120000-b.xml")];
	const branch = [next("20261103110000-second.xml"), next("20261101000000-first.xml")];
	const renames = reorder(base, branch, at("2026-11-03T11:00:00Z"));
	assert.deepEqual(renames, [
		{ from: next("20261101000000-first.xml"), to: next("20261103120001-first.xml") },
		{ from: next("20261103110000-second.xml"), to: next("20261103120002-second.xml") },
	]);
	// A rename that is already done is in order, so a second run changes nothing.
	assert.deepEqual(
		reorder(
			base,
			renames.map(({ to }) => to),
			at("2026-11-03T11:00:00Z"),
		),
		[],
	);
});

void test("files also move after every migration that a queued pull request adds", () => {
	const base = [next("20261103100829-a.xml")];
	const queued = [next("20261103154836-queued.xml"), next("20261103110000-mine.xml")];
	// Sorted after main's newest, but before a file that merges first from the queue.
	const branch = [next("20261103110000-mine.xml")];
	assert.deepEqual(reorder(base, branch, at("2026-11-03T12:00:00Z"), queued), [
		{ from: next("20261103110000-mine.xml"), to: next("20261103154837-mine.xml") },
	]);
	// Without the queue the same branch counts as in order: what the pre-push check reads.
	assert.deepEqual(reorder(base, branch, at("2026-11-03T12:00:00Z")), []);
});

const tree = (names: string[] | null) => ({
	file: names === null ? null : { object: { entries: names.map((name) => ({ name })) } },
});

/** One queue entry: its merge-group commit, its head branch's tip, and its last commit. */
const entry = (number: number, group?: string[], tip?: string[] | null, last?: string[]) => ({
	headCommit: group === undefined ? null : tree(group),
	pullRequest: {
		number,
		headRef: tip === undefined ? null : { target: tree(tip) },
		commits: { nodes: last === undefined ? [] : [{ commit: tree(last) }] },
	},
});

const response = (...nodes: unknown[]) => ({
	data: { repository: { mergeQueue: { entries: { nodes } } } },
});

void test("the queued migrations are the next/ files at each entry's merge-group commit, else at its head, however many files it changes", () => {
	assert.deepEqual(
		queuedMigrations(
			response(
				entry(7, ["20261101000000-base.xml", "20261103154836-grouped.xml", "README.md"]),
				entry(8, undefined, ["20261104000000-waiting.xml"], ["20261102000000-older.xml"]),
				entry(9, undefined, undefined, ["20261104010000-deleted-branch.xml"]),
			),
		),
		[
			next("20261101000000-base.xml"),
			next("20261103154836-grouped.xml"),
			next("20261104000000-waiting.xml"),
			next("20261104010000-deleted-branch.xml"),
		],
	);
	assert.deepEqual(queuedMigrations(response(entry(7, undefined, null))), []);
	assert.throws(() => queuedMigrations(response(entry(7))), /#7 has no commit/u);
	assert.deepEqual(queuedMigrations({ data: { repository: { mergeQueue: null } } }), []);
});

void test("a file on main or in a release is never renamed", () => {
	const renames = [
		{ from: next("20261101000000-mine.xml"), to: next("20261103120001-mine.xml") },
		{ from: next("20261102000000-merged.xml"), to: next("20261103120002-merged.xml") },
	];
	assert.deepEqual(refusals(renames, new Set([next("20261102000000-merged.xml")])), [
		next("20261102000000-merged.xml"),
	]);
});

const env = environmentForGitFixture({
	GIT_AUTHOR_NAME: "fixture",
	GIT_AUTHOR_EMAIL: "fixture@example.com",
	GIT_COMMITTER_NAME: "fixture",
	GIT_COMMITTER_EMAIL: "fixture@example.com",
});
const script = path.join(import.meta.dirname, "reorder-migrations.ts");

async function fixture(): Promise<{ cwd: string; git: (...args: string[]) => string }> {
	const root = await mkdtemp(path.join(tmpdir(), "reorder-migrations-"));
	const origin = path.join(root, "origin.git");
	const cwd = path.join(root, "work");
	const git = (...args: string[]): string =>
		execFileSync("git", args, { cwd, env, encoding: "utf8" }).trim();
	execFileSync("git", ["init", "--quiet", "--bare", "--initial-branch=main", origin], { env });
	execFileSync("git", ["clone", "--quiet", origin, cwd], { env });
	await mkdir(path.join(cwd, NEXT_CHANGELOG_DIRECTORY), { recursive: true });
	const add = async (name: string, message: string) => {
		await writeFile(
			path.join(cwd, next(name)),
			`<databaseChangeLog><!-- ${name} --></databaseChangeLog>\n`,
		);
		git("add", "--all");
		git("commit", "--quiet", "--message", message);
	};
	git("checkout", "--quiet", "-b", "main");
	await add("20261101000000-released.xml", "released");
	git("tag", "v1.0.0");
	git("push", "--quiet", "origin", "main", "v1.0.0");
	git("checkout", "--quiet", "-b", "feature");
	await add("20261102000000-mine.xml", "mine");
	git("checkout", "--quiet", "main");
	await add("20261103000000-merged.xml", "merged");
	git("push", "--quiet", "origin", "main");
	git("checkout", "--quiet", "feature");
	return { cwd, git };
}

const reorderIn = (cwd: string, ...args: string[]) =>
	spawnSync(process.execPath, [script, ...args], { cwd, env, encoding: "utf8" });

const snapshot = (git: (...args: string[]) => string, revision: string) => ({
	blobs: new Map(
		git("ls-tree", "-r", revision, "--", `${NEXT_CHANGELOG_DIRECTORY}/`)
			.split("\n")
			.filter(Boolean)
			.map((line) => {
				const [metadata = "", file = ""] = line.split("\t");
				return [file, metadata.split(" ")[2] ?? ""] as const;
			}),
	),
	master: "",
	// The order rules read names only; these fixtures hold no change sets for the recovery rule.
	read: () => "<databaseChangeLog/>",
});

void test("the command moves the branch's file after main and the merge-queue guard accepts it", async (t) => {
	const { cwd, git } = await fixture();
	t.after(async () => {
		await rm(path.dirname(cwd), { recursive: true, force: true });
	});
	git("fetch", "--quiet", "origin");
	git("rebase", "--quiet", "origin/main");
	// The guard the merge queue runs rejects the branch as it was.
	assert.match(
		violations(snapshot(git, "origin/main"), snapshot(git, "HEAD")).join("\n"),
		/sorts before existing history/u,
	);
	const check = reorderIn(cwd, "--check");
	assert.equal(check.status, 1);
	assert.match(check.stderr, /20261102000000-mine\.xml/u);

	const blob = git("rev-parse", `HEAD:${next("20261102000000-mine.xml")}`);
	const run = reorderIn(cwd);
	assert.equal(run.status, 0, run.stderr);
	git("commit", "--quiet", "--message", "reorder");
	const [renamed = ""] = git("ls-files", "--", `${NEXT_CHANGELOG_DIRECTORY}/*-mine.xml`).split(
		"\n",
	);
	assert.ok(path.basename(renamed) > "20261103000000-merged.xml", renamed);
	assert.equal(git("rev-parse", `HEAD:${renamed}`), blob);
	assert.deepEqual(violations(snapshot(git, "origin/main"), snapshot(git, "HEAD")), []);
	assert.equal(reorderIn(cwd, "--check").status, 0);
	assert.match(reorderIn(cwd).stdout, /nothing to rename/u);
});

void test("the command refuses to rename a file that main or a release has, whatever base it is given", async (t) => {
	const { cwd, git } = await fixture();
	t.after(async () => {
		await rm(path.dirname(cwd), { recursive: true, force: true });
	});
	git("fetch", "--quiet", "origin");
	git("rebase", "--quiet", "origin/main");
	// A file that only a release tag has, here a hotfix tag beside main, is released too.
	git("checkout", "--quiet", "-b", "hotfix", "origin/main");
	await writeFile(path.join(cwd, next("20261101120000-hotfix.xml")), "<databaseChangeLog/>\n");
	git("add", "--all");
	git("commit", "--quiet", "--message", "hotfix");
	git("tag", "v1.0.1");
	git("push", "--quiet", "origin", "v1.0.1");
	// A base without main's history, whose newest file sorts after all of them: from there, the
	// released file and main's merged file look like the branch's own. Neither may move.
	git("checkout", "--quiet", "--orphan", "elsewhere");
	git("rm", "--quiet", "-r", "--cached", ".");
	await rm(path.join(cwd, NEXT_CHANGELOG_DIRECTORY), { recursive: true });
	await mkdir(path.join(cwd, NEXT_CHANGELOG_DIRECTORY), { recursive: true });
	await writeFile(path.join(cwd, next("20261105000000-later.xml")), "<databaseChangeLog/>\n");
	git("add", "--all");
	git("commit", "--quiet", "--message", "elsewhere");
	git("checkout", "--quiet", "--force", "feature");
	git("checkout", "--quiet", "v1.0.1", "--", next("20261101120000-hotfix.xml"));
	git("commit", "--quiet", "--message", "carry the hotfix");
	const before = git("ls-files", "--", `${NEXT_CHANGELOG_DIRECTORY}/`);

	const fromRoot = reorderIn(cwd, "--base", "elsewhere");
	assert.equal(fromRoot.status, 1);
	assert.match(fromRoot.stderr, /Refusing to rename/u);
	assert.match(fromRoot.stderr, /20261101000000-released\.xml/u);
	assert.match(fromRoot.stderr, /20261103000000-merged\.xml/u);
	assert.match(fromRoot.stderr, /20261101120000-hotfix\.xml/u);
	assert.doesNotMatch(fromRoot.stderr, /20261102000000-mine\.xml/u);
	assert.equal(git("ls-files", "--", `${NEXT_CHANGELOG_DIRECTORY}/`), before);
	assert.equal(git("status", "--porcelain"), "");
	// From main itself, the hotfix is not on main but the tag still protects it.
	const fromMain = reorderIn(cwd);
	assert.equal(fromMain.status, 1);
	assert.match(fromMain.stderr, /20261101120000-hotfix\.xml/u);
});

void test("a migration deleted from the worktree is not renamed", async (t) => {
	const { cwd, git } = await fixture();
	t.after(async () => {
		await rm(path.dirname(cwd), { recursive: true, force: true });
	});
	git("fetch", "--quiet", "origin");
	git("rebase", "--quiet", "origin/main");
	await rm(path.join(cwd, next("20261102000000-mine.xml")));
	const run = reorderIn(cwd);
	assert.equal(run.status, 0, run.stderr);
	assert.match(run.stdout, /nothing to rename/u);
	assert.equal(git("diff", "--cached", "--name-only"), "");
});

void test("only a GitHub remote is checked for being a fork", () => {
	assert.equal(githubRepository("https://github.com/owner/name.git"), "owner/name");
	assert.equal(githubRepository("git@github.com:owner/name"), "owner/name");
	assert.equal(githubRepository("/tmp/origin.git"), undefined);
});

void test("with two migration pull requests in the queue, the branch moves after both, and the merge group accepts it", async (t) => {
	const { cwd, git } = await fixture();
	t.after(async () => {
		await rm(path.dirname(cwd), { recursive: true, force: true });
	});
	git("fetch", "--quiet", "origin");
	git("rebase", "--quiet", "origin/main");
	// In order against main: the pull request passes its own checks.
	git("mv", next("20261102000000-mine.xml"), next("20261103050000-mine.xml"));
	git("commit", "--quiet", "--message", "in order against main");
	assert.equal(orderProblem("origin/main", cwd), undefined);

	// Two pull requests ahead in the queue add migrations; the merge group applies both first.
	const queued = [next("20261103040000-first-ahead.xml"), next("20261103060000-second-ahead.xml")];
	const group = snapshot(git, "origin/main");
	for (const file of queued) {
		group.blobs.set(file, `blob of ${file}`);
	}
	const withBranch = (file: string) => ({
		...group,
		blobs: new Map([...group.blobs, [file, "blob of mine"]]),
	});
	assert.match(
		violations(group, withBranch(next("20261103050000-mine.xml"))).join("\n"),
		/sorts before existing history/u,
	);

	const problem = orderProblem("origin/main", cwd, queued);
	assert.match(
		problem ?? "",
		/20261103060000-second-ahead\.xml, the newest that the merge queue adds/u,
	);
	// A queued head also lists main's files; the message still names the queue's newest.
	const onMain = git(
		"ls-tree",
		"-r",
		"--name-only",
		"origin/main",
		"--",
		`${NEXT_CHANGELOG_DIRECTORY}/`,
	)
		.split("\n")
		.filter(Boolean);
	assert.match(
		orderProblem("origin/main", cwd, [...onMain, ...queued]) ?? "",
		/the newest that the merge queue adds/u,
	);
	// Main's files alone in the queued heads put nothing ahead of this branch.
	assert.equal(orderProblem("origin/main", cwd, onMain), undefined);
	const [rename] = reorderMigrations("origin/main", cwd, queued);
	assert.ok(rename !== undefined);
	assert.ok(path.basename(rename.to) > "20261103060000-second-ahead.xml", rename.to);
	assert.deepEqual(violations(group, withBranch(rename.to)), []);
	git("commit", "--quiet", "--message", "reorder");
	assert.equal(orderProblem("origin/main", cwd, queued), undefined);
});
