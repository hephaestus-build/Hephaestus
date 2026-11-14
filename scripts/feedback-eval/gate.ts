import { createHash } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";

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
	claims: Record<string, string>;
	panel: {
		cases: Record<string, string>;
		reasoningEffort: string;
		maxOutputTokens: number;
		/** The composer's own bound on model calls for one case. */
		callsPerCase: number;
		/** The most model calls one run may send. */
		maxCalls: number;
	};
	floors: {
		accepted: number;
		mechanicallyClean: number;
		raiseRecall: number;
		withholdCorrect: number;
	};
	clauses: { criterion: string; file: string; text: string }[];
	prompts: Record<string, string>;
	practices: Record<string, string>;
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
		"claims",
		"panel",
		"floors",
		"clauses",
		"prompts",
		"practices",
		"occasionGaps",
	],
	properties: {
		version: { type: "integer", minimum: 1 },
		description: { type: "string" },
		claims: { ...digests, minProperties: 1 },
		panel: {
			type: "object",
			additionalProperties: false,
			required: ["cases", "reasoningEffort", "maxOutputTokens", "callsPerCase", "maxCalls"],
			properties: {
				cases: { ...digests, minProperties: 1 },
				reasoningEffort: { type: "string", minLength: 1 },
				maxOutputTokens: { type: "integer", minimum: 1 },
				callsPerCase: { type: "integer", minimum: 1 },
				maxCalls: { type: "integer", minimum: 1 },
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
		prompts: { ...digests, minProperties: 1 },
		practices: { ...digests, minProperties: 1 },
		occasionGaps: { type: "array", uniqueItems: true, items: { type: "string" } },
	},
});

export function parseGateConfig(value: unknown): GateConfig {
	if (!validate(value)) {
		throw new Error(`feedback eval gate: ${new Ajv2020().errorsText(validate.errors)}`);
	}
	return value;
}

const sha256 = (text: string) => createHash("sha256").update(text).digest("hex");

/** A pinned case's digest: its JSON as the fixture file holds it. */
export const caseDigest = (value: unknown) => sha256(JSON.stringify(value));

/** Prompt files wrap lines anywhere, so a clause matches on its words, not on its line breaks. */
const words = (text: string) => text.replaceAll(/\s+/gu, " ").trim();

/**
 * A prompt's pin: the SHA-256 of its whole text. A text check cannot judge meaning, and a new paragraph can override a
 * rule that is still there, so any edit to a prompt fails until the pull request pins it again.
 */
export const promptDigest = (text: string) => sha256(text);

const PROMPT_DIRECTORIES = [
	"server/application/src/main/resources/agent",
	"server/application/src/main/resources/agent/mentor",
];
const WRITING_GUIDE = "docs/contributor/practice-feedback-language.md";

/** The prompts in scope, by their `gate.json` key: every prompt file, and the writing guide's section. */
export function readPrompts(): Record<string, string> {
	const files = PROMPT_DIRECTORIES.flatMap((directory) =>
		readdirSync(directory)
			.filter((name) => name.endsWith(".md"))
			.map((name) => `${directory}/${name}`),
	).toSorted();
	return {
		...Object.fromEntries(files.map((file) => [file, readFileSync(file, "utf8")])),
		[`${WRITING_GUIDE}#writing-feedback`]: writingFeedback(readFileSync(WRITING_GUIDE, "utf8")),
	};
}

/** The writing guide's section that `FeedbackWritingGuide` puts in the prompts, read by the same rule. */
export function writingFeedback(page: string): string {
	const heading = "## Writing feedback\n";
	const start = page.indexOf(heading);
	if (start === -1) {
		return "";
	}
	const end = page.indexOf("\n## ", start + heading.length);
	return page.slice(start, end === -1 ? undefined : end).trim();
}

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
 * Where a practice defines its occasion for a review: the Applicability section of its criteria. An Applies when gate
 * only rules out a change that cannot hold the subject, and a scan section covers only a scan, so neither replaces it.
 * The gate checks that the section is there; whether its words are right is the panel's question.
 */
export function occasionStatement(practice: CatalogPractice): string {
	const section = /^## Applicability\n(?<body>[\s\S]*?)(?=^## |(?![\s\S]))/mu.exec(
		practice.criteria,
	)?.groups?.body;
	return words(section ?? "");
}

