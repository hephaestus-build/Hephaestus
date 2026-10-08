import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import test from "node:test";

import {
	diffSections,
	type PinnedBlob,
	type PinnedDiff,
} from "../../../main/resources/agent/pi-change.ts";
import {
	buildBrief,
	buildPrimarySourceReference,
	buildPublicReviewHistory,
	buildSameWorkContext,
} from "../../../main/resources/agent/pi-review-brief.ts";

const paths = { contextRoot: "context", repositoryRoot: "repos/reviewed" };

const FRAMING = { repositoryFullName: "group/repo", pullRequestNumber: 7 };

const BASE = "b".repeat(40);
const HEAD = "a".repeat(40);
const EARLIER = "c".repeat(40);
const LOADER = `struct Item: Decodable {\n    let id: Int\n    let name: String\n}\n\nfunc load(_ data: Data) -> [Item] {\n    do {\n        return try JSONDecoder().decode([Item].self, from: data)\n    } catch {\n        print("decode failed: \\(error)")\n        return []\n    }\n}\n`;
const LOADER_SECTION =
	'diff --git a/App/Loader.swift b/App/Loader.swift\n--- a/App/Loader.swift\n+++ b/App/Loader.swift\n@@ -9,1 +9,2 @@\n[L9]     } catch {\n[L10] +        print("decode failed: \\(error)")';

const LEGACY_SECTION =
	"diff --git a/App/Legacy.swift b/App/Legacy.swift\ndeleted file mode 100644\n--- a/App/Legacy.swift\n+++ /dev/null\n@@ -1,1 +0,0 @@\n[L1] -func legacy() {}";
const UNCITED_SECTION =
	'diff --git a/App/Secret.swift b/App/Secret.swift\n--- a/App/Secret.swift\n+++ b/App/Secret.swift\n@@ -1,1 +1,1 @@\n[L1] -let token = "old"\n[L1] +let token = "UNCITED-SENTINEL"';
const PINNED_TEXT = `${LOADER_SECTION}\n${LEGACY_SECTION}\n${UNCITED_SECTION}\n`;
const PINNED_DIFF: PinnedDiff = {
	kind: "available",
	files: [
		{ status: "M", path: "App/Loader.swift" },
		{ status: "D", path: "App/Legacy.swift" },
		{ status: "M", path: "App/Secret.swift" },
	],
	text: PINNED_TEXT,
	sections: diffSections(PINNED_TEXT),
};

/**
 * A captured pull request whose primary checkout and pinned change are recorded. Its derived change view is stale on
 * purpose: the reference reads the pinned diff from Git, so nothing of the view may reach it.
 */
