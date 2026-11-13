import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";

import { parseCompositions, type Review, type RunFile } from "./feedback-eval/compositions.ts";
import {
	catalogPractices,
	deterministic,
	parseGateConfig,
	scorePanel,
	verdict,
	type Inputs,
} from "./feedback-eval/gate.ts";
import { asArray, asRecord, parseJson } from "./lib/json.ts";

const readJson = (file: string): unknown => parseJson(readFileSync(file, "utf8"));
const read = (file: string) => (existsSync(file) ? readFileSync(file, "utf8") : undefined);
const gate = parseGateConfig(readJson("scripts/feedback-eval/gate.json"));
const compositions = readJson("scripts/feedback-eval/compositions.json");
const claims = readJson("scripts/feedback-eval/cases.json");
const catalog = catalogPractices(
	readJson("server/application/src/main/resources/practices/default-catalog.json"),
);
const set = parseCompositions(compositions);
const inputs = (overrides: Partial<Inputs> = {}): Inputs => ({
	gate,
	compositions: structuredClone(compositions),
	claims: structuredClone(claims),
	catalog,
	read,
	...overrides,
});
const problems = (checks: ReturnType<typeof deterministic>, id: string) =>
	checks.find((check) => check.id === id)?.problems ?? [];
const failing = (checks: ReturnType<typeof deterministic>) =>
	checks.filter((check) => check.problems.length > 0).map((check) => check.id);

/** A composer run that sends each panel case's reference composition, with `edit` applied. */
function referenceRun(edit?: (review: Review) => void): RunFile {
	return {
		version: 1,
		model: null,
		runner: null,
		cases: Object.keys(gate.panel.cases).map((id) => {
			const item = set.cases.find((each) => each.id === id);
			const reference = item?.compositions.find((each) => each.id === "reference");
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

void test("a prompt that loses a criterion clause fails G4 with its criterion and file", () => {
	const clause = gate.clauses.find((each) => each.criterion === "O1");
	assert.ok(clause);
	const checks = deterministic(
		inputs({
			read: (file) =>
				file === clause.file
					? read(file)?.replace(/Settle\s+the\s+applicability\s+first/u, "Judge")
					: read(file),
		}),
	);
	assert.deepEqual(failing(checks), ["G4"]);
	assert.match(problems(checks, "G4")[0] ?? "", /^O1: .*pi-orchestrator\.md no longer says/u);
});

void test("a clause matches across the line breaks of its prompt file", () => {
	const clause = gate.clauses[0];
	assert.ok(clause);
	const checks = deterministic(
		inputs({
			read: (file) => (file === clause.file ? clause.text.replaceAll(" ", "\n  ") : ""),
		}),
	);
	assert.ok(!problems(checks, "G4").some((problem) => problem.startsWith(`${clause.criterion}:`)));
});

void test("a changed or missing pinned case fails G1 until the set is pinned again", () => {
	const changed = structuredClone(compositions);
	const [panelCase] = Object.keys(gate.panel.cases);
	const item = asArray(asRecord(changed, "set").cases, "cases")
		.map((each) => asRecord(each, "case"))
		.find((each) => each.id === panelCase);
	assert.ok(item);
	item.expectedWhy = "edited";
	assert.match(
		problems(deterministic(inputs({ compositions: changed })), "G1")[0] ?? "",
		/changed/u,
	);

	const removed = structuredClone(claims);
	const corpus = asRecord(removed, "corpus");
	corpus.cases = asArray(corpus.cases, "cases").filter(
		(each) => asRecord(each, "case").id !== "t2-2",
	);
	assert.deepEqual(problems(deterministic(inputs({ claims: removed })), "G1"), [
		"claim case t2-2 is missing",
	]);
});

void test("an absence claim whose text is in its evidence must be labelled false (G3)", () => {
	const changed = structuredClone(claims);
	const t49 = asArray(asRecord(changed, "corpus").cases, "cases")
		.map((each) => asRecord(each, "case"))
		.find((each) => each.id === "t4-9");
	assert.ok(t49);
	const claim = asRecord(asArray(t49.claims, "claims")[0], "claim");
	claim.expected = "true";
	// The pin also catches the edit; G3 names the inconsistency itself.
	const checks = deterministic(inputs({ claims: changed }));
	assert.match(problems(checks, "G3")[0] ?? "", /t4-9 c1: e1 holds the text/u);
});

void test("invalid fixtures fail G2 and leave the checks that need them unchecked", () => {
	const checks = deterministic(inputs({ claims: { version: 1, cases: "none" } }));
	assert.deepEqual(failing(checks), ["G1", "G2", "G3"]);
	assert.match(problems(checks, "G1")[0] ?? "", /Not checked/u);
});

void test("each automated practice states its occasion, and the known-gap list stays current (G5)", () => {
	const silent = {
		slug: "new-practice",
		automated: true,
		criteria: "## The standard\nJudge it.",
		appliesWhen: null,
	};
	assert.deepEqual(problems(deterministic(inputs({ catalog: [...catalog, silent] })), "G5"), [
		"new-practice states no occasion: say when it does not apply",
	]);
	assert.deepEqual(
		problems(
			deterministic(inputs({ catalog: [...catalog, { ...silent, automated: false }] })),
			"G5",
		),
		[],
	);
	const [gap] = gate.occasionGaps;
	assert.ok(gap !== undefined);
	const fixed = catalog.map((practice) =>
		practice.slug === gap
			? { ...practice, criteria: `${practice.criteria}\nRecord DOES_NOT_APPLY when …` }
			: practice,
	);
	assert.deepEqual(problems(deterministic(inputs({ catalog: fixed })), "G5"), [
		`${gap} now states its occasion: remove it from occasionGaps`,
	]);
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

void test("a skipped panel passes only with the maintainer's acceptance", () => {
	const passing = deterministic(inputs());
	const skipped = { status: "skipped", reason: "no credential" } as const;
	assert.equal(verdict(passing, skipped, false).pass, false);
	assert.equal(verdict(passing, skipped, true).pass, true);
	assert.equal(verdict(passing, { status: "not-run", reason: "push" }, false).pass, true);
	const broken = passing.map((check) =>
		check.id === "G4" ? { ...check, problems: ["O1"] } : check,
	);
	assert.equal(verdict(broken, skipped, true).pass, false);
});