/** A practice's pin: its whole criteria and Applies when, so an edit to either needs a new pin. */
export const practiceDigest = (practice: CatalogPractice) =>
	sha256(JSON.stringify([practice.criteria, practice.appliesWhen ?? null]));

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
	/** The manifest at the base as parsed JSON, when the run has one. Its schema can be older. */
	base: unknown;
	compositions: unknown;
	claims: unknown;
	catalog: CatalogPractice[];
	read: (file: string) => string | undefined;
	/**
	 * The prompt texts in scope, by their `gate.json` key: each prompt file, and the writing guide's section that the
	 * prompts include.
	 */
	prompts: Record<string, string>;
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

/** G1–G6: the checks that need no model. Each must pass exactly. */
export function deterministic(inputs: Inputs): Check[] {
	const { gate, catalog, read } = inputs;
	const compositions = parsed(() => parseCompositions(inputs.compositions));
	const claims = parsed(() => parseCorpus(inputs.claims));
	const invalid = [compositions, claims].filter((each) => typeof each === "string");
	const valid = typeof compositions !== "string" && typeof claims !== "string";
	return [
		valid
			? {
					id: "G1",
					name: "The pinned cases match, and a changed manifest has a new version",
					problems: [...pins(gate, compositions, claims), ...versioned(gate, inputs.base)],
				}
			: unchecked("G1", "The pinned cases match, and a changed manifest has a new version"),
		{ id: "G2", name: "The fixtures are valid", problems: invalid },
		valid
			? {
					id: "G3",
					name: "The literal checks agree with the claim labels",
					problems: literal(gate, claims),
				}
			: unchecked("G3", "The literal checks agree with the claim labels"),
		{
			id: "G4",
			name: "Each prompt is unchanged and carries every criterion clause",
			problems: [...clauses(gate, read), ...promptPins(gate, inputs.prompts)],
		},
		{
			id: "G5",
			name: "Each practice states its occasion, and its criteria are unchanged",
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

/** Every pin or floor change is a new manifest version, so the change cannot pass unnoticed. */
function versioned(gate: GateConfig, base: unknown): string[] {
	if (base === undefined) {
		return [];
	}
	if (
		typeof base !== "object" ||
		base === null ||
		!("version" in base) ||
		typeof base.version !== "number"
	) {
		return ["the manifest at the base has no version"];
	}
	const { version: baseVersion, ...before } = base;
	const { version, ...current } = gate;
	if (version < baseVersion) {
		return [`version ${version} is lower than ${baseVersion} at the base`];
	}
	return version === baseVersion && JSON.stringify(current) !== JSON.stringify(before)
		? [`gate.json changed since the base, but its version is still ${version}`]
		: [];
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

function promptPins(gate: GateConfig, prompts: Inputs["prompts"]): string[] {
	return [
		...Object.entries(prompts).flatMap(([key, text]) => {
			const pin = gate.prompts[key];
			const actual = promptDigest(text);
			if (pin === undefined) {
				return [`${key} has no pin (now ${actual})`];
			}
			return pin === actual
				? []
				: [`${key} changed (now ${actual}); pin it again under a new gate version`];
		}),
		...Object.keys(gate.prompts)
			.filter((key) => !(key in prompts))
			.map((key) => `gate.json pins ${key}, which is not a prompt in scope`),
	];
}

function ceiling(gate: GateConfig): string[] {
	const permitted = Object.keys(gate.panel.cases).length * gate.panel.callsPerCase;
	return permitted <= gate.panel.maxCalls
		? []
		: [
				`${Object.keys(gate.panel.cases).length} cases × ${gate.panel.callsPerCase} calls permit ${permitted} calls, over the ceiling of ${gate.panel.maxCalls}`,
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
				problems.push(`${practice.slug} has no Applicability section`);
			}
			if (stated && gaps.has(practice.slug)) {
				problems.push(`${practice.slug} now states its occasion: remove it from occasionGaps`);
			}
			const actual = practiceDigest(practice);
			const pin = gate.practices[practice.slug];
			if (pin === undefined) {
				problems.push(`${practice.slug} has no pin (now ${actual})`);
			} else if (pin !== actual) {
				problems.push(
					`${practice.slug}: its criteria or Applies when changed (now ${actual}); pin it again under a new gate version`,
				);
			}
			return problems;
		}),
		...[...gaps, ...Object.keys(gate.practices)]
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
