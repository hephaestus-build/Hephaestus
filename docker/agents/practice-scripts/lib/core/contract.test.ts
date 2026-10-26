import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
	CANDIDATE_LIMITS,
	CANDIDATE_REFUSALS,
	MAX_REVIEW_OPTIONS_BYTES,
	parseCandidate,
	parseMeta,
	parseReviewOptions,
	type CandidateRefusal,
} from "./contract.ts";
import { isJsonObject } from "./json.ts";

function accepted(value: unknown) {
	const parsed = parseCandidate(value);
	assert.ok(parsed.ok, parsed.ok ? "" : `${parsed.reasonCode}: ${parsed.message}`);
	return parsed.candidate;
}

function refusalOf(value: unknown): CandidateRefusal | "accepted" {
	const parsed = parseCandidate(value);
	return parsed.ok ? "accepted" : parsed.reasonCode;
}

function met(): Record<string, unknown> {
	return {
		outcome: "MET",
		summary: "Added comments explain why",
		explanation: "Each added comment states a constraint that the code cannot express.",
		citations: [citation(10)],
		evidence: {
			search: {
				consulted: ["scm.pull-request.diff"],
				lookedFor: "comments",
				boundary: "the change",
			},
		},
		coverage: {
			source: "host",
			reads: 2,
			truncatedReads: 0,
			failedReads: 0,
			receipts: [],
			complete: true,
		},
	};
}

function citation(line: number, quote = "  // why"): Record<string, unknown> {
	return {
		sourceKind: "scm.pull-request.diff",
		sourcePath: "context/change.json",
		path: "src/a.ts",
		side: "NEW",
		startLine: line,
		endLine: line,
		quote,
	};
}

function problem(line: number, fields: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		severity: "MAJOR",
		summary: `A problem at line ${line}`,
		explanation: "The added line drops the error.",
		citations: [citation(line, "  } catch {}")],
		basis: { type: "introduced", head: 0, explanation: "The change adds the line." },
		...fields,
	};
}

function notMet(): Record<string, unknown> {
	return {
		outcome: "NOT_MET",
		summary: "Two added catch blocks drop errors",
		explanation: "Two catch blocks end the failure path without a trace.",
		citations: [],
		problems: [problem(20), problem(30, { severity: "MINOR" })],
		example: { citation: citation(40, "  throw error;"), note: "This catch rethrows." },
		evidence: {},
		coverage: {
			source: "host",
			reads: 1,
			truncatedReads: 0,
			failedReads: 0,
			receipts: [],
			complete: true,
		},
	};
}

function notApplicable(): Record<string, unknown> {
	return {
		outcome: "DOES_NOT_APPLY",
		summary: "No user interface changes",
		explanation: "The change touches no view.",
		citations: [citation(1, "export {};")],
		evidence: {
			inapplicability: {
				consulted: ["scm.pull-request.diff"],
				prerequisite: "views",
				ruledOutBy: "no view file",
			},
		},
		coverage: {
			source: "host",
			reads: 1,
			truncatedReads: 0,
			failedReads: 0,
			receipts: [],
			complete: true,
		},
	};
}

function undetermined(): Record<string, unknown> {
	return {
		outcome: "COULD_NOT_DETERMINE",
		summary: "The header's origin is unclear",
		explanation: "Who sets the header decides it.",
		citations: [citation(5, "  read(header);")],
		evidence: {
			openQuestions: [
				{ openQuestion: "Who sets the header?", wouldSettleIt: "The gateway configuration." },
			],
		},
		coverage: {
			source: "host",
			reads: 1,
			truncatedReads: 0,
			failedReads: 0,
			receipts: [],
			complete: true,
		},
	};
}

const BASES = { met, notMet, notApplicable, undetermined };

type Breach = (value: Record<string, unknown>) => unknown;

const incomplete = {
	source: "host",
	reads: 2,
	truncatedReads: 1,
	failedReads: 0,
	receipts: [],
	complete: false,
	omitted: [{ what: "lines beyond the read limit", count: 1, reason: "truncated" }],
};

const problemsOf = (value: Record<string, unknown>): Record<string, unknown>[] =>
	Array.isArray(value.problems) ? value.problems.filter(isJsonObject) : [];
