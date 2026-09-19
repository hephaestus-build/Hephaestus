import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { buildBrief } from "../../../main/resources/agent/pi-review-brief.ts";

const paths = { contextRoot: "inputs/context", repositoryRoot: "inputs/sources/scm/repo" };

function workspace(files: Record<string, string>): string {
	const root = mkdtempSync(join(tmpdir(), "pi-review-brief-"));
	for (const [path, content] of Object.entries(files)) {
		mkdirSync(join(root, path, ".."), { recursive: true });
		writeFileSync(join(root, path), content);
	}
	return root;
}

void test("the brief shows each captured file under its workspace path, the change first among the derived files", () => {
	const root = workspace({
		"inputs/context/metadata.json": '{"title": "Add login"}',
		"inputs/context/review_threads.json": '{"threads": []}',
		"work/change/files.json": '{"files": [{"status": "M", "path": "a.ts"}]}',
		"work/change/diff.patch": "diff --git a/a.ts b/a.ts\n@@ -1 +1 @@\n[L1] -x\n[L1] +y\n",
		"work/precompute-out/summary.md": "# hints\n",
	});
	try {
		const brief = buildBrief(root, paths);
		const order = [
			"### `inputs/context/metadata.json`",
			"### `work/change/files.json`",
			"### `inputs/context/review_threads.json`",
			"### `work/precompute-out/summary.md`",
			"### `work/change/diff.patch`",
		].map((heading) => brief.indexOf(heading));
		assert.ok(
			order.every((index) => index >= 0),
			brief,
		);
		assert.deepEqual(
			order,
			[...order].toSorted((a, b) => a - b),
		);
		assert.match(
			brief,
			/```diff\ndiff --git a\/a\.ts b\/a\.ts\n@@ -1 \+1 @@\n\[L1\] -x\n\[L1\] \+y\n```/,
		);
		// Every other file is numbered the same way, so a citation can name the line it read.
		assert.match(brief, /```json\n\[L1\] \{"title": "Add login"\}\n```/);
		assert.match(brief, /```markdown\n\[L1\] # hints\n```/);
		assert.doesNotMatch(brief, /Too large/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("each linked issue's text file follows the linked-items record, in number order", () => {
	const root = workspace({
		"inputs/context/linked_work_items.json": '{"workItems": []}',
		"inputs/context/linked_work_items/12.md": "# Twelve\n\nbody\n",
		"inputs/context/linked_work_items/7.md": "# Seven\n\n- [x] done\n",
	});
	try {
		const brief = buildBrief(root, paths);
		const order = [
			"### `inputs/context/linked_work_items.json`",
			"### `inputs/context/linked_work_items/7.md`",
			"### `inputs/context/linked_work_items/12.md`",
		].map((heading) => brief.indexOf(heading));
		assert.ok(
			order.every((index) => index >= 0),
			brief,
		);
		assert.deepEqual(
			order,
			[...order].toSorted((a, b) => a - b),
		);
		assert.match(brief, /```markdown\n\[L1\] # Seven\n\[L2\] \n\[L3\] - \[x\] done\n```/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a file over its bound is named with its size instead of shown, and an empty one is skipped", () => {
	const root = workspace({
		"inputs/context/metadata.json": '{"title": "t"}',
		"inputs/context/comments.json": "",
		"work/change/diff.patch": `${"+".repeat(100)}\n`.repeat(600),
	});
	try {
		const brief = buildBrief(root, paths, {
			filePerChars: 1000,
			diffChars: 2000,
			totalChars: 10_000,
		});
		assert.match(brief, /### `inputs\/context\/metadata\.json`/);
		assert.doesNotMatch(brief, /comments\.json/);
		assert.match(brief, /Too large to show here[\s\S]*- `work\/change\/diff\.patch` \(60 KB\)/);
		assert.doesNotMatch(brief, /```diff/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("the brief as a whole stays under its total bound", () => {
	const root = workspace({
		"inputs/context/metadata.json": "x".repeat(900),
		"inputs/context/comments.json": "y".repeat(900),
		"inputs/context/review_threads.json": "z".repeat(900),
	});
	try {
		const brief = buildBrief(root, paths, {
			filePerChars: 1000,
			diffChars: 1000,
			totalChars: 2000,
		});
		assert.match(brief, /metadata\.json`\n```json\n\[L1\] x+\n```/);
		assert.match(brief, /comments\.json`\n```json\n\[L1\] y+\n```/);
		assert.match(brief, /Too large[\s\S]*review_threads\.json/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a backtick run inside a file cannot close its fence early", () => {
	const root = workspace({ "inputs/context/document.md": "text with ``` inside\n" });
	try {
		assert.match(buildBrief(root, paths), /````markdown\n\[L1\] text with ``` inside\n````/);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("nothing captured is an empty brief", () => {
	const root = workspace({});
	try {
		assert.equal(buildBrief(root, paths), "");
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
