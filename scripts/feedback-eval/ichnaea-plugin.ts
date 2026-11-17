/**
 * Hephaestus's Ichnaea plugin: the feedback eval's measures, scored per case from each composed review, and an
 * importer for the result files `CompositionEvalLiveTest` writes. Ichnaea decides binary values per trial, so every
 * measure is a pass or a fail for one case: docs/contributor/feedback-quality-evals.mdx § Decision on the composition
 * set defines each one.
 */
import { readFile } from "node:fs/promises";
import path from "node:path";

import {
	defineMeasure,
	definePlugin,
	type Importer,
	type MeasureDefinition,
	type MeasureExample,
} from "@hephaestus-build/ichnaea";
import { Case, type MeasuredValue, Trial } from "@hephaestus-build/ichnaea/contracts";

import {
	composedDecisions,
	ENDPOINT_FAILURES,
	mechanicalCodes,
	parseRun,
	type CompositionCase,
	type Disposition,
	type Review,
} from "./compositions.ts";
import { expectedRaises, expectedWithholds } from "./ichnaea-cases.ts";

/** JSON from a file, unchecked: each caller checks the shape it reads. */
const parseJson = (text: string): unknown => JSON.parse(text);

/** What one trial carries: the composer's output for one case, as the live test recorded it. */
interface Output {
	accepted: boolean;
	failure: string | null;
	errors: string[];
	review: Review | null;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);

const DISPOSITIONS: readonly Disposition[] = [
	"RAISE",
	"BELOW_BAR",
	"ALREADY_SAID",
	"NO_MATERIAL_CHANGE",
];
const isDisposition = (value: unknown): value is Disposition =>
	DISPOSITIONS.some((disposition) => disposition === value);

/**
 * A stored review, as far as the measures read it: its decisions, and the texts that mechanical codes check. The
 * importer stored it from a run file that passed the run schema, so a value of another shape is an error.
 */
function isReview(value: unknown): value is Review {
	return (
		isRecord(value) &&
		Array.isArray(value.decisions) &&
		value.decisions.every(
			(decision) =>
				isRecord(decision) &&
				typeof decision.resultId === "string" &&
				isDisposition(decision.disposition),
		) &&
		(value.summary === null || isRecord(value.summary))
	);
}

function outputOf(trial: Trial): Output {
	const { output } = trial;
	if (!isRecord(output) || typeof output.accepted !== "boolean") {
		throw new Error(`trial ${trial.id} carries no composer output`);
	}
	const stored = output.review ?? null;
	// The live test records a refused draft as the model sent it, so it can have any shape; it counts as no decision.
	// Main's check accepted an accepted review, so a malformed one there is an error of the record.
	if (stored !== null && !isReview(stored) && output.accepted) {
		throw new Error(`trial ${trial.id} carries a malformed review`);
	}
	const review = stored !== null && isReview(stored) ? stored : null;
	return {
		accepted: output.accepted,
		failure: typeof output.failure === "string" ? output.failure : null,
		errors: Array.isArray(output.errors) ? output.errors.map(String) : [],
		review,
	};
}

/** The expected decisions a case's hidden references hold. */
function expectationsOf(c: Case): Pick<CompositionCase, "expected" | "unsettled"> {
	const { references } = c;
	if (!isRecord(references) || !isRecord(references.expected)) {
		throw new Error(`case ${c.id} has no expected decisions`);
	}
	const expected: Record<string, Disposition> = {};
	for (const [result, disposition] of Object.entries(references.expected)) {
		if (!isDisposition(disposition)) {
			throw new Error(`case ${c.id}: ${result} expects no known disposition`);
		}
		expected[result] = disposition;
	}
	return {
		expected,
		unsettled: Array.isArray(references.unsettled) ? references.unsettled.map(String) : [],
	};
}

const accepted = (o: Output): boolean => o.accepted && o.failure === null;
const clean = (o: Output): boolean =>
	accepted(o) && (o.review === null || mechanicalCodes(o.review).length === 0);
const raisesAll = (o: Output, c: Case): boolean => {
	const decided = composedDecisions(o.review);
	return expectedRaises(expectationsOf(c)).every((result) => decided.get(result) === "RAISE");
};
const withholdsAll = (o: Output, c: Case): boolean => {
	const decided = composedDecisions(o.review);
	return expectedWithholds(expectationsOf(c)).every((result) => {
		const disposition = decided.get(result);
		return disposition !== undefined && disposition !== "RAISE";
	});
};
const pass = (value: boolean): MeasuredValue => ({ type: "binary", pass: value });

