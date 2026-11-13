import assert from "node:assert/strict";
import { test } from "node:test";

import { classifySkips, skippedSchemas } from "./check-client-generators.ts";

const NULL_SKIP = 'Schema "null" is not supported, reason: "schema type", skipping';
const nullWarning = (schema: string, property: string) =>
	`warning: ${NULL_SKIP} [context: foundIn=Components.Schemas.${schema} (#/components/schemas/${schema})/${property}]`;

const LOG = [
	`- Additional file comments: <none>${nullWarning("ItemRef", "provider")}`,
	nullWarning("ItemRef", "provider"),
	nullWarning("AdminNote", "author"),
	"warning: Multipart request bodies must always be required, but found an optional one - skipping. Mark as `required: true` to get this body generated. [context: foundIn=Operations.unsubscribe.Input.Body (#/paths/unsubscribe/{token}/POST/requestBody)/multipart/form-data]",
	"warning: Validation warning: Problem encountered when parsing `Schema` [context: codingPath=.paths['/items'].get]",
	"[25/26] Running swift-openapi-generator",
].join("\n");

const json = (name: string) => ({
	content: { "application/json": { schema: { $ref: `#/components/schemas/${name}` } } },
});
const SPEC = {
	paths: {
		"/items": { get: { responses: { "200": json("ItemPage") } } },
		"/admin/notes": { get: { responses: { "200": json("AdminNote") } } },
		"/unsubscribe/{token}": { post: { responses: { "204": { description: "Done" } } } },
	},
	components: {
		schemas: {
			ItemPage: { properties: { items: { items: { $ref: "#/components/schemas/ItemRef" } } } },
			ItemRef: { properties: { provider: { type: "string" } } },
			AdminNote: { properties: { author: { type: "string" } } },
		},
	},
};

void test("reads each distinct skip, also one that starts in the middle of a line", () => {
	assert.deepEqual(skippedSchemas(LOG), [
		{ pointer: "#/components/schemas/ItemRef/provider", message: NULL_SKIP },
		{ pointer: "#/components/schemas/AdminNote/author", message: NULL_SKIP },
		{
			pointer: "#/paths/unsubscribe/{token}/POST/requestBody/multipart/form-data",
			message:
				"Multipart request bodies must always be required, but found an optional one - skipping. Mark as `required: true` to get this body generated.",
		},
	]);
});

void test("a skip fails only when a public response reaches it", () => {
	const { publicSkips, otherSkips } = classifySkips(
		skippedSchemas(LOG),
		SPEC,
		new Set(["GET /items", "POST /unsubscribe/{token}"]),
	);
	assert.deepEqual(
		publicSkips.map(({ pointer }) => pointer),
		["#/components/schemas/ItemRef/provider"],
	);
	assert.deepEqual(
		otherSkips.map(({ pointer }) => pointer),
		[
			"#/components/schemas/AdminNote/author",
			"#/paths/unsubscribe/{token}/POST/requestBody/multipart/form-data",
		],
	);
});

void test("a skip in the response of a public operation fails", () => {
	const skip = {
		pointer: "#/paths/unsubscribe/{token}/POST/responses/204/content",
		message: NULL_SKIP,
	};
	const publicOperations = new Set(["POST /unsubscribe/{token}"]);
	assert.deepEqual(classifySkips([skip], SPEC, publicOperations).publicSkips, [skip]);
	assert.deepEqual(classifySkips([skip], SPEC, new Set()).publicSkips, []);
});
