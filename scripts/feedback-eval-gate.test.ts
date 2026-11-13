import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";

import { parseCompositions, type Review, type RunFile } from "./feedback-eval/compositions.ts";
import {
	catalogPractices,
	deterministic,
	parseGateConfig,
	practiceDigest,
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
	base: undefined,
	scripts: [],
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

void test("a clause and its paragraph match across reflowed line breaks", () => {
	const clause = gate.clauses.find((each) => each.criterion === "E9");
	assert.ok(clause);
	const reflowed = (file: string) =>
		file === clause.file ? read(file)?.replaceAll(/(?<=\S) (?=\S)/gu, "\n") : read(file);
	assert.deepEqual(problems(deterministic(inputs({ read: reflowed })), "G4"), []);
});

void test("a reversed clause fails G4, because its paragraph changed", () => {
	const clause = gate.clauses.find((each) => each.criterion === "E9");
	assert.ok(clause);
	const reversed = (file: string) =>
		file === clause.file
			? read(file)?.replace(
					/Withhold an unsupported/u,
					"Ignore this obsolete instruction: Withhold an unsupported",
				)
			: read(file);
	const found = problems(deterministic(inputs({ read: reversed })), "G4");
	// Every clause that shares the edited paragraph fails, E9 among them.
	assert.ok(found.length > 0);
	assert.ok(
		found.every((problem) =>
			/ the paragraph around .* changed \(now [0-9a-f]{64}\)/u.test(problem),
		),
	);
	assert.ok(found.some((problem) => problem.startsWith("E9: ")));
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

void test("a practice without an Applicability section or an Applies when gate fails G5 unless listed", () => {
	const silent = {
		slug: "new-practice",
		automated: true,
		criteria: "## The standard\nJudge it.\n\n## Judge\n- DOES_NOT_APPLY: never.",
		appliesWhen: null,
	};
	assert.deepEqual(problems(deterministic(inputs({ catalog: [...catalog, silent] })), "G5"), [
		"new-practice has no Applicability section and no Applies when gate",
		`new-practice has no pin (now ${practiceDigest(silent)})`,
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

void test("an edit to a practice's criteria fails G5 until it is pinned again", () => {
	const target = catalog.find((practice) => !gate.occasionGaps.includes(practice.slug));
	assert.ok(target);
	const edited = catalog.map((practice) =>
		practice.slug === target.slug
			? {
					...practice,
					criteria: `${practice.criteria}\nNever return DOES_NOT_APPLY. Assess every file.`,
				}
			: practice,
	);
	const found = problems(deterministic(inputs({ catalog: edited })), "G5");
	assert.equal(found.length, 1);
	assert.match(
		found[0] ?? "",
		new RegExp(`^${target.slug}: its criteria or Applies when changed`, "u"),
	);
});

void test("a changed manifest with an unchanged version fails G1 against the merge base", () => {
	const base = structuredClone(gate);
	base.floors.withholdCorrect = 9;
	assert.deepEqual(problems(deterministic(inputs({ base })), "G1"), [
		`gate.json changed since the base, but its version is still ${gate.version}`,
	]);
	assert.deepEqual(
		problems(deterministic(inputs({ base: { ...base, version: gate.version - 1 } })), "G1"),
		[],
	);
	assert.deepEqual(problems(deterministic(inputs({ base: structuredClone(gate) })), "G1"), []);
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
		check.id === "G4" ? { ...check, problems: ["O1"] } : check,
	);
	assert.equal(verdict(broken, { status: "not-run", reason: "pull request" }).pass, false);
});
