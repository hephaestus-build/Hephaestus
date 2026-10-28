import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { test } from "node:test";

import { z } from "zod";

import { threadRecordSchema } from "./chat/index.ts";
import { documentRecordSchema } from "./docs/index.ts";
import {
	fileRecordSchema,
	issueRecordSchema,
	pullRequestRecordSchema,
	pullRequestChecksRecordSchema,
	repositoryRecordSchema,
} from "./scm/records.ts";

const CONTRACTS = path.join(
	import.meta.dirname,
	"../../../../server/application/src/main/resources/contracts/items",
);

const PAIRS = [
	["chat.thread.schema.json", threadRecordSchema],
	["docs.document.schema.json", documentRecordSchema],
	["scm/pull_request.schema.json", pullRequestRecordSchema],
	["scm/pull_request_review.schema.json", pullRequestRecordSchema],
	["scm/issue.schema.json", issueRecordSchema],
	["../source-use/3.0.0/pull-request-checks.schema.json", pullRequestChecksRecordSchema],
	["scm/repository.schema.json", repositoryRecordSchema],
	["scm/file.schema.json", fileRecordSchema],
] as const;

const schemaKeywords = new Set([
	"type",
	"enum",
	"const",
	"pattern",
	"properties",
	"required",
	"items",
	"anyOf",
	"oneOf",
	"additionalProperties",
]);
function contractShape(value: unknown): unknown {
	if (Array.isArray(value)) {
		return value.map(contractShape);
	}
	if (typeof value !== "object" || value === null) {
		return value;
	}
	const record = z.record(z.string(), z.unknown()).parse(value);
	if (Array.isArray(record.type)) {
		return contractShape({ anyOf: record.type.map((type: unknown) => ({ type })) });
	}
	const kept = Object.fromEntries(
		Object.entries(record)
			.filter(([key]) => schemaKeywords.has(key))
			.map(([key, item]) => [
				key,
				key === "properties"
					? Object.fromEntries(
							Object.entries(z.record(z.string(), z.unknown()).parse(item)).map(
								([name, property]) => [name, contractShape(property)],
							),
						)
					: contractShape(item),
			]),
	);
	if (
		"const" in kept ||
		(Array.isArray(kept.enum) &&
			kept.enum.every((entry) => (entry === null ? "null" : typeof entry) === kept.type))
	) {
		delete kept.type;
	}
	if (kept.type === "object") {
		kept.additionalProperties ??= true;
	}
	if (Array.isArray(kept.required)) {
		kept.required = kept.required.toSorted((a, b) => String(a).localeCompare(String(b)));
	}
	if (Array.isArray(kept.enum)) {
		kept.enum = kept.enum.toSorted((a, b) => String(a).localeCompare(String(b)));
	}
	if (Array.isArray(kept.anyOf)) {
		kept.anyOf = kept.anyOf.toSorted((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
	}
	return kept;
}

const WORK_RECORD = path.join(
	import.meta.dirname,
	"../../../../server/application/src/test/resources/practice-results/work-records/scm.pull_request.json",
);

void test("a pull request record dated at capture parses beside a retained record that names admission fields", () => {
	const fixture: unknown = JSON.parse(readFileSync(WORK_RECORD, "utf8"));
	const { work } = z.object({ work: z.record(z.string(), z.unknown()) }).parse(fixture);
	const retained = pullRequestRecordSchema.parse(work);
	assert.equal(retained.basis.admission_fields?.includes("body"), true);
	assert.equal(retained.basis.captured_at, undefined);

	const captured = pullRequestRecordSchema.parse({
		...work,
		basis: {
			other_fields: "CAPTURE",
			captured_at: "2026-10-01T09:05:00Z",
			mirror_synced_at: "2026-10-01T09:00:00Z",
		},
	});
	assert.equal(captured.basis.captured_at, "2026-10-01T09:05:00Z");
	assert.equal(captured.basis.admission_fields, undefined);

	const untimed = pullRequestRecordSchema.safeParse({
		...work,
		basis: { other_fields: "CAPTURE", captured_at: 1_759_309_500_000 },
	});
	assert.equal(untimed.success, false);
});

for (const [file, schema] of PAIRS) {
	void test(`the kit preserves the server's nested types, enums and required fields for ${file}`, () => {
		const contract: unknown = JSON.parse(readFileSync(path.join(CONTRACTS, file), "utf8"));
		assert.deepEqual(
			contractShape(z.toJSONSchema(schema, { io: "input" })),
			contractShape(contract),
		);
	});
}