function primaryCapture(t: { after: (cleanup: () => void) => void }) {
	const root = mkdtempSync(nodePath.join(tmpdir(), "pi-primary-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	mkdirSync(nodePath.join(root, "context"), { recursive: true });
	mkdirSync(nodePath.join(root, "work/change"), { recursive: true });
	writeFileSync(
		nodePath.join(root, "context/change.json"),
		JSON.stringify({ base_sha: BASE, head_sha: HEAD }),
	);
	writeFileSync(
		nodePath.join(root, "work/change/files.json"),
		JSON.stringify({ files: [{ status: "M", path: "App/Loader.swift", diffPatchLines: [1, 2] }] }),
	);
	writeFileSync(
		nodePath.join(root, "work/change/diff.patch"),
		"diff --git a/App/Loader.swift b/App/Loader.swift\n[L1] +STALE-SENTINEL\n",
	);
	const tree = "repos/reviewed/.git/HEAD";
	const refs = "repos/reviewed/.git/hephaestus-captured-refs";
	const index = {
		artifactKind: "scm.pull_request",
		sources: [
			{
				kind: "scm.repository.tree",
				state: {
					availability: "AVAILABLE",
					completeness: "PARTIAL",
					limitations: ["Submodules are not captured."],
					facts: { immutableIdentity: `${HEAD}:${"d".repeat(40)}` },
				},
				artifacts: [{ path: tree }, { path: refs }],
			},
			{
				kind: "scm.pull-request.diff",
				state: { availability: "AVAILABLE", facts: { immutableIdentity: `${BASE}:${HEAD}` } },
				artifacts: [{ path: "context/change.json" }],
			},
		],
		artifacts: [
			{ kind: "scm.repository.tree", artifact: { path: tree } },
			{ kind: "scm.repository.tree", artifact: { path: refs } },
			{ kind: "scm.pull-request.diff", artifact: { path: "context/change.json" } },
		],
	};
	return { root, index };
}

const sha = (bytes: string | Buffer) => createHash("sha256").update(bytes).digest("hex");

/** A verified code citation as admission returns it. */
function cited(
	sourceKind: string,
	path: string,
	revision: string,
	text: string | Buffer,
	extra: Record<string, unknown> = {},
) {
	return {
		index: 0,
		sourceKind,
		artifactPath:
			sourceKind === "scm.repository.tree" ? "repos/reviewed/.git/HEAD" : "context/change.json",
		path,
		revision,
		startLine: 1,
		quote: "x",
		verification: { status: "VERIFIED", scope: "EXACT_LOCATION", artifactSha256: sha(text) },
		...extra,
	};
}

const decided = (citations: unknown[]) => ({
	id: "o",
	outcome: "NOT_MET",
	publicEligible: true,
	citations,
});

/** A reader over fixed blobs and one pinned diff that remembers every blob and range it was asked for. */
function blobs(
	files: Record<string, PinnedBlob>,
	checkedOut = HEAD,
	diff: PinnedDiff = PINNED_DIFF,
) {
	const asked: string[] = [];
	return {
		asked,
		reader: {
			blob: (revision: string, path: string) => {
				asked.push(`${revision}:${path}`);
				return files[`${revision}:${path}`] ?? { kind: "absent" as const };
			},
			diff: (base: string, head: string) => {
				asked.push(`diff ${base}..${head}`);
				return diff;
			},
			checkedOut: () => checkedOut,
		},
	};
}

void test("cited primary code arrives whole, once per file and revision, with its complete change section", (t) => {
	const { root, index } = primaryCapture(t);
	const model = "struct Item {}\n";
	const legacy = "func legacy() {}\n";
	const read = blobs({
		[`${HEAD}:App/Loader.swift`]: { kind: "regular", bytes: Buffer.from(LOADER) },
		[`${EARLIER}:App/Model.swift`]: { kind: "regular", bytes: Buffer.from(model) },
		[`${BASE}:App/Legacy.swift`]: { kind: "regular", bytes: Buffer.from(legacy) },
	});
	const reference = buildPrimarySourceReference(
		root,
		"context",
		"repos/reviewed",
		index,
		[
			{
				id: "concern",
				outcome: "NOT_MET",
				publicEligible: true,
				citations: [
					cited("scm.pull-request.diff", "App/Loader.swift", HEAD, LOADER, { side: "NEW" }),
				],
			},
			{
				id: "strength",
				outcome: "MET",
				publicEligible: true,
				citations: [
					cited("scm.pull-request.diff", "App/Loader.swift", HEAD, LOADER, { side: "NEW" }),
					cited("scm.repository.tree", "App/Model.swift", EARLIER, model),
					cited("scm.pull-request.diff", "App/Legacy.swift", BASE, legacy, { side: "OLD" }),
				],
			},
		],
		read.reader,
	);
	// The whole declaration and catch, numbered, and the unchanged decode line beside the added one in the change.
	assert.ok(reference.includes("[L2]     let id: Int"), reference);
	assert.ok(
		reference.includes(String.raw`[L10]         print("decode failed: \(error)")`),
		reference,
	);
	assert.equal(reference.split("[L8]         return try JSONDecoder()").length, 2, reference);
	assert.equal(reference.split(LOADER_SECTION).length, 2, reference);
	assert.equal(reference.split(LEGACY_SECTION).length, 2, reference);
	// Only the cited files' sections of the pinned diff; the stale derived view and uncited code never appear.
	assert.ok(!reference.includes("UNCITED-SENTINEL"), reference);
	assert.ok(!reference.includes("STALE-SENTINEL"), reference);
	// An explicit repository revision is read as cited; a removed file only from the base; the diff once.
	assert.deepEqual(read.asked, [
		`${HEAD}:App/Loader.swift`,
		`diff ${BASE}..${HEAD}`,
		`${EARLIER}:App/Model.swift`,
		`${BASE}:App/Legacy.swift`,
	]);
	assert.ok(
		reference.includes(`\`App/Model.swift\` at \`${EARLIER}\`, the repository revision`),
		reference,
	);
	assert.ok(reference.includes(`\`App/Legacy.swift\` at \`${BASE}\`, the OLD side`), reference);
	// A permitted file of a partial capture stays shown and qualified; an unstated completeness is not complete.
	assert.ok(reference.includes("PARTIAL: Submodules are not captured."), reference);
	assert.ok(
		reference.includes("`scm.pull-request.diff` capture is of unknown completeness"),
		reference,
	);
});

void test("private, auxiliary, undecided or unverifiable citations never expose source", (t) => {
	const { root, index } = primaryCapture(t);
	const text = "let x = 1\n";
	const image = Buffer.from([0x89, 0x00, 0x01]);
	const read = blobs({
		[`${HEAD}:Changed.swift`]: { kind: "regular", bytes: Buffer.from("let x = 2\n") },
		[`${HEAD}:Image.png`]: { kind: "regular", bytes: image },
		[`${HEAD}:Link.swift`]: { kind: "nonregular" },
		[`${HEAD}:Huge.swift`]: { kind: "tooLarge", size: 40_000 },
		[`${HEAD}:Private.swift`]: { kind: "regular", bytes: Buffer.from(text) },
	});
	const reference = buildPrimarySourceReference(
		root,
		"context",
		"repos/reviewed",
		index,
		[
			{
				...decided([cited("scm.repository.tree", "Private.swift", HEAD, text)]),
				publicEligible: false,
			},
			{
				...decided([cited("scm.repository.tree", "Private.swift", HEAD, text)]),
				outcome: "UNDETERMINED",
			},
			decided([
				cited("scm.repository.tree", "Private.swift", HEAD, text, {
					artifactPath: "repos/other/.git/HEAD",
				}),
				{
					...cited("scm.repository.tree", "Private.swift", HEAD, text),
					verification: { status: "UNVERIFIED" },
				},
				cited("scm.repository.tree", "Changed.swift", HEAD, text),
				cited("scm.repository.tree", "Image.png", HEAD, image),
				cited("scm.repository.tree", "Link.swift", HEAD, text),
				cited("scm.repository.tree", "Huge.swift", HEAD, text),
				cited("scm.repository.tree", "Missing.swift", HEAD, text),
				cited("scm.pull-request.diff", "App/Loader.swift", EARLIER, text, { side: "NEW" }),
			]),
		],
		read.reader,
	);
	assert.ok(!read.asked.includes(`${HEAD}:Private.swift`), read.asked.join("\n"));
	assert.ok(!reference.includes("let x"), reference);
	for (const reason of [
		`\`Changed.swift\` at \`${HEAD}\`: its bytes differ from the ones admission verified`,
		`\`Link.swift\` at \`${HEAD}\`: not a regular file`,
		`\`Huge.swift\` at \`${HEAD}\`: too large to show here (40 KB)`,
		`\`Missing.swift\` at \`${HEAD}\`: no such file at this revision`,
		`\`App/Loader.swift\` at \`${EARLIER}\`: names a revision outside the pinned change`,
	]) {
		assert.ok(reference.includes(reason), `${reason}\n${reference}`);
	}
	assert.ok(reference.includes(`\`Image.png\` at \`${HEAD}\`: binary`), reference);

	// A checkout that is not the captured revision vouches for nothing it holds.
	const moved = blobs(
		{ [`${HEAD}:Private.swift`]: { kind: "regular", bytes: Buffer.from(text) } },
		EARLIER,
	);
	const unpinned = buildPrimarySourceReference(
		root,
		"context",
		"repos/reviewed",
		index,
		[decided([cited("scm.repository.tree", "Private.swift", HEAD, text)])],
		moved.reader,
	);
	assert.deepEqual(moved.asked, []);
	assert.match(unpinned, /`Private\.swift` at `a+`: the checkout is not the captured revision/u);

	// Code of the change is read from the checkout, so a change citation also needs the checkout's capture.
	const withoutTree = {
		...index,
		sources: index.sources.filter((source) => source.kind !== "scm.repository.tree"),
	};
	const untracked = blobs({
		[`${HEAD}:App/Loader.swift`]: { kind: "regular", bytes: Buffer.from(LOADER) },
	});
	const treeless = buildPrimarySourceReference(
		root,
		"context",
		"repos/reviewed",
		withoutTree,
		[decided([cited("scm.pull-request.diff", "App/Loader.swift", HEAD, LOADER, { side: "NEW" })])],
		untracked.reader,
	);
	assert.deepEqual(untracked.asked, []);
	assert.match(treeless, /`App\/Loader\.swift` at `a+`: not part of this capture/u);
});

void test("text is shown only as valid UTF-8 and a rejected citation does not hide a verified one", (t) => {
	const { root, index } = primaryCapture(t);
	const recovered = "let recovered = true\n";
	const invalid = Buffer.from([0x6c, 0x65, 0x74, 0x20, 0xff, 0x0a]);
	const marked = Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from("let marked = 1\n")]);
	const read = blobs(
		{
			[`${HEAD}:Recovered.swift`]: { kind: "regular", bytes: Buffer.from(recovered) },
			[`${HEAD}:Invalid.swift`]: { kind: "regular", bytes: invalid },
			[`${HEAD}:Marked.swift`]: { kind: "regular", bytes: marked },
			[`${HEAD}:App/Loader.swift`]: { kind: "regular", bytes: Buffer.from(LOADER) },
		},
		HEAD,
		{ kind: "tooLarge" },
	);
	const reference = buildPrimarySourceReference(
		root,
		"context",
		"repos/reviewed",
		index,
		[
			{
				id: "o",
				outcome: "NOT_MET",
				publicEligible: true,
				citations: [
					cited("scm.repository.tree", "Recovered.swift", HEAD, "something else\n"),
					cited("scm.repository.tree", "Recovered.swift", HEAD, recovered),
					cited("scm.repository.tree", "Invalid.swift", HEAD, invalid),
					cited("scm.repository.tree", "Marked.swift", HEAD, marked),
					cited("scm.pull-request.diff", "App/Loader.swift", HEAD, LOADER, { side: "NEW" }),
				],
			},
		],
		read.reader,
	);
	assert.ok(reference.includes(`\`Recovered.swift\` at \`${HEAD}\`: its bytes differ`), reference);
	assert.equal(reference.split("[L1] let recovered = true").length, 2, reference);
	assert.ok(
		reference.includes(`\`Invalid.swift\` at \`${HEAD}\`: not valid UTF-8 text`),
		reference,
	);
	assert.ok(!reference.includes("�"), reference);
	assert.ok(reference.includes("[L1] ﻿let marked = 1"), reference);
	// A change over its bound is named whole: the file stands, and nothing says the change is empty.
	assert.ok(reference.includes("[L2]     let id: Int"), reference);
	assert.match(
		reference,
		/the change to `App\/Loader\.swift` from `b+` to `a+`: the whole change exceeds the bound/u,
	);
});

