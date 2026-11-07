/** The contract of a practice script, as the host parses it, and of the result it returns. */
import { z } from "zod";

export const NOT_A_DEFINITION_ERROR =
	"The script's default export is not a practice script. Write it with definePracticeScript.";

/** The bounds of a candidate. Counts are integers from 0 to {@link CANDIDATE_LIMITS.maxCount}. */
export const CANDIDATE_LIMITS = {
	summaryChars: 160,
	pathChars: 4096,
	versionChars: 1024,
	explanationChars: 10_000,
	problemExplanationChars: 4000,
	nextStepChars: 1000,
	exampleNoteChars: 280,
	quoteChars: 4000,
	openQuestionChars: 500,
	warrantFieldChars: 500,
	resultCitations: 50,
	citationsPerProblem: 20,
	problems: 50,
	openQuestions: 20,
	omissions: 20,
	omissionItems: 50,
	receipts: 200,
	consultedSources: 20,
	candidateBytes: 131_072,
	maxCount: 1_000_000,
} as const;

export const PRACTICE_SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/u;

export const REASONS = [
	"unavailable",
	"budget",
	"deadline",
	"too-large",
	"off-format",
	"refused",
	"error",
] as const;
export type Reason = (typeof REASONS)[number];

/** One line of text: a newline would let a program write its own headings into the reviewer's turn. */
const line = (max: number) =>
	z
		.string()
		.min(1)
		.max(max)
		.refine((text) => !/[\r\n]/u.test(text), "must be one line");

/** `meta` is empty: a script declares nothing to the host. */
export const metaSchema = z.strictObject({});

export const FACT_CHARS = 200;
export const DIRECTION_CHARS = 300;

const scalar = z.union([
	line(FACT_CHARS),
	z.number().refine(Number.isFinite, "must be finite"),
	z.boolean(),
]);
/** Scalar facts that a program computed: at most 32, each a short one-line text, a number or a flag. */
export const factsSchema = z
	.record(z.string().regex(/^[A-Za-z][A-Za-z0-9]{0,63}$/u), scalar)
	.refine((facts) => Object.keys(facts).length <= 32, "at most 32 facts");
export type Facts = z.infer<typeof factsSchema>;

export const CONTRACT_VERSION = 3 as const;
export const INPUTS_FILE_SUFFIX = ".inputs.json";
export const ORIGINS = ["EVENT", "REQUEST", "SCHEDULE", "BACKFILL"] as const;
export const OUTCOMES = ["MET", "NOT_MET", "DOES_NOT_APPLY", "COULD_NOT_DETERMINE"] as const;
export const SEVERITIES = ["CRITICAL", "MAJOR", "MINOR", "TRIVIAL"] as const;
export const ABOUT_ROLES = ["AUTHOR", "REVIEWER", "ASSIGNEE", "MERGER"] as const;
export type Origin = (typeof ORIGINS)[number];
export type AboutRole = (typeof ABOUT_ROLES)[number];
export const decimalIdSchema = z.string().regex(/^[1-9][0-9]*$/u);
const version = z.string().min(1).max(CANDIDATE_LIMITS.versionChars);
/** File locations use full commit ids so Git cannot interpret them as options. */
export const commitIdSchema = z.string().regex(/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/u);
export const kindSchema = z
	.string()
	.max(64)
	.regex(/^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/u);
