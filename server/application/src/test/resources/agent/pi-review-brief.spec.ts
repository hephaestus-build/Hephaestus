import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import test from "node:test";

import { buildBrief, buildSameWorkContext } from "../../../main/resources/agent/pi-review-brief.ts";

const paths = { contextRoot: "context", repositoryRoot: "repos/reviewed" };

const FRAMING = { repositoryFullName: "group/repo", pullRequestNumber: 7 };

/** A pull request capture index whose core and linked-work sources are in the given states. */
function pullRequestIndex(
	core: Record<string, unknown>,
	linked?: Record<string, unknown>,
	recorded = ["context/metadata.json", "context/linked_work_items.json"],
): Record<string, unknown> {
	const source = (kind: string, state: Record<string, unknown>, path: string) => ({
		kind,
		state,
		artifacts: state.availability === "AVAILABLE" && recorded.includes(path) ? [{ path }] : [],
	});
	return {
		artifactKind: "scm.pull_request",
		capturedAt: "2026-10-01T10:00:00Z",
		sources: [
			source("scm.pull-request.core", core, "context/metadata.json"),
			...(linked === undefined
				? []
				: [source("scm.linked-work-items", linked, "context/linked_work_items.json")]),
		],
		artifacts: [
			{ kind: "scm.pull-request.core", artifact: { path: "context/metadata.json" } },
			{ kind: "scm.linked-work-items", artifact: { path: "context/linked_work_items.json" } },
		].filter((entry) => recorded.includes(entry.artifact.path)),
	};
}

const METADATA = JSON.stringify({
	title: "Add login",
	body: "Adds the login screen.\n\nCloses #4",
	pr_url: "https://gitlab.example/group/repo/-/merge_requests/7",
	repository_full_name: "group/repo",
	pr_number: 7,
	state: "OPEN",
	is_merged: false,
	source_branch: "feature/login",
	target_branch: "main",
	author: { login: "someone-private" },
	assignees: ["another-person"],
});

const LINKED = JSON.stringify({
	workItems: [
		{
			number: 4,
			how: "closesOnMerge",
			title: "Login",
			state: "OPEN",
			url: "u4",
			body: "Users sign in.",
			labels: ["x"],
		},
	],
	unresolvedReferences: [9],
	truncated: false,
});

