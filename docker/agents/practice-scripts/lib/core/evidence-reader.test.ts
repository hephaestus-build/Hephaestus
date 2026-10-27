import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, test, type TestContext } from "node:test";
import { DIFF_SOURCE, TREE_SOURCE } from "../../kits/scm/sources.ts";

import { parseDiff } from "../../kits/scm/diff-parser.ts";
import {
	EvidenceReader,
	MAX_READ_BYTES,
	noReceipts,
	type EvidenceSources,
	type Receipts,
} from "./evidence-reader.ts";
import { UnratedError } from "./practice-script.ts";

const HEAD = "a".repeat(40);
const RECORD_SOURCE = "scm.pull-request.core";

const CHANGE = parseDiff(
	[
		"diff --git a/src/cart.ts b/src/cart.ts",
		"--- a/src/cart.ts",
		"+++ b/src/cart.ts",
		"@@ -1,2 +1,3 @@",
		" export class Cart {",
		"-  count = 0;",
		"+  // increment the item count by one",
		"+  count += 1;",
	].join("\n"),
);

async function captures(t: TestContext) {
	const root = await mkdtemp(path.join(tmpdir(), "evidence-reader-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	const repo = path.join(root, "repo");
	const contextDir = path.join(root, "context");
	await mkdir(path.join(repo, "src"), { recursive: true });
	await mkdir(path.join(repo, ".git"));
	await mkdir(contextDir);
	await writeFile(
		path.join(repo, "src", "cart.ts"),
		"export class Cart {\n  // increment the item count by one\n  count += 1;\n}\n",
	);
	await writeFile(path.join(repo, "empty.txt"), "");
	await writeFile(path.join(repo, "logo.png"), Buffer.from([0x89, 0x50, 0x00, 0x47]));
	await writeFile(path.join(repo, ".git", "HEAD"), "ref: refs/heads/main\n");
	await writeFile(path.join(root, "secret.txt"), "token\n");
	await symlink(path.join(root, "secret.txt"), path.join(repo, "link.txt"));
	await writeFile(
		path.join(contextDir, "metadata.json"),
		'{\n  "pr_number": 7,\n  "title": "Count"\n}\n',
	);
	const sources: EvidenceSources = {
		repo,
		headRevision: HEAD,
		treeSourceFile: "repos/reviewed/.git/HEAD",
		contextDir,
		contextReference: "context",
		change: { base: "b".repeat(40), head: HEAD, files: CHANGE },
		sourceKindOf: (sourcePath) =>
			sourcePath === "context/metadata.json" ? RECORD_SOURCE : undefined,
	};
	const receipts = noReceipts();
	return {
		root,
		repo,
		contextDir,
		sources,
		receipts,
		reader: new EvidenceReader(sources, receipts),
	};
}

async function unrated(promise: Promise<unknown>, reason: string, message?: RegExp): Promise<void> {
	await assert.rejects(promise, (error: unknown) => {
		assert.ok(error instanceof UnratedError, String(error));
		assert.equal(error.reason, reason);
		if (message !== undefined) {
			assert.match(error.message, message);
		}
		return true;
	});
}

void describe("reads of the head tree", () => {
	void test("a file is FOUND with its text, a binary file is BINARY, and a missing path is ABSENT", async (t) => {
		const { reader, receipts } = await captures(t);
		const read = async (file: string) => reader.answer({ op: "read", path: file });
		assert.deepEqual(await read("src/cart.ts"), {
			status: "FOUND",
			text: "export class Cart {\n  // increment the item count by one\n  count += 1;\n}\n",
			truncated: false,
		});
		assert.deepEqual(await read("empty.txt"), { status: "FOUND", text: "", truncated: false });
		assert.deepEqual(await read("logo.png"), { status: "BINARY" });
		assert.deepEqual(await read("src/absent.ts"), { status: "ABSENT" });
		assert.deepEqual(await read("src"), { status: "ABSENT" });
		assert.deepEqual(receipts, { reads: 5, truncatedReads: 0, failedReads: 0 });
	});

	void test("a file larger than one read is cut, and the read says so and counts as cut", async (t) => {
		const { reader, repo, receipts } = await captures(t);
		await writeFile(path.join(repo, "large.txt"), "x".repeat(MAX_READ_BYTES + 10));
		const large = await reader.answer({ op: "read", path: "large.txt" });
		assert.ok(large.status === "FOUND");
		assert.equal(large.truncated, true);
		assert.equal(large.text.length, MAX_READ_BYTES);
		assert.deepEqual(receipts, { reads: 1, truncatedReads: 1, failedReads: 0 });
	});

	void test("a read that fails is unavailable and counted as failed, never an absent file", async (t) => {
		const { reader, repo, receipts } = await captures(t);
		await symlink("loop.txt", path.join(repo, "loop.txt"));
		await unrated(reader.answer({ op: "read", path: "loop.txt" }), "refused");
		assert.deepEqual(receipts, { reads: 1, truncatedReads: 0, failedReads: 1 });
	});

	void test("a path outside the tree is refused, also through a link", async (t) => {
		const { reader, receipts } = await captures(t);
		await unrated(
			reader.answer({ op: "read", path: "../secret.txt" }),
			"refused",
			/outside the captured source|repository path|symlink/u,
		);
		await unrated(reader.answer({ op: "read", path: "link.txt" }), "refused");
		assert.deepEqual(receipts, { reads: 2, truncatedReads: 0, failedReads: 2 });
	});

	void test("work without a tree has none to read", async (t) => {
		const { sources } = await captures(t);
		const treeless = new EvidenceReader({ ...sources, repo: null }, noReceipts());
		await unrated(
			treeless.answer({ op: "read", path: "src/cart.ts" }),
			"unavailable",
			/no captured file tree/u,
		);
	});
});

void describe("listings and searches", () => {
	for (const glob of [undefined, "**/*.txt"]) {
		void test(`Git matches do not consume a search bound (${glob ?? "recursive"})`, async (t) => {
			const { reader, repo } = await captures(t);
			await writeFile(path.join(repo, ".git", "noise.txt"), "unique-match\n".repeat(20));
			await writeFile(path.join(repo, "visible.txt"), "unique-match\nunique-match\n");
			const result = await reader.answer({
				op: "grep",
				pattern: "unique-match",
				maxResults: 1,
				...(glob === undefined ? {} : { glob }),
			});
			assert.deepEqual(result, {
				matches: [{ file: "visible.txt", line: 1, content: "unique-match" }],
				truncated: true,
			});
		});
	}
	void test("only a search of the whole checkout's contents that ran to the end proves the search", async (t) => {
		const { reader } = await captures(t);
		await reader.answer({ op: "grep", pattern: "ControllerAdvice", glob: "*.java" });
		await reader.answer({ op: "list", glob: "src/**" });
		assert.deepEqual(reader.readReceipts, []);

		await reader.answer({ op: "grep", pattern: "ControllerAdvice" });
		assert.deepEqual(reader.readReceipts, [
			{
				sourceKind: TREE_SOURCE,
				sourcePath: "repos/reviewed/.git/HEAD",
				path: ".",
				version: HEAD,
				status: "SEARCHED",
			},
		]);
	});
	void test("an aborted read or search does no work and changes no receipts", async (t) => {
		const { reader, receipts } = await captures(t);
		const signal = AbortSignal.abort(new UnratedError("cancelled", "the attempt ended"));
		for (const request of [
			{ op: "read", path: "src/cart.ts" },
			{ op: "grep", pattern: "count" },
		] as const) {
			await assert.rejects(reader.answer(request, signal), /the attempt ended/u);
		}
		assert.deepEqual(receipts, noReceipts());
	});
	void test("a listing leaves out the repository's .git folder, and a long one is cut and says so", async (t) => {
		const { reader, repo, receipts } = await captures(t);
		assert.deepEqual(await reader.answer({ op: "list", glob: "src/**" }), {
			paths: ["src/cart.ts"],
			truncated: false,
		});
		const all = await reader.answer({ op: "list", tree: "head" });
		assert.equal(
			all.paths.some((file) => file.startsWith(".git")),
			false,
		);
		await mkdir(path.join(repo, "many"));
		await Promise.all(
			Array.from({ length: 5001 }, async (_, i) =>
				writeFile(path.join(repo, "many", `${i}.txt`), ""),
			),
		);
		const many = await reader.answer({ op: "list", glob: "many/**" });
		assert.equal(many.truncated, true);
		assert.equal(many.paths.length, 5000);
		assert.deepEqual(receipts, { reads: 3, truncatedReads: 1, failedReads: 0 });
	});

	void test("a search returns its matches, and one that reaches its bound says it may be cut", async (t) => {
		const { reader, repo, receipts } = await captures(t);
		await writeFile(path.join(repo, "src", "total.ts"), "count\ncount\n");
		const found = await reader.answer({
			op: "grep",
			pattern: "count \\+=",
			glob: "*.ts",
		});
		assert.deepEqual(found, {
			matches: [{ file: "src/cart.ts", line: 3, content: "count += 1;" }],
			truncated: false,
		});
		const bounded = await reader.answer({
			op: "grep",
			pattern: "count",
			glob: "*.ts",
			fixedString: true,
			maxResults: 2,
		});
		assert.equal(bounded.matches.length, 2);
		assert.equal(bounded.truncated, true);
		assert.deepEqual(receipts, { reads: 2, truncatedReads: 1, failedReads: 0 });
	});
});

void test("a search and a read leave out the repository's .git folder, as a listing does", async (t) => {
	const { reader, repo } = await captures(t);
	await writeFile(
		path.join(repo, ".git", "config"),
		"url = https://token@example.org/team/project\n",
	);
	const found = await reader.answer({ op: "grep", pattern: "token@" });
	assert.deepEqual(found.matches, []);
	await assert.rejects(reader.answer({ op: "read", path: ".git/config" }), {
		name: "UnratedError",
		reason: "refused",
	});
});

void describe("captured records", () => {
	void test("a record is FOUND or ABSENT, and a name outside the context folder is refused", async (t) => {
		const { reader, sources, receipts } = await captures(t);
		assert.deepEqual(await reader.answer({ op: "record", name: "metadata.json" }), {
			status: "FOUND",
			text: '{\n  "pr_number": 7,\n  "title": "Count"\n}\n',
		});
		assert.deepEqual(await reader.answer({ op: "record", name: "comments.json" }), {
			status: "ABSENT",
		});
		await unrated(reader.answer({ op: "record", name: "../repo/src/cart.ts" }), "refused");
		const uncaptured = new EvidenceReader({ ...sources, contextDir: null }, noReceipts());
		assert.deepEqual(await uncaptured.answer({ op: "record", name: "metadata.json" }), {
			status: "ABSENT",
		});
		assert.deepEqual(receipts, { reads: 3, truncatedReads: 0, failedReads: 1 });
	});
});

void describe("citations", () => {
	void test("a citation of the tree, the change or a record carries the exact quote and the canonical coordinates", async (t) => {
		const { reader, receipts } = await captures(t);
		assert.deepEqual(
			await reader.answer({
				op: "cite",
				source: "tree",
				path: "src/cart.ts",
				startLine: 2,
				endLine: 3,
			}),
			{
				sourceKind: TREE_SOURCE,
				sourcePath: "repos/reviewed/.git/HEAD",
				path: "src/cart.ts",
				commit: HEAD,
				startLine: 2,
				endLine: 3,
				quote: "  // increment the item count by one\n  count += 1;",
			},
		);
		assert.deepEqual(
			await reader.answer({
				op: "cite",
				source: "change",
				path: "src/cart.ts",
				side: "NEW",
				startLine: 2,
			}),
			{
				sourceKind: DIFF_SOURCE,
				sourcePath: "context/change.json",
				path: "src/cart.ts",
				side: "NEW",
				startLine: 2,
				endLine: 2,
				quote: "  // increment the item count by one",
			},
		);
		assert.partialDeepStrictEqual(
			await reader.answer({
				op: "cite",
				source: "change",
				path: "src/cart.ts",
				side: "OLD",
				startLine: 2,
			}),
			{ side: "OLD", quote: "  count = 0;" },
		);
		assert.deepEqual(
			await reader.answer({ op: "cite", source: "record", name: "metadata.json", startLine: 2 }),
			{
				sourceKind: RECORD_SOURCE,
				sourcePath: "context/metadata.json",
				path: "context/metadata.json",
				startLine: 2,
				endLine: 2,
				quote: '  "pr_number": 7,',
			},
		);
		assert.deepEqual(receipts, noReceipts() satisfies Receipts);
	});

	void test("a citation of lines that the source does not hold is an error, never a quote", async (t) => {
		const { reader, sources } = await captures(t);
		await unrated(
			reader.answer({
				op: "cite",
				source: "tree",
				path: "src/cart.ts",
				startLine: 3,
				endLine: 2,
			}),
			"error",
			/ends before it starts/u,
		);
		await unrated(
			reader.answer({
				op: "cite",
				source: "tree",
				path: "src/cart.ts",
				startLine: 40,
			}),
			"error",
			/has 5 lines, not 40/u,
		);
		await unrated(
			reader.answer({
				op: "cite",
				source: "tree",
				path: "src/absent.ts",
				startLine: 1,
			}),
			"unavailable",
			/could not be read/u,
		);
		await unrated(
			reader.answer({
				op: "cite",
				source: "tree",
				path: "src/cart.ts",
				startLine: 1,
				endLine: 300,
			}),
			"too-large",
		);
		await unrated(
			reader.answer({
				op: "cite",
				source: "change",
				path: "src/cart.ts",
				side: "NEW",
				startLine: 1,
			}),
			"error",
			/does not add line 1/u,
		);
		await unrated(
			reader.answer({ op: "cite", source: "change", path: "README.md", side: "NEW", startLine: 1 }),
			"error",
			/does not touch that file/u,
		);
		await unrated(
			reader.answer({ op: "cite", source: "record", name: "comments.json", startLine: 1 }),
			"error",
			/not a captured source/u,
		);
		const unchanged = new EvidenceReader({ ...sources, change: null }, noReceipts());
		await unrated(
			unchanged.answer({
				op: "cite",
				source: "change",
				path: "src/cart.ts",
				side: "NEW",
				startLine: 2,
			}),
			"unavailable",
			/no captured change/u,
		);
	});
});

void test("a byte limit inside a valid UTF-8 character remains truncation, not a capture failure", async (t) => {
	const { reader, sources, receipts } = await captures(t);
	assert.ok(sources.repo !== null);
	await writeFile(path.join(sources.repo, "utf8.ts"), `${"x".repeat(MAX_READ_BYTES - 1)}😀`);
	const result = await reader.answer({ op: "read", path: "utf8.ts" });
	assert.ok(result.status === "FOUND");
	assert.equal(result.truncated, true);
	assert.equal(result.text.length, MAX_READ_BYTES - 1);
	assert.deepEqual(receipts, { reads: 1, truncatedReads: 1, failedReads: 0 });
	assert.ok(reader.readReceipts.every((receipt) => receipt.status !== "COMPLETE"));
});

void test("frozen scan grants also govern metadata reads and record citations", async (t) => {
	const { reader, sources } = await captures(t);
	sources.permittedPaths = new Set(["src/cart.ts"]);
	await unrated(
		reader.answer({ op: "record", name: "metadata.json" }),
		"refused",
		/frozen context paths/u,
	);
	await unrated(
		reader.answer({ op: "cite", source: "record", name: "metadata.json", startLine: 1 }),
		"refused",
		/frozen context paths/u,
	);
	assert.equal(
		reader.allowsCitation({
			sourceKind: "scm.repository.tree",
			sourcePath: "repos/reviewed/.git/HEAD",
			path: "foreign.ts",
			startLine: 1,
			endLine: 1,
			quote: "foreign",
		}),
		false,
	);
});

void test("governed receipts retain a middle gap until every line through EOF was read", async (t) => {
	const { reader, repo, receipts } = await captures(t);
	await writeFile(
		path.join(repo, "long.txt"),
		Array.from({ length: 10_000 }, (_, i) => `line ${i + 1}`).join("\n"),
	);
	await reader.answer({ op: "read", path: "long.txt", offsetLine: 1, maxLines: 2000 });
	await reader.answer({ op: "read", path: "long.txt", offsetLine: 9999 });
	assert.equal(receipts.truncatedReads, 1);
	assert.ok(reader.readReceipts.every((receipt) => receipt.status !== "COMPLETE"));
	await reader.answer({ op: "read", path: "long.txt", offsetLine: 2001, maxLines: 7998 });
	assert.equal(receipts.truncatedReads, 0);
	assert.ok(
		reader.readReceipts.some(
			(receipt) =>
				receipt.status === "COMPLETE" && receipt.startLine === 1 && receipt.endLine === 10_000,
		),
	);
});