export const itemRefSchema = z.strictObject({
	id: decimalIdSchema,
	kind: kindSchema,
	parentId: decimalIdSchema.optional(),
	path: z.string().min(1).max(CANDIDATE_LIMITS.pathChars).optional(),
});
export type ItemRef = z.infer<typeof itemRefSchema>;
export const reviewScopeSchema = z.discriminatedUnion("kind", [
	z.strictObject({
		kind: z.literal("ITEM"),
		item: itemRefSchema,
		version,
		baseVersion: version.optional(),
	}),
	z.strictObject({ kind: z.literal("SCAN"), item: itemRefSchema, version }),
]);
export type ReviewScope = z.infer<typeof reviewScopeSchema>;
const previousCitationSchema = z.strictObject({
	sourceKind: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
	sourcePath: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
	path: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
	startLine: z.int().min(1).max(2_147_483_647),
	endLine: z.int().min(1).max(2_147_483_647),
	side: z.enum(["OLD", "NEW"]).optional(),
	commit: version.optional(),
});
export const previousSchema = z.strictObject({
	resultId: z.uuid(),
	reviewedAt: z.iso.datetime(),
	origin: z.enum(ORIGINS),
	eventName: z
		.string()
		.max(128)
		.regex(/^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/u)
		.optional(),
	itemVersion: version,
	outcome: z.enum(OUTCOMES),
	citations: z.array(previousCitationSchema).max(1050),
});
export type Previous = z.infer<typeof previousSchema>;
export const practiceInputsSchema = z
	.strictObject({
		contractVersion: z.literal(CONTRACT_VERSION),
		kind: kindSchema,
		item: z.unknown(),
		about: z.strictObject({ role: z.enum(ABOUT_ROLES), developerId: decimalIdSchema }).nullable(),
		practice: z.strictObject({
			id: decimalIdSchema,
			versionId: decimalIdSchema,
			reviewDigest: z.string().min(1).max(96),
			slug: z.string().regex(PRACTICE_SLUG),
			criteria: z.string(),
		}),
		scope: reviewScopeSchema,
		review: z.strictObject({
			id: z.uuid(),
			origin: z.enum(ORIGINS),
			startedAt: z.iso.datetime(),
			event: z
				.strictObject({
					name: z
						.string()
						.max(128)
						.regex(/^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/u),
					state: z.record(z.string().min(1), z.union([z.string(), z.number(), z.boolean()])),
				})
				.optional(),
		}),
		previous: previousSchema.nullable(),
	})
	.refine((v) => v.kind === v.scope.item.kind, "kind must match the assigned item")
	.refine((v) => v.scope.kind !== "SCAN" || v.about === null, "scans are about no developer");
export type PracticeInputs = z.infer<typeof practiceInputsSchema>;
export interface CitationCapabilities {
	side: "REQUIRED" | "OPTIONAL" | "FORBIDDEN";
	commit: "REQUIRED" | "OPTIONAL" | "FORBIDDEN";
}
/*
 * The result a practice script returns: one candidate per practice. Admission on the server parses
 * its structure again (`CandidateParser`) and verifies quotes and example overlap against captured
 * sources (`CandidateEvidenceVerifier`). `CandidateContractSyncTest` keeps the bounds and codes equal.
 */

export const CANDIDATE_REFUSALS = [
	"malformed_candidate",
	"outcome_problem_mismatch",
	"warrant_outcome_mismatch",
	"result_citations_required",
	"met_coverage_incomplete",
	"coverage_inconsistent",
	"example_outcome_mismatch",
	"example_overlaps_problem",
	"basis_citation_out_of_range",
	"duplicate_problem_key",
] as const;
export type CandidateRefusal = (typeof CANDIDATE_REFUSALS)[number];

export type NonEmpty<T> = readonly [T, ...T[]];
export type Severity = (typeof SEVERITIES)[number];
export type Outcome = (typeof OUTCOMES)[number];

/**
 * A verified place in a captured source, in canonical coordinates. The quote is the exact text at
 * those lines; admission reads it again from the source.
 */
export type Citation = z.infer<typeof citationShape>;

/** What a search for something missing consulted, looked for, and did not cover. Consulted names source kinds. */
export type SearchWarrant = z.infer<typeof searchShape>;
export type InapplicabilityWarrant = z.infer<typeof inapplicabilityShape>;
/** A real semantic question the evidence leaves open, located where it can be. */
export type OpenQuestion = z.infer<typeof openQuestionShape>;
export type Example = z.infer<typeof exampleShape>;
/** Why a problem is this work's: the indices name the problem's own citations. */
export type ClaimBasis = z.infer<typeof basisShape>;
export type Problem = z.infer<typeof problemShape>;
export const OMISSION_REASONS = ["selection", "truncated", ...REASONS, "cancelled"] as const;
export type Omission = z.infer<typeof omissionShape>;
export type Completeness =
	| { complete: true; omitted?: never }
	| { complete: false; omitted: NonEmpty<Omission> };
