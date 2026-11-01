import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	CODES,
	compositionReport,
	mechanicalCodes,
	parseCompositions,
	parseRun,
	parseRunLabels,
	type CompositionSet,
	type RunFile,
} from "./feedback-eval/compositions.ts";
import { parseJson } from "./lib/json.ts";

const raw = (): unknown =>
	parseJson(readFileSync(new URL("feedback-eval/compositions.json", import.meta.url), "utf8"));
const set = parseCompositions(raw());
const first = (): CompositionSet => {
	const copy = structuredClone(set);
	copy.cases = copy.cases.slice(0, 1);
	return copy;
};

void test("the composition set holds 60 cases and labels every rubric code", () => {
	assert.equal(set.cases.length, 60);
	const used = new Set(
		set.cases.flatMap((item) => item.compositions.flatMap((each) => each.label.codes)),
	);
	assert.deepEqual(
		CODES.filter((code) => !used.has(code)),
		[],
	);
	for (const item of set.cases) {
		assert.ok(item.compositions.some((each) => each.id === "reference" && each.label.good));
	}
});

void test("a good label carries no code", () => {
	const copy = first();
	const alternative = copy.cases[0]?.compositions.find(
		(each) => each.label.good && each.id !== "reference",
	);
	const target = alternative ?? copy.cases[0]?.compositions[0];
	assert.ok(target);
	target.label.codes = ["Q5"];
	assert.throws(() => parseCompositions(copy), /good means no code/u);
});

const runLabels = (good: boolean, codes: string[]) => ({
	version: 1,
	labels: [
		{ caseId: "gh-a-01-menu-view-does-its-own-networking", good, codes, why: "Test label." },
	],
});

void test("a run's good label carries no code either", () => {
	assert.throws(
		() => parseRunLabels(runLabels(true, ["T1"]), "labels.json"),
		/good means no code/u,
	);
	assert.throws(() => parseRunLabels(runLabels(false, []), "labels.json"), /good means no code/u);
	assert.equal(parseRunLabels(runLabels(false, ["T1"]), "labels.json").labels.length, 1);
});

void test("words over the length target need the length code", () => {
	const copy = first();
	const reference = copy.cases[0]?.compositions.find((each) => each.id === "reference");
	assert.ok(reference);
	reference.review.summary = {
		body: "x".repeat(601),
		basedOn: Object.keys(copy.cases[0]?.expected ?? {}),
	};
	assert.throws(() => parseCompositions(copy), /carry Q6/u);
	assert.deepEqual(
		mechanicalCodes({ decisions: [], summary: { body: "See `@Override` here.", basedOn: [] } }),
		[],
	);
	assert.deepEqual(
		mechanicalCodes({ decisions: [], summary: { body: "Ask @dev about it.", basedOn: [] } }),
		["Q9"],
	);
});

void test("the reference decides every result as expected", () => {
	const copy = first();
	const item = copy.cases[0];
	const decision = item?.compositions.find((each) => each.id === "reference")?.review.decisions[0];
	assert.ok(item && decision);
	decision.disposition = decision.disposition === "RAISE" ? "BELOW_BAR" : "RAISE";
	assert.throws(() => parseCompositions(copy), /unlike expected/u);
});

void test("a run reports raise precision and recall against the expected decisions", () => {
	const reviews = set.cases.map((item) => {
		const reference = item.compositions.find((each) => each.id === "reference");
		assert.ok(reference);
		return { id: item.id, accepted: true, errors: [], review: reference.review };
	});
	// One case raises everything, so a result to withhold counts against precision.
	const withheld = set.cases.find((item) =>
		Object.values(item.expected).some((value) => value !== "RAISE"),
	);
	assert.ok(withheld);
	const changed = reviews.find((entry) => entry.id === withheld.id);
	assert.ok(changed);
	changed.review = {
		...changed.review,
		decisions: Object.keys(withheld.expected).map((resultId) => ({
			resultId,
			disposition: "RAISE" as const,
		})),
	};
	const run: RunFile = parseRun({ version: 1, model: null, runner: null, cases: reviews }, "run");
	const report = compositionReport(set, undefined, [{ name: "reference", run, labels: undefined }]);
	const result = report.runs[0];
	assert.ok(result);
	assert.equal(result.raise.recall, 1);
	assert.ok(result.raise.precision !== null && result.raise.precision < 1);
	assert.ok(result.failures.some((failure) => failure.caseId === withheld.id));
});

void test("the good rate needs one label for every composed review", () => {
	const reviews = set.cases.map((item) => {
		const reference = item.compositions.find((each) => each.id === "reference");
		assert.ok(reference);
		return { id: item.id, accepted: true, errors: [], review: reference.review };
	});
	const run = parseRun({ version: 1, model: null, runner: null, cases: reviews }, "run");
	const one = { caseId: reviews[0]?.id ?? "", good: true, codes: [], why: "Good." };
	const partial = compositionReport(set, undefined, [
		{ name: "partial", run, labels: { version: 1, labels: [one] } },
	]);
	const quality = partial.runs[0]?.quality;
	assert.ok(quality);
	assert.equal(quality.goodRate, null);
	assert.equal(quality.unlabelled.length, reviews.length - 1);
	assert.throws(
		() =>
			compositionReport(set, undefined, [
				{ name: "twice", run, labels: { version: 1, labels: [one, one] } },
			]),
		/one label per composed case/u,
	);
	const empty = parseRun(
		{
			version: 1,
			model: null,
			runner: null,
			cases: [{ id: one.caseId, accepted: false, errors: [], review: null }],
		},
		"run",
	);
	assert.throws(
		() =>
			compositionReport(set, undefined, [
				{ name: "nothing", run: empty, labels: { version: 1, labels: [one] } },
			]),
		/one label per composed case/u,
	);
});
