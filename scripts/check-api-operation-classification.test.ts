import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
	API_OPERATION_REGISTRY,
	apiOperationClassifications,
	apiOperations,
	operationClassificationProblems,
} from "./lib/api-operation-classification.ts";

const spec = {
	paths: {
		"/threads": {
			parameters: [],
			get: { operationId: "listThreads" },
			post: { operationId: "startThread" },
			delete: { operationId: "removeThreads" },
		},
	},
};
const operations = apiOperations(spec);
const entries = [
	{ operation: "GET /threads", access: "public", callers: ["webapp"] },
	{ operation: "POST /threads", access: "internal" },
	{ operation: "DELETE /threads", access: "reserved" },
];

void test("the registry classifies each method and ignores path metadata", () => {
	assert.deepEqual([...operations.values()], ["listThreads", "startThread", "removeThreads"]);
	assert.deepEqual(
		operationClassificationProblems(operations, apiOperationClassifications(entries)),
		[],
	);
});

void test("missing and stale classifications both fail", () => {
	const classes = apiOperationClassifications([
		...entries.slice(1),
		{ operation: "POST /old", access: "internal" },
	]);
	assert.deepEqual(operationClassificationProblems(operations, classes), [
		"Unclassified API operation: GET /threads",
		"Classified operation is absent from the packaged API: POST /old",
	]);
});

void test("an operation has exactly one entry", () => {
	assert.throws(
		() => apiOperationClassifications([...entries, entries[0]]),
		/Duplicate operation classification: GET \/threads/u,
	);
});

void test("a public operation needs at least one named caller", () => {
	for (const callers of [undefined, [], ["external"], ["extension", "extension"]]) {
		assert.throws(
			() =>
				apiOperationClassifications([{ operation: "POST /threads", access: "public", callers }]),
			/POST \/threads needs a distinct, named 1\.0 caller/u,
		);
	}
});

void test("the CLI annotates invalid registries without a stack trace", async (context) => {
	const cwd = await mkdtemp(path.join(tmpdir(), "api-operation-classification-"));
	context.after(async () => rm(cwd, { recursive: true, force: true }));
	await mkdir(path.join(cwd, "server"));
	await writeFile(path.join(cwd, "server/openapi.yaml"), JSON.stringify(spec));
	await writeFile(path.join(cwd, API_OPERATION_REGISTRY), JSON.stringify(entries.slice(1)));
	const command = fileURLToPath(new URL("check-api-operation-classification.ts", import.meta.url));
	const result = spawnSync(process.execPath, [command], { cwd, encoding: "utf8" });
	assert.equal(result.status, 1, result.stderr);
	assert.match(
		result.stderr,
		/^::error file=server\/public-api-operations\.json::Unclassified API operation: GET \/threads%0A/u,
	);
	assert.equal(result.stderr.trimEnd().split("\n").length, 1);
});