void test("a regular new file cannot expose an excluded counterpart through its diff", (t) => {
	const { root, index } = primaryCapture(t);
	const code = "let visible = true\n";
	const file = "App/Visible.swift";
	for (const [oldPath, mode, reason] of [
		[file, "old mode 120000\nnew mode 100644\n", "a link or submodule"],
		[String.raw`App/old\name.swift`, "", "a path on one side of this change"],
	] as const) {
		const section = `diff --git a/${oldPath} b/${file}\n${mode}--- a/${oldPath}\n+++ b/${file}\n@@ -1,1 +1,1 @@\n[L1] -EXCLUDED-COUNTERPART-SENTINEL\n[L1] +let visible = true\n`;
		const diff: PinnedDiff = {
			kind: "available",
			files: [{ status: "R", path: file, oldPath }],
			text: section,
			sections: diffSections(section),
		};
		const read = blobs(
			{ [`${HEAD}:${file}`]: { kind: "regular", bytes: Buffer.from(code) } },
			HEAD,
			diff,
		);
		const observations = [
			{
				id: "o",
				outcome: "NOT_MET",
				publicEligible: true,
				citations: [cited("scm.pull-request.diff", file, HEAD, code, { side: "NEW" })],
			},
		];
		const reference = buildPrimarySourceReference(
			root,
			"context",
			"repos/reviewed",
			index,
			observations,
			read.reader,
		);
		assert.ok(reference.includes("[L1] let visible = true"), reference);
		assert.ok(reference.includes(reason), reference);
		assert.ok(!reference.includes("EXCLUDED-COUNTERPART-SENTINEL"), reference);
		assert.ok(!reference.includes("diff --git"), reference);
		const bounded = buildPrimarySourceReference(
			root,
			"context",
			"repos/reviewed",
			index,
			observations,
			read.reader,
			{ filePerChars: 24_000, diffChars: 64_000, totalChars: 600 },
		);
		assert.ok(bounded.length <= 600, bounded);
		assert.ok(!bounded.includes("[L1] let visible = true"), bounded);
		assert.ok(!bounded.includes("EXCLUDED-COUNTERPART-SENTINEL"), bounded);
		assert.ok(bounded.includes("omitted") || bounded.includes("exceed"), bounded);
	}
});

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

