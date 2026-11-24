import assert from "node:assert/strict";
import { test } from "node:test";

import {
	apiOperationClassifications,
	apiOperations,
	operationClassificationProblems,
} from "./lib/api-operation-classification.ts";

const before = apiOperations({ paths: { "/old": { get: { operationId: "old" } } } });
const after = apiOperations({
	paths: {
		"/old": { get: { operationId: "old" } },
		"/threads": {
			post: { operationId: "startThread" },
			get: { operationId: "listThreads" },
		},
	},
});

void test("each method added to the packaged spec needs an explicit classification", () => {
	const start = { operation: "POST /threads", access: "public", callers: ["webapp"] };
	assert.deepEqual(
		operationClassificationProblems(before, after, apiOperationClassifications([start])),
		["Unclassified API operation in the generated diff: GET /threads"],
	);
	assert.deepEqual(
		operationClassificationProblems(
			before,
			after,
			apiOperationClassifications([start, { operation: "GET /threads", access: "reserved" }]),
		),
		[],
	);
});

void test("a removed path cannot remain in the operation registry", () => {
	assert.deepEqual(
		operationClassificationProblems(
			before,
			before,
			apiOperationClassifications([{ operation: "POST /threads", access: "internal" }]),
		),
		["Classified operation is absent from the packaged API: POST /threads"],
	);
});

void test("a changed operation in the packaged diff also needs a classification", () => {
	const changed = apiOperations({
		paths: { "/old": { get: { operationId: "old", deprecated: true } } },
	});
	assert.deepEqual(
		operationClassificationProblems(before, changed, apiOperationClassifications([])),
		["Unclassified API operation in the generated diff: GET /old"],
	);
});

function specWithThreadState(stateType: string): unknown {
	return {
		paths: {
			"/status": { get: { operationId: "getStatus" } },
			"/threads": {
				get: {
					operationId: "listThreads",
					responses: { "200": { $ref: "#/components/responses/Threads" } },
				},
			},
		},
		components: {
			responses: {
				Threads: {
					content: { "application/json": { schema: { $ref: "#/components/schemas/Thread" } } },
				},
			},
			schemas: {
				Thread: { properties: { state: { $ref: "#/components/schemas/State" } } },
				State: { type: stateType, properties: { owner: { $ref: "#/components/schemas/Thread" } } },
			},
		},
	};
}

void test("a change to a reachable schema requires classification of the operation that uses it", () => {
	assert.deepEqual(
		operationClassificationProblems(
			apiOperations(specWithThreadState("string")),
			apiOperations(specWithThreadState("integer")),
			apiOperationClassifications([]),
		),
		["Unclassified API operation in the generated diff: GET /threads"],
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