const evidenceOf = (value: Record<string, unknown>): Record<string, unknown> =>
	isJsonObject(value.evidence) ? value.evidence : {};

const BREACHES: Record<
	Exclude<CandidateRefusal, "example_overlaps_problem">,
	[keyof typeof BASES, Breach]
> = {
	malformed_candidate: ["notMet", (v) => Object.assign(v, { extra: true })],
	outcome_problem_mismatch: ["met", (v) => Object.assign(v, { problems: [problem(20)] })],
	warrant_outcome_mismatch: [
		"notMet",
		(v) =>
			Object.assign(evidenceOf(v), {
				inapplicability: {
					consulted: ["scm.pull-request.diff"],
					prerequisite: "s",
					ruledOutBy: "r",
				},
			}),
	],
	result_citations_required: ["met", (v) => Object.assign(v, { citations: [] })],
	met_coverage_incomplete: ["met", (v) => Object.assign(v, { coverage: incomplete })],
	coverage_inconsistent: [
		"notMet",
		(v) =>
			Object.assign(v, {
				coverage: { source: "claimed", selected: 3, checked: 3, total: 3, complete: true },
			}),
	],
	example_outcome_mismatch: [
		"undetermined",
		(v) =>
			Object.assign(v, {
				example: { citation: citation(40, "  throw error;"), note: "A good catch." },
			}),
	],
	basis_citation_out_of_range: [
		"notMet",
		(v) => {
			const [first] = problemsOf(v);
			Object.assign(first ?? {}, {
				basis: { type: "worsened", base: 0, head: 1, explanation: "e" },
			});
		},
	],
	duplicate_problem_key: [
		"notMet",
		(v) => {
			for (const each of problemsOf(v)) {
				Object.assign(each, { key: "same-key" });
			}
		},
	],
};

function broken(
	base: keyof typeof BASES,
	...codes: (keyof typeof BREACHES)[]
): Record<string, unknown> {
	const value = BASES[base]();
	for (const code of codes) {
		BREACHES[code][1](value);
	}
	return value;
}

void describe("the candidate rules", () => {
	void test("claimed line overlap waits for canonical quote verification", () => {
		const value = notMet();
		value.example = {
			citation: citation(20, "  throw error;"),
			note: "A separate quote at a claimed problem line.",
		};
		assert.equal(refusalOf(value), "accepted");
	});
	void test("each base result is valid", () => {
		for (const make of Object.values(BASES)) {
			accepted(make());
		}
	});

	void test("each structural refusal is reachable by breaking its rule alone", () => {
		for (const [code, [base, breach]] of Object.entries(BREACHES)) {
			const value = BASES[base]();
			breach(value);
			assert.equal(refusalOf(value), code, code);
		}
	});

	void test("a result that breaks two rules is refused with the code of the earlier rule", () => {
		const pairs: [keyof typeof BASES, keyof typeof BREACHES, keyof typeof BREACHES][] = [
			["met", "malformed_candidate", "outcome_problem_mismatch"],
			["notMet", "warrant_outcome_mismatch", "coverage_inconsistent"],
			["met", "result_citations_required", "met_coverage_incomplete"],
			["notMet", "coverage_inconsistent", "basis_citation_out_of_range"],
			["notMet", "basis_citation_out_of_range", "duplicate_problem_key"],
			["undetermined", "example_outcome_mismatch", "malformed_candidate"],
		];
		for (const [base, first, second] of pairs) {
			const expected =
				CANDIDATE_REFUSALS.indexOf(first) < CANDIDATE_REFUSALS.indexOf(second) ? first : second;
			assert.equal(refusalOf(broken(base, first, second)), expected, `${first} with ${second}`);
			assert.equal(refusalOf(broken(base, second, first)), expected, `${second} with ${first}`);
		}
	});

	void test("a MET result with its problems is named by the outcome rule, never as an unknown key", () => {
		assert.equal(refusalOf(broken("met", "outcome_problem_mismatch")), "outcome_problem_mismatch");
	});

	void test("an COULD_NOT_DETERMINE result without an open question breaks the warrant rule before the citation rule", () => {
		const value = undetermined();
		value.evidence = {};
		value.citations = [];
		assert.equal(refusalOf(value), "warrant_outcome_mismatch");
	});

	void test("a NOT_MET result needs no result citation, and any other outcome needs one", () => {
		accepted(notMet());
		for (const make of [met, notApplicable, undetermined]) {
			const value = make();
			value.citations = [];
			assert.equal(refusalOf(value), "result_citations_required", String(value.outcome));
		}
	});

	void test("complete host coverage has no cut or failed read, while unknown coverage stays incomplete", () => {
		const value = undetermined();
		value.coverage = {
			source: "host",
			reads: 1,
			truncatedReads: 0,
			failedReads: 1,
			receipts: [],
			complete: true,
		};
		assert.equal(refusalOf(value), "coverage_inconsistent");
		value.coverage = { ...incomplete, truncatedReads: 0 };
		accepted(value);
		value.coverage = incomplete;
		accepted(value);
	});

	void test("a value without an outcome is malformed: nothing guesses what it meant", () => {
		assert.equal(refusalOf({ leads: [], facts: {} }), "malformed_candidate");
		assert.equal(refusalOf(null), "malformed_candidate");
		assert.equal(refusalOf("MET"), "malformed_candidate");
	});
});

