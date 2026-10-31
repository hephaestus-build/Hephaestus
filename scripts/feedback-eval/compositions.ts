/**
 * The labelled composition set: cases of the review on the work, each with the decisions we expect, a reference
 * composition and labelled alternatives. `docs/contributor/composition-label-rubric.md` owns the codes and the
 * measures. `CompositionEvalTest` renders each case through main's composer and writes the files this module reads.
 */
import Ajv2020, { type ValidateFunction } from "ajv/dist/2020.js";

import schema from "./compositions.schema.json" with { type: "json" };

export const CODES = [
	"T1",
	"T2",
	"T3",
	"T4",
	"T5",
	"T6",
	"T7",
	"D1",
	"D2",
	"Q1",
	"Q2",
	"Q3",
	"Q4",
	"Q5",
	"Q6",
	"Q7",
	"Q8",
	"Q9",
] as const;
export type Code = (typeof CODES)[number];
export type Disposition = "RAISE" | "BELOW_BAR" | "ALREADY_SAID" | "NO_MATERIAL_CHANGE";

export interface Review {
	decisions: { resultId: string; disposition: Disposition; witnessIds?: string[] | null }[];
	summary: { body: string; basedOn: string[] } | null;
	inline?:
		| { body: string; basedOn: string[]; anchor: { problemId: string; citationIndex: number } }[]
		| null;
}
export interface Label {
	good: boolean;
	codes: Code[];
	why: string;
}
export interface CompositionCase {
	id: string;
	provider: "github" | "gitlab";
	theme: string;
	results: { id: string; practice: string; problems: { id: string }[] }[];
	expected: Record<string, Disposition>;
	/** Results whose raise or withhold choice waits for the practice owner: outside the raise measures. */
	unsettled?: string[];
	expectedWhy: string;
	compositions: { id: string; review: Review; label: Label }[];
	criteriaQuestion: string | null;
}
export interface CompositionSet {
	version: 1;
	cases: CompositionCase[];
}
/** `CompositionEvalTest`'s check of every labelled composition with main's acceptance check. */
export interface GateFile {
	version: 1;
	checks: { caseId: string; compositionId: string; accepted: boolean; errors: string[] }[];
}
/** One composer run, checked by main's acceptance check, with the case's own ids. */
export interface RunFile {
	version: 1;
	model: unknown;
	runner: unknown;
	cases: {
		id: string;
		accepted: boolean;
		firstAccepted?: boolean | null;
		failure?: string | null;
		errors: string[];
		review: Review | null;
	}[];
}
/** A labeller's labels of one run's reviews, by case id. */
export interface RunLabels {
	version: 1;
	labels: ({ caseId: string } & Label)[];
}