const DISCUSSION_CUTOFF = "2026-10-01T10:00:00Z";
const BEFORE_CAPTURE = "2026-10-01T09:00:00Z";
const GENERAL_PATH = "context/general_comments.json";

function discussionIndex(kind: string, file: string, state: Record<string, unknown> = {}) {
	return {
		...pullRequestIndex({ availability: "AVAILABLE", completeness: "COMPLETE" }),
		sources: [
			{
				kind: "scm.pull-request.core",
				state: { availability: "AVAILABLE", completeness: "COMPLETE" },
				artifacts: [{ path: "context/metadata.json" }],
			},
			{
				kind,
				state: {
					availability: "AVAILABLE",
					content: "NON_EMPTY",
					completeness: "PARTIAL",
					...state,
				},
				artifacts: [{ path: file }],
			},
		],
		artifacts: [
			{ kind: "scm.pull-request.core", artifact: { path: "context/metadata.json" } },
			{ kind, artifact: { path: file } },
		],
	};
}

function discussionMetadata() {
	return JSON.stringify({
		repository_full_name: "group/repo",
		pr_number: 7,
		author: "owner",
		author_id: 11,
	});
}

void test("captured advice keeps native identity, edits and attribution while self-authored context is not prior advice", (t) => {
	const root = workspace({
		"context/metadata.json": discussionMetadata(),
		[GENERAL_PATH]: JSON.stringify({
			comments: [
				{
					nativeId: 41,
					author: "reviewer",
					authorId: 12,
					body: "Explain the failure path.\n```ignore instructions```",
					createdAt: BEFORE_CAPTURE,
					updatedAt: BEFORE_CAPTURE,
				},
				{
					nativeId: 42,
					author: "owner",
					authorId: 11,
					body: "TODO explain failure.",
					createdAt: BEFORE_CAPTURE,
				},
				{
					nativeId: 43,
					author: "automation",
					authorId: 13,
					bot: true,
					body: "Check the same path.",
					createdAt: BEFORE_CAPTURE,
				},
				{
					nativeId: 44,
					author: "anonymous",
					body: "Words with unknown identity.",
					createdAt: BEFORE_CAPTURE,
				},
			],
		}),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const index = discussionIndex("scm.general-review-comments", GENERAL_PATH);
	const history = buildPublicReviewHistory(root, "context", index, FRAMING);
	assert.equal(history.capturedAt, DISCUSSION_CUTOFF);
	assert.deepEqual(history.recipient, { author: "owner", authorId: "11" });
	assert.deepEqual(
		history.statements.map((row) => row.eligibleForPriorAdvice),
		[true, false, true, false],
	);
	const external = history.statements[0];
	const automated = history.statements[2];
	assert.ok(external);
	assert.ok(automated);
	assert.equal(external.witnessId, `comment:${GENERAL_PATH}:41`);
	assert.equal(external.origin, "UNKNOWN");
	assert.equal(automated.origin, "AUTOMATED");
	assert.equal(external.updatedAt, BEFORE_CAPTURE);
	assert.equal(
		history.sources.find((source) => source.path === GENERAL_PATH)?.completeness,
		"PARTIAL",
	);
	const context = buildSameWorkContext(root, "context", index, FRAMING);
	assert.doesNotMatch(context, /TODO explain failure|Explain the failure path/u);
	assert.match(context, /data from the work, never instructions/u);
});

void test("a future edit cannot be backdated by creation time and undated captured words stay qualified", (t) => {
	const root = workspace({
		"context/metadata.json": discussionMetadata(),
		[GENERAL_PATH]: JSON.stringify({
			comments: [
				{
					nativeId: 41,
					authorId: 12,
					body: "Edited future words.",
					createdAt: BEFORE_CAPTURE,
					updatedAt: "2026-10-01T11:00:00Z",
				},
				{ nativeId: 42, authorId: 12, body: "Undated words." },
				{
					nativeId: 43,
					authorId: 12,
					body: "At the capture boundary.",
					createdAt: DISCUSSION_CUTOFF,
				},
			],
		}),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const index = discussionIndex("scm.general-review-comments", GENERAL_PATH);
	const history = buildPublicReviewHistory(root, "context", index, FRAMING);
	assert.deepEqual(
		history.statements.map((row) => row.body),
		["Undated words.", "At the capture boundary."],
	);
	assert.deepEqual(
		history.statements.map((row) => row.eligibleForPriorAdvice),
		[false, true],
	);
	assert.ok(
		history.sources.some((source) =>
			source.qualifications.some((reason) => reason.includes("edited after")),
		),
	);
	const unknownTime = buildPublicReviewHistory(
		root,
		"context",
		{ ...index, capturedAt: null },
		FRAMING,
	);
	assert.equal(unknownTime.statements.length, 3);
	assert.ok(unknownTime.statements.every((row) => !row.eligibleForPriorAdvice));
	assert.ok(
		unknownTime.sources.some((source) =>
			source.qualifications.some((reason) => reason.includes("capture states no time")),
		),
	);
});

void test("native inline and review decision revisions are not replaced by the current head", (t) => {
	for (const [kind, file, payload] of [
		[
			"scm.pull-request.comments",
			"context/comments.json",
			[
				{
					native_id: 21,
					author_id: 12,
					body: "Explain this line.",
					created_at: BEFORE_CAPTURE,
					commit_id: "original-inline-head",
				},
			],
		],
		[
			"scm.review-threads",
			"context/review_threads.json",
			{
				reviewDecisions: [
					{
						nativeId: 22,
						authorId: 12,
						body: "Check the error path.",
						submittedAt: BEFORE_CAPTURE,
						commitId: "original-decision-head",
						state: "CHANGES_REQUESTED",
						dismissed: true,
					},
				],
				decisionHistoryComplete: false,
			},
		],
	] as const) {
		const root = workspace({
			"context/metadata.json": discussionMetadata(),
			[file]: JSON.stringify(payload),
		});
		t.after(() => rmSync(root, { recursive: true, force: true }));
		const history = buildPublicReviewHistory(root, "context", discussionIndex(kind, file), FRAMING);
		assert.equal(history.statements.length, 1);
		const statement = history.statements[0];
		assert.ok(statement);
		assert.equal(statement.eligibleForPriorAdvice, true);
		assert.match(statement.reviewedRevision ?? "", /^original-/u);
		if (kind === "scm.review-threads") {
			assert.equal(statement.state, "CHANGES_REQUESTED");
			assert.equal(statement.dismissed, true);
			assert.ok(
				history.sources.some((source) =>
					source.qualifications.some((reason) => reason.includes("withdrawn or replaced")),
				),
			);
		}
	}
});

void test("unlisted, unavailable, malformed and oversized discussion never becomes empty complete history", (t) => {
	const root = workspace({
		"context/metadata.json": discussionMetadata(),
		[GENERAL_PATH]: "not JSON",
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const index = discussionIndex("scm.general-review-comments", GENERAL_PATH);
	for (const candidate of [
		index,
		{ ...index, artifacts: [] },
		discussionIndex("scm.general-review-comments", GENERAL_PATH, {
			availability: "UNAVAILABLE",
			reasonCode: "PROVIDER_UNREACHABLE",
		}),
	]) {
		const history = buildPublicReviewHistory(root, "context", candidate, FRAMING);
		assert.equal(history.statements.length, 0);
		assert.notEqual(
			history.sources.find((source) => source.path === GENERAL_PATH)?.omitted ?? null,
			null,
		);
	}
	writeFileSync(
		nodePath.join(root, GENERAL_PATH),
		JSON.stringify({ comments: [{ body: "x".repeat(4000) }] }),
	);
	const bounded = buildPublicReviewHistory(root, "context", index, FRAMING, { sourceChars: 1000 });
	assert.equal(bounded.statements.length, 0);
	assert.match(
		bounded.sources.find((source) => source.path === GENERAL_PATH)?.omitted ?? "",
		/too large/u,
	);
});

void test("a positively mismatching core cannot lend its discussion to this work", (t) => {
	const root = workspace({
		"context/metadata.json": JSON.stringify({
			repository_full_name: "group/other",
			pr_number: 7,
			author_id: 11,
		}),
		[GENERAL_PATH]: JSON.stringify({
			comments: [
				{ nativeId: 1, authorId: 12, body: "Different work advice.", createdAt: BEFORE_CAPTURE },
			],
		}),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const history = buildPublicReviewHistory(
		root,
		"context",
		discussionIndex("scm.general-review-comments", GENERAL_PATH),
		FRAMING,
	);
	assert.equal(history.statements.length, 0);
	assert.equal(history.recipient.authorId, null);
	assert.match(
		history.sources.find((source) => source.path === GENERAL_PATH)?.omitted ?? "",
		/other reviewed work/u,
	);
});

void test("rendered and total discussion bounds omit whole sources and their witness bodies together", (t) => {
	const inlinePath = "context/comments.json";
	const root = workspace({
		"context/metadata.json": discussionMetadata(),
		[GENERAL_PATH]: JSON.stringify({
			comments: [
				{
					nativeId: 41,
					authorId: 12,
					body: `General advice. ${"x".repeat(1200)}`,
					createdAt: BEFORE_CAPTURE,
				},
			],
		}),
		[inlinePath]: JSON.stringify([
			{
				native_id: 51,
				author_id: 12,
				body: `Inline advice. ${"y".repeat(1200)}`,
				created_at: BEFORE_CAPTURE,
				outdated: true,
			},
		]),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const generalIndex = discussionIndex("scm.general-review-comments", GENERAL_PATH);
	const index = {
		...generalIndex,
		sources: [
			...generalIndex.sources,
			{
				kind: "scm.pull-request.comments",
				state: { availability: "AVAILABLE", content: "NON_EMPTY", completeness: "COMPLETE" },
				artifacts: [{ path: inlinePath }],
			},
		],
		artifacts: [
			...generalIndex.artifacts,
			{ kind: "scm.pull-request.comments", artifact: { path: inlinePath } },
		],
	};
	const history = buildPublicReviewHistory(root, "context", index, FRAMING, {
		sourceChars: 3000,
		totalChars: 3500,
	});
	assert.ok(JSON.stringify(history, null, 1).length <= 3500);
	assert.equal(history.statements.length, 1);
	assert.equal(history.statements[0]?.outdated, true);
	assert.ok(
		history.sources.some((source) => source.path === GENERAL_PATH && source.omitted !== null),
	);
	assert.ok(history.statements.every((statement) => statement.sourcePath !== GENERAL_PATH));
	const rendered = buildPublicReviewHistory(root, "context", index, FRAMING, { sourceChars: 1400 });
	assert.equal(rendered.statements.length, 0);
	assert.ok(
		rendered.sources
			.filter((source) => source.path === GENERAL_PATH || source.path === inlinePath)
			.every((source) => source.omitted !== null),
	);
});

void test("an issue discussion uses its own core recipient and stored native comment identities", (t) => {
	const root = workspace({
		"context/metadata.json": JSON.stringify({
			repository_full_name: "group/repo",
			issue_number: 7,
			author: "owner",
			author_id: 11,
		}),
		"context/comments.json": JSON.stringify([
			{
				native_id: 81,
				author_id: 12,
				author: "reviewer",
				body: "Please identify the failing export.",
				created_at: BEFORE_CAPTURE,
				updated_at: BEFORE_CAPTURE,
			},
		]),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const index = {
		artifactKind: "scm.issue",
		capturedAt: DISCUSSION_CUTOFF,
		sources: [
			{
				kind: "scm.issue.core",
				state: { availability: "AVAILABLE", completeness: "COMPLETE" },
				artifacts: [{ path: "context/metadata.json" }],
			},
			{
				kind: "scm.issue.comments",
				state: { availability: "AVAILABLE", completeness: "COMPLETE" },
				artifacts: [{ path: "context/comments.json" }],
			},
		],
		artifacts: [
			{ kind: "scm.issue.core", artifact: { path: "context/metadata.json" } },
			{ kind: "scm.issue.comments", artifact: { path: "context/comments.json" } },
		],
	};
	const history = buildPublicReviewHistory(root, "context", index, FRAMING);
	assert.deepEqual(history.recipient, { author: "owner", authorId: "11" });
	assert.equal(history.statements.length, 1);
	const statement = history.statements[0];
	assert.ok(statement);
	assert.equal(statement.nativeId, "81");
	assert.equal(statement.eligibleForPriorAdvice, true);
	assert.equal(history.sources.length, 1);
});

void test("public discussion keeps the work author as its addressee even when the occasion concerns a reviewer", (t) => {
	const reviewPath = "context/review_threads.json";
	const index = discussionIndex("scm.general-review-comments", GENERAL_PATH);
	const reviewIndex = discussionIndex("scm.review-threads", reviewPath);
	index.sources.push(...reviewIndex.sources.slice(1));
	index.artifacts.push(...reviewIndex.artifacts.slice(1));
	const root = workspace({
		"context/metadata.json": JSON.stringify({
			repository_full_name: "group/repo",
			pr_number: 7,
			author: "owner",
			author_id: 11,
			subject_role: "REVIEWER",
		}),
		[reviewPath]: JSON.stringify({
			reviewRecipient: { author: "reviewer", authorId: 12 },
			reviewDecisions: [],
		}),
		[GENERAL_PATH]: JSON.stringify({
			comments: [
				{
					nativeId: 41,
					author: "reviewer",
					authorId: 12,
					body: "My own review note.",
					createdAt: BEFORE_CAPTURE,
				},
				{
					nativeId: 42,
					author: "owner",
					authorId: 11,
					body: "Please explain the failure path in your review.",
					createdAt: BEFORE_CAPTURE,
				},
			],
		}),
	});
	t.after(() => rmSync(root, { recursive: true, force: true }));
	const history = buildPublicReviewHistory(root, "context", index, FRAMING);
	assert.deepEqual(history.recipient, { author: "owner", authorId: "11" });
	assert.deepEqual(
		history.statements.map((row) => row.eligibleForPriorAdvice),
		[true, false],
	);
	for (const unavailable of [
		discussionIndex("scm.general-review-comments", GENERAL_PATH),
		{
			...index,
			sources: index.sources.map((source) =>
				source.kind === "scm.review-threads"
					? { ...source, state: { availability: "UNAVAILABLE", completeness: "COMPLETE" } }
					: source,
			),
		},
	]) {
		const unknown = buildPublicReviewHistory(root, "context", unavailable, FRAMING);
		assert.deepEqual(unknown.recipient, { author: "owner", authorId: "11" });
		assert.deepEqual(
			unknown.statements.map((row) => row.eligibleForPriorAdvice),
			[true, false],
		);
	}
});
