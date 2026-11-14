import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

import {
	type AgentSession,
	type createAgentSession,
	defineTool,
} from "@earendil-works/pi-coding-agent";

import { startPractice } from "./pi-admitted-results.ts";
import { CHANGE_ROOT, readChange } from "./pi-change.ts";
import { type DiagnosticValue, diagnose, errorKind } from "./pi-diagnostics.ts";
import { errorText } from "./pi-error-text.ts";
import type { CitableText } from "./pi-folder-index.ts";
import type { PracticeCoverage } from "./pi-practice-coverage.ts";
import { ProblemDrafts } from "./pi-problem-drafts.ts";
import { validateChangeBasis } from "./pi-result-normalize.ts";
import { RESULT_TOOL_NAMES } from "./pi-result-tools.ts";
import type { buildBrief } from "./pi-review-brief.ts";
import { type Work, spent } from "./pi-review-turns.ts";
import { addAssistantUsage, type UsageLedger } from "./pi-runner-usage.ts";
import type {
	ActiveReview,
	RequiredSourceTrace,
	ReviewSessionState,
	StopReason,
	TurnDemand,
	TurnTrace,
} from "./pi-runner.ts";
import { abortSession, scheduleDeadline } from "./pi-session-lifecycle.ts";
import { SessionReads, type SessionTree } from "./pi-session-reads.ts";
import type { taskPaths } from "./pi-task-paths.ts";
import { hasText } from "./pi-text.ts";
import { prepareTurnText } from "./pi-turn-context.ts";
import { DIFF_SOURCE, TREE_SOURCE } from "./work/practice-scripts/kits/scm/sources.ts";
import type {
	Candidate,
	PracticeInputs,
	ReadReceipt,
	ReviewOptions,
} from "./work/practice-scripts/lib/core/contract.ts";
import {
	EvidenceReader,
	noReceipts,
	type EvidenceSources,
	type Receipts,
} from "./work/practice-scripts/lib/core/evidence-reader.ts";
import type { JobFiles } from "./work/practice-scripts/lib/core/ipc.ts";
import { renderReviewInput, resolveLeads } from "./work/practice-scripts/lib/core/leads.ts";
import {
	UnratedError,
	type UnratedReason,
} from "./work/practice-scripts/lib/core/practice-script.ts";
import {
	notReached as notReachedOutcome,
	runPracticeProgram,
	type PracticeProgram,
	type ProgramOutcome,
	type RequiredSource,
} from "./work/practice-scripts/lib/core/program-host.ts";
import type { PracticeIndexEntry } from "./work/practice-scripts/lib/core/runtime-inputs.ts";
import type { InputReader } from "./work/practice-scripts/lib/core/script-inputs.ts";
import {
	abortReasonOf,
	forkedProgram,
	RETURN_MS,
} from "./work/practice-scripts/lib/core/script-run.ts";

type CustomTools = NonNullable<
	NonNullable<Parameters<typeof createAgentSession>[0]>["customTools"]
>;

/** What the review job fixed before its first scope started. */
export interface ScopeJob {
	cwd: string;
	/** The task's paths, relative to the workspace. */
	paths: ReturnType<typeof taskPaths>;
	/** The task's paths, resolved against the workspace. */
	inputPaths: ReturnType<typeof taskPaths>;
	reviewInputRoot: string;
	proxy: { url: string; token: string; claim: Readonly<Record<string, string>> };
	practiceIndex: readonly PracticeIndexEntry[];
	evidenceSources: EvidenceSources;
	sourceFileSources: ReadonlyMap<string, string>;
	completeSourceKinds: ReadonlySet<string>;
	capturedTexts: CitableText[];
	inputReader: InputReader;
	recordNudge: string;
	abortSettleMs: number;
}

/** What one practice may spend, and the run's clock. */
export interface ScopeLimits {
	perPracticeWork: Work;
	processStartMs: number;
	measureEnd: number;
	/** Aborts when the run stops measuring. */
	measuringEnded: AbortSignal;
}

