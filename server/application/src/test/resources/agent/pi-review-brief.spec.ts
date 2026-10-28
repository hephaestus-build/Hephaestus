import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import test from "node:test";

import { type BriefScope, buildBrief } from "../../../main/resources/agent/pi-review-brief.ts";

const paths = { contextRoot: "context", repositoryRoot: "repos/reviewed" };

const CORE = "scm.pull-request.core";
const DIFF = "scm.pull-request.diff";
const KIND_BY_FILE: Record<string, string> = {
	"metadata.json": CORE,
	"description.md": CORE,
	"commits.json": CORE,
	"comments.json": "scm.pull-request.comments",
	"review_threads.json": "scm.review-threads",
	"general_comments.json": "scm.general-review-comments",
	"linked_work_items.json": "scm.linked-work-items",
	"document.json": "docs.document.core",
	"document.md": "docs.document.core",
};
const KINDS = new Set([...Object.values(KIND_BY_FILE), DIFF]);

/** Every kind captured and declared, unless a test narrows what the practice declares. */
function scope(
	declared: ReadonlySet<string> = KINDS,
	available: ReadonlySet<string> = KINDS,
): BriefScope {
	return {
		declared,
		available,
		fileKinds: new Map(
			Object.entries(KIND_BY_FILE).map(([name, kind]) => [`context/${name}`, kind]),
		),
	};
}

function workspace(files: Record<string, string>): string {
	const root = mkdtempSync(nodePath.join(tmpdir(), "pi-review-brief-"));
	for (const [path, content] of Object.entries(files)) {
		mkdirSync(nodePath.join(root, path, ".."), { recursive: true });
		writeFileSync(nodePath.join(root, path), content);
	}
	return root;
}

