import { createHash } from "node:crypto";

import Ajv2020 from "ajv/dist/2020.js";

import schema from "./schema.json" with { type: "json" };

export type Grounding = "supported" | "unsupported" | "unknown";
export type Verdict = "true" | "false" | "unsupported";
export interface Quality {
	relevant: boolean | null;
	repeated: boolean | null;
	useful: boolean | null;
	withholdingCorrect: boolean | null;
}
export interface EvalCase {
	id: string;
	sampleId: string;
	group: string;
	split: "development" | "held-out";
	sampling: "regression" | "random";
	reviewState: "reviewed" | "withheld" | "not-reviewed";
	provider: "github" | "gitlab" | "slack" | "outline";
	destination: "work" | "practice-page" | "conversation-app" | "conversation-slack";
	practice: string;
	claimCoverage: "selected" | "exhaustive";
	capture: {
		status: "complete" | "partial" | "failed";
		producerStatus: "complete" | "partial" | "failed" | "unknown";
		limitations: string[];
	};
	provenance: {
		source: string;
		revision: string | null;
		model: string | null;
		promptSha256: string | null;
		catalogRevision: string | null;
		costUsd: number | null;
		elapsedMs: number | null;
	};
	evidence: {
		id: string;
		role: "seen" | "reference" | "conversation" | "compaction";
		text: string;
		sha256: string;
		complete: boolean;
		seenByProducer: boolean | null;
		revision: string | null;
		side: "new" | "old" | "none";
	}[];
	output: { text: string; sha256: string };
	claims: {
		id: string;
		quote: string;
		expected: Verdict;
		expectedGrounding: Grounding;
		rationale: string;
		evidenceIds: string[];
		checks: {
			kind: "literal-absence" | "literal-quote";
			evidenceId: string;
			region: string;
			text: string;
		}[];
	}[];
	expectedQuality: Quality;
}
export interface Corpus {
	version: 1;
	cases: EvalCase[];
}
export interface ClaimRating {
	id: string;
	verdict: Verdict;
	grounding: Grounding;
	reason: string;
	evidenceIds: string[];
}
export interface Rating {
	caseHash: string;
	claims: ClaimRating[];
	quality: Quality;
}
export interface HumanLabels {
	version: 1;
	labels: (Rating & { reviewer: string })[];
	adjudications: (Rating & { reviewers: string[] })[];
}
export interface JudgeResults {
	version: 1;
	rubricHash: string;
	model: string;
	run: string;
	results: Rating[];
}

export function sha256(text: string): string {
	return createHash("sha256").update(text).digest("hex");
}

export function caseHash(value: EvalCase): string {
	return sha256(JSON.stringify(blindCase(value)));
}

export function blindCase(item: EvalCase) {
	return {
		sampleId: item.sampleId,
		capture: item.capture,
		reviewState: item.reviewState,
		evidence: item.evidence.map(({ id, role, text, complete, seenByProducer, revision, side }) => ({
			id,
			role,
			text,
			complete,
			seenByProducer,
			revision,
			side,
		})),
		output: item.output.text,
		claims: item.claims.map(({ id, quote }) => ({ id, quote })),
	};
}

const ajv = new Ajv2020({ strict: true, allErrors: true });
ajv.addSchema(schema, "eval");
const validateCorpus = ajv.compile<Corpus>({ $ref: "eval#/$defs/corpus" });
const validateHumans = ajv.compile<HumanLabels>({ $ref: "eval#/$defs/humans" });
const validateJudge = ajv.compile<JudgeResults>({ $ref: "eval#/$defs/judge" });

export function parseCorpus(value: unknown): Corpus {
	if (!validateCorpus(value)) {
		throw new Error(`Invalid corpus: ${ajv.errorsText(validateCorpus.errors)}`);
	}
	const groups = new Map<string, string>();
	unique(
		value.cases.map((item) => item.sampleId),
		"sample ids",
	);
	unique(
		value.cases.map((item) => item.id),
		"case ids",
	);
	for (const item of value.cases) {
		const previous = groups.get(item.group);
		if (previous !== undefined && previous !== `${item.split}:${item.sampling}`) {
			throw new Error(`Work group crosses splits: ${item.group}`);
		}
		groups.set(item.group, `${item.split}:${item.sampling}`);
		if (item.split === "development" && item.sampling === "random") {
			throw new Error(`${item.id}: random sampling is reserved for the held-out corpus`);
		}
		unique(
			item.evidence.map((evidence) => evidence.id),
			`${item.id} evidence ids`,
		);
		unique(
			item.claims.map((claim) => claim.id),
			`${item.id} claim ids`,
		);
		if (sha256(item.output.text) !== item.output.sha256) {
			throw new Error(`${item.id}: output digest mismatch`);
		}
		for (const evidence of item.evidence) {
			if (sha256(evidence.text) !== evidence.sha256) {
				throw new Error(`${item.id}: evidence digest mismatch: ${evidence.id}`);
			}
		}
		for (const claim of item.claims) {
			validateClaim(item, claim);
		}
	}
	return value;
}