/** What the job knows about each practice, by its slug. */
export interface ScopePractices {
	practiceAt: (slug: string) => number | undefined;
	programOf: (slug: string) => PracticeProgram;
	briefOf: (slug: string) => ReturnType<typeof buildBrief>;
	sourcesForPractice: (slug: string, sources: EvidenceSources) => EvidenceSources;
	requiredSourcesOf: (slug: string) => RequiredSource[];
	jobFiles: (inputs: PracticeInputs, evidence: EvidenceSources) => JobFiles | null;
	practiceTurnText: (heading: string, slug: string, brief: string, prepared: string) => string;
	buildReviewResultTools: (review: ActiveReview) => CustomTools;
}

/** What the run has recorded for each practice. Every scope writes to the same records. */
export interface ScopeRecords {
	reviewState: Map<string, Candidate>;
	executions: Map<string, ProgramOutcome>;
	assignedInputs: Map<string, PracticeInputs>;
	assignmentReceipts: Map<string, readonly ReadReceipt[]>;
	blockedPractices: Map<string, UnratedReason>;
	skippedPractices: Set<string>;
	/** runner-debug.json's required brief sources of each practice. */
	practiceSources: Record<string, RequiredSourceTrace[]>;
}

/** Writes the records to the output directory, and stages a practice that ended. */
export interface ScopePersistence {
	persistReviewState: () => void;
	maybeWriteResultFile: () => boolean;
	persistPracticeCoverage: () => PracticeCoverage;
	persistRecordedNotes: () => void;
	stageEnded: (practiceSlug: string) => Promise<void>;
}

/** The trace of the scope's turn, and the guards that end it. */
export interface ScopeTurns {
	placeOf: (label: string) => Record<string, DiagnosticValue>;
	openTurnTrace: (label: string, budget: Work, demand: TurnDemand) => TurnTrace;
	closeTurnTrace: (trace: TurnTrace) => void;
	stopTurn: (reason: StopReason) => void;
	owedPractices: (slugs?: readonly string[]) => string[];
	sessionFacts: (slug: string) => { usage: UsageLedger };
	refusalLimited: (slug: string) => boolean;
	countRefusal: (slug: string) => void;
}

export interface OpenedSession {
	session: AgentSession;
	unsubscribe: () => void;
}

/** Opens and stops the native sessions that review a practice. */
export interface ScopeSessions {
	sessionState: () => ReviewSessionState;
	openSession: (tools: string[], customTools: CustomTools | undefined) => Promise<OpenedSession>;
	closeSession: (opened: OpenedSession) => Promise<void>;
	settleSession: (session: AgentSession, label: string, maxMs: number) => Promise<boolean>;
	/**
	 * The first native session that did not stop. Every scope shares it, and a scope that finds it
	 * set throws it.
	 */
	stopFailure: { error: Error | null };
}

/** What a scope needs from its review job. The runner builds it once, and every scope shares it. */
export interface ScopeContext {
	job: ScopeJob;
	limits: ScopeLimits;
	practices: ScopePractices;
	records: ScopeRecords;
	persistence: ScopePersistence;
	turns: ScopeTurns;
	sessions: ScopeSessions;
}

const EVIDENCE_TOOLS = ["read", "grep", "find", "ls"] as const;
// Codemode runs a script that calls the other tools, so a chain of reads and searches, or a large
// result filtered down to what a criterion needs, costs one model call rather than one per step.
const PRACTICE_TOOLS = [...EVIDENCE_TOOLS, "bash", "codemode"] as const;

const UNRATED_BY_STOP: Readonly<Record<StopReason, UnratedReason>> = {
	budget: "budget",
	safety: "deadline",
	stall: "unavailable",
	loop: "off-format",
};

function readState(reads: SessionReads, target: string): RequiredSourceTrace["read"] {
	if (reads.hasCompleteRead(target)) {
		return "complete";
	}
	return reads.hasRead(target) ? "partial" : "none";
}

