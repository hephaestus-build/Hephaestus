import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import Ajv2020 from "ajv/dist/2020.js";

import { asArray, asRecord, parseJson } from "./lib/json.ts";

const resource = "../server/application/src/main/resources/practices/";
const schema = asRecord(
	parseJson(
		readFileSync(new URL(`${resource}default-catalog.schema.json`, import.meta.url), "utf8"),
	),
	"catalogue schema",
);
const catalogue = asRecord(
	parseJson(readFileSync(new URL(`${resource}default-catalog.json`, import.meta.url), "utf8")),
	"catalogue",
);
const validate = new Ajv2020({ strict: true, allErrors: true }).compile(schema);

function withPractice(change: (practice: Record<string, unknown>) => void) {
	const copy = structuredClone(catalogue);
	const group = asRecord(asArray(copy.groups, "groups")[0], "group");
	const practice = asRecord(asArray(group.practices, "practices")[0], "practice");
	change(practice);
	return copy;
}

function withDefinition(change: (definition: Record<string, unknown>) => void) {
	return withPractice((practice) => change(asRecord(practice.definition, "definition")));
}

await test("all shipped practices use complete definitions with immutable outer kinds", () => {
	assert.equal(validate(catalogue), true, JSON.stringify(validate.errors));
});

for (const [name, change] of [
	[
		"a retired flat criteria field",
		(p) => {
			p.criteria = "Old flat standard";
		},
	],
	[
		"a missing immutable kind",
		(p) => {
			delete p.kind;
		},
	],
	[
		"a malformed item kind",
		(p) => {
			p.kind = "scm.pull_request.extra";
		},
	],
	[
		"a retired resource field",
		(p) => {
			p.precompute = "script.ts";
		},
	],
] satisfies [string, (practice: Record<string, unknown>) => void][]) {
	await test(`the catalog rejects ${name}`, () =>
		assert.equal(validate(withPractice(change)), false));
}

for (const [name, change] of [
	[
		"missing required nullable about",
		(d) => {
			delete d.about;
		},
	],
	[
		"a non-object scan",
		(d) => {
			d.scan = "scm.file";
		},
	],
	[
		"a retired outcome-era subject",
		(d) => {
			d.subject = "AUTHOR";
		},
	],
	[
		"an invalid about role",
		(d) => {
			d.about = "OWNER";
		},
	],
	[
		"a null delivery boolean",
		(d) => {
			d.lineNotes = null;
		},
	],
	[
		"a flat event list",
		(d) => {
			d.reviewOn = ["scm.pull_request.opened"];
		},
	],
	[
		"an invalid event name",
		(d) => {
			d.reviewOn = { events: ["scm.pull_request"], state: {} };
		},
	],
	[
		"a retired evidence stance",
		(d) => {
			d.reads = {
				contract: "3.0.0",
				sources: [{ source: "scm.pull-request.core", need: "EXHAUSTIVE" }],
			};
		},
	],
	[
		"a retired review mode",
		(d) => {
			d.reviewedBy = { by: "LANGUAGE_MODEL", humanReason: null, limits: [] };
		},
	],
	[
		"an empty applicability alternative list",
		(d) => {
			d.appliesWhen = { otherwise: "No relevant code", anyOf: [] };
		},
	],
	[
		"two applicability predicates in one clause",
		(d) => {
			d.appliesWhen = {
				otherwise: "No relevant code",
				anyOf: [{ changedPath: ["**/*.swift"], diffContains: ["View"] }],
			};
		},
	],
	[
		"an empty scan selection",
		(d) => {
			d.scan = { itemKind: "scm.file", include: [], exclude: [], context: [] };
		},
	],
] satisfies [string, (definition: Record<string, unknown>) => void][]) {
	await test(`the definition rejects ${name}`, () =>
		assert.equal(validate(withDefinition(change)), false));
}

await test("event-free developerless definitions retain explicit nullable keys", () => {
	const value = withDefinition((d) => {
		d.about = null;
		d.reviewOn = { events: [], state: {} };
	});
	assert.equal(validate(value), true, JSON.stringify(validate.errors));
});

await test("a definition can omit its optional scan", () => {
	const value = withDefinition((d) => {
		delete d.scan;
	});
	assert.equal(validate(value), true, JSON.stringify(validate.errors));
});

await test("the catalog publishes exactly thirteen scan definitions", () => {
	const scans = asArray(catalogue.groups, "groups").flatMap((groupValue) => {
		const group = asRecord(groupValue, "group");
		return asArray(group.practices, "practices").filter((practiceValue) => {
			const practice = asRecord(practiceValue, "practice");
			const { scan } = asRecord(practice.definition, "definition");
			return scan !== undefined && scan !== null;
		});
	});
	assert.equal(scans.length, 13);
});

/**
 * The words a developer and an admin read. A rename of the review output field `reasoning` to
 * `evidenceRationale` once ran over this file as plain text and shipped "Record significant
 * decisions and the evidenceRationale" (v0.74.0 to v0.80.x); workspaces copy these words on adoption,
 * so a slip here outlives the fix. Criteria may name code, so only names carry the identifier check.
 */
await test("no shipped text carries a review output field or a name a code identifier", () => {
	const raw = readFileSync(new URL(`${resource}default-catalog.json`, import.meta.url), "utf8");
	assert.doesNotMatch(raw, /\b(?:evidenceRationale|practiceSlug)\b/u);
	const camelCase = /\b[a-z]{2,}[A-Z][a-z]+/u;
	for (const groupValue of asArray(catalogue.groups, "groups")) {
		const group = asRecord(groupValue, "group");
		assert.doesNotMatch(String(group.name), camelCase, `group ${String(group.slug)}`);
		for (const practiceValue of asArray(group.practices, "practices")) {
			const practice = asRecord(practiceValue, "practice");
			assert.doesNotMatch(
				String(asRecord(practice.definition, "definition").name),
				camelCase,
				`name of ${String(practice.slug)}`,
			);
		}
	}
});
