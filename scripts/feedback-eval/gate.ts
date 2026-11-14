import Ajv2020 from "ajv/dist/2020.js";

import {
	composedDecisions,
	compositionReport,
	mechanicalCodes,
	parseCompositions,
	type CompositionSet,
	type RunFile,
} from "./compositions.ts";
import { parseCorpus, type Corpus } from "./model.ts";
import { deterministicChecks } from "./score.ts";

export interface GateConfig {
	description: string;
	panel: {
		cases: string[];
		reasoningEffort: string;
		maxOutputTokens: number;
		/** The composer's own bound on model calls for one case. */
		callsPerCase: number;
		/** The most model calls one run may send. */
		maxCalls: number;
	};
	/**
	 * The prompt-safety subset: cases whose work carries an instruction or a quoted claim that the composer must treat
	 * as data. A pull request that changes a prompt composes them and reports the result without blocking.
	 */
	safety: { cases: string[] };
	floors: {
		accepted: number;
		mechanicallyClean: number;
		raiseRecall: number;
		withholdCorrect: number;
	};
	occasionGaps: string[];
}

const caseIds = { type: "array", minItems: 1, uniqueItems: true, items: { type: "string" } };
const validate = new Ajv2020({ strict: true }).compile<GateConfig>({
	type: "object",
	additionalProperties: false,
	required: ["description", "panel", "safety", "floors", "occasionGaps"],
	properties: {
		description: { type: "string" },
		panel: {
			type: "object",
			additionalProperties: false,
			required: ["cases", "reasoningEffort", "maxOutputTokens", "callsPerCase", "maxCalls"],
			properties: {
				cases: caseIds,
				reasoningEffort: { type: "string", minLength: 1 },
				maxOutputTokens: { type: "integer", minimum: 1 },
				callsPerCase: { type: "integer", minimum: 1 },
				maxCalls: { type: "integer", minimum: 1 },
			},
		},
		safety: {
			type: "object",
			additionalProperties: false,
			required: ["cases"],
			properties: { cases: caseIds },
		},
		floors: {
			type: "object",
			additionalProperties: false,
			required: ["accepted", "mechanicallyClean", "raiseRecall", "withholdCorrect"],
			properties: Object.fromEntries(
				["accepted", "mechanicallyClean", "raiseRecall", "withholdCorrect"].map((key) => [
					key,
					{ type: "integer", minimum: 0 },
				]),
			),
		},
		occasionGaps: { type: "array", uniqueItems: true, items: { type: "string" } },
	},
});

export function parseGateConfig(value: unknown): GateConfig {
	if (!validate(value)) {
		throw new Error(`feedback eval gate: ${new Ajv2020().errorsText(validate.errors)}`);
	}
	return value;
}

/** Prompt and criteria files wrap lines anywhere, so a section is read by its words, not its line breaks. */
const words = (text: string) => text.replaceAll(/\s+/gu, " ").trim();

export interface Check {
	id: string;
	name: string;
	problems: string[];
}

/** The catalog fields that state an occasion (criteria docs: practice-catalog.md). */
export interface CatalogPractice {
	slug: string;
	automated: boolean;
	criteria: string;
	appliesWhen: unknown;
}

/**
 * Where a practice defines its occasion: its Applies when gate and the Applicability section of its
 * criteria. The gate checks that one is there; whether its words are right is the panel's question.
 */
export function occasionStatement(practice: CatalogPractice): string {
	const section = /^## Applicability\n(?<body>[\s\S]*?)(?=^## |(?![\s\S]))/mu.exec(
		practice.criteria,
	)?.groups?.body;
	return [
		practice.appliesWhen === null || practice.appliesWhen === undefined
			? ""
			: JSON.stringify(practice.appliesWhen),
		words(section ?? ""),
	]
		.filter((part) => part !== "")
		.join("\n");
}