/**
 * What the result covers: the host derives it from its own read receipts. A candidate cannot carry
 * coverage of its own.
 */
export const readReceiptSchema = z
	.strictObject({
		sourceKind: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
		sourcePath: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
		path: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
		version: z.string().min(1).max(CANDIDATE_LIMITS.versionChars),
		/**
		 * SEARCHED: a search for `pattern` ran over the scope at `path` to the end. It carries no lines. Its digest is
		 * the checkout digest of the bytes it searched (`checkoutDigest` in the agent's search worker), which admission
		 * compares with the capture.
		 */
		status: z.enum(["COMPLETE", "TRUNCATED", "FAILED", "BINARY", "ABSENT", "SEARCHED"]),
		contentDigest: z
			.string()
			.regex(/^[0-9a-f]{64}$/u)
			.optional(),
		startLine: z.int().min(1).max(2_147_483_647).optional(),
		endLine: z.int().min(1).max(2_147_483_647).optional(),
		pattern: z.string().min(1).max(CANDIDATE_LIMITS.warrantFieldChars).optional(),
		/** SEARCHED: how many matches the search found. A match need not refute an absence, so the result accounts for it. */
		matches: z.int().min(0).max(2_147_483_647).optional(),
	})
	.refine(
		(r) => r.endLine === undefined || (r.startLine !== undefined && r.endLine >= r.startLine),
		"receipt extent must be ordered",
	)
	.refine(
		(r) => (r.status === "SEARCHED") === (r.pattern !== undefined),
		"only a search receipt names its pattern",
	)
	.refine(
		(r) => (r.status === "SEARCHED") === (r.matches !== undefined),
		"only a search receipt counts its matches",
	);
export type ReadReceipt = z.infer<typeof readReceiptSchema>;

/** A search as a receipt states it: admission reads what ran, so a literal is escaped and ignored case is a flag. */
export function searchedPattern({
	pattern,
	literal,
	ignoreCase,
}: {
	pattern: string;
	literal: boolean;
	ignoreCase: boolean;
}): string {
	const read = literal ? pattern.replaceAll(/[\\^$.*+?()[\]{}|]/gu, String.raw`\$&`) : pattern;
	return ignoreCase ? `(?i)${read}` : read;
}

export type Coverage = z.infer<typeof coverageShape> & Completeness;

export type Candidate = NonNullable<ReturnType<typeof candidateOf>>;
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;
/** What a review session submits: a candidate without numbers. The host adds the coverage it measured. */
export type SessionCandidate = DistributiveOmit<Candidate, "coverage">;

const count = z.int().min(0).max(CANDIDATE_LIMITS.maxCount);
const lineNumber = z.int().min(1).max(2_147_483_647);
const bounded = (max: number) => z.string().min(1).max(max);
const listOf = <T extends z.ZodType>(item: T, max: number) => z.array(item).max(max);
const nonEmptyListOf = <T extends z.ZodType>(item: T, max: number) =>
	z
		.array(item)
		.min(1)
		.max(max)
		.transform((items, ctx) => {
			const listed = nonEmpty(items);
			if (listed === undefined) {
				ctx.addIssue({ code: "custom", message: "must list at least one" });
				return z.NEVER;
			}
			return listed;
		});

/**
 * A file of a repository as a quote of code names it: relative, with no empty, `.` or `..` part, and outside the
 * repository's `.git` directory, which holds no reviewed code.
 */
export function isSafeRelativePath(path: string): boolean {
	return (
		!path.includes("\\") &&
		path.split("/").every((part) => part !== "" && part !== "." && part !== ".." && part !== ".git")
	);
}

const citationShape = z
	.strictObject({
		/** Integration-neutral, e.g. "scm.pull-request.diff", "scm.repository.tree", "scm.pull-request.core". */
		sourceKind: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
		/** The staged sourceFile, e.g. "context/change.json", "repos/reviewed/.git/HEAD", "context/metadata.json". */
		sourcePath: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
		/** The repository path for code sources; the record path otherwise. */
		path: z.string().min(1).max(CANDIDATE_LIMITS.pathChars),
		/** Source-catalog flags declare whether a side is required or permitted. */
		side: z.enum(["OLD", "NEW"]).optional(),
		/** An opaque version whose syntax and permission the domain source owns. */
		commit: z.string().min(1).max(CANDIDATE_LIMITS.versionChars).optional(),
		startLine: lineNumber,
		endLine: lineNumber,
		quote: bounded(CANDIDATE_LIMITS.quoteChars),
	})
	.refine(
		(citation) => citation.endLine >= citation.startLine,
		"endLine must be at least startLine",
	);
