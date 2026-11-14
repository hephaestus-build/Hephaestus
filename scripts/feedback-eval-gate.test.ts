import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import { parseCompositions, type Review, type RunFile } from "./feedback-eval/compositions.ts";
import {
	catalogPractices,
	deterministic,
	parseGateConfig,
	ceilingReached,
	scorePanel,
	scoreSafety,
	verdict,
	type Inputs,
} from "./feedback-eval/gate.ts";
import { asArray, asRecord, parseJson } from "./lib/json.ts";

const readJson = (file: string): unknown => parseJson(readFileSync(file, "utf8"));
const gate = parseGateConfig(readJson("scripts/feedback-eval/gate.json"));
const compositions = readJson("scripts/feedback-eval/compositions.json");
const claims = readJson("scripts/feedback-eval/cases.json");
const catalog = catalogPractices(
	readJson("server/application/src/main/resources/practices/default-catalog.json"),
);
const set = parseCompositions(compositions);
const inputs = (overrides: Partial<Inputs> = {}): Inputs => ({
	gate,
	scripts: [],
	compositions: structuredClone(compositions),
	claims: structuredClone(claims),
	catalog,
	...overrides,
});
const problems = (checks: ReturnType<typeof deterministic>, id: string) =>
	checks.find((check) => check.id === id)?.problems ?? [];
const failing = (checks: ReturnType<typeof deterministic>) =>
	checks.filter((check) => check.problems.length > 0).map((check) => check.id);

/** A composer run that sends each case's composition `composition`, with `edit` applied. */
function referenceRun(
	edit?: (review: Review) => void,
	ids: string[] = gate.panel.cases,
	composition = "reference",
): RunFile {
	return {
		version: 1,
		model: null,
		runner: null,
		cases: ids.map((id) => {
			const item = set.cases.find((each) => each.id === id);
			const reference = item?.compositions.find((each) => each.id === composition);
			assert.ok(reference, id);
			const review = structuredClone(reference.review);
			edit?.(review);
			return { id, accepted: true, failure: null, errors: [], review };
		}),
	};
}

void test("the repository passes every deterministic check", () => {
	assert.deepEqual(failing(deterministic(inputs())), []);
});

void test("a panel that permits more calls than the ceiling fails G7", () => {
	const over = { ...gate, panel: { ...gate.panel, cases: [...gate.panel.cases, "one-more-case"] } };
	const found = problems(deterministic(inputs({ gate: over })), "G7");
	assert.ok(problems(deterministic(inputs({ gate: over })), "G2").length > 0);
	assert.deepEqual(found, [
		`21 cases × ${gate.panel.callsPerCase} calls permit ${21 * gate.panel.callsPerCase} calls, over the ceiling of ${gate.panel.maxCalls}`,
	]);
});

void test("the hard stop ends a run before a case could pass the ceiling or after a case passed its bound", () => {
	const full = Array.from({ length: 19 }, () => gate.panel.callsPerCase);
	assert.equal(ceilingReached(gate, full), undefined);
	assert.match(ceilingReached(gate, [...full, 1]) ?? "", /could pass the ceiling of 240/u);
	assert.match(ceilingReached(gate, [gate.panel.callsPerCase + 1]) ?? "", /over its bound of 12/u);
	assert.equal(
		verdict(deterministic(inputs()), { status: "ceiling-reached", reason: "stop" }).pass,
		false,
	);
});

void test("an absence claim whose text is in its evidence must be labelled false (G3)", () => {
	const changed = structuredClone(claims);
	const t49 = asArray(asRecord(changed, "corpus").cases, "cases")
		.map((each) => asRecord(each, "case"))
		.find((each) => each.id === "t4-9");
	assert.ok(t49);
	const claim = asRecord(asArray(t49.claims, "claims")[0], "claim");
	claim.expected = "true";
	const checks = deterministic(inputs({ claims: changed }));
	assert.match(problems(checks, "G3")[0] ?? "", /t4-9 c1: e1 holds the text/u);
});

void test("invalid fixtures fail G2 and leave the checks that need them unchecked", () => {
	const checks = deterministic(inputs({ claims: { version: 1, cases: "none" } }));
	assert.deepEqual(failing(checks), ["G2", "G3"]);
	assert.match(problems(checks, "G3")[0] ?? "", /Not checked/u);
});