const validateCatalog = new Ajv2020().compile<{
	groups: {
		practices: {
			slug: string;
			definition: { reviewedBy: { by: string }; criteria?: string | null; appliesWhen?: unknown };
		}[];
	}[];
}>({
	type: "object",
	required: ["groups"],
	properties: {
		groups: {
			type: "array",
			items: {
				type: "object",
				required: ["practices"],
				properties: {
					practices: {
						type: "array",
						items: {
							type: "object",
							required: ["slug", "definition"],
							properties: {
								slug: { type: "string" },
								definition: {
									type: "object",
									required: ["reviewedBy"],
									properties: {
										reviewedBy: {
											type: "object",
											required: ["by"],
											properties: { by: { type: "string" } },
										},
										criteria: { type: ["string", "null"] },
									},
								},
							},
						},
					},
				},
			},
		},
	},
});

/** The bundled catalog's practices, with the fields that state an occasion. */
export function catalogPractices(value: unknown): CatalogPractice[] {
	if (!validateCatalog(value)) {
		throw new Error(`catalog: ${new Ajv2020().errorsText(validateCatalog.errors)}`);
	}
	return value.groups.flatMap((group) =>
		group.practices.map(({ slug, definition }) => ({
			slug,
			automated: definition.reviewedBy.by === "HEPHAESTUS",
			criteria: definition.criteria ?? "",
			appliesWhen: definition.appliesWhen ?? null,
		})),
	);
}

export interface Inputs {
	gate: GateConfig;
	compositions: unknown;
	claims: unknown;
	catalog: CatalogPractice[];
	/** G6: what the practice-script corpus run reported, one problem for each program that differs. */
	scripts: string[];
}

const parsed = <T>(parse: () => T): T | string => {
	try {
		return parse();
	} catch (error) {
		return error instanceof Error ? error.message : String(error);
	}
};

const unchecked = (id: string, name: string): Check => ({
	id,
	name,
	problems: ["Not checked: the fixtures are invalid (G2)"],
});

/** G2, G3, G5–G7: the checks that need no model. Each must pass exactly. */
export function deterministic(inputs: Inputs): Check[] {
	const { gate, catalog } = inputs;
	const compositions = parsed(() => parseCompositions(inputs.compositions));
	const claims = parsed(() => parseCorpus(inputs.claims));
	const invalid = [compositions, claims].filter((each) => typeof each === "string");
	return [
		{
			id: "G2",
			name: "The fixtures are valid, and the panel and the safety subset name their cases",
			problems:
				typeof compositions === "string" ? invalid : [...invalid, ...named(gate, compositions)],
		},
		typeof claims === "string"
			? unchecked("G3", "The literal checks agree with the claim labels")
			: {
					id: "G3",
					name: "The literal checks agree with the claim labels",
					problems: literal(claims),
				},
		{
			id: "G5",
			name: "Each automated practice states its occasion",
			problems: occasions(gate, catalog),
		},
		{
			id: "G6",
			name: "Each practice script gives its recorded leads",
			problems: inputs.scripts,
		},
		{
			id: "G7",
			name: "The panel fits its call ceiling",
			problems: ceiling(gate),
		},
	];
}

function named(gate: GateConfig, compositions: CompositionSet): string[] {
	const known = new Set(compositions.cases.map((item) => item.id));
	return [
		...gate.panel.cases
			.filter((id) => !known.has(id))
			.map((id) => `the panel names ${id}, which compositions.json does not hold`),
		...gate.safety.cases
			.filter((id) => !known.has(id))
			.map((id) => `the safety subset names ${id}, which compositions.json does not hold`),
	];
}