/** The checkout that a review session reads on demand, when the work has one at a known commit. */
function sessionTree(sources: EvidenceSources, complete: boolean): SessionTree | undefined {
	return sources.repo === null || sources.headRevision === null
		? undefined
		: {
				root: sources.repo,
				sourcePath: sources.treeSourceFile,
				version: sources.headRevision,
				complete,
			};
}

function governedScanRead(reader: EvidenceReader) {
	return defineTool({
		name: "read",
		label: "Read pinned file",
		description:
			"Read a captured repository file at the pinned commit. Files are context for the assigned file only.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["path"],
			properties: {
				path: { type: "string" },
				offset: { type: "integer", minimum: 1 },
				limit: { type: "integer", minimum: 1 },
			},
		},
		execute: async (_id, params: { path: string; offset?: number; limit?: number }, signal) => {
			const read = await reader.answer(
				{
					op: "read",
					path: params.path,
					...(params.offset === undefined ? {} : { offsetLine: params.offset }),
					...(params.limit === undefined ? {} : { maxLines: params.limit }),
				},
				signal,
			);
			return {
				content: [
					{
						type: "text" as const,
						text: read.status === "FOUND" ? read.text : `${params.path}: ${read.status}`,
					},
				],
				details: {
					status: read.status,
					...(read.status === "FOUND" ? { truncated: read.truncated } : {}),
				},
			};
		},
	});
}

function recordOnceMoreText(slug: string): string {
	return (
		`## Not recorded\nThis session ended without a result for ${slug}: text outside a ` +
		`submit_result call records nothing. Submit the outcome its criterion and the evidence you read ` +
		`support, in one submit_result call. If a required source is missing, truncated or blocked, that ` +
		`is a collection gap: submit nothing.`
	);
}