void describe("the candidate bounds", () => {
	void test("a summary is one line of at most 160 characters", () => {
		const value = met();
		value.summary = "s".repeat(CANDIDATE_LIMITS.summaryChars);
		accepted(value);
		value.summary = "s".repeat(CANDIDATE_LIMITS.summaryChars + 1);
		assert.equal(refusalOf(value), "malformed_candidate");
		value.summary = "first line\nsecond line";
		assert.equal(refusalOf(value), "malformed_candidate");
		assert.equal(CANDIDATE_LIMITS.summaryChars, 160);
	});

	void test("a quote holds at most 4000 characters", () => {
		const value = met();
		value.citations = [citation(1, "q".repeat(CANDIDATE_LIMITS.quoteChars))];
		accepted(value);
		value.citations = [citation(1, "q".repeat(CANDIDATE_LIMITS.quoteChars + 1))];
		assert.equal(refusalOf(value), "malformed_candidate");
		assert.equal(CANDIDATE_LIMITS.quoteChars, 4000);
	});

	void test("a candidate holds at most 131072 bytes", () => {
		assert.equal(CANDIDATE_LIMITS.candidateBytes, 131_072);
		const value = met();
		const quotes = Array.from({ length: 32 }, (_, i) =>
			citation(i + 1, "q".repeat(CANDIDATE_LIMITS.quoteChars)),
		);
		value.citations = quotes;
		const over = Buffer.byteLength(JSON.stringify(value)) - CANDIDATE_LIMITS.candidateBytes;
		assert.ok(over > 0 && over < CANDIDATE_LIMITS.quoteChars, `${over} bytes over`);
		quotes[quotes.length - 1] = citation(32, "q".repeat(CANDIDATE_LIMITS.quoteChars - over));
		assert.equal(Buffer.byteLength(JSON.stringify(value)), CANDIDATE_LIMITS.candidateBytes);
		accepted(value);
		quotes[quotes.length - 1] = citation(32, "q".repeat(CANDIDATE_LIMITS.quoteChars - over + 1));
		const parsed = parseCandidate(value);
		assert.ok(!parsed.ok);
		assert.equal(parsed.reasonCode, "malformed_candidate");
		assert.match(parsed.message, /131073 bytes; at most 131072/u);
	});

	void test("a count is an integer from 0 to 1 000 000", () => {
		const value = undetermined();
		value.coverage = {
			source: "host",
			reads: CANDIDATE_LIMITS.maxCount,
			truncatedReads: 0,
			failedReads: 0,
			receipts: [],
			complete: true,
		};
		accepted(value);
		for (const reads of [CANDIDATE_LIMITS.maxCount + 1, -1, 1.5]) {
			value.coverage = {
				source: "host",
				reads,
				truncatedReads: 0,
				failedReads: 0,
				receipts: [],
				complete: true,
			};
			assert.equal(refusalOf(value), "malformed_candidate", String(reads));
		}
	});
});

