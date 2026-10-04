import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { prepareObservationArguments } from "../../../main/resources/agent/pi-tool-arguments.ts";

const citation = {
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 12,
	quote: "insecure();",
};
const observation = {
	practiceSlug: "auth",
	outcome: "NOT_MET",
	severity: "MAJOR",
	summary: "Unsafe call",
	evidenceRationale: "The change calls insecure().",
	evidence: { citations: [citation] },
};

void test("decodes JSON containers without changing the observation", () => {
	const input = {
		observations: JSON.stringify([
			{ ...observation, evidence: JSON.stringify({ citations: JSON.stringify([citation]) }) },
		]),
	};
	assert.deepEqual(prepareObservationArguments(input), { observations: [observation] });
	assert.equal(typeof input.observations, "string");
});

void test("wraps a single observation and a single citation without dropping fields", () => {
	assert.deepEqual(
		prepareObservationArguments({
			observations: { ...observation, evidence: { citations: citation } },
		}),
		{ observations: [observation] },
	);
});

void test("reads only decimal line coordinates from numbered views", () => {
	for (const startLine of ["10", "L10", "[L10]", " [L10] "]) {
		assert.deepEqual(
			prepareObservationArguments({
				observations: [
					{
						...observation,
						evidence: { citations: [{ ...citation, startLine, endLine: "[L12]" }] },
					},
				],
			}),
			{ observations: [observation] },
		);
	}
	for (const startLine of [
		"E10",
		"[10]",
		"L10]",
		"[L10",
		"1e1",
		"10.5",
		"-10",
		"10-12",
		"line 10",
		"9007199254740993",
		"[L9007199254740993]",
		null,
	]) {
		const input = {
			observations: [{ ...observation, evidence: { citations: [{ ...citation, startLine }] } }],
		};
		assert.deepEqual(prepareObservationArguments(input), input);
	}
});

void test("leaves malformed containers and invalid claims for admission", () => {
	for (const evidence of [
		"{not json}",
		"null",
		"[]",
		"42",
		[],
		{ citations: "not JSON" },
		{
			citations: [
				{
					...citation,
					path: "../task.json",
					quote: "invented();",
					side: "INVALID",
					endLine: 99_999,
				},
			],
		},
	]) {
		const input = { observations: [{ ...observation, outcome: "INVALID", evidence }] };
		assert.deepEqual(prepareObservationArguments(input), input);
	}
	for (const observations of ["[{not json}]", "null", "42", 42]) {
		assert.deepEqual(prepareObservationArguments({ observations }), { observations });
	}
});

void test("preserves a mixed batch and is idempotent", () => {
	const input = { observations: [observation, null, { practiceSlug: "missing-evidence" }] };
	const prepared = prepareObservationArguments(input);
	assert.deepEqual(prepared, input);
	assert.deepEqual(prepareObservationArguments(prepared), prepared);
});

void test("review prompts and bundled practices do not require write or edit tools", () => {
	for (const path of [
		"agent/pi-orchestrator.md",
		"agent/feedback-composer.md",
		"practices/default-catalog.json",
	]) {
		const text = readFileSync(new URL(`../../../main/resources/${path}`, import.meta.url), "utf8");
		assert.doesNotMatch(
			text,
			/`(?:write|edit)`|tools\.(?:write|edit)\(|\b(?:write|edit) tool\b/u,
			path,
		);
	}
});

void test("refuses arguments without the tool's outer object", () => {
	for (const args of [null, 42, [observation], JSON.stringify([observation])]) {
		assert.deepEqual(prepareObservationArguments(args), {});
	}
});
