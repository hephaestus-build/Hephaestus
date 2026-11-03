import assert from "node:assert/strict";
import { test } from "node:test";

import { paginationProblems } from "./check-api-pagination.ts";

const OFFSET = {
	properties: { content: {}, page: {}, size: {}, totalElements: {}, totalPages: {} },
	required: ["content", "page", "size", "totalElements", "totalPages"],
};
const CURSOR = { properties: { content: {}, nextCursor: {} }, required: ["content"] };

const json = (name: string) => ({
	"200": {
		content: { "application/json": { schema: { $ref: `#/components/schemas/${name}` } } },
	},
});
const query = (...names: string[]) => names.map((name) => ({ in: "query", name }));

const spec = (
	schemas: Record<string, unknown>,
	parameters: unknown[] = [],
	responses: unknown = {},
) => ({
	components: { schemas },
	paths: { "/rows": { get: { parameters, responses } } },
});

void test("the two shapes pass, on their own and held by a resource", () => {
	assert.deepEqual(
		paginationProblems(
			spec({ OffsetPageDTORow: OFFSET }, query("page", "size"), json("OffsetPageDTORow")),
		),
		[],
	);
	assert.deepEqual(
		paginationProblems(
			spec(
				{
					CursorPageDTORow: CURSOR,
					Thread: { properties: { replies: { $ref: "#/components/schemas/CursorPageDTORow" } } },
				},
				query("cursor", "size"),
				json("Thread"),
			),
		),
		[],
	);
});

void test("a page shape that drifts fails", () => {
	const drifted = {
		properties: { ...CURSOR.properties, hasNext: {} },
		required: ["content"],
	};
	assert.equal(paginationProblems(spec({ CursorPageDTORow: drifted })).length, 1);
	const optional = { ...OFFSET, required: ["content"] };
	assert.equal(paginationProblems(spec({ OffsetPageDTORow: optional })).length, 1);
});

void test("a paging property outside the two shapes fails", () => {
	for (const property of ["nextCursor", "nextPage", "hasNext", "totalPages", "pageable"]) {
		const problems = paginationProblems(
			spec({ RowList: { properties: { content: {}, [property]: {} } } }),
		);
		assert.equal(problems.length, 1, property);
		assert.match(problems[0] ?? "", new RegExp(`^RowList: ${property}`, "u"));
	}
});

void test("a truncated preview without a way to read on passes", () => {
	assert.deepEqual(
		paginationProblems(spec({ WorkList: { properties: { content: {}, hasMore: {} } } })),
		[],
	);
});

void test("a paging parameter needs its page shape", () => {
	const bareArray = {
		"200": { content: { "application/json": { schema: { type: "array" } } } },
	};
	assert.equal(paginationProblems(spec({}, query("page"), bareArray)).length, 1);
	assert.equal(paginationProblems(spec({}, query("cursor"), bareArray)).length, 1);
	assert.equal(
		paginationProblems(
			spec({ OffsetPageDTORow: OFFSET }, query("cursor"), json("OffsetPageDTORow")),
		).length,
		1,
	);
});

void test("another name for paging fails", () => {
	for (const name of ["after", "before", "limit", "offset"]) {
		assert.equal(paginationProblems(spec({}, query(name), {})).length, 1, name);
	}
});
