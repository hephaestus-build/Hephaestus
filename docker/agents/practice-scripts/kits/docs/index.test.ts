import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { EvidenceReader, noReceipts } from "../../lib/core/evidence-reader.ts";
import { programContext } from "../../lib/core/program-context.ts";
import { docs } from "./index.ts";
import { documentRecordSchema } from "./records.ts";

const metadata = {
	id: "9223372036854775807",
	sourceId: "9007199254740993",
	title: "Deploy safely",
	collection: "Engineering",
	collectionSlug: "engineering",
	slug: "deploy-safely",
	createdBy: "Sam",
	createdByAccountId: "9007199254740993",
	updatedBy: null,
	updatedByAccountId: null,
	archived: false,
};

void test("document records preserve native metadata and bigint ids", () => {
	assert.deepEqual(documentRecordSchema.parse(metadata), metadata);
	assert.equal(documentRecordSchema.safeParse({ ...metadata, id: 1 }).success, false);
	assert.equal(
		documentRecordSchema.safeParse({ ...metadata, createdByAccountId: 1 }).success,
		false,
	);
});

void test("the docs kit reads and cites captured body bytes without inventing absent content", async (t) => {
	const contextDir = await mkdtemp(path.join(tmpdir(), "docs-kit-"));
	t.after(async () => rm(contextDir, { recursive: true, force: true }));
	const reader = new EvidenceReader(
		{
			repo: null,
			headRevision: null,
			treeSourceFile: "",
			contextDir,
			contextReference: "context",
			change: null,
			sourceKindOf: (sourcePath) =>
				sourcePath === "context/document.md" ? "docs.document.core" : undefined,
		},
		noReceipts(),
	);
	const now = "2026-10-14T12:00:00Z";
	const ctx = programContext(
		{
			slug: "safe-deployment",
			files: null,
			now,
			deadline: Date.now() + 10_000,
			inputs: {
				contractVersion: 3,
				kind: "docs.document",
				item: metadata,
				about: null,
				practice: {
					id: "1",
					versionId: "2",
					reviewDigest: `v6:${"a".repeat(64)}`,
					slug: "safe-deployment",
					criteria: "Describe the release checks.",
				},
				scope: {
					kind: "ITEM",
					item: { id: metadata.id, kind: "docs.document" },
					version: "outline:v1",
				},
				review: {
					id: "a69e5675-17c1-4336-9c8d-e3c74b130500",
					origin: "REQUEST",
					startedAt: now,
				},
				previous: null,
			},
		},
		{
			read: async (request) => reader.answer(request),
			review: async () => {
				throw new Error("This script reads captured documents directly");
			},
			log: (message) => assert.fail(message),
			abortSignal: new AbortController().signal,
		},
	);
	const document = docs(ctx);
	assert.deepEqual(await document.body(), { status: "ABSENT" });
	await writeFile(path.join(contextDir, "document.md"), "# Release\nRun the checks first.\n");
	assert.deepEqual(await document.body(), {
		status: "FOUND",
		text: "# Release\nRun the checks first.\n",
	});
	assert.deepEqual(await document.cite(2), {
		sourceKind: "docs.document.core",
		sourcePath: "context/document.md",
		path: "context/document.md",
		startLine: 2,
		endLine: 2,
		quote: "Run the checks first.",
	});
});
