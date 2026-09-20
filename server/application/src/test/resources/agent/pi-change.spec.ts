import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	annotateDiff,
	parseCommits,
	parseNameStatus,
	readChange,
	writeChangeView,
} from "../../../main/resources/agent/pi-change.ts";

function git(repository: string, ...args: string[]): string {
	return execFileSync("git", ["-C", repository, ...args], {
		encoding: "utf8",
		env: {
			...process.env,
			GIT_AUTHOR_NAME: "Ada",
			GIT_AUTHOR_EMAIL: "ada@example.invalid",
			GIT_COMMITTER_NAME: "Ada",
			GIT_COMMITTER_EMAIL: "ada@example.invalid",
			GIT_CONFIG_GLOBAL: "/dev/null",
			GIT_CONFIG_SYSTEM: "/dev/null",
		},
	}).trim();
}

/** A repository with a base, a two-commit branch that renames a file and adds a test, merged nowhere. */
function repositoryWithChange() {
	const root = mkdtempSync(path.join(tmpdir(), "pi-change-"));
	const repo = path.join(root, "repo");
	mkdirSync(repo);
	git(repo, "init", "-q", "-b", "main");
	writeFileSync(
		path.join(repo, "app.ts"),
		"export const a = 1;\nexport const b = 2;\nexport const c = 3;\n",
	);
	git(repo, "add", ".");
	git(repo, "commit", "-q", "-m", "Base");
	const base = git(repo, "rev-parse", "HEAD");
	git(repo, "mv", "app.ts", "lib.ts");
	writeFileSync(
		path.join(repo, "lib.ts"),
		"export const a = 1;\nexport const b = 20;\nexport const c = 3;\n",
	);
	git(repo, "add", ".");
	git(repo, "commit", "-q", "-m", "Rename app to lib\n\nRefs #7");
	writeFileSync(path.join(repo, "lib.test.ts"), "test('b', () => {});\n");
	writeFileSync(path.join(repo, "Wördle.md"), "# Wördle\n");
	git(repo, "add", ".");
	git(repo, "commit", "-q", "-m", "Add a test");
	const head = git(repo, "rev-parse", "HEAD");
	return { root, repo, base, head };
}

void test("annotates every hunk line with its source coordinate and leaves headers alone", () => {
	const patch = Buffer.from(
		"diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n\\ No newline at end of file\n",
	);
	assert.equal(
		annotateDiff(patch).toString(),
		"diff --git a/f b/f\n--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n[L1]  a\n[L2] -b\n[L2] +B\n[L3]  c\n\\ No newline at end of file\n",
	);
});

void test("a removed line that starts with dashes is content, not a file header", () => {
	const patch = Buffer.from("--- a/f\n+++ b/f\n@@ -1,2 +1,1 @@\n a\n--- not a header\n");
	assert.equal(
		annotateDiff(patch).toString(),
		"--- a/f\n+++ b/f\n@@ -1,2 +1,1 @@\n[L1]  a\n[L2] --- not a header\n",
	);
});

void test("bytes that are not UTF-8 survive annotation unchanged", () => {
	const patch = Buffer.concat([
		Buffer.from("--- a/f\n+++ b/f\n@@ -1 +1 @@\n-"),
		Buffer.from([0xff, 0xfe]),
		Buffer.from("\n+x\n"),
	]);
	const annotated = annotateDiff(patch);
	assert.ok(annotated.includes(Buffer.concat([Buffer.from("[L1] -"), Buffer.from([0xff, 0xfe])])));
});

void test("name-status records carry the old path of a rename and commits keep their full message", () => {
	assert.deepEqual(parseNameStatus(Buffer.from("R100\0old.ts\0new.ts\0A\0added.ts\0")), [
		{ status: "R", path: "new.ts", oldPath: "old.ts" },
		{ status: "A", path: "added.ts" },
	]);
	const commits = parseCommits(
		Buffer.from(
			`${"a".repeat(40)}\0${"b".repeat(40)}\0Ada\0t1\0Ada\0t2\0Subject\n\nBody\n\u001E${"c".repeat(40)}\0${"a".repeat(40)} ${"d".repeat(40)}\0Bob\0t3\0Bob\0t4\0Merge\n\u001E`,
		),
	);
	assert.deepEqual(
		commits.map((commit) => [commit.sha.charAt(0), commit.parents.length, commit.message]),
		[
			["a", 1, "Subject\n\nBody"],
			["c", 2, "Merge"],
		],
	);
});

void test("derives the change view from a real checkout with git", () => {
	const { root, repo, base, head } = repositoryWithChange();
	try {
		mkdirSync(path.join(root, "context"));
		writeFileSync(
			path.join(root, "context/change.json"),
			JSON.stringify({ base_sha: base, head_sha: head }),
		);
		const change = readChange(path.join(root, "context"));
		assert.deepEqual(change, { base, head });
		writeChangeView(root, repo, change);

		const diff = readFileSync(path.join(root, "work/change/diff.patch"), "utf8");
		assert.match(diff, /rename from app\.ts\nrename to lib\.ts/u);
		assert.match(diff, /\[L2\] -export const b = 2;\n\[L2\] \+export const b = 20;/u);
		assert.match(diff, /\[L1\] \+test\('b', \(\) => \{\}\);/u);
		// A path outside ASCII is printed as itself, never as git's quoted octal rendering.
		assert.match(diff, /^\+\+\+ b\/Wördle\.md$/mu);
		assert.match(
			readFileSync(path.join(root, "work/change/diff_stat.txt"), "utf8"),
			/3 files changed/u,
		);

		const files: unknown = JSON.parse(
			readFileSync(path.join(root, "work/change/files.json"), "utf8"),
		);
		assert.deepEqual(files, {
			files: [
				{ status: "A", path: "Wördle.md" },
				{ status: "A", path: "lib.test.ts" },
				{ status: "R", path: "lib.ts", oldPath: "app.ts" },
			],
		});
		const commits: unknown = JSON.parse(
			readFileSync(path.join(root, "work/change/commits.json"), "utf8"),
		);
		assert.ok(typeof commits === "object" && commits !== null);
		const list: unknown = Reflect.get(commits, "commits");
		assert.ok(Array.isArray(list));
		const messages = list.map((commit: unknown): unknown =>
			typeof commit === "object" && commit !== null ? Reflect.get(commit, "message") : null,
		);
		assert.deepEqual(messages, ["Rename app to lib\n\nRefs #7", "Add a test"]);
		const last: unknown = list[1];
		assert.ok(typeof last === "object" && last !== null);
		assert.equal(Reflect.get(last, "sha"), head);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a review without a captured change derives nothing", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-change-none-"));
	try {
		mkdirSync(path.join(root, "context"));
		assert.equal(readChange(path.join(root, "context")), null);
		writeFileSync(
			path.join(root, "context/change.json"),
			JSON.stringify({ base_sha: "nope", head_sha: "x" }),
		);
		assert.throws(() => readChange(path.join(root, "context")), /full commit ids/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