export async function runScope(
	context: ScopeContext,
	label: string,
	heading: string,
	requestedSlugs: readonly string[],
): Promise<StopReason | null> {
	const {
		cwd,
		paths,
		inputPaths,
		reviewInputRoot,
		proxy,
		practiceIndex,
		evidenceSources,
		sourceFileSources,
		completeSourceKinds,
		capturedTexts,
		inputReader,
		recordNudge,
		abortSettleMs,
	} = context.job;
	const { perPracticeWork, processStartMs, measureEnd, measuringEnded } = context.limits;
	const {
		practiceAt,
		programOf,
		briefOf,
		sourcesForPractice,
		requiredSourcesOf,
		jobFiles,
		practiceTurnText,
		buildReviewResultTools,
	} = context.practices;
	const {
		reviewState,
		executions,
		assignedInputs,
		assignmentReceipts,
		blockedPractices,
		skippedPractices,
		practiceSources,
	} = context.records;
	const {
		persistReviewState,
		maybeWriteResultFile,
		persistPracticeCoverage,
		persistRecordedNotes,
		stageEnded,
	} = context.persistence;
	const {
		placeOf,
		openTurnTrace,
		closeTurnTrace,
		stopTurn,
		owedPractices,
		sessionFacts,
		refusalLimited,
		countRefusal,
	} = context.turns;
	const { sessionState, openSession, closeSession, settleSession, stopFailure } = context.sessions;
	const safetyMs = measureEnd - Date.now();
	if (safetyMs <= RETURN_MS) {
		diagnose("turn.near_ceiling", { ...placeOf(label), skipped: true });
		return "safety";
	}
	const slugs: string[] = [];
	for (const slug of requestedSlugs) {
		const current = await startPractice(slug, async (body, signal) =>
			fetch(`${proxy.url}/start-practice`, {
				method: "POST",
				signal,
				headers: {
					authorization: `Bearer ${proxy.token}`,
					...proxy.claim,
					"content-type": "application/json",
					...(hasText(process.env.TRACEPARENT) ? { traceparent: process.env.TRACEPARENT } : {}),
				},
				body,
			}),
		);
		if (!current) {
			skippedPractices.add(slug);
			continue;
		}
		slugs.push(slug);
	}
	if (slugs.length === 0) {
		return null;
	}
	const budget = perPracticeWork;
	diagnose("turn.started", {
		...placeOf(label),
		practices: slugs.length,
		call_budget: budget.modelCalls,
		output_token_budget: budget.outputTokens,
		tokens_per_result: sessionState().tokensPerProblem,
	});
	const trace = openTurnTrace(label, budget, {
		owed: () => owedPractices(slugs).length,
		nudge: recordNudge,
	});
	if (slugs.length === 1) {
		trace.practiceSlug = slugs[0];
	}
	const safety = scheduleDeadline(safetyMs, () => stopTurn("safety"));
	// Native events may stop a turn while awaited work settles or another session opens.
	const stoppedBy = () => trace.stoppedBy;
	const pastSafety = () => safety.expired() || Date.now() >= measureEnd;
	const endedUnrecorded = (slug: string) =>
		owedPractices([slug]).length > 0 && stoppedBy() === null && !pastSafety() && !trace.modelError;
	async function promptPractice(session: AgentSession, text: () => string, slug: string) {
		const scope = sessionState();
		if (pastSafety()) {
			return;
		}
		const prepared = await prepareTurnText(session, text);
		if (pastSafety()) {
			return;
		}
		if (prepared === null) {
			blockedPractices.set(slug, "too-large");
			diagnose("practice.oversized", { ...placeOf(label), practice: practiceAt(slug) });
			return;
		}
		await session.prompt(prepared, {
			preflightResult: (disposition) => {
				if (disposition === "started") {
					scope.openingInContext = true;
				}
			},
		});
	}
	async function askOnceMore(
		session: AgentSession,
		text: () => string,
		slug: string,
		interrupted: Promise<undefined>,
	) {
		const state = sessionState();
		if (
			!endedUnrecorded(slug) ||
			spent({ modelCalls: trace.calls, outputTokens: trace.outputTokens }, trace.budget)
		) {
			return;
		}
		diagnose("practice.unsubmitted", {
			...placeOf(label),
			practice: practiceAt(slug),
			asking_again: true,
		});
		if (!(await settleSession(session, label, abortSettleMs))) {
			throw new Error("the session was still busy when it was asked once more");
		}
		// After a compaction the request carries the practice turn again, as any next turn does.
		const onceMore = () =>
			`${state.openingInContext ? "" : `${text()}\n\n`}${recordOnceMoreText(slug)}`;
		if (state.activeReview !== null) {
			state.activeReview.requireSubmission = true;
		}
		await Promise.race([promptPractice(session, onceMore, slug), safety.elapsed, interrupted]);
		if (endedUnrecorded(slug)) {
			blockedPractices.set(slug, "off-format");
			diagnose("practice.not_reached", {
				...placeOf(label),
				practice: practiceAt(slug),
				reason: "off_format",
			});
		}
	}
	function noResultReason(slug: string, signal: AbortSignal): UnratedReason {
		if (signal.aborted) {
			return abortReasonOf(signal);
		}
		const stop = stoppedBy();
		if (stop !== null) {
			return UNRATED_BY_STOP[stop];
		}
		if (pastSafety()) {
			return "deadline";
		}
		if (trace.modelError) {
			return "unavailable";
		}
		return blockedPractices.get(slug) ?? "off-format";
	}
	async function reviewPractice(
		slug: string,
		practiceHeading: string,
		options: ReviewOptions,
		signal: AbortSignal,
		reads: Receipts,
	): Promise<Candidate> {
		if (sessionState().activeReview !== null) {
			throw new UnratedError("refused", "a review is already running");
		}
		if (blockedPractices.has(slug)) {
			throw new UnratedError(
				blockedPractices.get(slug) ?? "off-format",
				`this review asks no more about ${slug}`,
			);
		}
		if (stoppedBy() !== null || pastSafety() || signal.aborted) {
			throw new UnratedError(noResultReason(slug, signal), "the scope ended before the review");
		}
		const sources = sourcesForPractice(slug, evidenceSources);
		const leads = await resolveLeads(options.leads ?? [], sources, signal);
		signal.throwIfAborted();
		const fullInput = `${reviewInputRoot}/${slug}.json`;
		const preparing =
			leads.length > 0 ||
			Object.keys(options.facts ?? {}).length > 0 ||
			(options.directions ?? []).length > 0 ||
			(options.context ?? []).length > 0 ||
			(options.limitations ?? []).length > 0;
		if (preparing) {
			mkdirSync(`${cwd}/${reviewInputRoot}`, { recursive: true });
			writeFileSync(`${cwd}/${fullInput}`, JSON.stringify({ ...options, leads }, null, 2));
		}
		const prepared = preparing
			? renderReviewInput(options, leads, paths.contextRoot, `\`${fullInput}\``)
			: "";
		const domainReads = noReceipts();
		const assignment = assignedInputs.get(slug);
		const isScan = assignment?.scope.kind === "SCAN";
		const { text: brief, sources: briefSources } = briefOf(slug);
		const reader = isScan ? new EvidenceReader(sources, domainReads) : undefined;
		let assignedText = "";
		if (isScan && reader !== undefined) {
			const { path } = assignment.scope.item;
			if (path === undefined) {
				throw new UnratedError("unavailable", "the scan has no assigned file");
			}
			const file = await reader.answer({ op: "read", path }, signal);
			if (file.status !== "FOUND" || file.truncated) {
				throw new UnratedError(
					file.status === "FOUND" ? "too-large" : "unavailable",
					"the assigned file cannot be read completely",
				);
			}
			assignedText = `\n## Assigned scan file\nPath: ${path}\nCommit: ${sources.headRevision ?? "unavailable"}\nSource: ${TREE_SOURCE}\nSource path: ${sources.treeSourceFile}\n${file.text}\n`;
		}
		const readSources = new Set(
			practiceIndex.find((practice) => practice.slug === slug)?.readsSources,
		);
		const relevantBrief = isScan
			? []
			: [...sourceFileSources].flatMap(([sourcePath, kind]) => {
					if (kind === TREE_SOURCE || !readSources.has(kind)) {
						return [];
					}
					const path = kind === DIFF_SOURCE ? `${CHANGE_ROOT}/diff.patch` : sourcePath;
					return [briefSources.find((source) => source.path === path) ?? { path, supplied: false }];
				});
		const review: ActiveReview = {
			slug,
			draft: null,
			problems: new ProblemDrafts(),
			requireSubmission: false,
			reads: new SessionReads(
				cwd,
				{
					required: relevantBrief.map((source) => source.path),
					supplied: isScan ? [] : briefSources,
				},
				undefined,
				capturedTexts,
				sessionTree(sources, completeSourceKinds.has(TREE_SOURCE)),
			),
			reader,
			domainReads,
		};
		sessionState().activeReview = review;
		const resultTools = buildReviewResultTools(review);
		sessionState().activePractice = slug;
		sessionState().currentTurnSlugs = [slug];
		trace.askedToRecord = false;
		let opened: Awaited<ReturnType<typeof openSession>>;
		try {
			opened = await openSession(
				isScan ? ["read", ...RESULT_TOOL_NAMES] : [...PRACTICE_TOOLS, ...RESULT_TOOL_NAMES],
				reader === undefined ? resultTools : [...resultTools, governedScanRead(reader)],
			);
		} catch (error) {
			sessionState().activeReview = null;
			sessionState().activePractice = null;
			throw error;
		}
		const { usage } = sessionFacts(slug);
		const unsubscribeReads = opened.session.subscribe((event) => {
			if (!isScan) {
				review.reads.note(event);
			}
			if (event.type === "message_end") {
				addAssistantUsage(usage, event.message);
			}
		});
		const { finishTurn } = opened.session.agent;
		const reviewScope = sessionState();
		let unresolvedRefusal = false;
		opened.session.agent.finishTurn = async (turn, finishSignal) => {
			const decision = await finishTurn?.(turn, finishSignal);
			for (const result of turn.toolResults) {
				if (result.toolName === "submit_result") {
					unresolvedRefusal = result.isError;
					if (result.isError) {
						review.requireSubmission = true;
						// Schema validation happens before execute; those refusals spend the same allowance.
						if (!reviewScope.answeredRefusals.delete(result.toolCallId)) {
							countRefusal(slug);
						}
					}
				}
			}
			if (refusalLimited(slug)) {
				return { action: "end" };
			}
			if (!unresolvedRefusal && review.draft !== null) {
				opened.session.clearQueue();
				return { action: "end" };
			}
			return decision ?? undefined;
		};
		const interrupted = Promise.withResolvers<undefined>();
		const signalIsAborted = () => signal.aborted;
		const stop = () => {
			interrupted.resolve(undefined);
			void abortSession(opened.session);
		};
		signal.addEventListener("abort", stop, { once: true });
		const text = () => `${practiceTurnText(practiceHeading, slug, brief, prepared)}${assignedText}`;
		try {
			signal.throwIfAborted();
			await Promise.race([
				promptPractice(opened.session, text, slug),
				safety.elapsed,
				interrupted.promise,
			]);
			if (!signalIsAborted()) {
				await askOnceMore(opened.session, text, slug, interrupted.promise);
			}
		} catch (error) {
			diagnose("practice.failed", {
				...placeOf(label),
				practice: practiceAt(slug),
				error: errorKind(error),
			});
		} finally {
			signal.removeEventListener("abort", stop);
			const closed = await closeSession(opened).then(
				() => null,
				(error: unknown) => (error instanceof Error ? error : new Error(String(error))),
			);
			unsubscribeReads();
			if (!isScan) {
				practiceSources[slug] = relevantBrief.map((source) => ({
					path: source.path,
					supplied: source.supplied,
					bytes: source.bytes ?? null,
					lines: source.lines ?? null,
					omitted: source.omitted ?? null,
					read: readState(review.reads, source.path),
				}));
			}
			const { receipts } = review.reads;
			reads.reads += receipts.reads + domainReads.reads;
			reads.truncatedReads += receipts.truncatedReads + domainReads.truncatedReads;
			reads.failedReads += receipts.failedReads + domainReads.failedReads;
			sessionState().activeReview = null;
			sessionState().activePractice = null;
			stopFailure.error ??= closed;
		}
		if (stopFailure.error !== null) {
			throw stopFailure.error;
		}
		signal.throwIfAborted();
		if (review.draft !== null) {
			return review.draft;
		}
		throw new UnratedError(
			noResultReason(slug, signal),
			`the review session of ${slug} ended without a result`,
		);
	}
	async function measureProgram(slug: string, practiceHeading: string): Promise<void> {
		const program = programOf(slug);
		const read = await inputReader.read(slug).catch((error: unknown) => ({
			error: `the host could not read them: ${errorText(error)}`,
		}));
		if ("error" in read) {
			diagnose("practice.inputs_invalid", { practice: practiceAt(slug) });
			executions.set(slug, {
				...notReachedOutcome(program, "unavailable"),
				status: "failed",
				error: `the review's inputs are not valid: ${read.error}`,
				durationMs: 0,
			});
			return;
		}
		assignedInputs.set(slug, read.inputs);
		if (
			read.inputs.scope.kind === "SCAN" &&
			practiceIndex.find((practice) => practice.slug === slug)?.contextPaths === undefined
		) {
			executions.set(slug, {
				...notReachedOutcome(program, "unavailable"),
				status: "withheld",
				durationMs: 0,
			});
			return;
		}
		const programReads = noReceipts();
		const sessionReads = noReceipts();
		const stopped = new AbortController();
		const safetyTimer = setTimeout(
			() => {
				stopped.abort(new UnratedError("deadline", "the run is near its safety ceiling"));
			},
			Math.max(0, measureEnd - Date.now()),
		);
		const sources = sourcesForPractice(slug, evidenceSources);
		const outcome = await runPracticeProgram(
			program,
			{
				slug,
				inputs: read.inputs,
				files: jobFiles(read.inputs, sources),
				now: new Date(processStartMs).toISOString(),
				deadline: measureEnd - RETURN_MS,
			},
			{
				reader: new EvidenceReader(sources, programReads),
				start: forkedProgram,
				review: async (options, signal) =>
					reviewPractice(slug, practiceHeading, options, signal, sessionReads),
				signal: AbortSignal.any([stopped.signal, measuringEnded]),
				// A practice script can log what it read, so its lines leave the sandbox only as a length.
				log: (line) => {
					diagnose("practice.program_log", { practice: practiceAt(slug), chars: line.length });
				},
				receipts: programReads,
				requiredSources: requiredSourcesOf(slug),
			},
		);
		const reached: Receipts = {
			reads: outcome.coverageReached.reads + sessionReads.reads,
			truncatedReads: outcome.coverageReached.truncatedReads + sessionReads.truncatedReads,
			failedReads: outcome.coverageReached.failedReads + sessionReads.failedReads,
		};
		clearTimeout(safetyTimer);
		executions.set(slug, { ...outcome, coverageReached: reached });
		if (stopFailure.error !== null) {
			throw stopFailure.error;
		}
		diagnose("practice.program_finished", {
			practice: practiceAt(slug),
			program: program.script === null ? "built_in" : "custom",
			status: outcome.status,
			program_stop: outcome.stopReason ?? undefined,
			error_chars: outcome.error?.length,
		});
		const { candidate } = outcome;
		if (candidate === undefined) {
			return;
		}
		try {
			validateChangeBasis(
				candidate,
				existsSync(`${cwd}/${CHANGE_ROOT}/diff.patch`)
					? readFileSync(`${cwd}/${CHANGE_ROOT}/diff.patch`, "utf8")
					: "",
				readChange(inputPaths.contextRoot),
			);
		} catch (error) {
			executions.set(slug, {
				...outcome,
				candidate: undefined,
				status: "failed",
				stopReason: "invalid-candidate",
				error: errorText(error),
			});
			return;
		}
		assignmentReceipts.set(slug, outcome.readReceipts ?? []);
		reviewState.set(slug, candidate);
		persistReviewState();
		maybeWriteResultFile();
		persistPracticeCoverage();
		persistRecordedNotes();
	}
	async function runProgram(slug: string, practiceHeading: string): Promise<void> {
		await measureProgram(slug, practiceHeading);
		await stageEnded(slug);
	}
	try {
		for (const [index, slug] of slugs.entries()) {
			if (stoppedBy() !== null || Date.now() >= measureEnd - RETURN_MS) {
				break;
			}
			if (executions.has(slug)) {
				continue;
			}
			await runProgram(slug, `${heading}\nPractice ${index + 1} of ${slugs.length} in this turn.`);
		}
	} finally {
		clearTimeout(safety.timer);
		sessionState().activePractice = null;
		sessionState().currentTurnSlugs = slugs;
		closeTurnTrace(trace);
	}
	if (pastSafety() && stoppedBy() === null) {
		diagnose("turn.near_ceiling", { ...placeOf(label), rest_not_reached: true });
		return "safety";
	}
	// A hung provider shows no stall here: its call waits for a first byte until the proxy gives up, and
	// the SDK retries it. A turn whose retries ended unanswered stalled as surely.
	return (
		stoppedBy() ?? (trace.finalModelFailure?.retryEndedUnsuccessfully === true ? "stall" : null)
	);
}
