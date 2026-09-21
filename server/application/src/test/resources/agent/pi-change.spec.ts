import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	annotateDiff,
	authoredDescription,
	parseNameStatus,
	readChange,
	renderAuthoredDescription,
	writeChangeView,
} from "../../../main/resources/agent/pi-change.ts";

/** The form a GitLab project ships under `.gitlab/merge_request_templates/`, as one cohort did. */
const TEMPLATE = `<!-- MR title format: #<IssueNumber>: <Short, imperative description> — Example: #12: Add login screen -->

## Description
Closes #<!-- issue number -->

<!-- Briefly describe what you implemented and why. -->

## Intro Course App Requirements
<!-- Fill this in when your MR fulfills a course requirement. Leave empty if not applicable. -->

- [ ] **Requirement:** <!-- e.g., Persistence --> — <!-- short explanation -->

## Testing Instructions
<!-- List steps to verify the changes, or write "N/A" if not applicable. -->

## Definition of Done
- [ ] MR title follows the \`#<IssueNumber>: <Description>\` format
- [ ] Description explains what was changed and why
- [ ] Related issue is linked (e.g., \`Closes #12\`)
`;

/** A description as an author left it: the form with three lines of their own and two boxes ticked. */
const DESCRIPTION = `<!--MR title format: #<IssueNumber>: <Short, imperative description> — Example: #12: Add login screen-->

## Description

Closes #11

Implement guess logic to all quiztypes

## Intro Course App Requirements

<!--Fill this in when your MR fulfills a course requirement. Leave empty if not applicable.-->

- [ ] **Requirement:**

  <!--e.g., Persistence-->

  —

## Testing Instructions

N/A

## Definition of Done

- [x] MR title follows the \`#<IssueNumber>: <Description>\` format
- [x] Description explains what was changed and why
- [ ] Related issue is linked (e.g., \`Closes #12\`)
`;

void test("the author's lines are told apart from the template's, comments and ticks included", () => {
	const view = authoredDescription(DESCRIPTION, new Map([["templates/Default.md", TEMPLATE]]));
	assert.equal(view.template, "templates/Default.md");
	// "Closes #11", the sentence and "N/A"; the label line and the placeholder dash are the form's.
	assert.deepEqual(view.authored, [5, 7, 21]);
	assert.deepEqual(view.ticked, [25, 26]);
	const rendered = renderAuthoredDescription(DESCRIPTION, view);
	assert.match(rendered, /Template `templates\/Default\.md`: 25 of the description's 28 lines/u);
	assert.match(
		rendered,
		/\[L5\] Closes #11\n\[L7\] Implement guess logic to all quiztypes\n\[L21\] N\/A/u,
	);
	assert.match(rendered, /ticked:\n\[L25\] - \[x\] MR title follows/u);
	// With no template, only the comments are left out and every other line is the author's.
	const bare = authoredDescription("Closes #3\n<!-- how -->\nBecause it is slow.\n", new Map());
	assert.equal(bare.template, null);
	assert.deepEqual(bare.authored, [1, 3]);
	assert.match(
		renderAuthoredDescription("x", bare),
		/No merge request template is in the checkout/u,
	);
});

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
	mkdirSync(path.join(repo, ".gitlab/merge_request_templates"), { recursive: true });
	writeFileSync(path.join(repo, ".gitlab/merge_request_templates/Default.md"), TEMPLATE);
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

void test("name-status records carry the old path of a rename", () => {
	assert.deepEqual(parseNameStatus(Buffer.from("R100\0old.ts\0new.ts\0A\0added.ts\0")), [
		{ status: "R", path: "new.ts", oldPath: "old.ts" },
		{ status: "A", path: "added.ts" },
	]);
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
		writeChangeView(root, repo, change, DESCRIPTION);

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
		// The template is read from the checkout at the reviewed head, and the authored view written.
		assert.match(
			readFileSync(path.join(root, "work/change/description.authored.md"), "utf8"),
			/Template `\.gitlab\/merge_request_templates\/Default\.md`[\s\S]*\[L5\] Closes #11/u,
		);
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
