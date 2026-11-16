import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, test } from "node:test";

import { stringify } from "yaml";

import { lintSpec } from "./check-openapi-lint.ts";
import { asArray, asRecord, asString, parseJson } from "./lib/json.ts";

const directory = await mkdtemp(path.join(tmpdir(), "openapi-lint-"));
after(async () => rm(directory, { recursive: true, force: true }));

const SCHEMA = { schema: { type: "string" } };

/** A spec with one deprecated operation whose 200 response is shared through `components`. */
const spec = (okHeaders: Record<string, unknown>, tags = [{ name: "items" }]) => ({
	openapi: "3.1.0",
	info: { title: "Fixture", version: "1.0.0" },
	tags,
	paths: {
		"/items": {
			get: {
				operationId: "listItems",
				tags: ["items"],
				deprecated: true,
				responses: {
					"200": { $ref: "#/components/responses/Ok" },
					"404": { description: "No such item" },
				},
			},
		},
	},
	components: { responses: { Ok: { description: "The items", headers: okHeaders } } },
});

/** The rule of each problem that Redocly reports for `fixture`. */
async function problems(name: string, fixture: unknown): Promise<string[]> {
	const file = path.join(directory, `${name}.yaml`);
	await writeFile(file, stringify(fixture));
	const { passed, report } = lintSpec(file, "json");
	const rules = asArray(asRecord(parseJson(report), "report").problems, "problems").map((problem) =>
		asString(asRecord(problem, "problem").ruleId, "ruleId"),
	);
	assert.equal(passed, rules.length === 0, report);
	return rules;
}

void test("a deprecated operation passes when each success response declares Deprecation", async () => {
	assert.deepEqual(await problems("declared", spec({ Deprecation: SCHEMA, Sunset: SCHEMA })), []);
});

void test("a deprecated operation fails on a success response without Deprecation", async () => {
	assert.deepEqual(await problems("missing", spec({ Sunset: SCHEMA })), [
		"hephaestus/deprecation-header",
	]);
});

void test("duplicate tags and a header without a schema fail", async () => {
	const rules = await problems(
		"structure",
		spec({ Deprecation: { description: "No schema" } }, [{ name: "items" }, { name: "items" }]),
	);
	assert.deepEqual(rules.toSorted(), ["no-duplicated-tag-names", "struct"]);
});
