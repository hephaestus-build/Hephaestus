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

void test("the CLI points errors to their source and limits classification guidance", async (context) => {
	const cwd = await mkdtemp(path.join(tmpdir(), "api-operation-classification-"));
	context.after(async () => rm(cwd, { recursive: true, force: true }));
	await mkdir(path.join(cwd, "server"));
	const command = fileURLToPath(new URL("check-api-operation-classification.ts", import.meta.url));
	const scenarios = [
		{
			packaged: { paths: { "/threads": { get: {} } } },
			registry: JSON.stringify(entries),
			prefix: /^::error file=server\/openapi\.yaml::.*GET \/threads operationId/u,
			guidance: false,
		},
		{
			packaged: spec,
			registry: "[invalid-json]",
			prefix: /^::error file=server\/public-api-operations\.json::/u,
			guidance: false,
		},
		{
			packaged: spec,
			registry: JSON.stringify(entries.slice(1)),
			prefix:
				/^::error file=server\/public-api-operations\.json::Unclassified API operation: GET \/threads%0A/u,
			guidance: true,
		},
	];
	for (const { packaged, registry, prefix, guidance } of scenarios) {
		await writeFile(path.join(cwd, "server/openapi.yaml"), JSON.stringify(packaged));
		await writeFile(path.join(cwd, API_OPERATION_REGISTRY), registry);
		const result = spawnSync(process.execPath, [command], { cwd, encoding: "utf8" });
		assert.equal(result.status, 1, result.stderr);
		assert.match(result.stderr, prefix);
		assert.equal(result.stderr.includes("Set access to public, internal, or reserved"), guidance);
		assert.equal(result.stderr.trimEnd().split("\n").length, 1);
	}
});
