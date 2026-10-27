/**
 * Runs one admitted practice's program under the scope the host supplies: the built-in in this
 * process, a custom program in a child process. Either way the program gets the same context, and its
 * final value is checked as one candidate. The host keeps one practice and one review active.
 */
import { createHash } from "node:crypto";

import { DIFF_SOURCE, TREE_SOURCE } from "../../kits/scm/sources.ts";

import {
	parseCandidate,
	sessionCandidateSchema,
	CANDIDATE_LIMITS,
	parseMeta,
	parseReviewOptions,
	type Candidate,
	type Citation,
	type Coverage,
	type Omission,
	type Outcome,
	type ParsedSessionCandidate,
	type ProgramCalls,
	type SessionCandidate,
	type ReadReceipt,
	type ProgramKind,
	type ReviewSessionFacts,
	type RunStatus,
	type StopReason,
} from "./contract.ts";
import { noReceipts, type Receipts } from "./evidence-reader.ts";
import type { ProgramJob } from "./ipc.ts";
import { isJsonObject } from "./json.ts";
import { definePracticeScript, reasonOf, UnratedError } from "./practice-script.ts";
import { programContext } from "./program-context.ts";
import {
	abortReasonOf,
	acceptModels,
	noCalls,
	ProgramSettlementError,
	runCustomProgram,
	type ProgramExecution,
	type ProgramServices,
	type SlotReport,
} from "./script-run.ts";

export const BUILTIN_SCRIPT = definePracticeScript({ meta: {}, run: async (ctx) => ctx.review() });

export interface PracticeProgram {
	/** The staged `<slug>.ts`, or null for the built-in. */
	script: { path: string; source: Buffer } | null;
}

/** How one practice's program ran, and the candidate it returned when it passed the host's checks. */
export interface ProgramOutcome {
	candidate?: Candidate;
	readReceipts?: readonly ReadReceipt[];
	program: { kind: ProgramKind; digest: string | null };
	models: SlotReport[];
	status: RunStatus;
	stopReason: StopReason | null;
	/** Why the program failed; only on `failed`. */
	error?: string;
	calls: ProgramCalls;
	/** What the practice's review sessions did. The runner that opens them adds it. */
	reviewSessions?: ReviewSessionFacts;
	coverageReached: Receipts;
	durationMs: number | null;
}

/** A captured source that the practice declares in its reads. Code that decides needs it read in full. */
export interface RequiredSource {
	sourceKind: string;
	sourcePath: string;
}

/** The captured sources, other than the tree, that the practice declares in its reads. */
export function requiredSources(
	declared: Iterable<string>,
	captured: Iterable<readonly [sourcePath: string, sourceKind: string]>,
): RequiredSource[] {
	const reads = new Set(declared);
	return [...captured]
		.filter(([, kind]) => kind !== TREE_SOURCE && reads.has(kind))
		.map(([sourcePath, sourceKind]) => ({ sourceKind, sourcePath }));
}

export const programDigest = (source: Buffer): string =>
	createHash("sha256").update(source).digest("hex");

/**
 * The outcome of a practice the host did not start: the attempt's time or budget ran out first, or
 * the attempt was cancelled.
 */
export function notReached(
	program: PracticeProgram,
	stopReason: StopReason,
	receipts: Receipts = noReceipts(),
): ProgramOutcome {
	return {
		program: identity(program),
		models: [],
		status: "not-reached",
		stopReason,
		calls: noCalls(),
		coverageReached: { ...receipts },
		durationMs: null,
	};
}

function identity(program: PracticeProgram): ProgramOutcome["program"] {
	return program.script === null
		? { kind: "builtin", digest: null }
		: { kind: "custom", digest: programDigest(program.script.source) };
}

