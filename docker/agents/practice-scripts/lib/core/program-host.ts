/**
 * Runs one admitted practice's program under the scope the host supplies: the built-in in this
 * process, a custom program in a child process. Either way the program gets the same context, and its
 * final value is checked as one candidate. The host keeps one practice and one review active.
 */
import { createHash } from "node:crypto";

import {
	parseCandidate,
	CANDIDATE_LIMITS,
	parseMeta,
	parseReviewOptions,
	type Candidate,
	type ProgramCalls,
	type ReadReceipt,
	type ProgramKind,
	type ReviewSessionFacts,
	type RunStatus,
	type StopReason,
} from "./contract.ts";
import { noReceipts, type Receipts } from "./evidence-reader.ts";
import type { ProgramJob } from "./ipc.ts";
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
	services: ProgramServices & { receipts: Receipts },
): Promise<ProgramOutcome> {
	const started = Date.now();
	/** The coverage each review of this practice returned: the only host coverage its candidate may carry. */
	const returned = new Set<string>();
	const sessionReadReceipts: ReadReceipt[] = [];
	const hosted: ProgramServices = {
		...services,
		review: async (options, signal) => {
			const candidate = await services.review(options, signal);
			returned.add(JSON.stringify(candidate.coverage));
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
	const parsed = parseCandidate(execution.value);
	if (!parsed.ok) {
		return finished("failed", "invalid-candidate", {
			error: `the program returned no valid result: ${parsed.reasonCode}: ${parsed.message}`,
		});
	}
	let { candidate } = parsed;
	if (job.inputs.scope.kind === "SCAN") {
		const citations = [
			...candidate.citations,
			...(candidate.outcome === "NOT_MET"
				? candidate.problems.flatMap((problem) => problem.citations)
				: []),
			...(!("example" in candidate) || candidate.example === undefined
				? []
				: [candidate.example.citation]),
			...("openQuestions" in candidate.evidence
				? (candidate.evidence.openQuestions ?? [])
				: []
			).flatMap((question) => (question.citation === undefined ? [] : [question.citation])),
		];
		if (citations.some((citation) => !services.reader.allowsCitation(citation))) {
			return finished("failed", "invalid-candidate", {
				error: "a scan citation is outside its frozen context",
			});
		}
	}

	if (candidate.coverage.source === "host" && !returned.has(JSON.stringify(candidate.coverage))) {
		return finished("failed", "invalid-candidate", {
			error: "the result carries host coverage that no review of this practice returned",
		});
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
	// Program code may type its coverage; the host replaces those counts with what the reads reached.
	const source =
		candidate.coverage.source === "host"
			? candidate.coverage
			: { ...candidate.coverage, reads: 0, truncatedReads: 0, failedReads: 0 };
	const omitted = [...(source.complete ? [] : source.omitted)];
	if (services.receipts.truncatedReads > 0) {
		omitted.push({
			what: "script reads cut short",
			count: services.receipts.truncatedReads,
			reason: "truncated",
		});
	}
	if (services.receipts.failedReads > 0) {
		omitted.push({
			what: "script reads that failed",
			count: services.receipts.failedReads,
			reason: "unavailable",
		});
	}
	const base = {
		source: "host" as const,
		reads: source.reads + services.receipts.reads,
		truncatedReads: source.truncatedReads + services.receipts.truncatedReads,
		failedReads: source.failedReads + services.receipts.failedReads,
		receipts: readReceipts,
	};
	const coverage =
		omitted.length === 0 ? { ...base, complete: true } : { ...base, complete: false, omitted };
	const final = parseCandidate({ ...candidate, coverage });
	if (!final.ok) {
		return finished("failed", "invalid-candidate", {
			error: `${final.reasonCode}: ${final.message}`,
		});
	}
	candidate = final.candidate;

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
	return finished("ok", null, { candidate, readReceipts });
}