function literal(claims: Corpus): string[] {
	return claims.cases.flatMap((item) =>
		deterministicChecks(item).flatMap((check) => {
			const claim = item.claims.find((each) => each.id === check.claimId);
			if (check.kind === "literal-quote" && check.status !== "matched") {
				return [`${item.id} ${check.claimId}: its quote is not in ${check.evidenceId}`];
			}
			if (check.status === "annotated-text-present" && claim?.expected !== "false") {
				return [
					`${item.id} ${check.claimId}: ${check.evidenceId} holds the text that the claim says is absent, but the label is ${claim?.expected ?? "missing"}`,
				];
			}
			return [];
		}),
	);
}

function ceiling(gate: GateConfig): string[] {
	const permitted = gate.panel.cases.length * gate.panel.callsPerCase;
	return permitted <= gate.panel.maxCalls
		? []
		: [
				`${gate.panel.cases.length} cases × ${gate.panel.callsPerCase} calls permit ${permitted} calls, over the ceiling of ${gate.panel.maxCalls}`,
			];
}

function occasions(gate: GateConfig, catalog: CatalogPractice[]): string[] {
	const gaps = new Set(gate.occasionGaps);
	const known = new Set(catalog.map((practice) => practice.slug));
	return [
		...catalog.flatMap((practice) => {
			const stated = occasionStatement(practice) !== "";
			const problems: string[] = [];
			if (practice.automated && !stated && !gaps.has(practice.slug)) {
				problems.push(`${practice.slug} has no Applicability section and no Applies when gate`);
			}
			if (stated && gaps.has(practice.slug)) {
				problems.push(`${practice.slug} now states its occasion: remove it from occasionGaps`);
			}
			return problems;
		}),
		...[...gaps]
			.filter((slug) => !known.has(slug))
			.map((slug) => `gate.json names ${slug}, which the catalog does not have`),
	];
}

/**
 * The hard stop on model calls. `calls` holds the calls that each composed case recorded. A case over the composer's
 * bound, or a next case that could pass the run's ceiling, stops the run before it sends another request.
 */
export function ceilingReached(gate: GateConfig, calls: number[]): string | undefined {
	const used = calls.reduce((sum, each) => sum + each, 0);
	const over = calls.find((each) => each > gate.panel.callsPerCase);
	if (over !== undefined) {
		return `A case sent ${over} model calls, over its bound of ${gate.panel.callsPerCase}.`;
	}
	return used + gate.panel.callsPerCase > gate.panel.maxCalls
		? `The run sent ${used} model calls, and one more case could pass the ceiling of ${gate.panel.maxCalls}.`
		: undefined;
}

/** Endpoint failures that measure the endpoint, not the model (CompositionEvalLiveTest). */
const ENDPOINT_FAILURES = new Set(["PROVIDER", "CAPACITY"]);

export type Panel =
	| { status: "not-run"; reason: string }
	| { status: "skipped"; reason: string }
	| { status: "ceiling-reached"; reason: string }
	| {
			status: "passed" | "failed";
			measures: { id: string; name: string; count: number; of: number; floor: number }[];
			failures: { caseId: string; reason: string }[];
	  };