async function runBuiltin(job: ProgramJob, services: ProgramServices): Promise<ProgramExecution> {
	const calls = noCalls();
	const parsed = parseMeta(BUILTIN_SCRIPT.meta);
	const report = "meta" in parsed ? acceptModels(parsed.meta, services.models).report : [];
	const signal = AbortSignal.any([
		services.signal,
		AbortSignal.timeout(Math.max(0, job.deadline - Date.now())),
	]);
	const ctx = programContext(job, {
		read: async (request) => services.reader.answer(request, signal),
		review: async (options) => {
			const checked = parseReviewOptions(options);
			if ("error" in checked) {
				throw new UnratedError("error", checked.error);
			}
			calls.review += 1;
			return services.review(checked.options, signal);
		},
		models: {},
		tokensLeft: () => services.budget.left,
		log: (text) => {
			services.log(`  [${job.slug}] ${text}`);
		},
		abortSignal: signal,
	});
	try {
		signal.throwIfAborted();
		if (Date.now() >= job.deadline) {
			throw new UnratedError("deadline", "the program reached its deadline");
		}
		const value = await BUILTIN_SCRIPT.run(ctx);
		signal.throwIfAborted();
		if (Date.now() >= job.deadline) {
			throw new UnratedError("deadline", "the program reached its deadline");
		}
		return { value, models: report, calls };
	} catch (error) {
		const stopReason = signal.aborted ? abortReasonOf(signal) : reasonOf(error);
		return {
			failure: { stopReason, error: error instanceof Error ? error.message : String(error) },
			models: report,
			calls,
		};
	}
}

/**
 * Run one practice's program and check what it returned. Its candidate passes only as a whole: a
 * value that is not a candidate, or host coverage that no review of this practice returned, fails
 * the practice and admits nothing for it.
 */