const SUMMARY_TARGET = 600;
const NOTE_TARGET = 300;
// What FeedbackTextPolicy refuses in prose: a link, a provider mention, raw HTML or an image.
const FORMATTING = /https?:\/\/|www\.|(?<![\w`])@[A-Za-z0-9]|<\/?[a-z][^>]*>|!\[/u;

const ajv = new Ajv2020({ allErrors: true, strict: true });
ajv.addSchema(schema);
const validate = ajv.compile<CompositionSet>(schema);
const parser =
	<T>(check: ValidateFunction<T>) =>
	(value: unknown, file: string): T => {
		if (!check(value)) {
			throw new Error(`${file}: ${ajv.errorsText(check.errors)}`);
		}
		return value;
	};
const definition = (name: string) => ({ $ref: `${schema.$id}#/$defs/${name}` });
export const parseGate = parser(ajv.compile<GateFile>(definition("gate")));
export const parseRun = parser(ajv.compile<RunFile>(definition("run")));
export const parseRunLabels = parser(ajv.compile<RunLabels>(definition("runLabels")));

const texts = (review: Review): { kind: "summary" | "note"; body: string }[] => [
	...(review.summary ? [{ kind: "summary" as const, body: review.summary.body }] : []),
	...(review.inline ?? []).map((note) => ({ kind: "note" as const, body: note.body })),
];

/** The rubric codes that a review's words carry mechanically: Q6 length and Q9 formatting. */
export function mechanicalCodes(review: Review): Code[] {
	const codes = new Set<Code>();
	for (const text of texts(review)) {
		const prose = text.body.replaceAll(/`[^`]*`/gu, " ");
		if (text.body.length > (text.kind === "summary" ? SUMMARY_TARGET : NOTE_TARGET)) {
			codes.add("Q6");
		}
		if (FORMATTING.test(prose)) {
			codes.add("Q9");
		}
	}
	return [...codes];
}

/** The set, after its schema and the consistency every label must keep. */
export function parseCompositions(value: unknown): CompositionSet {
	if (!validate(value)) {
		throw new Error(`compositions: ${ajv.errorsText(validate.errors)}`);
	}
	const ids = new Set<string>();
	for (const item of value.cases) {
		const where = `compositions ${item.id}`;
		if (ids.has(item.id)) {
			throw new Error(`${where}: repeated case id`);
		}
		ids.add(item.id);
		const results = new Set(item.results.map((result) => result.id));
		if (
			Object.keys(item.expected).length !== results.size ||
			!Object.keys(item.expected).every((id) => results.has(id))
		) {
			throw new Error(`${where}: expected names exactly the case's results`);
		}
		if ((item.unsettled ?? []).some((id) => !results.has(id))) {
			throw new Error(`${where}: unsettled names a result the case does not hold`);
		}
		const reference = item.compositions.find((composition) => composition.id === "reference");
		if (reference === undefined || !reference.label.good) {
			throw new Error(`${where}: needs a good reference composition`);
		}
		for (const decision of reference.review.decisions) {
			if (
				!(item.unsettled ?? []).includes(decision.resultId) &&
				item.expected[decision.resultId] !== decision.disposition
			) {
				throw new Error(`${where}: the reference decides ${decision.resultId} unlike expected`);
			}
		}
		for (const composition of item.compositions) {
			const { label } = composition;
			if (label.good !== (label.codes.length === 0)) {
				throw new Error(`${where} ${composition.id}: good means no code`);
			}
			const missing = mechanicalCodes(composition.review).filter(
				(code) => !label.codes.includes(code),
			);
			if (missing.length > 0) {
				throw new Error(`${where} ${composition.id}: its words carry ${missing.join(", ")}`);
			}
		}
	}
	return value;
}

const ratio = (hits: number, of: number): number | null => (of === 0 ? null : hits / of);

/** Precision and recall, with the counts behind them. */
function measure(truePositive: number, predicted: number, actual: number) {
	return {
		precision: ratio(truePositive, predicted),
		recall: ratio(truePositive, actual),
		truePositive,
		predicted,
		actual,
	};
}

const tally = (values: string[]) =>
	Object.fromEntries(
		[...new Set(values)]
			.toSorted()
			.map((value) => [value, values.filter((each) => each === value).length]),
	);

function corpusSummary(set: CompositionSet) {
	const count = tally;
	const alternatives = set.cases.flatMap((item) =>
		item.compositions.filter((each) => each.id !== "reference"),
	);
	const expected = set.cases.flatMap((item) => Object.values(item.expected));
	return {
		cases: set.cases.length,
		providers: count(set.cases.map((item) => item.provider)),
		themes: count(set.cases.map((item) => item.theme)),
		practices: count(set.cases.flatMap((item) => item.results.map((result) => result.practice))),
		expectedDecisions: count(expected),
		unsettled: set.cases.flatMap((item) =>
			(item.unsettled ?? []).map((resultId) => ({ caseId: item.id, resultId })),
		),
		alternatives: {
			good: alternatives.filter((each) => each.label.good).length,
			bad: alternatives.filter((each) => !each.label.good).length,
		},
		codes: count(alternatives.flatMap((each) => each.label.codes)),
		criteriaQuestions: set.cases
			.filter((item) => item.criteriaQuestion !== null)
			.map((item) => ({ caseId: item.id, question: item.criteriaQuestion })),
	};
}

/** How well main's acceptance check refuses the bad alternatives, overall and per rubric code. */
function gateSummary(set: CompositionSet, gate: GateFile) {
	const checks = new Map(
		gate.checks.map((check) => [`${check.caseId}\0${check.compositionId}`, check]),
	);
	const rows = set.cases.flatMap((item) =>
		item.compositions
			.filter((composition) => composition.id !== "reference")
			.map((composition) => {
				const check = checks.get(`${item.id}\0${composition.id}`);
				if (!check) {
					throw new Error(`gate: no check for ${item.id} ${composition.id}`);
				}
				return { label: composition.label, refused: !check.accepted };
			}),
	);
	const bad = rows.filter((row) => !row.label.good);
	const refused = rows.filter((row) => row.refused);
	return {
		refusesBad: measure(bad.filter((row) => row.refused).length, refused.length, bad.length),
		recallByCode: Object.fromEntries(
			CODES.flatMap((code) => {
				const carrying = bad.filter((row) => row.label.codes.includes(code));
				const refusedCount = carrying.filter((row) => row.refused).length;
				return carrying.length === 0
					? []
					: [[code, { refused: refusedCount, of: carrying.length }] as const];
			}),
		),
		refusedGood: rows.filter((row) => row.label.good && row.refused).length,
	};
}

/** A run's decisions by result id. A refused review can have any shape; only an accepted one has main's. */
function composedDecisions(review: unknown): Map<string, string> {
	const decisions: unknown =
		typeof review === "object" && review !== null && "decisions" in review ? review.decisions : [];
	const listed: unknown[] = Array.isArray(decisions) ? decisions : [];
	return new Map(
		listed.flatMap((each) =>
			typeof each === "object" &&
			each !== null &&
			"resultId" in each &&
			"disposition" in each &&
			typeof each.resultId === "string" &&
			typeof each.disposition === "string"
				? [[each.resultId, each.disposition] as const]
				: [],
		),
	);
}

/** Why a run's output for a case cannot stand, apart from its decisions. */
function outputFailure(output: RunFile["cases"][number] | undefined): string | null {
	if (!output) {
		return "no output";
	}
	if (!output.accepted) {
		return `refused by main's check: ${output.failure ?? output.errors.join("; ")}`;
	}
	const codes = output.review === null ? [] : mechanicalCodes(output.review);
	return codes.length > 0 ? `words carry ${codes.join(", ")}` : null;
}

/** One composer run: raise decisions against the expected ones, main's acceptance, and labelled quality. */
function runSummary(set: CompositionSet, run: RunFile, labels: RunLabels | undefined) {
	const outputs = new Map(run.cases.map((output) => [output.id, output]));
	const rows = set.cases.flatMap((item) => {
		const decided = composedDecisions(outputs.get(item.id)?.review);
		return Object.entries(item.expected)
			.filter(([resultId]) => !(item.unsettled ?? []).includes(resultId))
			.map(([resultId, expected]) => ({
				caseId: item.id,
				resultId,
				expected,
				got: decided.get(resultId),
			}));
	});
	const shouldRaise = rows.filter((row) => row.expected === "RAISE");
	const shouldWithhold = rows.filter((row) => row.expected !== "RAISE");
	const raised = rows.filter((row) => row.got === "RAISE");
	const failures = [
		...rows
			.filter(
				(row) => row.got === undefined || (row.got === "RAISE") !== (row.expected === "RAISE"),
			)
			.map((row) => ({
				caseId: row.caseId,
				reason: `${row.resultId}: expected ${row.expected}, composed ${row.got ?? "no decision"}`,
			})),
		...set.cases.flatMap((item) => {
			const reason = outputFailure(outputs.get(item.id));
			return reason === null ? [] : [{ caseId: item.id, reason }];
		}),
	];
	const composed = run.cases.filter((output) => output.review !== null);
	const labelled = labels?.labels ?? [];
	const labelledIds = new Set(labelled.map((label) => label.caseId));
	const composedIds = new Set(composed.map((output) => output.id));
	if (
		labelledIds.size !== labelled.length ||
		labelled.some((label) => !composedIds.has(label.caseId))
	) {
		throw new Error("run labels: one label per composed case, and no other case");
	}
	const unlabelled = composed
		.filter((output) => !labelledIds.has(output.id))
		.map((output) => output.id);
	return {
		model: run.model,
		runner: run.runner,
		cases: run.cases.length,
		accepted: run.cases.filter((output) => output.accepted).length,
		firstAccepted: run.cases.filter((output) => output.firstAccepted !== false && output.accepted)
			.length,
		raise: measure(
			raised.filter((row) => row.expected === "RAISE").length,
			raised.length,
			shouldRaise.length,
		),
		withhold: {
			correct: shouldWithhold.filter((row) => row.got !== undefined && row.got !== "RAISE").length,
			of: shouldWithhold.length,
		},
		quality:
			labels === undefined
				? null
				: {
						labelled: labelled.length,
						of: composed.length,
						unlabelled,
						good: labelled.filter((label) => label.good).length,
						// The rate needs a label for every composed review; a partial labelling has none.
						goodRate:
							unlabelled.length === 0
								? ratio(labelled.filter((label) => label.good).length, composed.length)
								: null,
						codes: Object.fromEntries(
							CODES.flatMap((code) => {
								const count = labelled.filter((label) => label.codes.includes(code)).length;
								return count === 0 ? [] : [[code, count] as const];
							}),
						),
						bad: labelled
							.filter((label) => !label.good)
							.map((label) => ({ caseId: label.caseId, codes: label.codes, why: label.why })),
					},
		failures,
	};
}

/** The set's report: its contents, main's check against the labels, and each run side by side. */
export function compositionReport(
	set: CompositionSet,
	gate: GateFile | undefined,
	runs: { name: string; run: RunFile; labels: RunLabels | undefined }[],
) {
	return {
		releaseDecision: "not-assessed",
		corpus: corpusSummary(set),
		gate: gate === undefined ? null : gateSummary(set, gate),
		runs: runs.map((entry) => ({ name: entry.name, ...runSummary(set, entry.run, entry.labels) })),
	};
}