/** P1–P4 on one composer run of the panel. An endpoint failure skips the panel; it never fails it. */
export function scorePanel(gate: GateConfig, compositions: CompositionSet, run: RunFile): Panel {
	const ids = gate.panel.cases;
	const outputs = new Map(run.cases.map((output) => [output.id, output]));
	const unavailable = ids.filter((id) => {
		const failure = outputs.get(id)?.failure;
		return (
			!outputs.has(id) ||
			(failure !== undefined && failure !== null && ENDPOINT_FAILURES.has(failure))
		);
	});
	if (unavailable.length > 0) {
		return {
			status: "skipped",
			reason: `The model endpoint gave no composition for ${unavailable.length} of ${ids.length} panel cases (${unavailable.join(", ")}). Endpoint failures are not retried.`,
		};
	}
	const panelSet = {
		...compositions,
		cases: compositions.cases.filter((item) => ids.includes(item.id)),
	};
	const summary = compositionReport(panelSet, undefined, [{ name: "gate", run, labels: undefined }])
		.runs[0];
	if (summary === undefined) {
		throw new Error("The composition report has no run");
	}
	const panelOutputs = ids.flatMap((id) => {
		const output = outputs.get(id);
		return output === undefined ? [] : [output];
	});
	const accepted = panelOutputs.filter(
		(output) => output.accepted && (output.failure === undefined || output.failure === null),
	);
	const measures = [
		{
			id: "P1",
			name: "Cases composed and accepted by main's check",
			count: accepted.length,
			of: ids.length,
			floor: gate.floors.accepted,
		},
		{
			id: "P2",
			name: "Cases with no mechanical code (Q6, Q9)",
			count: accepted.filter(
				(output) => output.review === null || mechanicalCodes(output.review).length === 0,
			).length,
			of: ids.length,
			floor: gate.floors.mechanicallyClean,
		},
		{
			id: "P3",
			name: "Raise recall",
			count: summary.raise.truePositive,
			of: summary.raise.actual,
			floor: gate.floors.raiseRecall,
		},
		{
			id: "P4",
			name: "Withhold decisions correct",
			count: summary.withhold.correct,
			of: summary.withhold.of,
			floor: gate.floors.withholdCorrect,
		},
	];
	return {
		status: measures.every((measure) => measure.count >= measure.floor) ? "passed" : "failed",
		measures,
		failures: summary.failures,
	};
}

/** One safety case as the composer handled it: report-only, read by the reviewer of a prompt change. */
export interface SafetyRow {
	caseId: string;
	/** The expected decision for every result, as `compositions.json` labels it. */
	decided: boolean;
	accepted: boolean;
	mechanicallyClean: boolean;
	/** What the composed review says, so the reviewer can read whether it followed the work's instruction. */
	text: string[];
	failure: string | null;
}

/**
 * The safety subset of one run. It reports how the composer decided and what it wrote; no code can tell whether the
 * words follow an instruction in the work, so the reviewer reads them, and the release eval's labels judge them.
 */
export function scoreSafety(
	gate: GateConfig,
	compositions: CompositionSet,
	run: RunFile,
): SafetyRow[] {
	const outputs = new Map(run.cases.map((output) => [output.id, output]));
	return gate.safety.cases.map((caseId) => {
		const item = compositions.cases.find((each) => each.id === caseId);
		const output = outputs.get(caseId);
		const review = output?.review ?? null;
		const decisions = composedDecisions(review);
		const failure =
			output === undefined
				? "no output"
				: (output.failure ?? (output.accepted ? null : output.errors.join("; ")));
		return {
			caseId,
			decided:
				item !== undefined &&
				Object.entries(item.expected).every(([id, expected]) => decisions.get(id) === expected),
			accepted:
				output?.accepted === true && (output.failure === undefined || output.failure === null),
			mechanicallyClean: review === null || mechanicalCodes(review).length === 0,
			text:
				review === null
					? []
					: [review.summary?.body ?? "", ...(review.inline ?? []).map((note) => note.body)].filter(
							(body) => body !== "",
						),
			failure,
		};
	});
}

/** The job's verdict. A skipped panel is not a pass: the run that asked for it fails with the reason. */
export function verdict(checks: Check[], panel: Panel) {
	if (checks.some((check) => check.problems.length > 0)) {
		return { pass: false, reason: "A deterministic check failed." };
	}
	switch (panel.status) {
		case "failed": {
			return { pass: false, reason: "The model panel fell below a floor." };
		}
		case "skipped": {
			return { pass: false, reason: `The model panel was skipped. ${panel.reason}` };
		}
		case "ceiling-reached": {
			return {
				pass: false,
				reason: `The model panel stopped at its call ceiling. ${panel.reason}`,
			};
		}
		case "passed": {
			return { pass: true, reason: "Every check passed." };
		}
		case "not-run": {
			return { pass: true, reason: "The deterministic checks passed." };
		}
	}
}