void test("the brief shows each declared file under its workspace path: the change, the core text, then the discussion", () => {
	const root = workspace({
		"INDEX.md": "# Permitted workspace\n",
		"context/metadata.json": '{"title": "Add login"}',
		"context/description.md": "# Add login\n",
		"context/review_threads.json": '{"threads": []}',
		"work/change/files.json": '{"files": [{"status": "M", "path": "a.ts"}]}',
		"work/change/diff.patch": "diff --git a/a.ts b/a.ts\n@@ -1 +1 @@\n[L1] -x\n[L1] +y\n",
	});
	try {
		const { text: brief } = buildBrief(root, paths, scope());
		const order = [
			"### `work/change/files.json` — derived here, not citable",
			"### `work/change/diff.patch` — derived here, not citable",
			"### `context/metadata.json`",
			"### `context/review_threads.json`",
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
			/```diff\ndiff --git a\/a\.ts b\/a\.ts\n@@ -1 \+1 @@\n\[L1\] -x\n\[L1\] \+y\n```/u,
		);
		// Every captured file is numbered the same way, so a citation can name the line it read.
		assert.match(brief, /```json\n\[L1\] \{"title": "Add login"\}\n```/u);
		// A derived view is shown as written: numbering it would offer a coordinate no citation can use.
		assert.match(brief, /```json\n\{"files": \[\{"status": "M", "path": "a\.ts"\}\]\}\n```/u);
		assert.match(brief, /```markdown\n\[L1\] # Add login\n```/u);
		assert.doesNotMatch(brief, /Too large/u);
		// The capture index lists every source, so it would end the shared prefix at its first byte.
		assert.doesNotMatch(brief, /INDEX\.md/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a practice is shown only the sources it declares, and one declared set gives one opening", () => {
	const root = workspace({
		"context/metadata.json": '{"title": "Add login"}',
		"context/description.md": "# Add login\n",
		"context/comments.json": '{"comments": [{"body": "nit"}]}',
		"work/change/files.json": '{"files": []}',
		"work/change/diff.patch": "diff --git a/a.ts b/a.ts\n",
	});
	try {
		const diffOnly = buildBrief(root, paths, scope(new Set([DIFF, "scm.repository.tree"]))).text;
		assert.match(diffOnly, /work\/change\/diff\.patch/u);
		assert.doesNotMatch(diffOnly, /### `context\/|Add login|nit/u);
		const layered = buildBrief(root, paths, scope(new Set([DIFF, CORE])));
		const withCore = layered.text;
		assert.equal(layered.cacheBoundaries.length, 2);
		const [changeEnd, coreEnd] = layered.cacheBoundaries;
		assert.ok(changeEnd !== undefined && coreEnd !== undefined);
		assert.doesNotMatch(withCore.slice(0, changeEnd), /context\/metadata.json/u);
		assert.match(withCore.slice(changeEnd, coreEnd), /context\/metadata.json/u);
		assert.doesNotMatch(withCore.slice(0, coreEnd), /The sources of this practice/u);
		assert.equal(buildBrief(root, paths, scope(new Set([DIFF]))).cacheBoundaries[0], changeEnd);
		assert.match(withCore, /context\/description\.md/u);
		assert.doesNotMatch(withCore, /### `context\/comments\.json`/u);
		// The change layer comes first, so both openings share it as a prefix.
		const change = withCore.slice(0, withCore.indexOf("### `context/metadata.json`"));
		assert.ok(diffOnly.startsWith(change.trimEnd()), diffOnly);
		assert.equal(buildBrief(root, paths, scope(new Set([DIFF, CORE]))).text, withCore);
		// The practice's own index ends its opening and names only its sources.
		assert.match(
			diffOnly,
			/### The sources of this practice\n[^#]*`scm\.pull-request\.diff`: `work\/change\/`/u,
		);
		assert.doesNotMatch(
			diffOnly.slice(diffOnly.indexOf("### The sources of this practice")),
			/core|comments/u,
		);
		assert.ok(diffOnly.trimEnd().endsWith("with its Git history"), diffOnly);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("each linked issue's text file follows the linked-items record, in number order", () => {
	const root = workspace({
		"context/linked_work_items.json": '{"workItems": []}',
		"context/linked_work_items/12.md": "# Twelve\n\nbody\n",
		"context/linked_work_items/7.md": "# Seven\n\n- [x] done\n",
	});
	try {
		const { text: brief } = buildBrief(root, paths, scope());
		const order = [
			"### `context/linked_work_items.json`",
			"### `context/linked_work_items/7.md`",
			"### `context/linked_work_items/12.md`",
		].map((heading) => brief.indexOf(heading));
		assert.ok(
			order.every((index) => index >= 0),
			brief,
		);
		assert.deepEqual(
			order,
			[...order].toSorted((a, b) => a - b),
		);
		assert.match(brief, /```markdown\n\[L1\] # Seven\n\[L2\] \n\[L3\] - \[x\] done\n```/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a file over its bound is named with its size instead of shown, and an empty one is named as empty", () => {
	const root = workspace({
		"context/metadata.json": '{"title": "t"}',
		"context/comments.json": "",
		"work/change/diff.patch": `${"+".repeat(100)}\n`.repeat(600),
	});
	try {
		const { text: brief } = buildBrief(
			root,
			paths,
			scope(KINDS, new Set([CORE, DIFF, "scm.pull-request.comments"])),
			{ filePerChars: 1000, diffChars: 2000, totalChars: 10_000 },
		);
		assert.match(brief, /### `context\/metadata\.json`/u);
		// An empty record is not shown but named as captured and empty. One the capture did not write is named
		// apart, as unknown, so the review neither looks for it nor reads it as empty.
		assert.doesNotMatch(brief, /### `context\/comments\.json`/u);
		assert.match(brief, /### Captured and empty\n`context\/comments\.json`\n/u);
		assert.match(
			brief,
			/### Not captured — do not look for these\nNothing is known about what they would hold\. Never read one as empty: a fact that depends on one is a collection gap\.\n`docs\.document\.core`, `scm\.general-review-comments`, `scm\.linked-work-items`, `scm\.review-threads`/u,
		);
		assert.match(brief, /Too large to show here[\s\S]*- `work\/change\/diff\.patch` \(60 KB\)/u);
		assert.doesNotMatch(brief, /```diff/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("the brief as a whole stays under its total bound", () => {
	const root = workspace({
		"context/metadata.json": "x".repeat(900),
		"context/comments.json": "y".repeat(900),
		"context/review_threads.json": "z".repeat(900),
	});
	try {
		const { text: brief } = buildBrief(root, paths, scope(), {
			filePerChars: 1000,
			diffChars: 1000,
			totalChars: 2700,
		});
		assert.match(brief, /metadata\.json`\n```json\n\[L1\] x+\n```/u);
		assert.ok(brief.length <= 2700, `Rendered ${brief.length} characters`);
		assert.doesNotMatch(brief, /comments\.json`\n```json/u);
		assert.match(brief, /Too large[\s\S]*review_threads\.json[\s\S]*comments\.json/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a backtick run inside a file cannot close its fence early", () => {
	const root = workspace({ "context/document.md": "text with ``` inside\n" });
	try {
		assert.match(
			buildBrief(root, paths, scope()).text,
			/````markdown\n\[L1\] text with ``` inside\n````/u,
		);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("nothing captured and nothing missing is an empty brief", () => {
	const root = workspace({});
	try {
		assert.equal(buildBrief(root, paths, scope(new Set(), new Set())).text, "");
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

for (const [name, content] of [
	["line coordinates", "x\n".repeat(500)],
	["fence escaping", "`".repeat(1000)],
] as const) {
	void test(`the file bound includes ${name}`, () => {
		const root = workspace({ "context/document.md": content });
		try {
			const { text: brief } = buildBrief(root, paths, scope(), {
				filePerChars: 2000,
				diffChars: 2000,
				totalChars: 4000,
			});
			assert.ok(brief.length <= 4000);
			assert.doesNotMatch(brief, /### `context\/document\.md`/u);
			assert.match(brief, /Too large[\s\S]*`context\/document\.md` \(1 KB\)/u);
		} finally {
			rmSync(root, { recursive: true, force: true });
		}
	});
}

void test("an oversized capture index gives a complete fallback instruction within the bound", () => {
	const root = workspace({ "context/document.md": "x".repeat(1000) });
	try {
		const { text: brief } = buildBrief(root, paths, scope(), {
			filePerChars: 100,
			diffChars: 100,
			totalChars: 128,
		});
		assert.ok(brief.length <= 128);
		assert.match(brief, /capture index exceeds the brief limit/u);
		assert.match(brief, /Read the capture manifest and context files/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("brief coverage names supplied, empty, and omitted sources, with why, after rendering limits", (t) => {
	const root = workspace({
		"context/metadata.json": '{"title":"Add login"}',
		"context/comments.json": "",
		"work/change/diff.patch": "x".repeat(1000),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const brief = buildBrief(root, paths, scope(), {
		filePerChars: 1000,
		diffChars: 100,
		totalChars: 5000,
	});
	assert.deepEqual(brief.sources, [
		{ path: "work/change/diff.patch", supplied: false, bytes: 1000, omitted: "file-limit" },
		{ path: "context/metadata.json", supplied: true, lines: 1, bytes: 21 },
		{ path: "context/comments.json", supplied: true, lines: 1, bytes: 0 },
	]);
	assert.match(brief.text, /Captured and empty/u);
	assert.match(brief.text, /Too large to show here/u);
	const crowded = buildBrief(root, paths, scope(), {
		filePerChars: 1000,
		diffChars: 2000,
		totalChars: 1100,
	});
	assert.deepEqual(
		crowded.sources.find((source) => source.path === "work/change/diff.patch"),
		{ path: "work/change/diff.patch", supplied: false, bytes: 1000, omitted: "brief-limit" },
	);
	const unavailable = buildBrief(root, paths, scope(), {
		filePerChars: 1000,
		diffChars: 100,
		totalChars: 1,
	});
	assert.equal(unavailable.text, "");
	assert.ok(unavailable.sources.every((source) => !source.supplied));
	assert.equal(
		unavailable.sources.find((source) => source.path === "context/comments.json")?.omitted,
		"index-limit",
	);
});