const consulted = nonEmptyListOf(
	bounded(CANDIDATE_LIMITS.warrantFieldChars),
	CANDIDATE_LIMITS.consultedSources,
);
const warrantField = bounded(CANDIDATE_LIMITS.warrantFieldChars);
const searchShape = z.strictObject({
	consulted,
	lookedFor: warrantField,
	boundary: warrantField,
	hits: warrantField.optional(),
});
const inapplicabilityShape = z.strictObject({
	consulted,
	prerequisite: warrantField,
	ruledOutBy: warrantField,
});
const openQuestionShape = z.strictObject({
	openQuestion: bounded(CANDIDATE_LIMITS.openQuestionChars),
	wouldSettleIt: bounded(CANDIDATE_LIMITS.openQuestionChars),
	citation: citationShape.optional(),
});
const exampleShape = z.strictObject({
	citation: citationShape,
	note: bounded(CANDIDATE_LIMITS.exampleNoteChars),
});
const basisExplanation = bounded(CANDIDATE_LIMITS.problemExplanationChars);
const basisShape = z.discriminatedUnion("type", [
	z.strictObject({ type: z.literal("introduced"), head: count, explanation: basisExplanation }),
	z.strictObject({
		type: z.literal("worsened"),
		base: count,
		head: count,
		explanation: basisExplanation,
	}),
	z.strictObject({
		type: z.literal("duty"),
		criterionQuote: bounded(CANDIDATE_LIMITS.quoteChars),
		event: count,
		explanation: basisExplanation,
	}),
	z.strictObject({ type: z.literal("state"), explanation: basisExplanation }),
]);
const severityShape = z.enum(SEVERITIES);
const problemShape = z.strictObject({
	/** Practice-slug shaped and unique within the candidate. */
	key: z.string().regex(PRACTICE_SLUG).optional(),
	severity: severityShape,
	summary: line(CANDIDATE_LIMITS.summaryChars),
	explanation: bounded(CANDIDATE_LIMITS.problemExplanationChars),
	citations: nonEmptyListOf(citationShape, CANDIDATE_LIMITS.citationsPerProblem),
	basis: basisShape,
	nextStep: bounded(CANDIDATE_LIMITS.nextStepChars).optional(),
});
const omissionShape = z.strictObject({
	what: line(CANDIDATE_LIMITS.summaryChars),
	count,
	reason: z.enum(OMISSION_REASONS),
	items: listOf(bounded(CANDIDATE_LIMITS.warrantFieldChars), CANDIDATE_LIMITS.omissionItems)
		.readonly()
		.optional(),
});
const omitted = listOf(omissionShape, CANDIDATE_LIMITS.omissions).readonly().optional();
const coverageShape = z.strictObject({
	source: z.literal("host"),
	reads: count,
	truncatedReads: count,
	failedReads: count,
	receipts: z.array(readReceiptSchema).max(CANDIDATE_LIMITS.receipts).readonly(),
	complete: z.boolean(),
	omitted,
});

// Check outcome rules before reporting an outcome-specific field as an unknown key.
const supersetShape = z.strictObject({
	outcome: z.enum(OUTCOMES),
	summary: line(CANDIDATE_LIMITS.summaryChars),
	explanation: bounded(CANDIDATE_LIMITS.explanationChars),
	citations: listOf(citationShape, CANDIDATE_LIMITS.resultCitations).readonly(),
	problems: listOf(problemShape, CANDIDATE_LIMITS.problems).optional(),
	example: exampleShape.optional(),
	evidence: z.strictObject({
		search: searchShape.optional(),
		inapplicability: inapplicabilityShape.optional(),
		openQuestions: nonEmptyListOf(openQuestionShape, CANDIDATE_LIMITS.openQuestions).optional(),
	}),
	coverage: coverageShape,
});
type Superset = z.infer<typeof supersetShape>;