export async function runPracticeProgram(
	program: PracticeProgram,
	job: ProgramJob,
	services: ProgramServices & { receipts: Receipts; requiredSources: readonly RequiredSource[] },
): Promise<ProgramOutcome> {
	const started = Date.now();
	/** The coverage each review of this practice returned: the only host coverage its candidate may carry. */
	const returned = new Set<string>();
	/** What each review decided: its outcome and citations, on its coverage. */
	const decisions = new Set<string>();
	const sessionReadReceipts: ReadReceipt[] = [];
	const hosted: ProgramServices = {
		...services,
		review: async (options, signal) => {
			const candidate = await services.review(options, signal);
			returned.add(JSON.stringify(candidate.coverage));
			decisions.add(decisionKey(candidate, candidate.coverage));
			if (candidate.coverage.source === "host") {
				sessionReadReceipts.push(...candidate.coverage.receipts);
			}
			return candidate;
		},
	};
	let execution: ProgramExecution;
	try {
		execution =
			program.script === null
				? await runBuiltin(job, hosted)
				: await runCustomProgram(program.script.path, job, hosted);
	} catch (error) {
		if (error instanceof ProgramSettlementError) {
			throw error;
		}
		execution = {
			failure: {
				stopReason: "error",
				error: error instanceof Error ? error.message : String(error),
			},
			models: [],
			calls: noCalls(),
		};
	}
	const finished = (
		status: RunStatus,
		stopReason: StopReason | null,
		parts: { candidate?: Candidate; error?: string; readReceipts?: readonly ReadReceipt[] } = {},
	): ProgramOutcome => ({
		...parts,
		program: identity(program),
		models: execution.models,
		status,
		stopReason,
		calls: execution.calls,
		coverageReached: { ...services.receipts },
		durationMs: Date.now() - started,
	});
	const { failure } = execution;
	if (failure !== undefined) {
		return failure.skipped === true
			? finished("withheld", failure.stopReason)
			: finished("failed", failure.stopReason, { error: failure.error });
	}
	const readReceipts = [
		...new Map(
			[...services.reader.readReceipts, ...sessionReadReceipts].map((receipt) => [
				JSON.stringify(receipt),
				receipt,
			]),
		).values(),
	];
	if (readReceipts.length > CANDIDATE_LIMITS.receipts) {
		return finished("failed", "too-large", { error: "read receipts exceed the proof bound" });
	}
	const { value } = execution;
	let reviewed: Extract<Coverage, { source: "host" }> | null = null;
	let shaped: ParsedSessionCandidate | SessionCandidate;
	if (isJsonObject(value) && "coverage" in value) {
		const parsed = parseCandidate(value);
		if (!parsed.ok) {
			return finished("failed", "invalid-candidate", {
				error: `the program returned no valid result: ${parsed.reasonCode}: ${parsed.message}`,
			});
		}
		const { coverage, ...rest } = parsed.candidate;
		if (coverage.source !== "host" || !returned.has(JSON.stringify(coverage))) {
			return finished("failed", "invalid-candidate", {
				error: "the result carries host coverage that no review of this practice returned",
			});
		}
		reviewed = coverage;
		shaped = rest;
	} else {
		const parsed = sessionCandidateSchema.safeParse(value);
		if (!parsed.success) {
			return finished("failed", "invalid-candidate", {
				error: `the program returned no valid result: ${parsed.error.issues[0]?.message ?? "invalid"}`,
			});
		}
		shaped = parsed.data;
	}
	const citations = citationsOf(shaped);
	if (
		job.inputs.scope.kind === "SCAN" &&
		citations.some((citation) => !services.reader.allowsCitation(citation))
	) {
		return finished("failed", "invalid-candidate", {
			error: "a scan citation is outside its frozen context",
		});
	}
	// Only a result that returns a review's decision unchanged keeps that review's coverage. Any other
	// result is code's decision, measured against the reads, whatever coverage it carries.
	const decided =
		reviewed !== null && decisions.has(decisionKey(shaped, reviewed)) ? reviewed : null;
	// A search proves an absence only for the decision that ran it: the review kept unchanged, or else the code.
	const searchedBy = new Set(
		(decided?.receipts ?? services.reader.readReceipts).map((receipt) => JSON.stringify(receipt)),
	);
	const proof = readReceipts.filter(
		(receipt) => receipt.status !== "SEARCHED" || searchedBy.has(JSON.stringify(receipt)),
	);
	const coverage = hostCoverage(
		decided,
		decided === null
			? unreadSources(citations, services.requiredSources, readReceipts, job.files?.change != null)
			: [],
		services.receipts,
		proof,
	);
	if (decided === null && shaped.outcome === "DOES_NOT_APPLY" && !coverage.complete) {
		return finished("failed", "invalid-candidate", {
			error: "a Does not apply that code decided needs complete coverage",
		});
	}
	const final = parseCandidate({ ...shaped, coverage });
	if (!final.ok) {
		return finished("failed", "invalid-candidate", {
			error: `${final.reasonCode}: ${final.message}`,
		});
	}
	const { candidate } = final;

	if (job.inputs.scope.kind === "SCAN" && candidate.outcome === "MET") {
		const file = job.inputs.scope.item.path;
		if (
			!readReceipts.some(
				(receipt) =>
					receipt.path === file &&
					receipt.version === job.files?.head.commit &&
					receipt.status === "COMPLETE",
			) ||
			services.receipts.failedReads > 0 ||
			services.receipts.truncatedReads > 0
		) {
			return finished("failed", "invalid-candidate", {
				error:
					"SCAN MET requires a complete receipt for the assigned pinned file and complete relevant host coverage",
			});
		}
	}
	return finished("ok", null, { candidate, readReceipts: proof });
}

interface Cited {
	citations: readonly Citation[];
	problems?: readonly { citations: readonly Citation[] }[];
	example?: { citation: Citation };
	evidence: {
		search?: unknown;
		inapplicability?: unknown;
		openQuestions?: readonly { citation?: Citation }[];
	};
}