void describe("the program's meta", () => {
	void test("names the direct models a program needs, and nothing else", () => {
		assert.deepEqual(parseMeta({}), { meta: {} });
		assert.deepEqual(parseMeta({ models: { chat: "required", embedding: "optional" } }), {
			meta: { models: { chat: "required", embedding: "optional" } },
		});
		for (const meta of [
			{ models: { chat: "sometimes" } },
			{ models: { vision: "required" } },
			{ limits: {} },
			{ version: 1 },
			null,
			"meta",
		]) {
			const parsed = parseMeta(meta);
			assert.ok("error" in parsed, JSON.stringify(meta));
			assert.match(parsed.error, /^meta: /u);
		}
	});
});

function sized(bytes: number) {
	const overhead = Buffer.byteLength(JSON.stringify({ context: [{ label: "Data", value: "" }] }));
	return { context: [{ label: "Data", value: "v".repeat(bytes - overhead) }] };
}

const lead = (n: number, label = "A line to check.") => ({
	at: { change: "src/a.ts", line: n + 1 },
	label,
});
const omission = { what: "added comments", count: 1, reason: "unavailable" };

void describe("the review options", () => {
	void test("no options are an empty input", () => {
		assert.deepEqual(parseReviewOptions(undefined), { options: {} });
		assert.deepEqual(parseReviewOptions({}), { options: {} });
	});

	const bounds: [string, (n: number) => Record<string, unknown>, number][] = [
		["leads", (n) => ({ leads: Array.from({ length: n }, (_, i) => lead(i)) }), 200],
		[
			"directions",
			(n) => ({ directions: Array.from({ length: n }, (_, i) => `Direction ${i}.`) }),
			10,
		],
		[
			"context",
			(n) => ({
				context: Array.from({ length: n }, (_, i) => ({ label: `Value ${i}`, value: i })),
			}),
			20,
		],
		["limitations", (n) => ({ limitations: Array.from({ length: n }, () => omission) }), 20],
		[
			"facts",
			(n) => ({ facts: Object.fromEntries(Array.from({ length: n }, (_, i) => [`fact${i}`, i])) }),
			32,
		],
	];
	for (const [name, make, max] of bounds) {
		void test(`at most ${max} ${name}`, () => {
			assert.ok("options" in parseReviewOptions(make(max)));
			const parsed = parseReviewOptions(make(max + 1));
			assert.ok("error" in parsed);
			assert.match(parsed.error, new RegExp(`^review options: ${name}`, "u"));
		});
	}

	void test("a lead label and a direction are one line of at most 300 characters", () => {
		assert.ok(
			"options" in
				parseReviewOptions({ leads: [lead(0, "l".repeat(300))], directions: ["d".repeat(300)] }),
		);
		for (const options of [
			{ leads: [lead(0, "l".repeat(301))] },
			{ leads: [lead(0, "first\nsecond")] },
			{ directions: ["d".repeat(301)] },
			{ directions: ["first\nsecond"] },
		]) {
			assert.ok("error" in parseReviewOptions(options), JSON.stringify(options).slice(0, 80));
		}
	});

	void test("an invalid lead or an unknown key is an error, never dropped", () => {
		for (const options of [
			{ leads: [{ at: { change: "src/a.ts", line: 0 }, label: "l" }] },
			{ leads: [{ at: { change: "src/a.ts", line: 1 }, label: "l", kind: "retired" }] },
			{ leads: [{ at: { change: "src/a.ts", file: "src/a.ts", line: 1 }, label: "l" }] },
			{ unrated: [omission] },
		]) {
			assert.ok("error" in parseReviewOptions(options), JSON.stringify(options));
		}
	});

	void test(`the options hold at most ${MAX_REVIEW_OPTIONS_BYTES} bytes`, () => {
		assert.ok("options" in parseReviewOptions(sized(MAX_REVIEW_OPTIONS_BYTES)));
		const parsed = parseReviewOptions(sized(MAX_REVIEW_OPTIONS_BYTES + 1));
		assert.ok("error" in parsed);
		assert.equal(
			parsed.error,
			`review options have ${MAX_REVIEW_OPTIONS_BYTES + 1} bytes; at most ${MAX_REVIEW_OPTIONS_BYTES}`,
		);
	});
});