function nonEmpty<T>(items: readonly T[]): NonEmpty<T> | undefined {
	const [first, ...rest] = items;
	return first === undefined ? undefined : [first, ...rest];
}

interface Refusal {
	ok: false;
	reasonCode: CandidateRefusal;
	message: string;
}
const refuse = (reasonCode: CandidateRefusal, message: string): Refusal => ({
	ok: false,
	reasonCode,
	message,
});

function readCoverage(coverage: Superset["coverage"]): Coverage | string {
	const omissions = nonEmpty(coverage.omitted ?? []);
	if (coverage.complete !== (omissions === undefined)) {
		return coverage.complete
			? "complete coverage lists no omission"
			: "incomplete coverage lists what it omitted";
	}
	const { reads, truncatedReads, failedReads, receipts } = coverage;
	if (coverage.complete && (truncatedReads > 0 || failedReads > 0)) {
		return "complete host coverage cannot have truncated or failed reads";
	}
	if (coverage.complete && receipts.some((receipt) => receipt.status === "FAILED")) {
		return "complete host coverage cannot contain a failed receipt";
	}
	if (
		coverage.complete &&
		receipts.some(
			(receipt) =>
				receipt.status === "TRUNCATED" &&
				!receipts.some(
					(complete) =>
						complete.status === "COMPLETE" &&
						complete.sourceKind === receipt.sourceKind &&
						complete.sourcePath === receipt.sourcePath &&
						complete.path === receipt.path &&
						complete.version === receipt.version &&
						complete.startLine !== undefined &&
						complete.endLine !== undefined &&
						receipt.startLine !== undefined &&
						receipt.endLine !== undefined &&
						complete.startLine <= receipt.startLine &&
						complete.endLine >= receipt.endLine,
				),
		)
	) {
		return "complete host coverage cannot contain an unsettled truncated receipt";
	}

	return omissions === undefined
		? { source: "host", reads, truncatedReads, failedReads, receipts, complete: true }
		: {
				source: "host",
				reads,
				truncatedReads,
				failedReads,
				receipts,
				complete: false,
				omitted: omissions,
			};
}

function basisIndices(basis: ClaimBasis): number[] {
	switch (basis.type) {
		case "introduced": {
			return [basis.head];
		}
		case "worsened": {
			return [basis.base, basis.head];
		}
		case "duty": {
			return [basis.event];
		}
		case "state": {
			return [];
		}
	}
}

function issueText(error: z.ZodError): string {
	const issue = error.issues[0];
	if (issue === undefined) {
		return "invalid";
	}
	const where = issue.path.map(String).join(".");
	return where === "" ? issue.message : `${where}: ${issue.message}`;
}

function jsonBytes(value: unknown): number | undefined {
	try {
		const text: unknown = JSON.stringify(value);
		return typeof text === "string" ? Buffer.byteLength(text) : 0;
	} catch {
		return undefined;
	}
}

const WARRANT_RULES: Readonly<
	Record<Outcome, { holds: (evidence: Superset["evidence"]) => boolean; message: string }>
> = {
	MET: {
		holds: (e) => e.inapplicability === undefined && e.openQuestions === undefined,
		message: "a MET result may carry a search warrant only",
	},
	NOT_MET: {
		holds: (e) => e.inapplicability === undefined,
		message: "a NOT_MET result may carry a search warrant and open questions only",
	},
	DOES_NOT_APPLY: {
		holds: (e) =>
			e.inapplicability !== undefined && e.search === undefined && e.openQuestions === undefined,
		message: "a DOES_NOT_APPLY result carries its inapplicability warrant only",
	},
	COULD_NOT_DETERMINE: {
		holds: (e) => e.inapplicability === undefined && e.openQuestions !== undefined,
		message:
			"a COULD_NOT_DETERMINE result carries its open questions, and a search warrant at most",
	},
};