/** A decision's identity: its outcome and every citation, on the coverage it rests on. */
function decisionKey(candidate: Cited & { outcome: Outcome }, coverage: Coverage): string {
	return JSON.stringify([candidate.outcome, citationsOf(candidate), coverage]);
}

/** Every citation of a candidate: its own, its problems', its example's and its open questions'. */
function citationsOf(candidate: Cited): Citation[] {
	const questions = candidate.evidence.openQuestions ?? [];
	return [
		...candidate.citations,
		...(candidate.problems ?? []).flatMap((problem) => problem.citations),
		...(candidate.example === undefined ? [] : [candidate.example.citation]),
		...questions.flatMap((question) =>
			question.citation === undefined ? [] : [question.citation],
		),
	];
}

/**
 * What code left unread, by the rule review sessions follow: each required source and each cited
 * source needs a complete read, or the host supplied it whole (the captured change). Code that read
 * nothing covers nothing.
 */
function unreadSources(
	citations: readonly Citation[],
	required: readonly RequiredSource[],
	receipts: readonly ReadReceipt[],
	changeSupplied: boolean,
): Omission[] {
	const complete = (sourcePath: string, file?: string) =>
		receipts.some(
			(receipt) =>
				receipt.status === "COMPLETE" &&
				receipt.sourcePath === sourcePath &&
				(file === undefined || receipt.path === file),
		);
	const covered = (sourceKind: string, sourcePath: string, file?: string) =>
		sourceKind === DIFF_SOURCE ? changeSupplied : complete(sourcePath, file);
	const unreadRequired = required
		.filter((source) => !covered(source.sourceKind, source.sourcePath))
		.map((source) => source.sourcePath);
	const unreadCited = [
		...new Set(
			citations
				.filter(
					(citation) =>
						!covered(
							citation.sourceKind,
							citation.sourcePath,
							citation.sourceKind === TREE_SOURCE ? citation.path : undefined,
						),
				)
				.map((citation) => citation.path),
		),
	];
	const omitted: Omission[] = [];
	for (const [what, items] of [
		["required sources not read in full", unreadRequired],
		["cited sources not read in full", unreadCited],
	] as const) {
		if (items.length > 0) {
			omitted.push({
				what,
				count: items.length,
				reason: "unavailable",
				items: items
					.slice(0, CANDIDATE_LIMITS.omissionItems)
					.map((item) => item.slice(0, CANDIDATE_LIMITS.warrantFieldChars)),
			});
		}
	}
	if (receipts.length === 0 && !changeSupplied) {
		omitted.push({
			what: "no relevant source was supplied or read",
			count: 1,
			reason: "unavailable",
		});
	}
	return omitted;
}

/** The coverage the host attaches: the review's, or none, with the script's own reads added to it. */
function hostCoverage(
	reviewed: Extract<Coverage, { source: "host" }> | null,
	unread: readonly Omission[],
	script: Receipts,
	receipts: readonly ReadReceipt[],
): Extract<Coverage, { source: "host" }> {
	const omitted: Omission[] = [
		...unread,
		...(reviewed === null || reviewed.complete ? [] : reviewed.omitted),
	];
	if (script.truncatedReads > 0) {
		omitted.push({
			what: "script reads cut short",
			count: script.truncatedReads,
			reason: "truncated",
		});
	}
	if (script.failedReads > 0) {
		omitted.push({
			what: "script reads that failed",
			count: script.failedReads,
			reason: "unavailable",
		});
	}
	const base = {
		source: "host" as const,
		reads: (reviewed?.reads ?? 0) + script.reads,
		truncatedReads: (reviewed?.truncatedReads ?? 0) + script.truncatedReads,
		failedReads: (reviewed?.failedReads ?? 0) + script.failedReads,
		receipts,
	};
	const [first, ...rest] = omitted;
	return first === undefined
		? { ...base, complete: true }
		: { ...base, complete: false, omitted: [first, ...rest] };
}