type Card = MeasureDefinition["card"];
const card = <const Id extends `hephaestus/${string}`>(
	id: Id,
	tier: Card["tier"],
	fields: Pick<Card, "construct" | "operationalDefinition" | "applicability" | "establishes">,
): Omit<Card, "id"> & { readonly id: Id } => ({
	id,
	// Ichnaea's measure digest covers the card, not the scorer, so a change to what a measure scores changes this version.
	version: "2.1.0",
	...fields,
	valueType: "binary",
	unit: "proportion",
	direction: "higher",
	justification:
		"A measure of the feedback eval on the composition set: docs/contributor/feedback-quality-evals.mdx § Decision on the composition set.",
	errorModel:
		"Decisions are read from the composed review; a malformed review counts as no decision. The cases are synthetic and public.",
	tier,
	unitOfEvaluation: "turn",
	computedBy: "ichnaea",
});

// Known-good and known-bad examples on one case that expects a raise of r1 and a withholding of r2.
const exampleCase = Case.parse({
	schema: "ichnaea.case/1",
	id: "example",
	unit: "turn",
	dataClass: "none",
	input: { provider: "github" },
	references: { expected: { r1: "RAISE", r2: "BELOW_BAR" }, unsettled: [] },
	provenance: {
		source: "authored",
		selection: "curated",
		created: "2026-11-01",
		licence: "MIT",
		exposure: "public",
	},
	pool: "development",
});
const review = (
	r1: Disposition,
	r2: Disposition,
	summary = "One problem to fix before merging.",
): Review => ({
	decisions: [
		{ resultId: "r1", disposition: r1 },
		{ resultId: "r2", disposition: r2 },
	],
	summary: { body: summary, basedOn: ["r1"] },
});
const trialOf = (output: object): Trial =>
	Trial.parse({
		schema: "ichnaea.trial/1",
		id: "example",
		source: "offline",
		case: "example",
		unit: "turn",
		configuration: `sha256:${"0".repeat(64)}`,
		replicate: 0,
		outcome: "completed",
		dataClass: "none",
		output,
	});
const example = (name: string, output: object, value: boolean): MeasureExample => ({
	name,
	case: exampleCase,
	trial: trialOf(output),
	expected: pass(value),
});
const good: Output = {
	accepted: true,
	failure: null,
	errors: [],
	review: review("RAISE", "BELOW_BAR"),
};
const refused: Output = {
	accepted: false,
	failure: null,
	errors: ["citation not found"],
	review: null,
};
// A refused draft as the model sent it, in no shape that a review has.
const refusedMalformed = { ...refused, review: { decisions: "r1 RAISE" } };
const inverted: Output = {
	accepted: true,
	failure: null,
	errors: [],
	review: review("BELOW_BAR", "RAISE"),
};
const linked: Output = {
	accepted: true,
	failure: null,
	errors: [],
	review: review("RAISE", "BELOW_BAR", "See https://example.org for the fix."),
};

/** P1: main would deliver the review. */
export const acceptedMeasure = defineMeasure({
	card: card("hephaestus/accepted", "primary", {
		construct: "The composer's review passes main's acceptance check.",
		operationalDefinition:
			"1 if the live test recorded the case as accepted without a failure, else 0.",
		applicability: "Every case.",
		establishes: "That main would deliver the review, not that it is useful.",
	}),
	score: (t) => pass(accepted(outputOf(t))),
	examples: {
		good: [example("accepted", good, true)],
		bad: [example("refused", refused, false)],
	},
});

/** P2: the accepted review carries no mechanical defect. */
export const mechanicallyCleanMeasure = defineMeasure({
	card: card("hephaestus/mechanically-clean", "primary", {
		construct:
			"An accepted review carries no mechanical defect: no text over its length target, no link, mention, HTML or image.",
		operationalDefinition: "1 if accepted and the review carries no Q6 or Q9 code, else 0.",
		applicability: "Every case.",
		establishes: "The absence of mechanical text defects, not that the wording is good.",
	}),
	score: (t) => pass(clean(outputOf(t))),
	examples: {
		good: [example("clean", good, true)],
		bad: [example("a link in prose", linked, false), example("refused", refused, false)],
	},
});

/** P3: the review raises every settled result the case expects it to raise. */
export const raisesExpectedMeasure = defineMeasure({
	card: card("hephaestus/raises-expected", "primary", {
		construct: "The composer raises every settled result the case expects it to raise.",
		operationalDefinition:
			"1 if every settled result expected as RAISE is decided RAISE in the review, else 0. A case with several expected raises fails when any of them is not raised.",
		applicability: "Cases with at least one settled expected raise.",
		establishes: "Agreement with the case's expected raises, not usefulness to the developer.",
	}),
	appliesTo: (c) => expectedRaises(expectationsOf(c)).length > 0,
	score: (t, c) => pass(raisesAll(outputOf(t), c)),
	examples: {
		good: [example("raises r1", good, true)],
		bad: [
			example("withholds r1", inverted, false),
			example("a refused malformed draft", refusedMalformed, false),
		],
	},
});