function outcomeRefusal(
	sent: Omit<Superset, "coverage">,
	problems: NonEmpty<NonNullable<Superset["problems"]>[number]> | undefined,
): Refusal | undefined {
	const { outcome } = sent;
	if (
		(outcome === "NOT_MET") !== (problems !== undefined) ||
		(sent.problems !== undefined && problems === undefined)
	) {
		return refuse(
			"outcome_problem_mismatch",
			outcome === "NOT_MET"
				? "a NOT_MET result lists its problems"
				: `a ${outcome} result lists no problem`,
		);
	}
	if (!WARRANT_RULES[outcome].holds(sent.evidence)) {
		return refuse("warrant_outcome_mismatch", WARRANT_RULES[outcome].message);
	}
	if (outcome !== "NOT_MET" && sent.citations.length === 0) {
		return refuse("result_citations_required", `a ${outcome} result cites what it rests on`);
	}
	return undefined;
}

function unsupportedProblemCitation(problem: Problem): number | undefined {
	return basisIndices(problem.basis).find((at) => at >= problem.citations.length);
}

function problemRefusal(sent: Omit<Superset, "coverage">): Refusal | undefined {
	const { example, outcome } = sent;
	const problems = sent.problems ?? [];
	if (example !== undefined && outcome !== "MET" && outcome !== "NOT_MET") {
		return refuse("example_outcome_mismatch", `a ${outcome} result has no example`);
	}
	for (const [index, problem] of problems.entries()) {
		const out = unsupportedProblemCitation(problem);
		if (out !== undefined) {
			return refuse(
				"basis_citation_out_of_range",
				`problem ${index}: its basis names citation ${out}, and it has ${problem.citations.length}`,
			);
		}
	}
	const keys = problems.flatMap((problem) => (problem.key === undefined ? [] : [problem.key]));
	return new Set(keys).size === keys.length
		? undefined
		: refuse("duplicate_problem_key", "two problems share a key");
}

/** A local problem draft uses the same shape and citation indexes as a complete candidate. */
export const sessionProblemSchema = problemShape.superRefine((problem, ctx) => {
	const out = unsupportedProblemCitation(problem);
	if (out !== undefined) {
		ctx.addIssue({
			code: "custom",
			path: ["basis"],
			message: `its basis names citation ${out}, and it has ${problem.citations.length}; use a zero-based index of a citation that supports the claim`,
		});
	}
});

/** Session structure and outcome rules share the program candidate's authoritative schemas. */
export const sessionCandidateSchema = supersetShape
	.omit({ coverage: true })
	.superRefine((sent, ctx) => {
		const problems = sent.problems === undefined ? undefined : nonEmpty(sent.problems);
		for (const refusal of [outcomeRefusal(sent, problems), problemRefusal(sent)]) {
			if (refusal !== undefined) {
				ctx.addIssue({ code: "custom", message: refusal.message });
			}
		}
	});
export type ParsedSessionCandidate = z.infer<typeof sessionCandidateSchema>;

function candidateOf(sent: Superset, coverage: Coverage) {
	const { outcome, summary, explanation, example } = sent;
	const { search, inapplicability, openQuestions } = sent.evidence;
	const base = { summary, explanation };
	const citations = nonEmpty(sent.citations);
	const problems = sent.problems === undefined ? undefined : nonEmpty(sent.problems);
	const searched = search === undefined ? {} : { search };
	const exemplified = example === undefined ? {} : { example };
	if (outcome === "MET" && citations !== undefined && coverage.complete) {
		return { ...base, outcome, citations, coverage, ...exemplified, evidence: searched } as const;
	}
	if (outcome === "NOT_MET" && problems !== undefined) {
		return {
			...base,
			outcome,
			citations: sent.citations,
			problems,
			...exemplified,
			coverage,
			evidence: { ...searched, ...(openQuestions === undefined ? {} : { openQuestions }) },
		} as const;
	}
	if (outcome === "DOES_NOT_APPLY" && citations !== undefined && inapplicability !== undefined) {
		return { ...base, outcome, citations, coverage, evidence: { inapplicability } } as const;
	}
	if (outcome === "COULD_NOT_DETERMINE" && citations !== undefined && openQuestions !== undefined) {
		return {
			...base,
			outcome,
			citations,
			coverage,
			evidence: { ...searched, openQuestions },
		} as const;
	}
	return null;
}

/**
 * Parse a candidate: its shape first, then the outcome rules in the order of
 * {@link CANDIDATE_REFUSALS}. A value of another shape, such as a leads return, is refused as it is;
 * nothing guesses what it meant.
 */
