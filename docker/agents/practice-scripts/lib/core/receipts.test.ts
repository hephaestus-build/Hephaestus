import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test, type TestContext } from "node:test";
import { requested, scan } from "../../run-bundled.ts";
import { stagePracticeScripts } from "../../stage.ts";
import { parseCandidate, validateCitationCapabilities, practiceInputsSchema } from "./contract.ts";
import { EvidenceReader, noReceipts } from "./evidence-reader.ts";
import { ModelBudget } from "./models-host.ts";
import { runPracticeProgram } from "./program-host.ts";

const DEFAULT_FILES = { "a.ts": "first\nsecond\nthird" };
async function fixture(t: TestContext, files: Record<string, string> = DEFAULT_FILES) {
	const root = await mkdtemp(path.join(tmpdir(), "practice-receipts-"));
	t.after(async () => rm(root, { recursive: true, force: true }));
	for (const [name, text] of Object.entries(files)) {
		await mkdir(path.dirname(path.join(root, name)), { recursive: true });
		await writeFile(path.join(root, name), text);
	}
	const counters = noReceipts();
	const reader = new EvidenceReader(
		{
			repo: root,
			headRevision: "a".repeat(40),
			treeSourceFile: "repos/reviewed/.git/HEAD",
			contextDir: null,
			contextReference: "context",
			change: null,
			sourceKindOf: () => undefined,
		},
		counters,
	);
	return { reader, counters };
}

void test("a complete file receipt records pinned identity, exact returned extent and content digest", async (t) => {
	const { reader, counters } = await fixture(t);
	await reader.answer({ op: "read", path: "a.ts" });
	assert.deepEqual(reader.readReceipts, [
		{
			sourceKind: "scm.repository.tree",
			sourcePath: "repos/reviewed/.git/HEAD",
			path: "a.ts",
			version: "a".repeat(40),
			status: "COMPLETE",
			contentDigest: createHash("sha256").update("first\nsecond\nthird").digest("hex"),
			startLine: 1,
			endLine: 3,
		},
	]);
	assert.deepEqual(counters, { reads: 1, truncatedReads: 0, failedReads: 0 });
});

void test("a later read settles truncation only after the returned extents cover the whole file", async (t) => {
	const { reader, counters } = await fixture(t);
	await reader.answer({ op: "read", path: "a.ts", offsetLine: 1, maxLines: 1 });
	await reader.answer({ op: "read", path: "a.ts", offsetLine: 3, maxLines: 1 });
	assert.equal(counters.truncatedReads, 1);
	assert.equal(
		reader.readReceipts.some((receipt) => receipt.status === "COMPLETE"),
		false,
	);
	await reader.answer({ op: "read", path: "a.ts", offsetLine: 2, maxLines: 1 });
	assert.equal(counters.truncatedReads, 0);
	assert.equal(
		reader.readReceipts.some(
			(receipt) =>
				receipt.status === "COMPLETE" && receipt.startLine === 1 && receipt.endLine === 3,
		),
		true,
	);
});

void test("a citation does not prove a full file read, and repeated reads deduplicate receipts", async (t) => {
	const { reader } = await fixture(t);
	await reader.answer({ op: "cite", source: "tree", path: "a.ts", startLine: 2 });
	assert.deepEqual(reader.readReceipts, []);
	await reader.answer({ op: "read", path: "a.ts" });
	await reader.answer({ op: "read", path: "a.ts" });
	assert.equal(reader.readReceipts.length, 1);
});

void test("source-catalog flags decide side and commit without a provider allowlist", () => {
	const citation = {
		sourceKind: "custom.source",
		sourcePath: "context/a.txt",
		path: "a.txt",
		startLine: 1,
		endLine: 1,
		quote: "first",
	};
	assert.match(
		validateCitationCapabilities(citation, { side: "REQUIRED", commit: "OPTIONAL" }) ?? "",
		/side is required/u,
	);
	assert.equal(
		validateCitationCapabilities(
			{ ...citation, commit: "opaque:v3" },
			{ side: "FORBIDDEN", commit: "OPTIONAL" },
		),
		undefined,
	);
	assert.match(
		validateCitationCapabilities(
			{ ...citation, side: "NEW" },
			{ side: "FORBIDDEN", commit: "OPTIONAL" },
		) ?? "",
		/side is forbidden/u,
	);
});

void test("code that only quotes the assigned scan file gets no MET", async (t) => {
	const { reader, counters } = await fixture(t);
	const met = {
		outcome: "MET",
		summary: "The file meets this practice",
		explanation: "A direct authored result.",
		citations: [
			{
				sourceKind: "scm.repository.tree",
				sourcePath: "repos/reviewed/.git/HEAD",
				path: "a.ts",
				startLine: 1,
				endLine: 1,
				quote: "first",
			},
		],
		evidence: {},
	};
	const parsed = parseCandidate({
		...met,
		coverage: {
			source: "host",
			reads: 0,
			truncatedReads: 0,
			failedReads: 0,
			receipts: [],
			complete: true,
		},
	});
	assert.ok(parsed.ok);
	const inputs = practiceInputsSchema.parse({
		...scan("a.ts"),
		practice: { ...requested().practice, slug: "fixture" },
	});
	const staged = await stagePracticeScripts(
		{ fixture: `export default {meta:{},run:async()=>(${JSON.stringify(met)})};` },
		{ inputs },
	);
	t.after(async () => rm(staged.root, { recursive: true, force: true }));
	const source = Buffer.from(`export default {meta:{},run:async()=>(${JSON.stringify(met)})};`);
	const outcome = await runPracticeProgram(
		{ script: { path: path.join(staged.practices, "fixture.ts"), source } },
		{
			slug: "fixture",
			inputs,
			files: { head: { commit: "a".repeat(40) }, change: null },
			now: inputs.review.startedAt,
			deadline: Date.now() + 10_000,
		},
		{
			reader,
			receipts: counters,
			requiredSources: [],
			models: {},
			budget: new ModelBudget(0, 1),
			review: async () => parsed.candidate,
			signal: new AbortController().signal,
			log: () => undefined,
		},
	);
	assert.equal(outcome.candidate, undefined);
	assert.match(outcome.error ?? "", /^met_coverage_incomplete:/u);
});