export function parseHumans(value: unknown, corpus: Corpus): HumanLabels {
	if (!validateHumans(value)) {
		throw new Error(`Invalid human labels: ${ajv.errorsText(validateHumans.errors)}`);
	}
	unique(
		value.labels.map((label) => `${label.caseHash}:${label.reviewer}`),
		"human labels",
	);
	for (const label of value.labels) {
		validateRating(label, corpus);
	}
	unique(
		value.adjudications.map((rating) => rating.caseHash),
		"adjudicated case hashes",
	);
	for (const adjudication of value.adjudications) {
		validateRating(adjudication, corpus, true);
		if (
			!adjudication.reviewers.every((reviewer) =>
				value.labels.some(
					(label) => label.reviewer === reviewer && label.caseHash === adjudication.caseHash,
				),
			)
		) {
			throw new Error("Adjudication requires the original independent labels from its reviewers");
		}
	}
	return value;
}

export function parseJudge(value: unknown, corpus: Corpus, rubric: string): JudgeResults {
	if (!validateJudge(value)) {
		throw new Error(`Invalid judge results: ${ajv.errorsText(validateJudge.errors)}`);
	}
	if (value.rubricHash !== sha256(rubric)) {
		throw new Error("Judge rubric digest mismatch");
	}
	unique(
		value.results.map((rating) => rating.caseHash),
		"judge case hashes",
	);
	for (const rating of value.results) {
		validateRating(rating, corpus);
	}
	return value;
}

function validateRating(rating: Rating, corpus: Corpus, partial = false): void {
	const item = corpus.cases.find((candidate) => caseHash(candidate) === rating.caseHash);
	if (item === undefined) {
		throw new Error(`Unknown or stale case hash: ${rating.caseHash}`);
	}
	unique(
		rating.claims.map((claim) => claim.id),
		"rating claim ids",
	);
	if (!partial && rating.claims.length !== item.claims.length) {
		throw new Error(`${item.id}: rating must cover every selected claim`);
	}
	for (const claim of rating.claims) {
		if (!item.claims.some((candidate) => candidate.id === claim.id)) {
			throw new Error(`${item.id}: unknown rated claim ${claim.id}`);
		}
		for (const id of claim.evidenceIds) {
			evidenceById(item, id);
		}
		if (
			claim.grounding === "supported" &&
			!claim.evidenceIds.some((id) => {
				const evidence = evidenceById(item, id);
				return evidence.seenByProducer === true;
			})
		) {
			throw new Error(`${item.id}: supported grounding needs producer evidence`);
		}
		if (claim.verdict !== "unsupported" && claim.evidenceIds.length === 0) {
			throw new Error(`${item.id}: a truth verdict needs evidence references`);
		}
	}
}

function evidenceById(item: EvalCase, id: string) {
	const found = item.evidence.find((evidence) => evidence.id === id);
	if (found === undefined) {
		throw new Error(`${item.id}: unknown evidence ${id}`);
	}
	return found;
}

function unique(values: string[], label: string): void {
	if (new Set(values).size !== values.length) {
		throw new Error(`Duplicate ${label}`);
	}
}

function validateClaim(item: EvalCase, claim: EvalCase["claims"][number]): void {
	if (!item.output.text.includes(claim.quote)) {
		throw new Error(`${item.id}: claim ${claim.id} is absent from output`);
	}
	for (const id of claim.evidenceIds) {
		evidenceById(item, id);
	}
	for (const check of claim.checks) {
		const evidence = evidenceById(item, check.evidenceId);
		if (evidence.role !== "seen" && evidence.role !== "reference") {
			throw new Error(`${item.id}: literal checks need source evidence`);
		}
		if (evidence.revision === null || evidence.side === "none") {
			throw new Error(`${item.id}: literal checks need a pinned revision and source side`);
		}
		if (!claim.evidenceIds.includes(check.evidenceId)) {
			throw new Error(`${item.id}: check evidence must be linked to the claim`);
		}
		if (check.kind === "literal-quote" && !claim.quote.includes(check.text)) {
			throw new Error(`${item.id}: quote check text is absent from claim`);
		}
		if (!evidence.text.includes(check.region)) {
			throw new Error(`${item.id}: check region is absent from ${evidence.id}`);
		}
	}
}
