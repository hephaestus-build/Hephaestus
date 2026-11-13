import { createHash } from "node:crypto";

import Ajv2020 from "ajv/dist/2020.js";

import {
	compositionReport,
	mechanicalCodes,
	parseCompositions,
	type CompositionSet,
	type RunFile,
} from "./compositions.ts";
import { parseCorpus, type Corpus } from "./model.ts";
import { deterministicChecks } from "./score.ts";

export interface GateConfig {
	version: number;
	description: string;
	acceptLabel: string;
	claims: Record<string, string>;
	panel: { cases: Record<string, string>; reasoningEffort: string; maxOutputTokens: number };
	floors: {
		accepted: number;
		mechanicallyClean: number;
		raiseRecall: number;
		withholdCorrect: number;
	};
	clauses: { criterion: string; file: string; text: string }[];
	occasionGaps: string[];
}

const digests = {
	type: "object",
	additionalProperties: { type: "string", pattern: "^[0-9a-f]{64}$" },
};
const validate = new Ajv2020({ strict: true }).compile<GateConfig>({
	type: "object",
	additionalProperties: false,
	required: [
		"version",
		"description",
		"acceptLabel",
		"claims",
		"panel",
		"floors",
		"clauses",
		"occasionGaps",
	],
	properties: {
		version: { type: "integer", minimum: 1 },
		description: { type: "string" },
		acceptLabel: { type: "string", minLength: 1 },
		claims: { ...digests, minProperties: 1 },
		panel: {
			type: "object",
			additionalProperties: false,
			required: ["cases", "reasoningEffort", "maxOutputTokens"],
			properties: {
				cases: { ...digests, minProperties: 1 },
				reasoningEffort: { type: "string", minLength: 1 },
				maxOutputTokens: { type: "integer", minimum: 1 },
			},
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
		clauses: {
			type: "array",
			minItems: 1,
			items: {
				type: "object",
				additionalProperties: false,
				required: ["criterion", "file", "text"],
				properties: {
					criterion: { type: "string", minLength: 1 },
					file: { type: "string", minLength: 1 },
					text: { type: "string", minLength: 1 },
				},
			},
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

/** A pinned case's digest: its JSON as the fixture file holds it. */
export const caseDigest = (value: unknown) =>
	createHash("sha256").update(JSON.stringify(value)).digest("hex");

/** Prompt files wrap lines anywhere, so a clause matches on its words, not on its line breaks. */
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
	read: (file: string) => string | undefined;
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

const pinned = (kind: string, pinsOf: Record<string, string>, found: Map<string, unknown>) =>
	Object.entries(pinsOf).flatMap(([id, digest]) => {
		const item = found.get(id);
		if (item === undefined) {
			return [`${kind} ${id} is missing`];
		}
		const actual = caseDigest(item);
		return actual === digest
			? []
			: [`${kind} ${id} changed (now ${actual}); pin it again under a new gate version`];
	});

/** G1–G5: the checks that need no model. Each must pass exactly. */
export function deterministic(inputs: Inputs): Check[] {
	const { gate, catalog, read } = inputs;
	const compositions = parsed(() => parseCompositions(inputs.compositions));
	const claims = parsed(() => parseCorpus(inputs.claims));
	const invalid = [compositions, claims].filter((each) => typeof each === "string");
	const fixtures = { id: "G2", name: "The fixtures are valid", problems: invalid };
	const clauseCheck = {
		id: "G4",
		name: "The prompts carry every criterion clause",
		problems: clauses(gate, read),
	};
	const occasionCheck = {
		id: "G5",
		name: "Each automated practice states its occasion",
		problems: occasions(gate, catalog),
	};
	if (typeof compositions === "string" || typeof claims === "string") {
		return [
			unchecked("G1", "The fixed case set is unchanged"),
			fixtures,
			unchecked("G3", "The literal checks agree with the claim labels"),
			clauseCheck,
			occasionCheck,
		];
	}
	return [
		{
			id: "G1",
			name: "The fixed case set is unchanged",
			problems: pins(gate, compositions, claims),
		},
		fixtures,
		{
			id: "G3",
			name: "The literal checks agree with the claim labels",
			problems: literal(gate, claims),
		},
		clauseCheck,
		occasionCheck,
	];
}

function pins(gate: GateConfig, compositions: CompositionSet, claims: Corpus): string[] {
	return [
		...pinned("claim case", gate.claims, new Map(claims.cases.map((item) => [item.id, item]))),
		...pinned(
			"panel case",
			gate.panel.cases,
			new Map(compositions.cases.map((item) => [item.id, item])),
		),
	];
}

function literal(gate: GateConfig, claims: Corpus): string[] {
	return claims.cases
		.filter((item) => item.id in gate.claims)
		.flatMap((item) =>
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

function clauses(gate: GateConfig, read: Inputs["read"]): string[] {
	return gate.clauses.flatMap(({ criterion, file, text }) => {
		const source = read(file);
		if (source === undefined) {
			return [`${criterion}: ${file} is missing`];
		}
		return words(source).includes(words(text))
			? []
			: [`${criterion}: ${file} no longer says "${text}"`];
	});
}

function occasions(gate: GateConfig, catalog: CatalogPractice[]): string[] {
	const gaps = new Set(gate.occasionGaps);
	const known = new Set(catalog.map((practice) => practice.slug));
	return [
		...catalog
			.filter((practice) => practice.automated)
			.flatMap((practice) => {
				const stated =
					(practice.appliesWhen !== null && practice.appliesWhen !== undefined) ||
					/DOES_NOT_APPLY|does not apply/iu.test(practice.criteria);
				if (!stated && !gaps.has(practice.slug)) {
					return [`${practice.slug} states no occasion: say when it does not apply`];
				}
				if (stated && gaps.has(practice.slug)) {
					return [`${practice.slug} now states its occasion: remove it from occasionGaps`];
				}
				return [];
			}),
		...gate.occasionGaps
			.filter((slug) => !known.has(slug))
			.map((slug) => `occasionGaps names ${slug}, which the catalog does not have`),
	];
}

/** Endpoint failures that measure the endpoint, not the model (CompositionEvalLiveTest). */
const ENDPOINT_FAILURES = new Set(["PROVIDER", "CAPACITY"]);

export type Panel =
	| { status: "not-run"; reason: string }
	| { status: "skipped"; reason: string }
	| {
			status: "passed" | "failed";
			measures: { id: string; name: string; count: number; of: number; floor: number }[];
			failures: { caseId: string; reason: string }[];
	  };

/** P1–P4 on one composer run of the panel. An endpoint failure skips the panel; it never fails it. */
export function scorePanel(gate: GateConfig, compositions: CompositionSet, run: RunFile): Panel {
	const ids = Object.keys(gate.panel.cases);
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

/** The job's verdict. A skipped panel passes only with the maintainer's acceptance label. */
export function verdict(checks: Check[], panel: Panel, accepted: boolean) {
	const deterministicFailed = checks.some((check) => check.problems.length > 0);
	if (deterministicFailed) {
		return { pass: false, reason: "A deterministic check failed." };
	}
	if (panel.status === "failed") {
		return { pass: false, reason: "The model panel fell below a floor." };
	}
	if (panel.status === "skipped") {
		return accepted
			? { pass: true, reason: "The model panel was skipped, and a maintainer accepted the skip." }
			: {
					pass: false,
					reason:
						"The model panel was skipped. A maintainer accepts the skip with the acceptance label, then re-runs this job.",
				};
	}
	return {
		pass: true,
		reason: panel.status === "passed" ? "Every check passed." : "The deterministic checks passed.",
	};
}