export function parseCandidate(
	value: unknown,
):
	| { ok: true; candidate: Candidate }
	| { ok: false; reasonCode: CandidateRefusal; message: string } {
	const bytes = jsonBytes(value);
	if (bytes === undefined) {
		return refuse("malformed_candidate", "the candidate is not JSON");
	}
	if (bytes > CANDIDATE_LIMITS.candidateBytes) {
		return refuse(
			"malformed_candidate",
			`the candidate has ${bytes} bytes; at most ${CANDIDATE_LIMITS.candidateBytes}`,
		);
	}
	const parsed = supersetShape.safeParse(value);
	if (!parsed.success) {
		return refuse("malformed_candidate", issueText(parsed.error));
	}
	const sent = parsed.data;
	const problems = sent.problems === undefined ? undefined : nonEmpty(sent.problems);
	const before = outcomeRefusal(sent, problems);
	if (before !== undefined) {
		return before;
	}
	if (sent.outcome === "MET" && !sent.coverage.complete) {
		return refuse("met_coverage_incomplete", "a MET result needs complete coverage");
	}
	const coverage = readCoverage(sent.coverage);
	if (typeof coverage === "string") {
		return refuse("coverage_inconsistent", coverage);
	}
	const after = problemRefusal(sent);
	if (after !== undefined) {
		return after;
	}
	const candidate = candidateOf(sent, coverage);
	return candidate === null
		? refuse("malformed_candidate", "the candidate matches no outcome")
		: { ok: true, candidate };
}

/*
 * What a program gives `ctx.review()`: prepared leads, scalar facts, authored directions, bounded
 * context and explicit limitations. It is input to the review session, never another result.
 */

/** Where a lead points: a line of the change, a repository file, or a captured record. The host quotes it. */
export const evidenceLocationSchema = z.union([
	z.strictObject({
		change: z.string().min(1),
		line: lineNumber,
		endLine: lineNumber.optional(),
		side: z.enum(["OLD", "NEW"]).optional(),
	}),
	z.strictObject({
		file: z.string().min(1),
		line: lineNumber,
		endLine: lineNumber.optional(),
		commit: commitIdSchema.optional(),
	}),
	z.strictObject({
		record: z.string().min(1),
		line: z.int().nonnegative().optional(),
	}),
]);
export type EvidenceLocation = z.infer<typeof evidenceLocationSchema>;

export const MAX_REVIEW_OPTIONS_BYTES = 262_144;

const reviewLeadSchema = z.strictObject({
	at: evidenceLocationSchema,
	/** What the lead is, for the reviewer: one line. */
	label: line(DIRECTION_CHARS),
	facts: factsSchema.optional(),
});
export type ReviewLead = z.infer<typeof reviewLeadSchema>;

const preparedContextSchema = z.strictObject({
	label: line(160),
	value: z.json(),
	citations: listOf(citationShape, CANDIDATE_LIMITS.resultCitations).optional(),
});
export type PreparedContext = z.infer<typeof preparedContextSchema>;

export const REVIEW_LEAD_LIMIT = 200;

export const reviewOptionsSchema = z.strictObject({
	leads: listOf(reviewLeadSchema, REVIEW_LEAD_LIMIT).readonly().optional(),
	facts: factsSchema.optional(),
	/** Directions to the reviewer. The program's author writes them; reviewed text and model text belong in context. */
	directions: listOf(line(DIRECTION_CHARS), 10).readonly().optional(),
	context: listOf(preparedContextSchema, 20).readonly().optional(),
	limitations: listOf(omissionShape, CANDIDATE_LIMITS.omissions).readonly().optional(),
});
export type ReviewOptions = z.infer<typeof reviewOptionsSchema>;

/** Parse review options, or say why they are not. An invalid lead is an error, never dropped. */
export function parseReviewOptions(value: unknown): { options: ReviewOptions } | { error: string } {
	let bytes: number;
	try {
		bytes = Buffer.byteLength(JSON.stringify(value ?? {}));
	} catch {
		return { error: "review options are not JSON" };
	}
	if (bytes > MAX_REVIEW_OPTIONS_BYTES) {
		return { error: `review options have ${bytes} bytes; at most ${MAX_REVIEW_OPTIONS_BYTES}` };
	}
	const parsed = reviewOptionsSchema.safeParse(value ?? {});
	return parsed.success
		? { options: parsed.data }
		: { error: `review options: ${issueText(parsed.error)}` };
}