void test("the review is told what the same work is, from its exact core and linked records, with what they do not state", () => {
	const root = workspace({
		"context/metadata.json": METADATA,
		"context/linked_work_items.json": LINKED,
	});
	try {
		const context = buildSameWorkContext(
			root,
			"context",
			pullRequestIndex(
				{ availability: "AVAILABLE", completeness: "COMPLETE" },
				{ availability: "AVAILABLE", completeness: "PARTIAL" },
			),
			FRAMING,
		);
		assert.match(context, /scm\.pull_request, captured at 2026-10-01T10:00:00Z/u);
		assert.match(context, /"title": "Add login"/u);
		// A field the capture does not state stays unknown: a missing draft flag is not "not a draft".
		assert.match(context, /"notInCapture": \[[^\]]*"is_draft"/u);
		assert.doesNotMatch(context, /"is_draft": false/u);
		assert.match(context, /```markdown\nAdds the login screen\.\n\nCloses #4\n```/u);
		assert.ok(
			context.indexOf("metadata.json") < context.indexOf("linked_work_items.json"),
			context,
		);
		assert.match(context, /"how": "closesOnMerge"/u);
		assert.match(context, /"completeness": "PARTIAL"/u);
		assert.match(context, /"completeness": "COMPLETE"/u);
		assert.match(context, /"unresolvedReferences": \[\n\s*9\n\s*\]/u);
		// People, labels and other fields the review is not oriented by never reach it.
		assert.doesNotMatch(context, /someone-private|another-person|"labels"/u);
		assert.doesNotMatch(context, /Not shown/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a record that is not shown is named with why, never cut or read as empty", (t) => {
	const roots: string[] = [];
	const captured = (files: Record<string, string>) => {
		const root = workspace(files);
		roots.push(root);
		return root;
	};
	t.after(() => {
		for (const root of roots) {
			rmSync(root, { recursive: true, force: true });
		}
	});
	const unavailable = buildSameWorkContext(
		captured({}),
		"context",
		pullRequestIndex(
			{ availability: "UNAVAILABLE", reasonCode: "PROVIDER_UNREACHABLE" },
			{ availability: "COLLECTION_ERROR", errorCode: "COLLECTION_FAILED" },
		),
		FRAMING,
	);
	assert.match(
		unavailable,
		/metadata\.json[^\n]*the source is UNAVAILABLE \(PROVIDER_UNREACHABLE\)/u,
	);
	assert.match(
		unavailable,
		/linked_work_items\.json[^\n]*the source is COLLECTION_ERROR \(COLLECTION_FAILED\)/u,
	);

	const unlisted = buildSameWorkContext(
		captured({ "context/metadata.json": METADATA }),
		"context",
		pullRequestIndex({ availability: "AVAILABLE" }, undefined, []),
		FRAMING,
	);
	// An available source that did not record this file shows nothing from it.
	assert.match(unlisted, /metadata\.json[^\n]*did not record this file/u);
	assert.match(unlisted, /linked_work_items\.json[^\n]*not part of this capture/u);
	assert.doesNotMatch(unlisted, /Add login/u);

	const oversize = buildSameWorkContext(
		captured({
			"context/metadata.json": JSON.stringify({ title: "Huge", body: "x".repeat(30_000) }),
		}),
		"context",
		pullRequestIndex({ availability: "AVAILABLE" }),
		FRAMING,
	);
	assert.match(oversize, /metadata\.json[^\n]*too large to show here \(\d+ KB\)/u);
	assert.doesNotMatch(oversize, /Huge|xxxx/u);

	const unreadable = buildSameWorkContext(
		captured({ "context/metadata.json": "{not json" }),
		"context",
		pullRequestIndex({ availability: "AVAILABLE" }),
		FRAMING,
	);
	assert.match(unreadable, /metadata\.json[^\n]*not readable/u);

	const otherWork = buildSameWorkContext(
		captured({ "context/metadata.json": METADATA, "context/linked_work_items.json": LINKED }),
		"context",
		pullRequestIndex({ availability: "AVAILABLE" }, { availability: "AVAILABLE" }),
		{ repositoryFullName: "group/repo", pullRequestNumber: 8 },
	);
	assert.match(otherWork, /metadata\.json[^\n]*another pull request than the task/u);
	assert.doesNotMatch(otherWork, /Add login|Users sign in/u);
	assert.match(otherWork, /linked_work_items.json[^\n]*core record names other reviewed work/u);
	const missingCore = buildSameWorkContext(
		captured({ "context/linked_work_items.json": LINKED }),
		"context",
		pullRequestIndex(
			{ availability: "UNAVAILABLE", reasonCode: "PROVIDER_UNREACHABLE" },
			{ availability: "AVAILABLE", completeness: "PARTIAL" },
		),
		FRAMING,
	);
	assert.match(missingCore, /Users sign in/u);
	assert.match(missingCore, /metadata.json[^\n]*UNAVAILABLE/u);
});

void test("the complete context stays in budget without slicing a source", () => {
	const root = workspace({
		"context/metadata.json": METADATA,
		"context/linked_work_items.json": LINKED,
	});
	try {
		const index = pullRequestIndex(
			{ availability: "AVAILABLE", completeness: "COMPLETE" },
			{ availability: "AVAILABLE", completeness: "PARTIAL" },
		);
		const context = buildSameWorkContext(root, "context", index, FRAMING, {
			sourceChars: 24_000,
			totalChars: 1400,
		});
		assert.ok(context.length <= 1400);
		assert.ok(context.includes('"title": "Add login"') || !context.includes("Add login"));
		assert.ok(context.includes('"body": "Users sign in."') || !context.includes("Users sign in."));
		assert.match(context, /linked_work_items.json[^\n]*exceed its bound/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("an issue uses only its recorded issue core", () => {
	const root = workspace({
		"context/metadata.json": JSON.stringify({
			title: "A capability",
			body: "People can sign in.",
			issue_number: 4,
			html_url: "u4",
			repository_full_name: "group/repo",
			state: "OPEN",
			author: "private-person",
		}),
	});
	try {
		const index = {
			artifactKind: "scm.issue",
			capturedAt: "2026-10-01T10:00:00Z",
			sources: [
				{
					kind: "scm.issue.core",
					state: { availability: "AVAILABLE", completeness: "COMPLETE" },
					artifacts: [{ path: "context/metadata.json" }],
				},
			],
			artifacts: [{ kind: "scm.issue.core", artifact: { path: "context/metadata.json" } }],
		};
		const context = buildSameWorkContext(root, "context", index, {
			repositoryFullName: "group/repo",
			pullRequestNumber: 4,
		});
		assert.match(context, /"issue_number": 4/u);
		assert.match(context, /People can sign in/u);
		assert.doesNotMatch(context, /private-person|linked_work_items|is_draft/u);
		const mismatched = buildSameWorkContext(root, "context", index, FRAMING);
		assert.match(mismatched, /another issue than the task/u);
		assert.doesNotMatch(mismatched, /A capability/u);
		const firstArtifact = index.artifacts[0];
		assert.ok(firstArtifact);
		firstArtifact.kind = "scm.pull-request.core";
		assert.doesNotMatch(buildSameWorkContext(root, "context", index, FRAMING), /A capability/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

function workspace(files: Record<string, string>): string {
	const root = mkdtempSync(nodePath.join(tmpdir(), "pi-review-brief-"));
	for (const [path, content] of Object.entries(files)) {
		mkdirSync(nodePath.join(root, path, ".."), { recursive: true });
		writeFileSync(nodePath.join(root, path), content);
	}
	return root;
}

void test("the brief shows each captured file under its workspace path, the change first among the derived files", () => {
	const root = workspace({
		"INDEX.md": "# Permitted workspace\n",
		"context/metadata.json": '{"title": "Add login"}',
		"context/description.md": "# Add login\n",
		"context/review_threads.json": '{"threads": []}',
		"work/change/files.json": '{"files": [{"status": "M", "path": "a.ts"}]}',
		"work/change/diff.patch": "diff --git a/a.ts b/a.ts\n@@ -1 +1 @@\n[L1] -x\n[L1] +y\n",
	});
	try {
		const brief = buildBrief(root, paths);
		const order = [
			"### `INDEX.md`",
			"### `context/metadata.json`",
			"### `work/change/files.json` — derived here, not citable",
			"### `context/review_threads.json`",
			"### `work/change/diff.patch` — derived here, not citable",
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
		const brief = buildBrief(root, paths);
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
		const brief = buildBrief(root, paths, {
			filePerChars: 1000,
			diffChars: 2000,
			totalChars: 10_000,
		});
		assert.match(brief, /### `context\/metadata\.json`/u);
		// An empty record is not shown but named as captured and empty. One the capture did not write is named
		// apart, as unknown, so the review neither looks for it nor reads it as empty.
		assert.doesNotMatch(brief, /### `context\/comments\.json`/u);
		assert.match(brief, /### Captured and empty\n`context\/comments\.json`\n/u);
		assert.match(
			brief,
			/### Not captured — do not look for these\nNothing is known about what they would hold\. Never read one as empty: a fact that depends on one is a collection gap\.\n`context\/description\.md`, `context\/review_threads\.json`, `context\/general_comments\.json`, `context\/linked_work_items\.json`/u,
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
		const brief = buildBrief(root, paths, {
			filePerChars: 1000,
			diffChars: 1000,
			totalChars: 2000,
		});
		assert.match(brief, /metadata\.json`\n```json\n\[L1\] x+\n```/u);
		assert.ok(brief.length <= 2000, `Rendered ${brief.length} characters`);
		assert.doesNotMatch(brief, /comments\.json`\n```json/u);
		assert.match(brief, /Too large[\s\S]*comments\.json[\s\S]*review_threads\.json/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a backtick run inside a file cannot close its fence early", () => {
	const root = workspace({ "context/document.md": "text with ``` inside\n" });
	try {
		assert.match(buildBrief(root, paths), /````markdown\n\[L1\] text with ``` inside\n````/u);
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

for (const [name, content] of [
	["line coordinates", "x\n".repeat(500)],
	["fence escaping", "`".repeat(1000)],
] as const) {
	void test(`the file bound includes ${name}`, () => {
		const root = workspace({ "context/document.md": content });
		try {
			const brief = buildBrief(root, paths, {
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
		const brief = buildBrief(root, paths, { filePerChars: 100, diffChars: 100, totalChars: 128 });
		assert.ok(brief.length <= 128);
		assert.match(brief, /capture index exceeds the brief limit/u);
		assert.match(brief, /Read the capture manifest and context files/u);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