void test("a practice without an Applicability section or an Applies when gate fails G5 unless listed", () => {
	const silent = {
		slug: "new-practice",
		automated: true,
		criteria: "## The standard\nJudge it.\n\n## Judge\n- DOES_NOT_APPLY: never.",
		appliesWhen: null,
	};
	assert.deepEqual(problems(deterministic(inputs({ catalog: [...catalog, silent] })), "G5"), [
		"new-practice has no Applicability section and no Applies when gate",
	]);
	const [gap] = gate.occasionGaps;
	assert.ok(gap !== undefined);
	const fixed = catalog.map((practice) =>
		practice.slug === gap
			? {
					...practice,
					criteria: `${practice.criteria}\n\n## Applicability\nIt applies to every change.`,
				}
			: practice,
	);
	const found = problems(deterministic(inputs({ catalog: fixed })), "G5");
	assert.ok(found.includes(`${gap} now states its occasion: remove it from occasionGaps`));
});

void test("a practice script that gives other leads fails G6", () => {
	const scripts = ["handles-errors-instead-of-swallowing-them gives other leads than its snapshot"];
	const checks = deterministic(inputs({ scripts }));
	assert.deepEqual(failing(checks), ["G6"]);
	assert.deepEqual(problems(checks, "G6"), scripts);
});

void test("the reference compositions pass every panel floor", () => {
	const panel = scorePanel(gate, set, referenceRun());
	assert.ok(panel.status === "passed", panel.status);
	for (const measure of panel.measures) {
		assert.equal(measure.count, measure.of, measure.id);
	}
});

void test("a composer that raises every result fails the withhold floor", () => {
	const panel = scorePanel(
		gate,
		set,
		referenceRun((review) => {
			for (const decision of review.decisions) {
				decision.disposition = "RAISE";
			}
		}),
	);
	assert.ok(panel.status === "failed");
	const p4 = panel.measures.find((measure) => measure.id === "P4");
	assert.equal(p4?.count, 0);
});

void test("an endpoint failure or a missing case skips the panel instead of failing it", () => {
	const run = referenceRun();
	const [first] = run.cases;
	assert.ok(first);
	first.failure = "PROVIDER";
	first.accepted = false;
	assert.equal(scorePanel(gate, set, run).status, "skipped");
	assert.equal(scorePanel(gate, set, { ...run, cases: run.cases.slice(1) }).status, "skipped");
	const refused = referenceRun();
	const [second] = refused.cases;
	assert.ok(second);
	second.accepted = false;
	second.failure = "REFUSED";
	const scored = scorePanel(gate, set, refused);
	assert.ok(scored.status === "passed" || scored.status === "failed");
	assert.equal(scored.measures.find((measure) => measure.id === "P1")?.count, 19);
});

void test("a skipped panel fails the run that asked for it, and a run without the panel passes", () => {
	const passing = deterministic(inputs());
	const skipped = { status: "skipped", reason: "No model provider in this run." } as const;
	assert.deepEqual(verdict(passing, skipped), {
		pass: false,
		reason: "The model panel was skipped. No model provider in this run.",
	});
	assert.equal(verdict(passing, { status: "not-run", reason: "pull request" }).pass, true);
	const broken = passing.map((check) =>
		check.id === "G5" ? { ...check, problems: ["new-practice"] } : check,
	);
	assert.equal(verdict(broken, { status: "not-run", reason: "pull request" }).pass, false);
});

void test("the panel and the safety subset name only cases that the composition set holds (G2)", () => {
	const renamed = { ...gate, safety: { cases: [...gate.safety.cases, "mx-d-99-gone"] } };
	assert.deepEqual(problems(deterministic(inputs({ gate: renamed })), "G2"), [
		"the safety subset names mx-d-99-gone, which compositions.json does not hold",
	]);
});

void test("the safety subset reports the decisions and the words of a review that follows the work's instruction", () => {
	const praised = "mx-d-10-injection-praise-tests-kotlin";
	assert.ok(gate.safety.cases.includes(praised));
	const clean = scoreSafety(gate, set, referenceRun(undefined, gate.safety.cases));
	assert.deepEqual(
		clean.map((row) => [row.caseId, row.decided, row.accepted, row.mechanicallyClean]),
		gate.safety.cases.map((id) => [id, true, true, true]),
	);
	// alt-1 raises as expected but praises the tests, as the description asks: only its words show it.
	const followed = scoreSafety(gate, set, referenceRun(undefined, [praised], "alt-1")).find(
		(row) => row.caseId === praised,
	);
	assert.ok(followed);
	assert.equal(followed.decided, true);
	assert.ok(followed.text.some((body) => /tests are well structured/u.test(body)));
	const missing = scoreSafety(gate, set, { version: 1, model: null, runner: null, cases: [] });
	assert.ok(missing.every((row) => row.failure === "no output" && !row.accepted));
});
