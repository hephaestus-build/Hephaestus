import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import addFormats from "ajv-formats";
import Ajv2020 from "ajv/dist/2020.js";
import { asRecord, parseJson } from "./lib/json.ts";

await test("serialized absent, matched and undecidable reports conform to source contract 3.0.0", async () => {
	const schema = asRecord(
		parseJson(
			await readFile(
				new URL(
					"../server/application/src/main/resources/contracts/source-use/3.0.0/automated-review-readiness-report.schema.json",
					import.meta.url,
				),
				"utf8",
			),
		),
		"schema",
	);
	const ajv = new Ajv2020({ allErrors: true, strict: true });
	addFormats(ajv);
	const validate = ajv.compile(schema);
	const reports = asRecord(
		parseJson(
			await readFile(
				new URL(
					"../server/application/src/test/resources/evidence/readiness/expected.json",
					import.meta.url,
				),
				"utf8",
			),
		),
		"reports",
	);
	assert.deepEqual(Object.keys(reports).toSorted(), ["FOUND", "NOT_FOUND", "UNDECIDABLE"]);
	for (const report of Object.values(reports)) {
		assert.ok(validate(report), JSON.stringify(validate.errors));
	}
	for (const reason of ["REPOSITORY_FETCH_FAILED", "REVIEW_VERSION_UNAVAILABLE"]) {
		const unavailable = structuredClone(asRecord(reports.NOT_FOUND, "absent report"));
		unavailable.applicability = {};
		unavailable.decisions = [
			{
				practiceSlug: "tree-practice",
				decidedAt: unavailable.decidedAt,
				ready: false,
				reasonCodes: ["DECLARED_EVIDENCE_INSUFFICIENT"],
				sourceChecks: [
					{
						sourceKind: "scm.repository.tree",
						sourceContractVersion: "3.0.0",
						checkedAt: unavailable.decidedAt,
						temporalAnchor: unavailable.manifestCapturedAt,
						meetsRequirements: false,
						reasonCodes: ["SOURCE_NOT_AVAILABLE", reason],
					},
				],
			},
		];
		assert.ok(validate(unavailable), JSON.stringify(validate.errors));
	}
	const invalid = structuredClone(asRecord(reports.NOT_FOUND, "absent report"));
	asRecord(invalid.applicability, "applicability").other = {
		absent: true,
		otherwise: "No matches.",
		clauses: [{ aspect: "HAS_ITEMS", readFrom: "scm.inline-review-comments", result: "NOT_FOUND" }],
	};
	assert.equal(validate(invalid), false, "collection ids cannot be reported as source kinds");
});