/*
 * The execution report: `out/practice-scripts.json`, one entry for each admitted practice. It records
 * how each program ran, never the candidate it returned.
 */

export const RUN_STATUSES = ["ok", "withheld", "failed", "not-reached", "not-finished"] as const;
export type RunStatus = (typeof RUN_STATUSES)[number];
export const STOP_REASONS = [
	...REASONS,
	"cancelled",
	"invalid-program",
	"invalid-candidate",
	"unread-evidence",
] as const;
export type StopReason = (typeof STOP_REASONS)[number];
export const PROGRAM_KINDS = ["builtin", "custom"] as const;
export type ProgramKind = (typeof PROGRAM_KINDS)[number];
export const EXECUTION_REPORT_SCHEMA_VERSION = 3;

const callsSchema = z.strictObject({ review: count });
export type ProgramCalls = z.infer<typeof callsSchema>;
const coverageReachedSchema = z.strictObject({
	reads: count,
	truncatedReads: count,
	failedReads: count,
});
/**
 * What the practice's review sessions did, as the runner counted it. Model calls follow the usage
 * report's rule. A refusal counts once for the gate that refused it: the evidence check refused a
 * result that had not read its required or cited sources; the tool schema refused a call of the
 * wrong shape.
 */
const reviewSessionsSchema = z.strictObject({
	modelCalls: count,
	evidenceRefusals: count,
	formatRefusals: count,
});
export type ReviewSessionFacts = z.infer<typeof reviewSessionsSchema>;

export const executionEntrySchema = z
	.strictObject({
		practiceSlug: z.string().regex(PRACTICE_SLUG),
		program: z.strictObject({
			kind: z.enum(PROGRAM_KINDS),
			digest: z
				.string()
				.regex(/^[0-9a-f]{64}$/u)
				.nullable(),
		}),
		status: z.enum(RUN_STATUSES),
		stopReason: z.enum(STOP_REASONS).nullable(),
		error: z
			.string()
			.min(1)
			.refine((text) => (text.match(/./gsu) ?? []).length <= 500, "at most 500 code points")
			.optional(),
		calls: callsSchema,
		/** Absent when the runner did not count them. */
		reviewSessions: reviewSessionsSchema.optional(),
		coverageReached: coverageReachedSchema,
		/** How long the program ran: up to the longest attempt, not a count. */
		durationMs: z.int().min(0).max(2_147_483_647).nullable(),
	})
	.refine(
		(entry) => (entry.status === "ok") === (entry.stopReason === null),
		"status ok exactly when there is no stop reason",
	)
	.refine(
		(entry) => entry.error === undefined || entry.status === "failed",
		"an error only on a failed practice",
	)
	.refine(
		(entry) => (entry.status === "not-reached") === (entry.durationMs === null),
		"no duration exactly for a practice not reached",
	)
	.refine(
		(entry) => (entry.program.kind === "builtin") === (entry.program.digest === null),
		"a digest exactly for a custom program",
	);
export type ExecutionEntry = z.infer<typeof executionEntrySchema>;

export const executionReportSchema = z.strictObject({
	schemaVersion: z.literal(EXECUTION_REPORT_SCHEMA_VERSION),
	truncated: z.boolean(),
	practices: z.array(executionEntrySchema),
});

export function validateCitationCapabilities(
	citation: Citation,
	capabilities: CitationCapabilities,
): string | undefined {
	for (const field of ["side", "commit"] as const) {
		const present = citation[field] !== undefined;
		if (capabilities[field] === "REQUIRED" && !present) {
			return `${field} is required by the source catalog`;
		}
		if (capabilities[field] === "FORBIDDEN" && present) {
			return `${field} is forbidden by the source catalog`;
		}
	}
	return undefined;
}
export function isDecimalId(value: unknown): value is string {
	return decimalIdSchema.safeParse(value).success;
}