/** P4: the review withholds every settled result the case expects it to withhold. */
export const withholdsExpectedMeasure = defineMeasure({
	card: card("hephaestus/withholds-expected", "primary", {
		construct: "The composer withholds every settled result the case expects it to withhold.",
		operationalDefinition:
			"1 if every settled result expected as BELOW_BAR, ALREADY_SAID or NO_MATERIAL_CHANGE is decided, and not as RAISE, else 0.",
		applicability: "Cases with at least one settled expected withholding.",
		establishes:
			"Agreement with the case's expected withholdings, not that withholding helped the developer.",
	}),
	appliesTo: (c) => expectedWithholds(expectationsOf(c)).length > 0,
	score: (t, c) => pass(withholdsAll(outputOf(t), c)),
	examples: {
		good: [example("withholds r2", good, true)],
		bad: [
			example("raises r2", inverted, false),
			example("a refused malformed draft", refusedMalformed, false),
		],
	},
});

/** Secondary and descriptive: the whole case is right. It never gives or changes a verdict. */
export const caseGoodMeasure = defineMeasure({
	card: card("hephaestus/case-good", "secondary", {
		construct: "The composer gets the whole case right.",
		operationalDefinition:
			"1 if the review is accepted, mechanically clean, raises every settled expected raise and withholds every settled expected withholding, else 0.",
		applicability: "Every case.",
		establishes: "A per-case summary of P1 to P4, reported beside them, never decided on.",
	}),
	score: (t, c) => {
		const o = outputOf(t);
		return pass(clean(o) && raisesAll(o, c) && withholdsAll(o, c));
	},
	examples: {
		good: [example("right throughout", good, true)],
		bad: [example("decisions inverted", inverted, false), example("refused", refused, false)],
	},
});

/**
 * Reads the result files of `CompositionEvalLiveTest`, one per arm and replicate, named `<arm>-r<replicate>.json`. A
 * case whose request failed at the endpoint is an infrastructure failure, so missing; a planned case absent from the
 * files was not run.
 */
export const compositionRuns: Importer<"hephaestus/composition-runs"> = {
	id: "hephaestus/composition-runs",
	version: "2.0.0",
	description: "CompositionEvalLiveTest result files, one per arm and replicate (<arm>-r<k>.json).",
	async run({ files, options }) {
		const arms = Array.isArray(options.arms)
			? options.arms.flatMap((arm: unknown) =>
					isRecord(arm) && typeof arm.name === "string" && typeof arm.identity === "string"
						? [{ name: arm.name, identity: arm.identity }]
						: [],
				)
			: [];
		if (arms.length === 0) {
			throw new Error("the import names no arms");
		}
		const trials: Trial[] = [];
		for (const file of files) {
			const match = /^(?<arm>[A-Za-z0-9][A-Za-z0-9._-]*)-r(?<replicate>\d+)\.json$/u.exec(
				path.basename(file),
			);
			const arm = match?.groups?.arm;
			if (arm === undefined) {
				throw new Error(`${file}: name a result file <arm>-r<replicate>.json`);
			}
			const replicate = Number(match?.groups?.replicate);
			const identity = arms.find((a) => a.name === arm)?.identity;
			if (identity === undefined) {
				throw new Error(`${file}: arm '${arm}' is not in the plan`);
			}
			const run = parseRun(parseJson(await readFile(file, "utf8")), file);
			for (const output of run.cases) {
				const failure = output.failure ?? null;
				trials.push(
					Trial.parse({
						schema: "ichnaea.trial/1",
						id: `${output.id}/${arm}/r${replicate}`,
						source: "offline",
						run: options.run,
						case: output.id,
						unit: "turn",
						configuration: identity,
						arm,
						replicate,
						outcome:
							failure !== null && ENDPOINT_FAILURES.has(failure)
								? "infrastructure_failure"
								: "completed",
						dataClass: "none",
						output: {
							accepted: output.accepted,
							failure,
							errors: output.errors,
							review: output.review,
						},
					}),
				);
			}
		}
		return { trials, values: [] };
	},
};

/** The plugin. */
export const hephaestus = definePlugin({
	id: "hephaestus",
	version: "2.1.0",
	stability: "stable",
	measures: [
		acceptedMeasure,
		mechanicallyCleanMeasure,
		raisesExpectedMeasure,
		withholdsExpectedMeasure,
		caseGoodMeasure,
	],
	importers: [compositionRuns],
});
