import { AsyncLocalStorage } from "node:async_hooks";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import nodePath from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { isDeepStrictEqual } from "node:util";
import { admit, AdmissionUnreachableError } from "./pi-admitted-results.ts";
import { DIFF_SOURCE, TREE_SOURCE } from "./work/practice-scripts/kits/scm/sources.ts";

import {
	type AgentSession,
	type AgentSessionEvent,
	type AgentToolResult,
	createAgentSession,
	createBashToolDefinition,
	createCodemodeExtension,
	DefaultResourceLoader,
	defineTool,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import {
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { assessmentCacheExtension } from "./pi-assessment-cache.ts";
import {
	CHANGE_ROOT,
	checkedOutCommit,
	readChange,
	readDescription,
	readPinnedBlob,
	safeRepositoryPath,
	writeChangeView,
} from "./pi-change.ts";
import { errorText } from "./pi-error-text.ts";

import {
	CONTEXT_IS_LOOP_ONLY,
	type DeclaredSources,
	declaredSourcesExtension,
	readableKinds,
	undeclaredCitedKinds,
} from "./pi-declared-sources.ts";
import { type CitableText, folderCitationIndex, citableTextIndex } from "./pi-folder-index.ts";
import {
	addModelCall,
	type ModelCallTiming,
	type ModelFailure,
	type ModelFailureKind,
	modelFailure,
	newModelCallTiming,
	stopIfAttemptRefused,
} from "./pi-model-failure.ts";
import { PracticeCoverageLedger } from "./pi-practice-coverage.ts";
import { executionReport } from "./pi-practice-scripts-report.ts";
import { ProblemDrafts } from "./pi-problem-drafts.ts";
import {
	loadProviderConfig,
	reasoningSetting,
	registerHephaestusProvider,
	submissionExtension,
} from "./pi-provider.ts";
import {
	CONVERSATION_THREAD,
	citationCorrectionExample,
	citesReviewedTurn,
	normalizeSubmission,
	normalizeProblem,
	validateCitationSources,
	type CitationSources,
	resolveQuote,
	resolveCapturedText,
	submissionCitations,
	validateChangeBasis,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
} from "./pi-result-normalize.ts";
import { buildResultTools, RESULT_TOOL_NAMES } from "./pi-result-tools.ts";
import { type BriefSource, buildBrief } from "./pi-review-brief.ts";
import {
	type Work,
	missingSlugs,
	planTurns,
	partitionBudget,
	shouldRecordNow,
	spent,
	turnBudget,
} from "./pi-review-turns.ts";
import { outputPath } from "./pi-runner-output.ts";
import {
	addAssistantUsage,
	extractUsageFromSession,
	newUsageLedger,
	outputTokensOf,
	type UsageReport,
} from "./pi-runner-usage.ts";
import {
	REVIEW_SESSION_ENTRY,
	type ReviewSessionBinding,
	type SessionPhase,
	stopSession,
	scheduleDeadline,
} from "./pi-session-lifecycle.ts";
import {
	REPAIR_LIMIT,
	SessionReads,
	type SessionTree,
	insideRoot,
	unreadCheckoutClaim,
} from "./pi-session-reads.ts";
import { SUPPORTED_SCHEMA_VERSION, taskPaths, resolveTaskPaths } from "./pi-task-paths.ts";
import { hasText, isBlank } from "./pi-text.ts";
import { prepareTurnText } from "./pi-turn-context.ts";
import { parseDiff } from "./work/practice-scripts/kits/scm/diff-parser.ts";
import { fileRecordSchema } from "./work/practice-scripts/kits/scm/records.ts";
import type { DiffFile } from "./work/practice-scripts/kits/scm/types.ts";
import { MODEL_CONCURRENCY } from "./work/practice-scripts/lib/core/concurrency.ts";
import {
	boundModelsSchema,
	parseCandidate,
	type BoundModels,
	type Candidate,
	type Problem,
	type Citation,
	type Coverage,
	type PracticeInputs,
	isDecimalId,
	type ReadReceipt,
	validateCitationCapabilities,
	type ReviewOptions,
	type ReviewSessionFacts,
} from "./work/practice-scripts/lib/core/contract.ts";
import {
	EvidenceReader,
	noReceipts,
	type EvidenceSources,
	type Receipts,
} from "./work/practice-scripts/lib/core/evidence-reader.ts";
import type { JobFiles } from "./work/practice-scripts/lib/core/ipc.ts";
import { jsonArray, isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";
import { renderReviewInput, resolveLeads } from "./work/practice-scripts/lib/core/leads.ts";
import { hostModels, ModelBudget } from "./work/practice-scripts/lib/core/models-host.ts";
import {
	UnratedError,
	type UnratedReason,
} from "./work/practice-scripts/lib/core/practice-script.ts";
import {
	notReached as notReachedOutcome,
	requiredSources,
	runPracticeProgram,
	type PracticeProgram,
	type ProgramOutcome,
	type RequiredSource,
} from "./work/practice-scripts/lib/core/program-host.ts";
import {
	practiceIndexSchema,
	runtimeIdSchema,
} from "./work/practice-scripts/lib/core/runtime-inputs.ts";
import { InputReader } from "./work/practice-scripts/lib/core/script-inputs.ts";
import { abortReasonOf, RETURN_MS } from "./work/practice-scripts/lib/core/script-run.ts";

function logValue(value: unknown): string {
	if (typeof value === "string") {
		return value;
	}
	if (value === undefined) {
		return "undefined";
	}
	return JSON.stringify(value);
}

/** SDK event arrays may be absent before completion or after abort. */
function listOrEmpty<T>(items: T[] | undefined): T[] {
	return items ?? [];
}

/** The task this runner was started for. */
interface TaskEnvelope {
	schemaVersion: number;
	reviewId: string;
	jobId: string;
	attempt: number;
	workspaceId: string;
	paths: ReturnType<typeof taskPaths>;
	prompt: string;
	repositoryFullName: unknown;
	pullRequestNumber: unknown;
}

/** The server sets every one of these; a missing one stops the runner before it does any work. */
function requiredEnv(name: string): string {
	const value = process.env[name];
	if (!hasText(value)) {
		throw new Error(`${name} env var is required`);
	}
	return value;
}

/** What this run spent, in the buckets usage.json reports. */
interface UsageTotals {
	model: string | null;
	inputTokens: number;
	outputTokens: number;
	reasoningTokens: number;
	cacheReadTokens: number;
	cacheWriteTokens: number;
	costUsd: number;
	totalCalls: number;
}

/** One attempt's worth of what happened, for runner-debug.json. */
interface AttemptDebug {
	label: string;
	durationMs: number;
	assistantMessages: number;
	stopReasons: Record<string, number>;
	usage: UsageReport;
	resultFilePresent: boolean;
}

interface TurnTrace {
	practiceSlug?: string;
	phase: SessionPhase;
	modelError: boolean;
	/** Failed or aborted model calls of this turn, by the adapter's own closed kind; no provider text is kept. */
	modelFailures: Partial<Record<ModelFailureKind, number>>;
	/** The most recent available adapter failure timestamp; an unknown later failure keeps its own time null. */
	lastModelFailureAt: number | null;
	/**
	 * The failure the turn's last model call ended on, marked when the SDK then ended the retry unsuccessfully; null once a later
	 * call succeeds.
	 */
	finalModelFailure: (ModelFailure & { retryEndedUnsuccessfully: boolean }) | null;
	/** Failed or aborted calls whose failure diagnostic was missing or unreadable; their kind reads UNKNOWN. */
	unattributedModelFailures: number;
	/** The adapter's own call spans, failed calls included; calls without one are not counted. */
	modelCallTiming: ModelCallTiming;
	runtimeError: boolean;
	label: string;
	durationMs: number;
	calls: number;
	toolCalls: Record<string, number>;
	/** Calls of the turn's recording tool, counted whether or not the SDK let them reach it. */
	recordingCalls: number;
	stored: number;
	refused: number;
	refusalReasons: string[];
	/** Calls that ended in an error: a bash command that failed, a schema the SDK refused, a truncated call. */
	toolErrors: number;
	toolErrorReasons: string[];
	/** The most times one call — the same tool with the same arguments — was repeated in this turn. */
	repeatedCalls: number;
	compactions: number;
	providerRetries: number;
	outputTokens: number;
	/** Calls that ran into the model's output limit, and so reached their tool incomplete. */
	cutOff: number;
	/** What the turn may spend; it ends after the model call that spends it. */
	budget: Work;
	/** Whether the turn was told to record what it owes before its budget ran out. */
	askedToRecord: boolean;
	/** Why the runner ended the turn; null when the model ended it. */
	stoppedBy: StopReason | null;
}

/**
 * Why the runner ended a turn: its work budget was spent, the session stalled, the run neared its safety
 * ceiling, or a loop guard fired.
 */
type StopReason = "budget" | "stall" | "safety" | "loop";

/** Why a required source was or was not in the brief, and how much of it the session read. */
interface RequiredSourceTrace {
	path: string;
	supplied: boolean;
	bytes: number | null;
	lines: number | null;
	/** Null for a supplied source and for one that the brief does not consider. */
	omitted: BriefSource["omitted"] | null;
	read: "complete" | "partial" | "none";
}

function compareSlugs(a: string, b: string): number {
	if (a === b) {
		return 0;
	}
	return a < b ? -1 : 1;
}

interface Validated {
	candidate: Candidate;
	notes: string[];
}

/** The evidence check refused a MET result whose required or cited sources were not read completely. */
class UnreadEvidenceError extends Error {
	constructor(message: string) {
		super(message);
		this.name = "UnreadEvidenceError";
	}
}

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

function incompleteCoverageText(coverage: Coverage, reads: SessionReads, scan: boolean): string {
	const omissions = coverage.complete ? [] : coverage.omitted;
	const targets = omissions.flatMap((omission) => omission.items ?? []);
	// The governed scan reader replaces the native read tool and owns scan coverage, so the
	// session's native line counts and shell credit do not apply to a scan citation.
	const scanCalls = [...new Set(targets)]
		.filter(insideRoot)
		.slice(0, REPAIR_LIMIT)
		.map((target) => `read(${JSON.stringify({ path: target })})`);
	let repair = "";
	if (scan && scanCalls.length > 0) {
		repair = `Read each cited file whole: ${scanCalls.join("; ")}.`;
	} else if (!scan) {
		repair = reads.repair(targets);
	}
	return (
		`a MET result needs complete coverage of its required and cited sources: ${omissions.map((omission) => `${omission.what}: ${omission.count}`).join("; ")}. ` +
		`${repair || "Read the captured sources needed by the criterion to their last line."} ` +
		"Submit a MET result again only after every named source is read to its last line. " +
		"A required source that remains unavailable produces no result."
	);
}

function validateDomainBasis(
	submission: { problems?: readonly Problem[] },
	assignment: PracticeInputs | undefined,
): void {
	const scan = assignment?.scope.kind === "SCAN";

	for (const [index, problem] of (submission.problems ?? []).entries()) {
		if (scan && problem.basis.type !== "state") {
			throw new Error(
				`basis_not_allowed: problems.${index}.basis: a scan needs state basis. Corrected field example: ${JSON.stringify({ basis: { type: "state", explanation: problem.basis.explanation } })}. Use only when the pinned file supports the claim.`,
			);
		}
		if (!scan && assignment?.kind === "scm.pull_request" && problem.basis.type === "state") {
			throw new Error(
				`state_basis_not_allowed: problems.${index}.basis.type: an ITEM change needs introduced, worsened or duty basis. Corrected field example: ${JSON.stringify({ basis: { type: "introduced", head: 0, explanation: "<how the cited NEW line introduces this problem>" } })}. This is a shape example; choose introduced only if the changed line proves introduction, worsened only with OLD and NEW witnesses, or duty with an exact criterionQuote and event index. Otherwise remove the problem.`,
			);
		}
	}
}

/** Infer an omitted diff side by exact quote match, preferring NEW; never override a supplied side. */
function resolveOnEitherSide(citation: Citation, content: string): ReturnType<typeof resolveQuote> {
	if (citation.sourceKind !== "scm.pull-request.diff" || citation.side !== undefined) {
		return resolveQuote(citation, content);
	}
	const onNew = resolveQuote({ ...citation, side: "NEW" }, content);
	if ("quote" in onNew) {
		citation.side = "NEW";
		return onNew;
	}
	const onOld = resolveQuote({ ...citation, side: "OLD" }, content);
	if ("quote" in onOld) {
		citation.side = "OLD";
		return onOld;
	}
	return { mismatch: `${onNew.mismatch} (no side was named; NEW was tried, then OLD)` };
}

function excerptOf(text: string): string {
	return JSON.stringify(text.length > 160 ? `${text.slice(0, 160)}…` : text);
}

/** A draft disposition; its practice slug is its stable reference within this review. */
type Recorded =
	| (({ kind: "stored" } | { kind: "revised" }) & {
			slug: string;
			negative: boolean;
			filled: string[];
	  })
	| { kind: "duplicate"; slug: string }
	| { kind: "refused"; slug: string; reason: string };

interface TurnDemand {
	owed: () => number;
	nudge: string;
}

function problemsIn(result: unknown): number {
	const details = isRecord(result) ? result.details : undefined;
	if (!isRecord(details)) {
		return 0;
	}
	return [details.inserted, details.duplicates, details.refused]
		.map((count) => (typeof count === "number" ? count : 0))
		.reduce((sum, count) => sum + count, 0);
}

// Each review owns its draft; only the script's final return becomes a candidate.
interface ActiveReview {
	slug: string;
	draft: Candidate | null;
	problems: ProblemDrafts;
	requireSubmission: boolean;
	reads: SessionReads;
	reader?: EvidenceReader;
	domainReads: Receipts;
}

interface SubmitResultDetails {
	inserted: number;
	revised: number;
	duplicates: number;
	refused: number;
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

function recordModelResponse(trace: TurnTrace, stopReason: string, message: unknown): void {
	const failed = stopReason === "error" || stopReason === "aborted";
	addModelCall(trace.modelCallTiming, message, failed);
	if (failed) {
		const failure = modelFailure(message);
		trace.modelFailures[failure.kind] = (trace.modelFailures[failure.kind] ?? 0) + 1;
		if (failure.source !== "ADAPTER") {
			trace.unattributedModelFailures += 1;
		}
		trace.lastModelFailureAt = failure.at ?? trace.lastModelFailureAt;
		trace.finalModelFailure = { ...failure, retryEndedUnsuccessfully: false };
	} else {
		trace.finalModelFailure = null;
	}
}

function recordUnsuccessfulRetryEnd(trace: TurnTrace): void {
	const final = trace.finalModelFailure;
	if (final !== null) {
		trace.finalModelFailure = { ...final, retryEndedUnsuccessfully: true };
	}
}

// A failed steer must not interrupt the active turn.
async function steer(session: AgentSession, message: string): Promise<void> {
	try {
		await session.steer(message);
	} catch (error) {
		console.error(`[pi-runner] steer failed: ${errorText(error)}`);
	}
}

// Pi rejects prompts while the previous run is active.
async function abortSession(session: AgentSession): Promise<void> {
	session.clearQueue();
	// A compaction in flight is a model call of its own that `abort()` leaves running, and the
	// session is not idle until it ends; the next turn compacts again if it must, inside its own share.
	session.abortCompaction();
	try {
		await session.abort();
	} catch (error) {
		console.error(`[pi-runner] session abort failed: ${errorText(error)}`);
	}
}

async function settleSession(
	session: AgentSession,
	label: string,
	maxMs: number,
): Promise<boolean> {
	if (session.isIdle) {
		return true;
	}
	const started = Date.now();
	const becameIdle = async (): Promise<boolean> => {
		await session.waitForIdle();
		return true;
	};
	const idle = await Promise.race([becameIdle(), sleep(maxMs, false)]);
	// The run flag clears a beat after the prompt resolves; a wait that short is not worth a line.
	const waitedMs = Date.now() - started;
	if (!idle || waitedMs >= 1000) {
		console.error(
			`[pi-runner] ${label}: waited ${(waitedMs / 1000).toFixed(1)}s for the session to go idle${idle ? "" : " — still busy"}`,
		);
	}
	return idle;
}

function preparedSectionOf(slug: string, section: string): string {
	const shown = section.trim();
	return shown
		? `\n\n#### Leads from the practice script for \`${slug}\` — starting points to check against the criteria, not verdicts\nThe full input is generated preparation, not citable evidence. Cite the captured source paths and exact text in the review brief.\n${shown}`
		: "";
}

function toolResultText(result: unknown): string {
	return jsonArray(isRecord(result) ? result.content : null)
		.map((block) => (isRecord(block) && typeof block.text === "string" ? block.text : ""))
		.filter(Boolean)
		.join("\n");
}

interface ReviewSessionState {
	currentTurn: TurnTrace | null;
	activeSession: AgentSession | null;
	turnDemand: TurnDemand | null;
	tokensPerProblem: number;
	recordingMessageTokens: number;
	callOutputLimit: number | null;
	lastEventAt: number;
	activeReview: ActiveReview | null;
	currentTurnSlugs: readonly string[];
	/** A measuring session may submit only its assigned practice. */
	activePractice: string | null;
	openingInContext: boolean;
	sessionGuards: { cutOff: number; recordingCalls: number; stored: number };
	answeredRefusals: Set<string>;
	refusedItems: Map<string, { reason: string; resent: number }>;
	repeatedCalls: Map<string, number>;
}

// Native tools and SDK callbacks use the state of their session across awaited work.
function newSessionState(): ReviewSessionState {
	return {
		currentTurn: null,
		activeSession: null,
		turnDemand: null,
		tokensPerProblem: 1500,
		recordingMessageTokens: 0,
		callOutputLimit: null,
		lastEventAt: Date.now(),
		activeReview: null,
		currentTurnSlugs: [],
		activePractice: null,
		openingInContext: false,
		sessionGuards: { cutOff: 0, recordingCalls: 0, stored: 0 },
		answeredRefusals: new Set(),
		refusedItems: new Map(),
		repeatedCalls: new Map(),
	};
}

// A submit_result the model sent is counted when its turn finishes, which releases its mark; a call a
// codemode script made is not among that turn's results.
function answeredByTool(
	scope: ReviewSessionState,
	event: Extract<AgentSessionEvent, { type: "tool_execution_end" }>,
): boolean {
	return event.toolName === "submit_result" && event.parentToolCallId === undefined
		? scope.answeredRefusals.has(event.toolCallId)
		: scope.answeredRefusals.delete(event.toolCallId);
}

// The pinned assignment says whose work a result is about; a record's place in the work does not.
// Its developer id is an internal account id. Only a conversation capture records that same id, as each
// turn's accountId, which validateConversationAttribution also matches; other captures name provider
// accounts, so there the id is not shown and a role with several holders stays unidentified.
function assignmentText(inputs: PracticeInputs | undefined): string {
	if (inputs === undefined) {
		return "";
	}
	let subject = `This result is about the ${inputs.kind} item; its assignment names no person.`;
	if (inputs.scope.kind === "SCAN") {
		subject = "This scan judges the assigned file at its pinned commit; it is about no person.";
	} else if (inputs.kind === "chat.thread" && inputs.about !== null) {
		subject = `This result is about one participant of this chat.thread item, as the ${inputs.about.role} of their own turns: the turns in \`${CONVERSATION_THREAD}\` whose \`accountId\` is ${inputs.about.developerId}. Assess that participant's turns; the other turns are context.`;
	} else if (inputs.about?.role === "ASSIGNEE") {
		subject = `This result is about one ASSIGNEE of this ${inputs.kind} item; the role alone does not say which captured account that is.`;
	} else if (inputs.about !== null) {
		subject = `This result is about the ${inputs.about.role} of this ${inputs.kind} item.`;
	}
	return `## Assignment
${subject} Each captured record keeps the attribution it was captured with: its author, \`bot\` where the provider classified the account, or no author where none was captured. That attribution, not where a record appears, says who wrote it; the practice's criteria decide what it shows.

`;
}

export async function runReviewJob(): Promise<void> {
	const WORKSPACE_ROOT = "/workspace";
	const EVIDENCE_TOOLS = ["read", "grep", "find", "ls"] as const;
	// Codemode runs a script that calls the other tools, so a chain of reads and searches, or a large
	// result filtered down to what a criterion needs, costs one model call rather than one per step.
	const PRACTICE_TOOLS = [...EVIDENCE_TOOLS, "bash", "codemode"] as const;
	const CWD = process.env.PI_RUNNER_CWD ?? WORKSPACE_ROOT;
	const ENVELOPE_MISMATCH_EXIT = 42;
	const TASK_PATH = `${CWD}/task.json`;

	const taskEnvelope = readTaskEnvelope();
	const INPUT_PATHS = resolveTaskPaths(CWD, taskEnvelope.paths);
	const OUTPUT = `${CWD}/out`;
	const RESULT_PATH = outputPath(OUTPUT, "result.json");
	const REVIEW_STATE_PATH = outputPath(OUTPUT, "review-state.json");
	const WATCHDOG_PATH = outputPath(OUTPUT, "watchdog-killed.json");
	const USAGE_PATH = outputPath(OUTPUT, "usage.json");
	const RUNNER_DEBUG_PATH = outputPath(OUTPUT, "runner-debug.json");
	const PRACTICE_COVERAGE_PATH = outputPath(OUTPUT, "practice-coverage.json");
	const PRACTICE_SCRIPT_REPORT_PATH = outputPath(OUTPUT, "practice-scripts.json");
	const PRACTICE_SCRIPT_MODELS_PATH = `${CWD}/practice-script-models.json`;
	const REVIEW_INPUT_ROOT = "work/review-input";
	// Compaction can remove prior recordings from the model context; retain them on disk.
	const NOTES_PATH = `${CWD}/work/notes/review.md`;

	function positiveIntegerEnv(name: string): number {
		const text = requiredEnv(name);
		const value = Number(text);
		if (!Number.isInteger(value) || value <= 0) {
			throw new Error(`${name} env var must be a positive integer, got: ${text}`);
		}
		return value;
	}
	const AGENT_BUDGET_MS = positiveIntegerEnv("AGENT_BUDGET_MS");
	const AGENT_DIR = requiredEnv("PI_CODING_AGENT_DIR");
	const LLM_PROXY_URL = requiredEnv("LLM_PROXY_URL");
	const LLM_PROXY_TOKEN = requiredEnv("LLM_PROXY_TOKEN");
	const DIRECT_MODEL_TOKENS = positiveIntegerEnv("PI_DIRECT_MODEL_TOKENS");
	const PRACTICE_CONCURRENCY = positiveIntegerEnv("PI_PRACTICE_CONCURRENCY");
	if (PRACTICE_CONCURRENCY > 32) {
		throw new Error("PI_PRACTICE_CONCURRENCY must not exceed 32");
	}
	const PRACTICES_PER_TURN = hasText(process.env.PI_PRACTICE_BATCH_SIZE)
		? Number(process.env.PI_PRACTICE_BATCH_SIZE)
		: 6;
	// Refusal exhaustion leaves the practice NOT_REACHED.
	const MAX_REFUSALS_PER_PRACTICE = 8;
	// The server sets each practice's budget; turnBudget combines it for the assigned practices.
	const PER_PRACTICE_WORK: Work = {
		modelCalls: positiveIntegerEnv("PI_PRACTICE_MODEL_CALLS"),
		outputTokens: positiveIntegerEnv("PI_PRACTICE_OUTPUT_TOKENS"),
	};
	// Abort a stalled stream independently of the turn's work budget.
	const STALL_MS = 300_000;
	// Reaching the model's output limit can leave its tool arguments incomplete.
	let outputLimit = Number.POSITIVE_INFINITY;
	const CUT_OFF_ABORT = 2;

	const STALL_CHECK_MS = 1000;

	// The instant the watchdog below counts from. Every stage deadline starts later than this, so it is
	// the only reading against which the process budget means anything.
	const PROCESS_START_MS = Date.now();

	setTimeout(() => {
		console.error(`[pi-runner] Watchdog: ${AGENT_BUDGET_MS + 30_000}ms elapsed, hard-exiting`);
		// First, because finalizing removes from out/ whatever the session left there — which would
		// include the marker written next.
		finalizeOutputQuietly();
		try {
			writeFileSync(
				WATCHDOG_PATH,
				JSON.stringify({
					budgetMs: AGENT_BUDGET_MS,
					elapsedMs: AGENT_BUDGET_MS + 30_000,
					reason: "runtime exceeded budget + 30s grace, hard-killed by watchdog",
				}),
			);
		} catch {
			/* best-effort — already exiting */
		}
		process.exit(3);
	}, AGENT_BUDGET_MS + 30_000).unref();

	mkdirSync(OUTPUT, { recursive: true });

	function readPracticeIndex() {
		return practiceIndexSchema.parse(JSON.parse(readFileSync(INPUT_PATHS.practiceIndex, "utf8")));
	}

	const folderIndex: unknown = JSON.parse(readFileSync(INPUT_PATHS.manifest, "utf8"));
	const { availableSourceKinds, completeSourceKinds, sourceFileSources, citationCapabilities } =
		folderCitationIndex(folderIndex);
	const capturedTexts = citableTextIndex(folderIndex);
	const availableSourceKindValues = [...availableSourceKinds].toSorted();
	const practiceIndex = readPracticeIndex();

	/** The one declared set of a practice: its brief shows it, and its guard and citations follow it. */
	function declaredOf(slug: string | null): Set<string> {
		const practice = practiceIndex.find((entry) => entry.slug === slug);
		// A search that must be exhaustive over a source has to be able to read it.
		return new Set([...(practice?.readsSources ?? []), ...(practice?.exhaustiveSources ?? [])]);
	}

	function declaredSourcesOf(slug: string | null): DeclaredSources {
		return {
			readable: readableKinds(declaredOf(slug)),
			fileKinds: sourceFileSources,
			contextRoot: taskEnvelope.paths.contextRoot,
			repositoryRoot: taskEnvelope.paths.repositoryRoot,
		};
	}

	// ABSENT is sound only over sources the practice declares exhaustive.
	const practiceExhaustiveSources = new Map(
		practiceIndex.map((practice) => [practice.slug, new Set(practice.exhaustiveSources)]),
	);

	const usageTotals: UsageTotals = {
		model: null,
		inputTokens: 0,
		outputTokens: 0,
		reasoningTokens: 0,
		cacheReadTokens: 0,
		cacheWriteTokens: 0,
		costUsd: 0,
		totalCalls: 0,
	};

	const UNRATED_BY_STOP: Readonly<Record<StopReason, UnratedReason>> = {
		budget: "budget",
		safety: "deadline",
		stall: "unavailable",
		loop: "off-format",
	};

	const runnerDebug: {
		attempts: AttemptDebug[];
		turns: TurnTrace[];
		usageTotals: UsageTotals;
		practiceConcurrency?: { configured: number; effective: number };
		/** Each practice's required brief sources: sizes and read state only, never their text. */
		practiceSources: Record<string, RequiredSourceTrace[]>;
	} = {
		practiceSources: {},
		attempts: [],
		turns: [],
		usageTotals,
	};

	const sessionScopes = new AsyncLocalStorage<ReviewSessionState>();
	const rootSessionState = newSessionState();
	const activeScopes = new Set<ReviewSessionState>();
	const sessionState = () => sessionScopes.getStore() ?? rootSessionState;

	const TRACE_REASON_CHARS = 140;
	const reviewState = new Map<string, Candidate>();
	const executions = new Map<string, ProgramOutcome>();
	let unreachedReason: "deadline" | "budget" | "error" = "deadline";

	const assignedInputs = new Map<string, PracticeInputs>();
	const assignmentReceipts = new Map<string, readonly ReadReceipt[]>();
	const runtimeNonResults: { practiceSlug: string; reason: string }[] = [];
	function admissionRequest() {
		return {
			contractVersion: 3,
			nonResults: runtimeNonResults.toSorted((a, b) =>
				compareSlugs(a.practiceSlug, b.practiceSlug),
			),
			reviewId: taskEnvelope.reviewId,
			jobId: taskEnvelope.jobId,
			attempt: taskEnvelope.attempt,
			results: [...reviewState]
				.toSorted(([a], [b]) => compareSlugs(a, b))
				.map(([practiceSlug, candidate]) => {
					const assigned = assignedInputs.get(practiceSlug);
					if (assigned === undefined) {
						throw new Error(`no frozen assignment for ${practiceSlug}`);
					}
					return {
						practiceSlug,
						itemId: assigned.scope.item.id,
						itemVersion: assigned.scope.version,
						candidate,
						readReceipts: assignmentReceipts.get(practiceSlug) ?? [],
					};
				}),
		};
	}

	function persistUsage() {
		writeFileSync(USAGE_PATH, JSON.stringify(usageTotals, null, 2));
	}
	function persistRunnerDebug() {
		const turns = runnerDebug.turns.toSorted((a, b) =>
			a.practiceSlug !== undefined && b.practiceSlug !== undefined
				? compareSlugs(a.practiceSlug, b.practiceSlug)
				: 0,
		);
		writeFileSync(RUNNER_DEBUG_PATH, JSON.stringify({ ...runnerDebug, turns }, null, 2));
	}
	function persistReviewState() {
		writeFileSync(REVIEW_STATE_PATH, JSON.stringify(admissionRequest(), null, 2));
	}

	function maybeWriteResultFile(): boolean {
		if (reviewState.size === 0) {
			return false;
		}
		writeFileSync(RESULT_PATH, JSON.stringify(admissionRequest(), null, 2));
		return true;
	}

	function programOf(slug: string): PracticeProgram {
		const file = nodePath.join(INPUT_PATHS.practiceScripts, `${slug}.ts`);
		return existsSync(file)
			? { script: { path: file, source: readFileSync(file) } }
			: { script: null };
	}

	function persistExecutionReport(): void {
		// A practice cut off before its outcome was kept is reported not reached, which opened no session.
		const entries = practiceIndex.map((practice) => {
			const done = executions.get(practice.slug);
			return [
				practice.slug,
				done === undefined
					? notReachedOutcome(programOf(practice.slug), unreachedReason)
					: { ...done, reviewSessions: reviewSessionsOf(practice.slug) },
			] as const;
		});
		writeFileSync(PRACTICE_SCRIPT_REPORT_PATH, executionReport(entries));
	}

	// out/ is the sandbox's own claim, so the runner writes every file in it last, from memory, and
	// removes whatever else a session left there.
	function finalizeOutput(): void {
		const written = new Set<string>();
		const persist = (path: string, write: () => unknown) => {
			if (write() !== false) {
				written.add(path);
			}
		};
		persist(REVIEW_STATE_PATH, persistReviewState);
		persist(RESULT_PATH, maybeWriteResultFile);
		persist(USAGE_PATH, persistUsage);
		persist(RUNNER_DEBUG_PATH, persistRunnerDebug);
		if (practiceCoverageLedger !== null) {
			persist(PRACTICE_COVERAGE_PATH, persistPracticeCoverage);
		}
		persist(PRACTICE_SCRIPT_REPORT_PATH, persistExecutionReport);
		for (const entry of readdirSync(OUTPUT)) {
			const path = `${OUTPUT}/${entry}`;
			if (!written.has(path)) {
				rmSync(path, { recursive: true, force: true });
			}
		}
	}

	function hasPersistedReviewState(): boolean {
		return reviewState.size > 0;
	}

	function orchestratorWithPaths(text: string): string {
		const {
			contextRoot,
			repositoryRoot,
			manifest,
			practiceIndex: indexPath,
			preparedFeedback,
		} = taskEnvelope.paths;
		const paths: Record<string, string> = {
			contextRoot,
			repositoryRoot,
			manifest,
			practiceIndex: indexPath,
			practiceRoot: nodePath.dirname(indexPath),
			historyRoot: nodePath.dirname(preparedFeedback),
		};
		return text.replaceAll(
			/<(?<path>contextRoot|repositoryRoot|manifest|practiceIndex|practiceRoot|historyRoot)>/gu,
			(placeholder, key: string) => paths[key] ?? placeholder,
		);
	}

	function validateConversationAttribution(
		submission: { problems?: readonly Problem[] },
		assignment: PracticeInputs | undefined,
	): void {
		const thread = `${CWD}/${CONVERSATION_THREAD}`;
		if (assignment?.kind === "chat.thread" && assignment.about !== null && existsSync(thread)) {
			const turns = readFileSync(thread, "utf8");
			const { developerId } = assignment.about;
			if (
				(submission.problems ?? []).some(
					(problem) => !citesReviewedTurn(problem.citations, turns, developerId),
				)
			) {
				throw new Error(
					`each problem of a NOT_MET of this conversation review must cite a turn of the participant under review: quote a ` +
						`line inside a turn of ${CONVERSATION_THREAD} whose accountId equals about.developerId. The other turns are ` +
						"context, and a lapse that only they show is not this participant's",
				);
			}
		}
	}

	function citationSources(slug: string): CitationSources {
		const scan =
			assignedInputs.get(slug)?.scope.kind === "SCAN"
				? sourcesForPractice(slug, captureSources())
				: null;
		return {
			sourceFiles: sourceFileSources,
			capturedTexts,
			sandboxRoot: CWD,
			repositoryRoot: taskEnvelope.paths.repositoryRoot,
			headRevision:
				scan?.headRevision ??
				checkedOutCommit(INPUT_PATHS.repositoryRoot) ??
				readChange(INPUT_PATHS.contextRoot)?.head ??
				null,
			...(scan === null ? {} : { treeSourceFile: scan.treeSourceFile }),
		};
	}

	function validateCitationScope(citations: readonly Citation[], slug: string): void {
		const scope = assignedInputs.get(slug)?.scope;
		if (scope?.kind !== "SCAN") {
			return;
		}
		const head = citationSources(slug).headRevision;
		if (citations.some((citation) => citation.commit !== head)) {
			throw new Error("a scan citation must cite the pinned repository commit");
		}
		const contextPaths = practiceIndex.find((practice) => practice.slug === slug)?.contextPaths;
		const allowed = new Set([scope.item.path, ...(contextPaths ?? [])]);
		if (citations.some((citation) => !allowed.has(citation.path))) {
			throw new Error(
				`a scan citation is outside its frozen context. Correction example: read(${JSON.stringify({ path: scope.item.path })}), then cite the exact text from the assigned file.`,
			);
		}
	}

	function validateProblemClaims(problems: readonly Problem[], slug: string): void {
		const submission = { problems };
		validateDomainBasis(submission, assignedInputs.get(slug));
		validateChangeBasis(
			submission,
			existsSync(`${CWD}/${CHANGE_ROOT}/diff.patch`)
				? readFileSync(`${CWD}/${CHANGE_ROOT}/diff.patch`, "utf8")
				: "",
			readChange(INPUT_PATHS.contextRoot),
		);
		validateConversationAttribution(submission, assignedInputs.get(slug));
	}

	function validateProblemDraft(raw: unknown, slug: string): Problem & { key: string } {
		const notes: string[] = [];
		const problem = normalizeProblem(raw, notes, citationSources(slug));
		if (problem.key === undefined) {
			throw new Error("A local problem draft requires a stable key.");
		}
		validateCitationScope(problem.citations, slug);
		validateCitationSources(
			problem.citations,
			availableSourceKinds,
			sourceFileSources,
			`${taskEnvelope.paths.repositoryRoot.replace(/\/+$/u, "")}/`,
		);
		for (const citation of problem.citations) {
			verifySubmissionCitation(citation, notes);
		}
		validateProblemClaims([problem], slug);
		return { ...problem, key: problem.key };
	}

	function validateSubmission(
		raw: unknown,
		slug: string,
		measureCoverage: (citations: readonly Citation[]) => Coverage,
		reads: SessionReads,
	): Validated {
		const notes: string[] = [];
		const submission = normalizeSubmission(
			raw,
			notes,
			(sourceFile) => sourceFileSources.get(sourceFile),
			citationSources(slug),
		);
		const citations = submissionCitations(submission);
		validateCitationScope(citations, slug);

		try {
			validateEvidenceSources(
				submission,
				availableSourceKinds,
				sourceFileSources,
				`${taskEnvelope.paths.repositoryRoot.replace(/\/+$/u, "")}/`,
			);
		} catch (error) {
			throw new Error(
				`${errorText(error)}. Correction example: read(${JSON.stringify({ path: taskEnvelope.paths.manifest })}), then cite a captured source listed there.`,
				{ cause: error },
			);
		}
		validateSearchScope(
			submission,
			practiceExhaustiveSources.get(slug) ?? new Set(),
			availableSourceKinds,
		);
		validateInapplicabilityScope(submission, availableSourceKinds);
		const assignment = assignedInputs.get(slug);
		const scan = assignment?.scope.kind === "SCAN";

		if (
			(submission.outcome === "DOES_NOT_APPLY" || submission.outcome === "COULD_NOT_DETERMINE") &&
			!scan &&
			availableSourceKinds.has(DIFF_SOURCE) &&
			practiceIndex
				.find((practice) => practice.slug === slug)
				?.readsSources.includes(DIFF_SOURCE) === true &&
			!reads.hasRead(`${CHANGE_ROOT}/diff.patch`)
		) {
			throw new Error(
				`a result that decides nothing must show it read the change: content of ${CHANGE_ROOT}/diff.patch must be in this session's opening or read in this session. Call read(${JSON.stringify({ path: `${CHANGE_ROOT}/diff.patch` })}), judge from what it returns, then submit again. ` +
					`Another practice's reads and a list of consulted sources cannot establish this session's access.${reads.bashNote(
						[`${CHANGE_ROOT}/diff.patch`],
					)}`,
			);
		}
		for (const citation of citations) {
			verifySubmissionCitation(citation, notes);
		}
		validateProblemClaims(submission.problems ?? [], slug);
		const coverage = measureCoverage(citations);
		// A scan's search covers its assigned file, which it must read whole.
		const unread = scan
			? undefined
			: unreadCheckoutClaim(submission, coverage, {
					checkout: citationCapabilities.has(TREE_SOURCE)
						? {
								root: existsSync(`${INPUT_PATHS.repositoryRoot}/.git`)
									? INPUT_PATHS.repositoryRoot
									: undefined,
								complete: completeSourceKinds.has(TREE_SOURCE),
							}
						: undefined,
					ask: !askedAboutCheckout.has(slug),
				});
		if (unread !== undefined) {
			askedAboutCheckout.add(slug);
			throw new UnreadEvidenceError(unread.text);
		}
		const parsed = parseCandidate({ ...submission, coverage });
		if (!parsed.ok) {
			if (parsed.reasonCode === "met_coverage_incomplete") {
				throw new UnreadEvidenceError(incompleteCoverageText(coverage, reads, scan));
			}
			throw new Error(`${parsed.reasonCode}: ${parsed.message}`);
		}
		return { candidate: parsed.candidate, notes };
	}

	function verifySubmissionCitation(citation: Citation, notes: string[]): void {
		const slug = sessionState().activeReview?.slug;
		const preparedInput = slug === undefined ? null : `${REVIEW_INPUT_ROOT}/${slug}.json`;
		if (
			preparedInput !== null &&
			citation.path === preparedInput &&
			existsSync(`${CWD}/${preparedInput}`)
		) {
			throw new Error(
				`${citation.path} is generated practice preparation, not captured evidence. Read the captured source paths in the review brief, or read(${JSON.stringify({ path: taskEnvelope.paths.manifest })}), then cite a captured source listed there.`,
			);
		}
		if (citation.sourceKind !== DIFF_SOURCE) {
			requireCitationCapabilities(citation);
		}
		if (citation.sourceKind === TREE_SOURCE && citation.path.startsWith(".git/")) {
			const recordPath = [...sourceFileSources.keys()].find((path) =>
				path.endsWith("/commits.json"),
			);
			const commit =
				citation.commit ?? /commit (?<sha>[0-9a-f]{40,64})/u.exec(citation.quote)?.groups?.sha;
			const text =
				recordPath === undefined ? null : readFileSync(nodePath.resolve(CWD, recordPath), "utf8");
			const line =
				commit === undefined
					? -1
					: (text?.split("\n").findIndex((textLine) => textLine.includes(commit)) ?? -1);
			const example =
				recordPath !== undefined && commit !== undefined && line >= 0
					? `Corrected citation example: ${JSON.stringify({ path: recordPath, startLine: line + 1, quote: commit })}. ` +
						"Cite the captured commit record for commit facts. Reassess your claim against that record."
					: `Read the captured commit record, for example read(${JSON.stringify({ path: recordPath ?? `${taskEnvelope.paths.contextRoot}/commits.json` })}), then quote its exact text.`;
			throw new Error(
				`A Git log is not the content of ${citation.path}. The captured HEAD identifies the tree. It is not a repository file that can quote Git history. ${example}`,
			);
		}
		// Match admission: repository quotes use Git blobs; change quotes use the derived diff.
		const registered = citation.sourceKind === DIFF_SOURCE ? null : capturedText(citation);
		const content = registered ?? citedContent(citation);
		const resolved =
			registered === null
				? resolveCited(citation, content)
				: resolveCapturedText(citation, registered);
		if ("mismatch" in resolved) {
			throw new Error(
				`citation does not match ${citation.path}:${citation.startLine}-${citation.endLine} ` +
					`(${citation.side ?? "text"}) in '${citation.sourcePath}': ${resolved.mismatch}. ${citationCorrectionExample(citation, typeof content === "string" ? content : null)}`,
			);
		}
		const cited = `${citation.path}:${citation.startLine}-${citation.endLine}`;
		if (!citation.quote.trim()) {
			notes.push(`${cited} recorded ${excerptOf(resolved.quote)}`);
		}
		if (resolved.path !== undefined && resolved.side !== undefined) {
			citation.path = resolved.path;
			citation.side = resolved.side;
		}
		if (resolved.startLine !== undefined && resolved.endLine !== undefined) {
			notes.push(
				`${cited} does not hold that text; it is at ${citation.path}:${resolved.startLine}-${resolved.endLine}${resolved.side === undefined ? "" : ` (${resolved.side})`}, recorded there`,
			);
			citation.startLine = resolved.startLine;
			citation.endLine = resolved.endLine;
		}
		citation.quote = resolved.quote;
		if (citation.sourceKind === DIFF_SOURCE) {
			requireCitationCapabilities(citation);
		}
	}

	function requireCitationCapabilities(citation: Citation): void {
		const flags = citationCapabilities.get(citation.sourceKind);
		if (flags === undefined) {
			throw new Error(
				`the source catalog declares no citation capabilities for ${citation.sourceKind}`,
			);
		}
		const capabilityError = validateCitationCapabilities(citation, flags);
		if (capabilityError !== undefined) {
			const { side: _side, commit: _commit, ...location } = citation;
			const corrected = {
				...location,
				...(flags.side !== "FORBIDDEN" && citation.side !== undefined
					? { side: citation.side }
					: {}),
				...(flags.commit !== "FORBIDDEN" && citation.commit !== undefined
					? { commit: citation.commit }
					: {}),
			};
			throw new Error(
				`${capabilityError} for ${citation.sourcePath}. Corrected citation example: ${JSON.stringify(corrected)}.`,
			);
		}
	}

	// Git classifies a file as binary when its first 8000 bytes contain NUL.
	const BINARY = Symbol("binary");

	// A model cannot know which captured commit holds a text, so an omitted commit means the reviewed version of its side.
	function reviewedVersion(citation: Citation, matches: readonly CitableText[]): CitableText {
		const change = readChange(INPUT_PATHS.contextRoot);
		const reviewed = (citation.side ?? "NEW") === "OLD" ? change?.base : change?.head;
		const pinned =
			citation.commit === undefined ? matches.filter((entry) => entry.commit === reviewed) : [];
		const [only] = pinned;
		if (pinned.length === 1 && only !== undefined) {
			return only;
		}
		const commits = [...new Set(matches.map((entry) => entry.commit ?? "none"))];
		throw new Error(
			`citation names text captured at several commits (${commits.join(", ")}); add the commit you read. Corrected citation example: ${JSON.stringify({ ...citation, commit: commits[0] })}.`,
		);
	}

	function capturedText(citation: Citation): string | null {
		const matches = capturedTexts.filter(
			(entry) =>
				entry.source === citation.sourceKind &&
				entry.path === citation.path &&
				entry.side === (citation.side ?? "NEW") &&
				(citation.commit === undefined || entry.commit === citation.commit),
		);
		if (matches.length === 0) {
			return null;
		}
		const proof = matches.length === 1 ? matches[0] : reviewedVersion(citation, matches);
		if (proof === undefined) {
			throw new Error("the captured text proof is absent");
		}
		const bytes = readFileSync(nodePath.resolve(CWD, proof.stagedPath));
		if (createHash("sha256").update(bytes).digest("hex") !== proof.sha256) {
			throw new Error("the captured text digest changed");
		}
		return new TextDecoder("utf-8", { fatal: true, ignoreBOM: true }).decode(bytes);
	}

	function citedContent(citation: Citation): string | typeof BINARY | null {
		if (citation.sourceKind === TREE_SOURCE) {
			if (citation.commit === undefined) {
				return readCheckoutFile(citation.path, citationRepository(citation));
			}
			const blob = readPinnedBlob(
				citationRepository(citation),
				citation.commit,
				citation.path,
				64 * 1024 * 1024,
			);
			return blob.kind === "regular" ? asText(blob.bytes) : null;
		}
		if (citation.sourceKind === DIFF_SOURCE) {
			return readFileSync(`${CWD}/${CHANGE_ROOT}/diff.patch`, "utf8");
		}
		return readFileSync(`${CWD}/${citation.sourcePath}`, "utf8");
	}

	function resolveCited(
		citation: Citation,
		content: string | typeof BINARY | null,
	): ReturnType<typeof resolveOnEitherSide> {
		if (content === null) {
			return {
				mismatch:
					citation.commit === undefined
						? "no such file in the checkout"
						: `no such file at commit ${citation.commit} in the checkout's history`,
			};
		}
		if (content === BINARY) {
			return {
				mismatch:
					`that file is binary and has no lines to quote; cite the commit that adds it in ` +
					`${taskEnvelope.paths.contextRoot}/commits.json, or the text that embeds it`,
			};
		}
		return resolveOnEitherSide(citation, content);
	}

	// Binary files cannot supply verifiable quotation text.
	function asText(bytes: Buffer): string | typeof BINARY {
		return bytes.subarray(0, 8000).includes(0) ? BINARY : bytes.toString("utf8");
	}

	function citationRepository(citation: Citation): string {
		const match = /^repos\/(?<repository>[A-Za-z0-9_-]+)\/\.git\/HEAD$/u.exec(citation.sourcePath);
		if (!match?.groups || typeof match.groups.repository !== "string") {
			throw new Error("Repository citation requires a captured repository HEAD");
		}
		return nodePath.resolve(CWD, "repos", match.groups.repository);
	}

	function readCheckoutFile(path: string, repository: string): string | typeof BINARY | null {
		const file = nodePath.resolve(repository, path);
		if (!file.startsWith(`${repository}/`)) {
			return null;
		}
		try {
			return asText(readFileSync(file));
		} catch {
			return null;
		}
	}

	let measurementClosed = false;

	const MAX_RECORDING_ATTEMPTS_PER_TURN = 24;

	const REPEATED_CALL_NUDGE = 3;
	const REPEATED_CALL_ABORT = 6;
	// Recording answers depend on their arguments; repeating an identical call cannot resolve a refusal.
	const REPEATED_RECORDING_NUDGE = 2;
	const REPEATED_RECORDING_ABORT = 3;

	// SDK error events can repeat a refusal already logged by the tool.

	async function refusal<T>(toolCallId: string, text: string): Promise<AgentToolResult<T>> {
		sessionState().answeredRefusals.add(toolCallId);
		throw new Error(text);
	}

	const TOOL_ERROR_CHARS = 240;

	// SDK error headings can be followed by the refused content; retain only a bounded reason.
	function practiceLogLabel(): string {
		const slug = sessionState().currentTurn?.practiceSlug;
		return slug === undefined ? "review" : `review ${slug}`;
	}

	function firstLine(text: string): string {
		const line = text.replaceAll(/\s+/gu, " ").trim() || "(no reason given)";
		return line.length > TOOL_ERROR_CHARS ? `${line.slice(0, TOOL_ERROR_CHARS)}…` : line;
	}

	// End budget-exhausted turns between model calls so recording tools from the last call can finish.
	function chargeWork(event: AgentSessionEvent): void {
		const state = sessionState();
		state.lastEventAt = Date.now();
		const turn = state.currentTurn;
		if (event.type === "message_end" && event.message.role === "assistant") {
			const output = outputTokensOf(event.message);
			state.recordingMessageTokens = listOrEmpty(event.message.content).some(
				(c) => c.type === "toolCall" && c.name === "submit_result",
			)
				? output
				: 0;
			if (!turn || !state.turnDemand) {
				return;
			}
			turn.calls += 1;
			turn.outputTokens += output;
			noteCutOff(
				turn,
				event.message.stopReason === "length" || output >= (state.callOutputLimit ?? outputLimit),
			);
			const used: Work = { modelCalls: turn.calls, outputTokens: turn.outputTokens };
			if (
				!turn.askedToRecord &&
				turn.stoppedBy === null &&
				shouldRecordNow(used, turn.budget, state.turnDemand.owed(), state.tokensPerProblem)
			) {
				turn.askedToRecord = true;
				if (state.activeReview !== null) {
					state.activeReview.requireSubmission = true;
				}
				console.error(
					`[pi-runner] ${turn.label}: ${turn.calls} of ${turn.budget.modelCalls} calls and ` +
						`${turn.outputTokens} of ${turn.budget.outputTokens} output tokens spent — nudging to record`,
				);
				if (state.activeSession) {
					void steer(state.activeSession, state.turnDemand.nudge);
				}
			}
			return;
		}
		if (event.type === "tool_execution_end" && event.toolName === "submit_result") {
			const written = problemsIn(event.result);
			if (written > 0 && state.recordingMessageTokens > 0) {
				state.tokensPerProblem = Math.round(
					(state.tokensPerProblem + state.recordingMessageTokens / written) / 2,
				);
			}
			state.recordingMessageTokens = 0;
			return;
		}
		if (
			event.type === "turn_end" &&
			turn?.stoppedBy === null &&
			spent({ modelCalls: turn.calls, outputTokens: turn.outputTokens }, turn.budget)
		) {
			stopTurn(
				"budget",
				`work budget spent (${turn.calls} calls, ${turn.outputTokens} output tokens) — ending this turn`,
			);
		}
	}

	// A cutoff can produce invalid tool arguments; identify truncation before retrying.
	function noteCutOff(turn: TurnTrace, cutOff: boolean): void {
		const state = sessionState();
		const limit = state.callOutputLimit ?? outputLimit;
		if (!cutOff) {
			return;
		}
		turn.cutOff += 1;
		state.sessionGuards.cutOff += 1;
		if (state.sessionGuards.cutOff >= CUT_OFF_ABORT) {
			stopTurn(
				"loop",
				`${state.sessionGuards.cutOff} calls in this session ran into the ${limit}-token output limit — aborting this turn`,
			);
			return;
		}
		console.error(
			`[pi-runner] ${turn.label}: a call ran into the output limit — telling the model`,
		);
		if (state.activeSession) {
			void steer(
				state.activeSession,
				`Your last call was cut off at the ${limit}-token output limit, so its tool received it ` +
					"incomplete and failed: the error it reports is the cut, not your syntax. Send a short call " +
					"instead — a search with a pattern rather than a list of every value. Keep each recording " +
					"call complete.",
			);
		}
	}

	function stopTurn(reason: StopReason, why: string): void {
		const state = sessionState();
		const turn = state.currentTurn;
		console.error(`[pi-runner] ${turn?.label ?? "review"}: ${why}`);
		if (turn && turn.stoppedBy === null) {
			turn.stoppedBy = reason;
		}
		if (state.activeSession) {
			void abortSession(state.activeSession);
		}
	}

	const refusals = new Map<string, number>();
	// Practices already asked once about a claim that the checkout lacks something; admission withholds a repeat.
	const askedAboutCheckout = new Set<string>();
	// Per practice, across its review sessions: the execution report carries these.
	const reviewSessions = new Map<
		string,
		{ usage: ReturnType<typeof newUsageLedger>; evidenceRefusals: number; formatRefusals: number }
	>();
	function sessionFacts(slug: string) {
		let facts = reviewSessions.get(slug);
		if (facts === undefined) {
			facts = { usage: newUsageLedger(), evidenceRefusals: 0, formatRefusals: 0 };
			reviewSessions.set(slug, facts);
		}
		return facts;
	}
	/** The SDK refused a recording call against the tool's schema before the runner saw it. */
	function noteSchemaRefusal(scope: ReviewSessionState, toolName: string): void {
		if (toolName === "submit_result" && scope.activeReview !== null) {
			sessionFacts(scope.activeReview.slug).formatRefusals += 1;
		}
	}
	function reviewSessionsOf(slug: string): ReviewSessionFacts {
		const facts = reviewSessions.get(slug);
		return {
			modelCalls: facts?.usage.totalCalls ?? 0,
			evidenceRefusals: facts?.evidenceRefusals ?? 0,
			formatRefusals: facts?.formatRefusals ?? 0,
		};
	}
	// A blocked practice without a draft stays not reached.
	const blockedPractices = new Map<string, UnratedReason>();

	function refusalLimited(slug: string): boolean {
		return (refusals.get(slug) ?? 0) >= MAX_REFUSALS_PER_PRACTICE;
	}

	function countRefusal(slug: string): void {
		const count = (refusals.get(slug) ?? 0) + 1;
		refusals.set(slug, count);
		if (count >= MAX_REFUSALS_PER_PRACTICE) {
			blockedPractices.set(slug, "off-format");
		}
	}

	// Identical refused submissions retain their answer within a turn.

	const IDENTICAL_REFUSAL_ABORT = 2;

	function record(raw: unknown): Recorded {
		const review = sessionState().activeReview;
		if (review === null) {
			return {
				kind: "refused",
				slug: "unknown",
				reason: "no review of a practice is open in this session; nothing is accepted",
			};
		}
		const { slug } = review;
		if (blockedPractices.has(slug)) {
			return {
				kind: "refused",
				slug,
				reason: refusalLimited(slug)
					? `${MAX_REFUSALS_PER_PRACTICE} submissions for '${slug}' were refused; no more are accepted for it.`
					: `this review asks no more about '${slug}'; no more submissions are accepted for it.`,
			};
		}
		const sent = JSON.stringify(raw);
		const earlier = sessionState().refusedItems.get(sent);
		if (earlier !== undefined) {
			earlier.resent += 1;
			countRefusal(slug);
			if (earlier.resent >= IDENTICAL_REFUSAL_ABORT) {
				stopTurn(
					"loop",
					`the same refused ${slug} result sent ${earlier.resent + 1} times — aborting this turn`,
				);
			}
			return {
				kind: "refused",
				slug,
				reason:
					`identical to a result this turn already refused, so the answer is the same: ${earlier.reason}. ` +
					"Change what that names",
			};
		}
		let validated: Validated;
		try {
			validated = validateSubmission(
				raw,
				slug,
				(citations) => {
					const citedPaths = citations.flatMap((citation) => {
						// Governed scan reads already carry a pinned, complete file receipt.
						if (
							review.reader?.readReceipts.some(
								(receipt) =>
									receipt.status === "COMPLETE" &&
									receipt.sourceKind === citation.sourceKind &&
									receipt.sourcePath === citation.sourcePath &&
									receipt.path === citation.path &&
									(citation.commit === undefined || receipt.version === citation.commit),
							) === true
						) {
							return [];
						}
						if (assignedInputs.get(slug)?.scope.kind === "SCAN") {
							return [citation.path];
						}
						if (citation.sourceKind === DIFF_SOURCE) {
							return [`${CHANGE_ROOT}/diff.patch`];
						}
						const matches = capturedTexts.filter(
							(entry) =>
								entry.source === citation.sourceKind &&
								entry.path === citation.path &&
								entry.side === (citation.side ?? "NEW") &&
								(citation.commit === undefined || entry.commit === citation.commit),
						);
						const proof = matches.length > 1 ? reviewedVersion(citation, matches) : matches[0];
						const sourcePath =
							citation.sourceKind === TREE_SOURCE
								? nodePath.relative(
										CWD,
										nodePath.resolve(citationRepository(citation), citation.path),
									)
								: citation.sourcePath;
						// The checkout can cover a captured copy only when the complete bytes match.
						if (
							citation.sourceKind === TREE_SOURCE &&
							proof !== undefined &&
							review.reads.hasCompleteRead(sourcePath) &&
							createHash("sha256")
								.update(readFileSync(nodePath.resolve(CWD, sourcePath)))
								.digest("hex") === proof.sha256
						) {
							return [sourcePath];
						}
						return [proof?.stagedPath ?? sourcePath];
					});
					return review.reads.coverage(review.reader, review.domainReads, citedPaths);
				},
				review.reads,
			);
		} catch (error) {
			countRefusal(slug);
			const reason = errorText(error);
			// A later source read can settle coverage without changing the submitted result.
			if (error instanceof UnreadEvidenceError) {
				sessionFacts(slug).evidenceRefusals += 1;
			} else {
				sessionState().refusedItems.set(sent, { reason, resent: 0 });
			}
			return { kind: "refused", slug, reason };
		}
		const { candidate, notes } = validated;
		const { readable } = declaredSourcesOf(slug);
		const undeclared = undeclaredCitedKinds(candidate, readable);
		if (undeclared.length > 0) {
			countRefusal(slug);
			const reason = `the result cites or consulted ${undeclared.map((kind) => `\`${kind}\``).join(", ")}, which ${slug} does not declare. Cite only: ${[...readable].toSorted().join(", ")}.`;
			sessionState().refusedItems.set(sent, { reason, resent: 0 });
			return { kind: "refused", slug, reason };
		}
		const previous = review.draft;
		if (previous !== null && isDeepStrictEqual(previous, candidate)) {
			return { kind: "duplicate", slug };
		}
		review.draft = candidate;
		return {
			kind: previous === null ? "stored" : "revised",
			slug,
			negative: candidate.outcome === "NOT_MET",
			filled: notes,
		};
	}

	function persistRecordedNotes(): void {
		try {
			mkdirSync(nodePath.dirname(NOTES_PATH), { recursive: true });
			writeFileSync(NOTES_PATH, `# Current results\n\n${recordedSoFar()}\n`);
		} catch (error) {
			console.error(`[pi-runner] notes could not be written: ${errorText(error)}`);
		}
	}

	function recordedSoFar(only?: readonly string[]): string {
		const results = new Map(reviewState);
		for (const scope of [rootSessionState, ...activeScopes]) {
			const review = scope.activeReview;
			if (review?.draft != null) {
				results.set(review.slug, review.draft);
			}
		}
		const shown = [...results]
			.toSorted(([a], [b]) => compareSlugs(a, b))
			.filter(([slug]) => only === undefined || only.includes(slug));
		if (shown.length === 0) {
			return "Nothing recorded yet.";
		}
		return shown
			.map(([slug, candidate]) => {
				const cited = [
					...new Set(
						[
							...candidate.citations,
							...(candidate.outcome === "NOT_MET"
								? candidate.problems.flatMap((problem) => problem.citations)
								: []),
						].map((citation) => citation.path),
					),
				];
				return `- ${slug}: ${candidate.outcome} — ${candidate.summary} (cites ${cited.join(", ")})`;
			})
			.join("\n");
	}

	const MAX_REFUSAL_LOG_CHARS = 500;

	function logRefusal(slug: string, reason: string): void {
		const state = sessionState();
		const line = `[pi-runner] result refused for ${slug}: ${reason}`;
		console.error(
			line.length > MAX_REFUSAL_LOG_CHARS ? `${line.slice(0, MAX_REFUSAL_LOG_CHARS)}…` : line,
		);
		if (state.currentTurn) {
			state.currentTurn.refused += 1;
			state.currentTurn.refusalReasons.push(
				reason.length > TRACE_REASON_CHARS ? `${reason.slice(0, TRACE_REASON_CHARS)}…` : reason,
			);
		}
	}

	function owedPractices(slugs: readonly string[] = sessionState().currentTurnSlugs): string[] {
		const review = sessionState().activeReview;
		return review === null || review.draft !== null || blockedPractices.has(review.slug)
			? []
			: slugs.filter((slug) => slug === review.slug);
	}

	function requireOpenReview(expected: ActiveReview): ActiveReview {
		const review = sessionState().activeReview;
		if (measurementClosed || review === null || review !== expected) {
			throw new Error("Measurement is closed; no local result or problem draft can change.");
		}
		return review;
	}

	function buildReviewResultTools(review: ActiveReview) {
		return buildResultTools({
			sourceKinds: availableSourceKindValues,
			contextRoot: taskEnvelope.paths.contextRoot,
			drafts: () => requireOpenReview(review).problems,
			validateProblem: (raw) => validateProblemDraft(raw, requireOpenReview(review).slug),
			submit: async (toolCallId, params): Promise<AgentToolResult<SubmitResultDetails>> => {
				const state = sessionState();
				requireOpenReview(review);
				if (!isRecord(params) || Array.isArray(params)) {
					const reason = "the arguments must be one result object";
					logRefusal("(not an object)", reason);
					return refusal(toolCallId, `result refused — ${reason}. Nothing changed.`);
				}
				const outcome = record(params);
				const stored = outcome.kind === "stored" || outcome.kind === "revised" ? 1 : 0;
				if (outcome.kind === "refused") {
					logRefusal(outcome.slug, outcome.reason);
				}
				if (state.currentTurn) {
					state.currentTurn.stored += stored;
					state.sessionGuards.stored += stored;
				}
				if (stored > 0) {
					persistRecordedNotes();
				}
				const head = `${outcome.slug}:`;
				let text: string;
				if (outcome.kind === "stored" || outcome.kind === "revised") {
					text = `${head} ${outcome.kind}${outcome.negative ? " (negative)" : ""}. This is a local result awaiting server admission.${outcome.filled.map((line) => `\n   ${line}`).join("")}`;
				} else if (outcome.kind === "duplicate") {
					text = `${head} already submitted; this result changed nothing, so do not send it again.`;
				} else {
					text = `${head} refused — ${outcome.reason}`;
				}
				if (blockedPractices.has(outcome.slug) && refusalLimited(outcome.slug)) {
					text += `\nNo longer accepted (refusal limit): ${outcome.slug}.`;
				}
				const details: SubmitResultDetails = {
					inserted: outcome.kind === "stored" ? 1 : 0,
					revised: outcome.kind === "revised" ? 1 : 0,
					duplicates: outcome.kind === "duplicate" ? 1 : 0,
					refused: outcome.kind === "refused" ? 1 : 0,
				};
				if (stored === 0) {
					return refusal(toolCallId, text);
				}
				return { content: [{ type: "text", text }], details, terminate: true };
			},
		});
	}

	function accumulateUsage(report: UsageReport): void {
		usageTotals.model = report.model ?? usageTotals.model;
		usageTotals.inputTokens += report.inputTokens;
		usageTotals.outputTokens += report.outputTokens;
		usageTotals.reasoningTokens += report.reasoningTokens;
		usageTotals.cacheReadTokens += report.cacheReadTokens;
		usageTotals.cacheWriteTokens += report.cacheWriteTokens;
		usageTotals.costUsd += report.costUsd;
		usageTotals.totalCalls += report.totalCalls;
	}

	function deriveChangeView(): void {
		const change = readChange(INPUT_PATHS.contextRoot);
		if (change !== null) {
			writeChangeView(
				CWD,
				INPUT_PATHS.repositoryRoot,
				change,
				readDescription(INPUT_PATHS.contextRoot),
			);
		}
	}

	function capturedChange(): { base: string; head: string; files: Map<string, DiffFile> } | null {
		const change = readChange(INPUT_PATHS.contextRoot);
		const view = `${CWD}/${CHANGE_ROOT}/diff.patch`;
		if (change === null || !existsSync(view)) {
			return null;
		}
		return { ...change, files: parseDiff(readFileSync(view, "utf8")) };
	}

	function captureSources(): EvidenceSources {
		const repository = INPUT_PATHS.repositoryRoot;
		const checkout = existsSync(`${repository}/.git`) ? repository : null;
		const change = capturedChange();
		return {
			repo: checkout,
			headRevision: change?.head ?? (checkout === null ? null : checkedOutCommit(checkout)),
			treeSourceFile: `${taskEnvelope.paths.repositoryRoot}/.git/HEAD`,
			contextDir: existsSync(INPUT_PATHS.contextRoot) ? INPUT_PATHS.contextRoot : null,
			contextReference: taskEnvelope.paths.contextRoot,
			change,
			sourceKindOf: (sourcePath) => sourceFileSources.get(sourcePath),
		};
	}

	function sourcesForPractice(slug: string, sources: EvidenceSources): EvidenceSources {
		const inputs = assignedInputs.get(slug);
		if (inputs?.scope.kind !== "SCAN") {
			return sources;
		}
		const contextPaths = practiceIndex.find((practice) => practice.slug === slug)?.contextPaths;
		const assigned = inputs.scope.item.path;
		if (contextPaths === undefined || assigned === undefined) {
			throw new UnratedError("unavailable", "scan context grants were not captured");
		}
		const treeSourceFile = `${taskEnvelope.paths.contextRoot}/item.json`;
		if (sourceFileSources.get(treeSourceFile) !== TREE_SOURCE) {
			throw new UnratedError("unavailable", "the scan has no captured item source witness");
		}
		const capturedItem = fileRecordSchema.parse(
			JSON.parse(readFileSync(`${INPUT_PATHS.contextRoot}/item.json`, "utf8")),
		);
		const assignedItem = fileRecordSchema.parse(inputs.item);
		if (
			!safeRepositoryPath(assigned) ||
			capturedItem.path !== assigned ||
			!isDeepStrictEqual(capturedItem, assignedItem)
		) {
			throw new UnratedError("unavailable", "the captured scan item contradicts its assignment");
		}
		return {
			...sources,
			repo: INPUT_PATHS.repositoryRoot,
			headRevision: capturedItem.commitId,
			treeSourceFile,
			change: null,
			permittedPaths: new Set([assigned, ...contextPaths]),
		};
	}

	/** The captured sources, other than the tree, that the practice declares in its reads; as a review session requires them. */
	function requiredSourcesOf(slug: string): RequiredSource[] {
		if (assignedInputs.get(slug)?.scope.kind === "SCAN") {
			return [];
		}
		return requiredSources(
			practiceIndex.find((practice) => practice.slug === slug)?.readsSources ?? [],
			sourceFileSources,
		);
	}

	function jobFiles(inputs: PracticeInputs, evidence: EvidenceSources): JobFiles | null {
		if (inputs.scope.kind === "SCAN") {
			return evidence.headRevision === null
				? null
				: { head: { commit: evidence.headRevision }, change: null };
		}
		const { kind } = inputs;
		const sources = capturedChange();
		const repository = INPUT_PATHS.repositoryRoot;
		const head =
			sources?.head ?? (existsSync(`${repository}/.git`) ? checkedOutCommit(repository) : null);
		if (
			!["scm.pull_request", "scm.pull_request_review", "scm.file"].includes(kind) ||
			head === null
		) {
			return null;
		}
		return { head: { commit: head }, change: sources };
	}

	function readBoundModels(): BoundModels {
		if (!existsSync(PRACTICE_SCRIPT_MODELS_PATH)) {
			return {};
		}
		try {
			return boundModelsSchema.parse(JSON.parse(readFileSync(PRACTICE_SCRIPT_MODELS_PATH, "utf8")));
		} catch (error) {
			console.error(
				`[pi-runner] the bound models do not parse, so none is bound: ${errorText(error)}`,
			);
			return {};
		}
	}

	function loadPracticeSlugs(): string[] {
		return practiceIndex.map((practice) => practice.slug).filter(Boolean);
	}

	let practiceCoverageLedger: PracticeCoverageLedger | null = null;

	function persistPracticeCoverage() {
		if (practiceCoverageLedger === null) {
			throw new Error("practice coverage ledger is not initialized");
		}
		return practiceCoverageLedger.markEvaluated([...reviewState.keys()]);
	}

	function logPracticeCoverage() {
		const coverage = persistPracticeCoverage();
		const ratio =
			coverage.eligible === 0 ? "n/a" : (coverage.evaluated / coverage.eligible).toFixed(4);
		console.error(
			`[pi-runner] Practice coverage: evaluated=${coverage.evaluated}, eligible=${coverage.eligible}, ratio=${ratio}`,
		);
	}

	const PERSIST_DISCIPLINE =
		`Submit the outcome the evidence supports, MET, NOT_MET, DOES_NOT_APPLY, or COULD_NOT_DETERMINE; there is no quota. ` +
		`Use tools only from this point onward; no planning prose.`;

	const RECORD_NUDGE = `This turn has the work left to write its result and no more. Stop exploring. Submit what the inspected evidence supports for this session's practice with submit_result. ${PERSIST_DISCIPLINE}`;

	// The server interprets exit 75 as unreachable admission and retries the review.
	const SERVER_UNREACHABLE_EXIT = 75;
	// The server interprets exit 76 as provider failure without a recorded result and retries the review.
	const PROVIDER_UNREACHABLE_EXIT = 76;

	function readTaskEnvelope(): TaskEnvelope {
		let raw: string;
		try {
			raw = readFileSync(TASK_PATH, "utf8");
		} catch (error) {
			console.error(`[pi-runner] Failed to read ${TASK_PATH}: ${errorText(error)}`);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}
		let parsed: unknown;
		try {
			parsed = JSON.parse(raw);
		} catch (error) {
			console.error(`[pi-runner] Failed to parse ${TASK_PATH}: ${errorText(error)}`);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}
		const envelope: Record<string, unknown> = isRecord(parsed) ? parsed : {};
		if (envelope.schemaVersion !== SUPPORTED_SCHEMA_VERSION) {
			console.error(
				`[pi-runner] Unsupported schemaVersion: got ${logValue(envelope.schemaVersion)}, expected ${SUPPORTED_SCHEMA_VERSION}. ` +
					`Task envelope and staged runner disagree.`,
			);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}
		if (
			typeof envelope.reviewId !== "string" ||
			typeof envelope.jobId !== "string" ||
			!runtimeIdSchema.safeParse(envelope.reviewId).success ||
			!runtimeIdSchema.safeParse(envelope.jobId).success ||
			typeof envelope.attempt !== "number" ||
			!Number.isSafeInteger(envelope.attempt) ||
			envelope.attempt < 0 ||
			!isDecimalId(envelope.workspaceId)
		) {
			console.error(
				"[pi-runner] task requires reviewId/jobId UUIDs, a nonnegative attempt and a decimal workspaceId",
			);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}

		if (typeof envelope.prompt !== "string" || envelope.prompt.trim() === "") {
			console.error(`[pi-runner] prompt is missing or blank in ${TASK_PATH}`);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}
		let paths: ReturnType<typeof taskPaths>;
		try {
			paths = taskPaths(envelope);
		} catch (error) {
			console.error(`[pi-runner] ${errorText(error)}`);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}
		return {
			paths,
			schemaVersion: SUPPORTED_SCHEMA_VERSION,
			reviewId: envelope.reviewId,
			jobId: envelope.jobId,
			attempt: envelope.attempt,
			workspaceId: envelope.workspaceId,
			prompt: envelope.prompt,
			repositoryFullName: envelope.repositoryFullName,
			pullRequestNumber: envelope.pullRequestNumber,
		};
	}

	const prompt = taskEnvelope.prompt.trim();
	console.error(
		`[pi-runner] Task envelope loaded: ` +
			`jobId=${logValue(taskEnvelope.jobId)}, workspaceId=${logValue(taskEnvelope.workspaceId)}, ` +
			`repository=${logValue(taskEnvelope.repositoryFullName ?? "?")}, ` +
			`prNumber=${logValue(taskEnvelope.pullRequestNumber ?? "?")}`,
	);

	function noteToolCall(turn: TurnTrace, toolName: string, args: unknown): void {
		const state = sessionState();
		turn.toolCalls[toolName] = (turn.toolCalls[toolName] ?? 0) + 1;
		if (!state.activeSession) {
			return;
		}
		const signature = `${toolName}:${JSON.stringify(args)}`;
		const repeats = (state.repeatedCalls.get(signature) ?? 0) + 1;
		state.repeatedCalls.set(signature, repeats);
		turn.repeatedCalls = Math.max(turn.repeatedCalls, repeats);
		const recording = toolName === "submit_result";
		if (repeats === (recording ? REPEATED_RECORDING_NUDGE : REPEATED_CALL_NUDGE)) {
			console.error(
				`[pi-runner] ${turn.label}: the same ${toolName} call ${repeats} times — nudging to record`,
			);
			// Naming what is still owed matters most when the repeated call is the recording itself: "record"
			// alone reads as "send that again".
			const owed = owedPractices();
			const next =
				owed.length > 0
					? `Still owed: ${owed.join(", ")}. Submit what the evidence you have read supports for these`
					: "Submit what the evidence you have read supports";
			void steer(
				state.activeSession,
				`You have run the same ${toolName} call ${repeats} times; its result will not change. ${next}, in one submit_result call. ${PERSIST_DISCIPLINE}`,
			);
		}
		if (repeats >= (recording ? REPEATED_RECORDING_ABORT : REPEATED_CALL_ABORT)) {
			stopTurn("loop", `the same ${toolName} call ${repeats} times — aborting this turn`);
		}
		// SDK schema refusals bypass tool execution and its per-practice cap; count attempts here too.
		if (recording) {
			turn.recordingCalls += 1;
			state.sessionGuards.recordingCalls += 1;
			if (
				state.sessionGuards.recordingCalls >= MAX_RECORDING_ATTEMPTS_PER_TURN &&
				state.sessionGuards.stored === 0
			) {
				stopTurn(
					"loop",
					`${state.sessionGuards.recordingCalls} recording calls without a record — aborting this turn`,
				);
			}
		}
	}

	// Guard state belongs to one native session; the turn trace spans sessions.
	function resetSessionGuards(): void {
		sessionState().repeatedCalls.clear();
		sessionState().refusedItems.clear();
		sessionState().recordingMessageTokens = 0;
		sessionState().sessionGuards = { cutOff: 0, recordingCalls: 0, stored: 0 };
	}

	function openTurnTrace(label: string, budget: Work, demand: TurnDemand): TurnTrace {
		const state = sessionState();
		resetSessionGuards();
		state.lastEventAt = Date.now();
		state.currentTurn = {
			phase: "practice",
			modelError: false,
			modelFailures: {},
			lastModelFailureAt: null,
			finalModelFailure: null,
			unattributedModelFailures: 0,
			modelCallTiming: newModelCallTiming(),
			runtimeError: false,
			label,
			durationMs: Date.now(),
			calls: 0,
			toolCalls: {},
			recordingCalls: 0,
			stored: 0,
			refused: 0,
			refusalReasons: [],
			toolErrors: 0,
			toolErrorReasons: [],
			repeatedCalls: 0,
			compactions: 0,
			providerRetries: 0,
			outputTokens: 0,
			cutOff: 0,
			budget,
			askedToRecord: false,
			stoppedBy: null,
		};
		state.turnDemand = demand;
		return state.currentTurn;
	}

	function closeTurnTrace(trace: TurnTrace): void {
		trace.durationMs = Date.now() - trace.durationMs;
		runnerDebug.turns.push(trace);
		sessionState().currentTurn = null;
		sessionState().turnDemand = null;
		persistRunnerDebug();
		console.error(
			`[pi-runner] ${trace.label}: ${(trace.durationMs / 1000).toFixed(1)}s, calls=${trace.calls}/${trace.budget.modelCalls}, ` +
				`outputTokens=${trace.outputTokens}/${trace.budget.outputTokens}${trace.stoppedBy ? `, stoppedBy=${trace.stoppedBy}` : ""}, ` +
				`tools=${JSON.stringify(trace.toolCalls)}, stored=${trace.stored}, refused=${trace.refused}` +
				`${trace.toolErrors ? `, toolErrors=${trace.toolErrors}` : ""}${trace.compactions ? `, compactions=${trace.compactions}` : ""}${trace.providerRetries ? `, providerRetries=${trace.providerRetries}` : ""}`,
		);
	}

	const ABORT_SETTLE_MS = 30_000;

	function criteriaPathOf(slug: string): string {
		return `${nodePath.dirname(INPUT_PATHS.practiceIndex)}/${slug}.md`;
	}

	function criteriaFileOf(slug: string): string | null {
		const file = criteriaPathOf(slug);
		return existsSync(file) ? readFileSync(file, "utf8") : null;
	}

	function criteriaOf(slugs: readonly string[]): string {
		return slugs
			.map((slug) => {
				const criteria = criteriaFileOf(slug) ?? "(criteria file missing)";
				const exhaustive = [...(practiceExhaustiveSources.get(slug) ?? [])];
				const scope =
					exhaustive.length > 0
						? `Exhaustive sources (an absence claim must have searched all of them): ${exhaustive.join(", ")}.\n\n`
						: "";
				return `### Practice \`${slug}\`\n${scope}${criteria}`;
			})
			.join("\n\n");
	}

	// Compaction summarizes the opening away; resend its exact text after compaction.

	function assessmentOpening(brief: string): string {
		return `${prompt}\n\n${brief}\n\n`;
	}

	// Restore only the current practice's draft after compaction; other drafts are outside its context.
	function openingIfNeeded(brief: string): string {
		if (sessionState().openingInContext) {
			return "";
		}
		const review = sessionState().activeReview;
		const problemDrafts = review?.problems.inventory() ?? [];
		const ownDrafts = review?.draft != null;
		return `${assessmentOpening(brief)}${
			ownDrafts ? `## Recorded so far\n${recordedSoFar(sessionState().currentTurnSlugs)}\n\n` : ""
		}${problemDrafts.length === 0 ? "" : `## Local problem drafts (not admitted)\n${JSON.stringify(problemDrafts)}\nUse read_problem with one key to recover its complete local body before correcting it. Select keys explicitly with submit_result; unselected drafts are not submitted.\n\n`}`;
	}

	const RESULT_EXAMPLE = (() => {
		const context = taskEnvelope.paths.contextRoot;
		const example = [
			{
				outcome: "MET",
				summary: "Added test asserts that malformed date input is rejected",
				explanation:
					'The added assertion passes "not-a-date" to DateParser.parse and expects an error.',
				citations: [
					{
						sourceKind: "scm.pull-request.diff",
						sourcePath: `${context}/change.json`,
						path: "Tests/DateParserTests.swift",
						side: "NEW",
						startLine: 12,
						endLine: 12,
						quote: 'XCTAssertThrowsError(try DateParser.parse("not-a-date"))',
					},
				],
				evidence: {},
			},
			{
				outcome: "NOT_MET",
				summary: "New error branch of the parser ships without a test",
				explanation:
					"The change adds a branch that rejects an empty string; no added test feeds the parser one.",
				citations: [],
				problems: [
					{
						severity: "MINOR",
						summary: "Empty-string branch of the parser has no test",
						explanation:
							"The change adds a guard that throws for an empty string; no added test passes one.",
						citations: [
							{
								sourceKind: "scm.pull-request.diff",
								sourcePath: `${context}/change.json`,
								path: "App/DateParser.swift",
								side: "NEW",
								startLine: 30,
								endLine: 30,
								quote: "guard !text.isEmpty else { throw ParseError.empty }",
							},
						],
						basis: {
							type: "introduced",
							head: 0,
							explanation: "The change adds this branch on the new side.",
						},
					},
				],
				evidence: {
					search: {
						consulted: ["scm.pull-request.diff"],
						lookedFor: "an added test that passes an empty string to the parser",
						boundary: "the added test files of this change; tests outside the change were not read",
					},
				},
			},
			{
				outcome: "DOES_NOT_APPLY",
				summary: "No completed merge is recorded",
				explanation: "The captured provider record states that this work has not merged.",
				citations: [
					{
						sourceKind: "scm.pull-request.core",
						sourcePath: `${context}/metadata.json`,
						path: `${context}/metadata.json`,
						startLine: 3,
						endLine: 3,
						quote: '"is_merged": false',
					},
				],
				evidence: {
					inapplicability: {
						consulted: ["scm.pull-request.core"],
						prerequisite: "work that has merged",
						ruledOutBy: "the provider records is_merged as false",
					},
				},
			},
		];
		return `## How submit_result takes a result
The arguments are one complete result object for this session's practice, not a list and not a JSON-encoded string. Each block below is a whole alternative call, with an illustrative outcome, not evidence from this work:
${example.map((result) => `\`\`\`json\n${JSON.stringify(result, null, 1)}\n\`\`\``).join("\n\n")}`;
	})();

	function practiceTurnText(
		heading: string,
		slug: string,
		brief: string,
		prepared: string,
	): string {
		const illustrations = sessionState().openingInContext ? "" : `\n\n${RESULT_EXAMPLE}`;
		return `${openingIfNeeded(brief)}${heading}

${assignmentText(assignedInputs.get(slug))}${criteriaOf([slug])}${illustrations}${preparedSectionOf(slug, prepared)}

Submit one result for ${slug} with submit_result: the outcome its criterion above decides through its applicability, Judge and Defer, as the evidence supports it, DOES_NOT_APPLY and COULD_NOT_DETERMINE included. What the brief shows is yours to quote; read more when the criterion needs evidence beyond it. This session submits only this practice's result. For several NOT_MET problems, optionally use upsert_problem with a stable key for each full problem body; remove_problem retracts a draft. Then submit_result selects the ordered problemKeys instead of inline problems. Nothing is admitted until the complete result passes server admission.`;
	}

	async function main() {
		console.error(`[pi-runner] Review: one fresh session per practice`);
		console.error(
			`[pi-runner] Work per practice: ${PER_PRACTICE_WORK.modelCalls} model calls, ${PER_PRACTICE_WORK.outputTokens} output tokens; ` +
				`safety ceiling ${AGENT_BUDGET_MS}ms`,
		);

		// pi-agent-sandbox.ts has the rationale for running untrusted; both Pi runners in this image
		// share it.
		const settingsManager = SettingsManager.create(
			CWD,
			AGENT_DIR,
			SANDBOX_SETTINGS_MANAGER_OPTIONS,
		);
		// Disable instruction discovery; load only the server-staged orchestrator (see pi-agent-sandbox.ts).
		const orchestratorPath = `${AGENT_DIR}/AGENTS.md`;
		const orchestrator = orchestratorWithPaths(readFileSync(orchestratorPath, "utf8"));
		const modelRuntime = await ModelRuntime.create({
			authPath: `${AGENT_DIR}/auth.json`,
			modelsPath: `${AGENT_DIR}/models.json`,
			allowModelNetwork: false,
		});

		const providerConfig = loadProviderConfig(CWD);
		if (!registerHephaestusProvider(modelRuntime, providerConfig)) {
			throw new Error(
				"Hephaestus provider is not configured — pi-provider.json and proxy credentials are required",
			);
		}
		const registeredModel = modelRuntime.getModel("hephaestus", providerConfig.modelId);
		if (!registeredModel) {
			throw new Error(`Hephaestus model was not registered: ${providerConfig.modelId}`);
		}
		const model = registeredModel;
		outputLimit = model.maxTokens > 0 ? model.maxTokens : Number.POSITIVE_INFINITY;
		console.error(
			`[pi-runner] registered hephaestus provider: apiProtocol=${providerConfig.apiProtocol} ` +
				`model=${providerConfig.modelId} contextWindow=${model.contextWindow}`,
		);
		const { thinkingLevel } = reasoningSetting(providerConfig.reasoningEffort);
		console.error(
			`[pi-runner] reasoning effort: ${providerConfig.reasoningEffort?.toLowerCase() ?? "provider default"}`,
		);

		// SDK custom entries bind debug transcripts without entering the model context.
		function nativeSessionManager(practiceSlug: string | null) {
			const manager = SessionManager.create(CWD, `${CWD}/.sessions`);
			const binding: ReviewSessionBinding = {
				phase: "practice",
				practiceSlug,
				practiceVersionId:
					practiceIndex.find((practice) => practice.slug === practiceSlug)?.versionId ?? null,
				model: model.id,
				jobId: typeof taskEnvelope.jobId === "string" ? taskEnvelope.jobId : null,
				workspaceId: isDecimalId(taskEnvelope.workspaceId) ? taskEnvelope.workspaceId : null,
				openedAt: new Date().toISOString(),
			};
			manager.appendCustomEntry(REVIEW_SESSION_ENTRY, binding);
			return manager;
		}

		const streamUsage = newUsageLedger();
		let providerFailures = 0;
		const measurementReply = { received: false };
		const subscribeSession = (trackedSession: AgentSession) => {
			const scope = sessionState();
			return trackedSession.subscribe((event: AgentSessionEvent) =>
				sessionScopes.run(scope, () => {
					chargeWork(event);
					const label = practiceLogLabel();
					if (event.type === "tool_execution_start") {
						// A call a codemode script makes is the script's work, not a call the model sent: the loop
						// guards count only the latter, and the trace keeps the former apart.
						if (event.parentToolCallId === undefined) {
							measurementReply.received = true;
							console.error(`[pi-runner] ${label} tool: ${event.toolName}`);
							if (scope.currentTurn) {
								noteToolCall(scope.currentTurn, event.toolName, event.args);
							}
						} else {
							console.error(`[pi-runner] ${label} tool: codemode → ${event.toolName}`);
							if (scope.currentTurn) {
								const key = `codemode.${event.toolName}`;
								scope.currentTurn.toolCalls[key] = (scope.currentTurn.toolCalls[key] ?? 0) + 1;
							}
						}
					}
					// SDK validation errors do not reach the tool, so log them from the session event.
					if (
						event.type === "tool_execution_end" &&
						event.isError &&
						!answeredByTool(scope, event)
					) {
						const result: unknown = event.result;
						const reason = firstLine(toolResultText(result));
						const tool =
							event.parentToolCallId === undefined
								? event.toolName
								: `codemode → ${event.toolName}`;
						console.error(`[pi-runner] ${label} tool error: ${tool} — ${reason}`);
						noteSchemaRefusal(scope, event.toolName);
						if (scope.currentTurn) {
							scope.currentTurn.toolErrors += 1;
							scope.currentTurn.toolErrorReasons.push(`${tool}: ${reason}`);
						}
					}
					if (event.type === "compaction_end") {
						console.error(
							`[pi-runner] ${label} context compacted (${event.reason})${hasText(event.errorMessage) ? `: ${event.errorMessage}` : ""}`,
						);
						if (!event.aborted && !hasText(event.errorMessage)) {
							scope.openingInContext = false;
						}
						if (scope.currentTurn && !event.aborted) {
							scope.currentTurn.compactions += 1;
						}
					}
					// The SDK rides out a retryable provider failure on its own. A run that took longer, or that
					// gave up after several of these, says so here rather than looking like an idle session.
					if (event.type === "auto_retry_start") {
						if (scope.currentTurn) {
							scope.currentTurn.providerRetries += 1;
						}
						console.error(
							`[pi-runner] ${label} provider call failed, retrying in ${event.delayMs}ms ` +
								`(attempt ${event.attempt}/${event.maxAttempts}): ${event.errorMessage}`,
						);
					}
					if (event.type === "auto_retry_end" && !event.success) {
						if (scope.currentTurn) {
							scope.currentTurn.modelError = true;
							recordUnsuccessfulRetryEnd(scope.currentTurn);
						}
						const finalError = event.finalError ?? "no error given";
						providerFailures += 1;
						console.error(
							`[pi-runner] ${label} provider call failed for good after ${event.attempt} retries: ${finalError}`,
						);
					}
					if (event.type === "message_end" && event.message.role === "assistant") {
						addAssistantUsage(streamUsage, event.message);
						const { stopReason } = event.message;
						// An error or abort alone is not a reply; meaningful partial output is. Once answered,
						// later provider failures cannot turn a recording failure into a wholly unreachable review.
						if (
							(stopReason !== "error" && stopReason !== "aborted") ||
							event.message.content.some(
								(content) =>
									content.type === "toolCall" ||
									(content.type === "text" && !isBlank(content.text)),
							)
						) {
							measurementReply.received = true;
						}
						stopIfAttemptRefused(stopReason, event.message);
						if (scope.currentTurn) {
							scope.currentTurn.modelError = stopReason === "error";
							recordModelResponse(scope.currentTurn, stopReason, event.message);
						}
						const types = listOrEmpty(event.message.content).map((c) => c.type);
						const toolCalls = types.filter((t) => t === "toolCall").length;
						const { rawStopReason } = event.message;
						// An error can leave a review unanswered even when the SDK does not retry it.
						if (stopReason === "error") {
							providerFailures += 1;
						}
						const failure =
							stopReason === "error" || stopReason === "length"
								? `, error=${event.message.errorMessage ?? "none given"}${hasText(rawStopReason) ? `, rawStopReason=${rawStopReason}` : ""}`
								: "";
						console.error(
							`[pi-runner] ${label} assistant msg: stopReason=${stopReason}, toolCalls=${toolCalls}, ` +
								`types=[${types.join(",")}]${failure}`,
						);
					}
				}),
			);
		};

		const allSlugs = loadPracticeSlugs();
		practiceCoverageLedger = new PracticeCoverageLedger(PRACTICE_COVERAGE_PATH, allSlugs);
		deriveChangeView();
		persistRecordedNotes();
		const turns = planTurns(practiceIndex, PRACTICES_PER_TURN);
		// One brief for each declared set: practices that declare the same sources share its bytes.
		const briefs = new Map<string, ReturnType<typeof buildBrief>>();
		function briefOf(slug: string): ReturnType<typeof buildBrief> {
			const declared = declaredOf(slug);
			const key = JSON.stringify([...declared].toSorted());
			const known = briefs.get(key);
			if (known !== undefined) {
				return known;
			}
			const built = buildBrief(
				CWD,
				{
					contextRoot: taskEnvelope.paths.contextRoot,
					repositoryRoot: taskEnvelope.paths.repositoryRoot,
				},
				{ declared, fileKinds: sourceFileSources, available: availableSourceKinds },
			);
			briefs.set(key, built);
			return built;
		}
		console.error(
			`[pi-runner] Review: ${allSlugs.length} practice(s) in ${turns.length} turn(s); context loop-only=${CONTEXT_IS_LOOP_ONLY}; briefs=${[
				...new Set(allSlugs.map((slug) => briefOf(slug).text.length)),
			].join(",")} chars`,
		);

		const measureEnd = PROCESS_START_MS + AGENT_BUDGET_MS;
		const startMs = Date.now();
		const tooLate = Date.now() >= measureEnd;
		if (tooLate) {
			console.error(
				`[pi-runner] FAILED: the run reached its safety ceiling before the session could start`,
			);
		}

		if (tooLate) {
			logPracticeCoverage();
			finalizeOutput();
			process.exit(1);
		}

		const cacheConfig = providerConfig;
		async function assessmentLoader() {
			const scope = sessionState();
			const practice = scope.activePractice;
			const cacheExtension = assessmentCacheExtension(
				cacheConfig,
				modelRuntime,
				practice === null ? "" : assessmentOpening(briefOf(practice).text),
				taskEnvelope.workspaceId,
				taskEnvelope.jobId,
			);
			// Extension bindings belong to the native session that uses them.
			const resourceLoader = new DefaultResourceLoader({
				cwd: CWD,
				agentDir: AGENT_DIR,
				settingsManager,
				...SANDBOX_RESOURCE_LOADER_OPTIONS,
				systemPrompt: orchestrator,
				agentsFilesOverride: () => ({ agentsFiles: [] }),
				// "on" keeps every tool callable directly as well; scripts get no model catalog to call.
				extensionFactories: [
					createCodemodeExtension({ mode: "on", models: false }),
					cacheExtension,
					// A session with no practice reads only what every session reads.
					declaredSourcesExtension(declaredSourcesOf(practice), CWD),
					submissionExtension(
						providerConfig ?? {},
						() => scope.activeReview?.requireSubmission === true,
					),
				],
			});
			await resourceLoader.reload();
			return resourceLoader;
		}

		type SessionOptions = NonNullable<Parameters<typeof createAgentSession>[0]>;
		async function openSession(tools: string[], customTools: SessionOptions["customTools"]) {
			const bash = createBashToolDefinition(CWD);
			const sessionTools: SessionOptions["customTools"] = tools.includes("bash")
				? [
						defineTool({
							...bash,
							description: `${bash.description} Use read or grep for source content you will rely on. Use bash for Git history and listings. Only simple literal single-file reads can receive exact read coverage; pipes, redirection, globs, substitutions, and compound commands keep coverage unknown.`,
							promptGuidelines: [
								"Use read or grep for evidence content. Use bash for Git history and listings.",
							],
						}),
						...(customTools ?? []),
					]
				: customTools;
			const { session: fresh, extensionsResult } = await createAgentSession({
				cwd: CWD,
				agentDir: AGENT_DIR,
				tools,
				customTools: sessionTools?.map((tool) => ({
					...tool,
					execute: AsyncLocalStorage.bind(async (...args: Parameters<typeof tool.execute>) =>
						tool.execute(...args),
					),
				})),
				sessionManager: nativeSessionManager(sessionState().activePractice),
				settingsManager,
				resourceLoader: await assessmentLoader(),
				modelRuntime,
				model,
				thinkingLevel,
			});
			const scope = sessionState();
			if (scope.currentTurn?.practiceSlug !== undefined) {
				const stream = fresh.agent.streamFunction;
				fresh.agent.streamFunction = (
					requestModel,
					context,
					options,
				): ReturnType<AgentSession["agent"]["streamFunction"]> => {
					const trace = scope.currentTurn;
					if (
						trace === null ||
						trace.stoppedBy !== null ||
						spent({ modelCalls: trace.calls, outputTokens: trace.outputTokens }, trace.budget)
					) {
						throw new UnratedError("budget", "the practice reservation is spent");
					}
					// A final call can finish, but cannot write tokens beyond its turn or reserved for a sibling practice.
					scope.callOutputLimit = Math.min(
						options?.maxTokens ?? requestModel.maxTokens,
						trace.budget.outputTokens - trace.outputTokens,
					);
					return stream(requestModel, context, { ...options, maxTokens: scope.callOutputLimit });
				};
			}
			for (const error of extensionsResult.errors) {
				console.error(`[pi-runner] extension error: ${error.path}: ${error.error}`);
			}
			resetSessionGuards();
			sessionState().openingInContext = false;
			sessionState().lastEventAt = Date.now();
			sessionState().activeSession = fresh;
			return { session: fresh, unsubscribe: subscribeSession(fresh) };
		}

		// Keep settlement handlers subscribed until the native session stops.
		async function stopBeforeSwitching(session: AgentSession): Promise<void> {
			const deadline = scheduleDeadline(ABORT_SETTLE_MS, () => undefined);
			try {
				const stopped = await Promise.race([
					stopSession(session).then(() => true),
					deadline.elapsed.then(() => false),
				]);
				if (!stopped) {
					throw new Error("Native session did not settle; no later session may start");
				}
			} catch (error) {
				measurementClosed = true;
				throw error;
			} finally {
				clearTimeout(deadline.timer);
			}
		}

		async function closeSession(opened: Awaited<ReturnType<typeof openSession>>): Promise<void> {
			opened.session.abortCompaction();
			try {
				await stopBeforeSwitching(opened.session);
			} finally {
				opened.unsubscribe();
				if (sessionState().activeSession === opened.session) {
					sessionState().activeSession = null;
				}
			}
		}

		// Liveness, not a budget: a turn that shows no sign of life for STALL_MS is aborted.
		const checkStall = (scope: ReviewSessionState) => {
			sessionScopes.run(scope, () => {
				if (scope.currentTurn?.stoppedBy === null && Date.now() - scope.lastEventAt >= STALL_MS) {
					providerFailures += 1;
					stopTurn("stall", `no model or tool event for ${STALL_MS / 1000}s — aborting this turn`);
				}
			});
		};
		const stallWatch = setInterval(() => {
			for (const scope of [rootSessionState, ...activeScopes]) {
				checkStall(scope);
			}
		}, STALL_CHECK_MS);
		stallWatch.unref();

		const evidenceSources = captureSources();
		const inputReader = new InputReader({ practicesDir: INPUT_PATHS.practiceScripts });
		const boundModels = readBoundModels();
		const directBudget = new ModelBudget(DIRECT_MODEL_TOKENS, MODEL_CONCURRENCY);
		let sessionStopFailure: Error | null = null;
		const measuringEnded = new AbortController();

		async function runScope(
			label: string,
			heading: string,
			slugs: readonly string[],
			reservedBudget?: Work,
			practicePosition?: { index: number; count: number },
		): Promise<StopReason | null> {
			const safetyMs = measureEnd - Date.now();
			if (safetyMs <= RETURN_MS) {
				console.error(`[pi-runner] ${label}: the run is near its safety ceiling — skipped`);
				return "safety";
			}
			const budget = reservedBudget ?? turnBudget(slugs.length, PER_PRACTICE_WORK);
			console.error(
				`[pi-runner] ${label}: ${slugs.length} practice(s), up to ${budget.modelCalls} calls and ${budget.outputTokens} output tokens (${sessionState().tokensPerProblem} tokens per result so far)`,
			);
			const trace = openTurnTrace(label, budget, {
				owed: () => owedPractices(slugs).length,
				nudge: RECORD_NUDGE,
			});
			if (reservedBudget !== undefined) {
				trace.practiceSlug = slugs[0];
			}
			const safety = scheduleDeadline(safetyMs, () =>
				stopTurn("safety", "the run is near its safety ceiling — aborting this turn"),
			);
			// Native events may stop a turn while awaited work settles or another session opens.
			const stoppedBy = () => trace.stoppedBy;
			const pastSafety = () => safety.expired() || Date.now() >= measureEnd;
			const endedUnrecorded = (slug: string) =>
				owedPractices([slug]).length > 0 &&
				stoppedBy() === null &&
				!pastSafety() &&
				!trace.modelError;
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
					console.error(
						`[pi-runner] ${label}: essential input for ${slug} exceeds the context window — not reached`,
					);
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
				console.error(
					`[pi-runner] ${label}: the ${slug} session ended without a submitted result — asking once more`,
				);
				if (!(await settleSession(session, label, ABORT_SETTLE_MS))) {
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
					console.error(
						`[pi-runner] ${label}: the ${slug} session ended again without a submitted result — not reached`,
					);
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
				const fullInput = `${REVIEW_INPUT_ROOT}/${slug}.json`;
				const preparing =
					leads.length > 0 ||
					Object.keys(options.facts ?? {}).length > 0 ||
					(options.directions ?? []).length > 0 ||
					(options.context ?? []).length > 0 ||
					(options.limitations ?? []).length > 0;
				if (preparing) {
					mkdirSync(`${CWD}/${REVIEW_INPUT_ROOT}`, { recursive: true });
					writeFileSync(`${CWD}/${fullInput}`, JSON.stringify({ ...options, leads }, null, 2));
				}
				const prepared = preparing
					? renderReviewInput(options, leads, taskEnvelope.paths.contextRoot, `\`${fullInput}\``)
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
							return [
								briefSources.find((source) => source.path === path) ?? { path, supplied: false },
							];
						});
				const review: ActiveReview = {
					slug,
					draft: null,
					problems: new ProblemDrafts(),
					requireSubmission: false,
					reads: new SessionReads(
						CWD,
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
				const text = () =>
					`${practiceTurnText(practiceHeading, slug, brief, prepared)}${assignedText}`;
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
					console.error(`[pi-runner] ${label} (${slug}) failed: ${errorText(error)}`);
				} finally {
					signal.removeEventListener("abort", stop);
					const closed = await closeSession(opened).then(
						() => null,
						(error: unknown) => (error instanceof Error ? error : new Error(String(error))),
					);
					unsubscribeReads();
					if (!isScan) {
						runnerDebug.practiceSources[slug] = relevantBrief.map((source) => ({
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
					sessionStopFailure ??= closed;
				}
				if (sessionStopFailure !== null) {
					throw sessionStopFailure;
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
			async function runProgram(slug: string, practiceHeading: string): Promise<void> {
				const program = programOf(slug);
				const read = await inputReader.read(slug).catch((error: unknown) => ({
					error: `the host could not read them: ${errorText(error)}`,
				}));
				if ("error" in read) {
					console.error(`[pi-runner] ${slug}: its inputs are not valid: ${read.error}`);
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
						now: new Date(PROCESS_START_MS).toISOString(),
						deadline: measureEnd - RETURN_MS,
					},
					{
						reader: new EvidenceReader(sources, programReads),
						models: hostModels(boundModels, LLM_PROXY_URL, LLM_PROXY_TOKEN, slug),
						budget: directBudget,
						review: async (options, signal) =>
							reviewPractice(slug, practiceHeading, options, signal, sessionReads),
						signal: AbortSignal.any([stopped.signal, measuringEnded.signal]),
						log: (line) => {
							console.error(`[pi-runner]${line}`);
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
				if (sessionStopFailure !== null) {
					throw sessionStopFailure;
				}
				console.error(
					`[pi-runner] ${slug}: ${program.script === null ? "built-in" : "custom"} program ${outcome.status}` +
						`${outcome.stopReason === null ? "" : ` (${outcome.stopReason})`}${outcome.error === undefined ? "" : `: ${firstLine(outcome.error)}`}`,
				);
				const { candidate } = outcome;
				if (candidate === undefined) {
					return;
				}
				try {
					validateChangeBasis(
						candidate,
						existsSync(`${CWD}/${CHANGE_ROOT}/diff.patch`)
							? readFileSync(`${CWD}/${CHANGE_ROOT}/diff.patch`, "utf8")
							: "",
						readChange(INPUT_PATHS.contextRoot),
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
			try {
				for (const [index, slug] of slugs.entries()) {
					if (stoppedBy() !== null || Date.now() >= measureEnd - RETURN_MS) {
						break;
					}
					if (executions.has(slug)) {
						continue;
					}
					await runProgram(
						slug,
						`${heading}\nPractice ${(practicePosition?.index ?? index) + 1} of ${practicePosition?.count ?? slugs.length} in this turn.`,
					);
				}
			} finally {
				clearTimeout(safety.timer);
				sessionState().activePractice = null;
				sessionState().currentTurnSlugs = slugs;
				closeTurnTrace(trace);
			}
			if (pastSafety() && stoppedBy() === null) {
				console.error(
					`[pi-runner] ${label}: the run is near its safety ceiling — the rest is not reached`,
				);
				return "safety";
			}
			return stoppedBy();
		}

		async function measurePractices(): Promise<void> {
			let stalls = 0;
			const serial =
				PRACTICE_CONCURRENCY === 1 ||
				practiceIndex.some((practice) => practice.contextPaths !== undefined);
			runnerDebug.practiceConcurrency = {
				configured: PRACTICE_CONCURRENCY,
				effective: serial ? 1 : PRACTICE_CONCURRENCY,
			};
			console.error(
				`[pi-runner] Practice concurrency: configured=${PRACTICE_CONCURRENCY}, effective=${serial ? 1 : PRACTICE_CONCURRENCY}`,
			);
			if (serial) {
				for (const [index, turn] of turns.entries()) {
					const stop = await runScope(
						`turn ${index + 1}/${turns.length} (${turn.id})`,
						`## Turn ${index + 1} of ${turns.length}: ${turn.id}`,
						turn.slugs,
					);
					stalls = stop === "stall" ? stalls + 1 : 0;
					if (stop === "safety" || stalls >= 2) {
						console.error(
							`[pi-runner] Measuring ends early: ${stop === "safety" ? "safety ceiling" : "the session stalled twice in a row"}`,
						);
						break;
					}
				}
			} else {
				const queue = turns.flatMap((turn, index) => {
					const budget = turnBudget(turn.slugs.length, PER_PRACTICE_WORK);
					return turn.slugs.map((slug, position) => ({
						slug,
						label: `turn ${index + 1}/${turns.length} (${turn.id}) / ${slug}`,
						heading: `## Turn ${index + 1} of ${turns.length}: ${turn.id}`,
						budget: partitionBudget(budget, turn.slugs.length, position),
						position: { index: position, count: turn.slugs.length },
					}));
				});
				let next = 0;
				const workers = await Promise.allSettled(
					Array.from({ length: Math.min(PRACTICE_CONCURRENCY, queue.length) }, async () => {
						while (
							next < queue.length &&
							Date.now() < measureEnd - RETURN_MS &&
							!measurementClosed
						) {
							const work = queue[next];
							next += 1;
							if (work === undefined) {
								return;
							}
							const scope = newSessionState();
							activeScopes.add(scope);
							try {
								await sessionScopes.run(scope, async () =>
									runScope(work.label, work.heading, [work.slug], work.budget, work.position),
								);
							} catch (error) {
								measurementClosed = true;
								unreachedReason = "error";
								measuringEnded.abort(
									new UnratedError("unavailable", "a review session could not stop safely"),
								);
								throw error;
							} finally {
								activeScopes.delete(scope);
							}
						}
					}),
				);
				const failure = workers.find((worker) => worker.status === "rejected");
				if (failure?.status === "rejected") {
					console.error(
						`[pi-runner] Measurement stopped: ${errorText(failure.reason)}; retaining completed practices`,
					);
				}
			}

			const unfinished = allSlugs.filter(
				(slug) => !executions.has(slug) && !blockedPractices.has(slug),
			);
			if (serial && unfinished.length > 0 && stalls < 2 && Date.now() < measureEnd - RETURN_MS) {
				console.error(
					`[pi-runner] Finishing ${unfinished.length} practice(s): ${unfinished.join(", ")}`,
				);
				await runScope(
					"finish",
					"## Unfinished practices\nNo turn recorded a result for this practice; evaluate it now.",
					unfinished,
				);
			}
		}

		try {
			await measurePractices();
		} finally {
			measuringEnded.abort(new UnratedError("deadline", "measuring ended"));
			persistRecordedNotes();
			const measureDurationMs = Date.now() - startMs;
			const measureUsage = extractUsageFromSession({}, streamUsage);
			accumulateUsage(measureUsage);
			runnerDebug.attempts.push({
				label: "measure",
				durationMs: measureDurationMs,
				assistantMessages: measureUsage.assistantMessages,
				stopReasons: measureUsage.stopReasons,
				usage: measureUsage,
				resultFilePresent: hasPersistedReviewState(),
			});
			persistRunnerDebug();
			persistUsage();
			console.error(
				`[pi-runner] Measured: ${(measureDurationMs / 1000).toFixed(1)}s, calls=${measureUsage.totalCalls}, ` +
					`stopped=[${runnerDebug.turns
						.flatMap((turn) =>
							turn.stoppedBy === null ? [] : [`${turn.label}: ${turn.stoppedBy}`],
						)
						.join("; ")}], results=${reviewState.size}`,
			);
		}

		const notReached = missingSlugs(allSlugs, [...reviewState.keys()]);
		const runtimeReasons: Partial<Record<NonNullable<ProgramOutcome["stopReason"]>, string>> = {
			budget: "BUDGET",
			deadline: "DEADLINE",
			cancelled: "CANCELLED",
			unavailable: "EXECUTION_INCOMPLETE",
		};
		for (const practiceSlug of notReached) {
			const stop = executions.get(practiceSlug)?.stopReason ?? unreachedReason;
			const reason = runtimeReasons[stop] ?? "EXECUTION_FAILED";
			runtimeNonResults.push({ practiceSlug, reason });
		}
		logPracticeCoverage();
		if (notReached.length > 0) {
			console.error(
				`[pi-runner] PARTIAL: ${notReached.length} of ${allSlugs.length} practice(s) without a result: ${notReached.join(", ")}`,
			);
		}

		if (!maybeWriteResultFile()) {
			if (providerFailures > 0 && !measurementReply.received) {
				console.error(
					`[pi-runner] UNREACHABLE: this review reached no practice, and ${providerFailures} model call(s) ` +
						`went unanswered — the provider, not the work, is what this run could not read`,
				);
				finalizeOutput();
				process.exit(PROVIDER_UNREACHABLE_EXIT);
			}
			console.error(`[pi-runner] FAILED: this review reached no practice at all`);
			finalizeOutput();
			process.exit(1);
		}

		measurementClosed = true;
		await admit(admissionRequest(), async (body, signal) =>
			fetch(`${LLM_PROXY_URL}/admit-results`, {
				method: "POST",
				signal,
				headers: {
					authorization: `Bearer ${LLM_PROXY_TOKEN}`,
					"content-type": "application/json",
					...(hasText(process.env.TRACEPARENT) ? { traceparent: process.env.TRACEPARENT } : {}),
				},
				body,
			}),
		);
		console.error(`[pi-runner] SUCCESS: result.json holds ${reviewState.size} result(s)`);
		finalizeOutput();
		process.exit(0);
	}

	function finalizeOutputQuietly() {
		try {
			finalizeOutput();
		} catch (error) {
			console.error(`[pi-runner] output could not be finalized: ${errorText(error)}`);
		}
	}

	process.on("uncaughtException", (err) => {
		console.error(`[pi-runner] FATAL: ${errorText(err)}`);
		finalizeOutputQuietly();
		process.exit(2);
	});

	process.on("unhandledRejection", (reason) => {
		console.error(`[pi-runner] UNHANDLED REJECTION: ${errorText(reason)}`);
		finalizeOutputQuietly();
		process.exit(2);
	});

	async function run(): Promise<void> {
		try {
			await main();
		} catch (error) {
			console.error(
				`[pi-runner] FATAL: ${errorText(error)}\n${error instanceof Error ? (error.stack ?? "") : ""}`,
			);
			unreachedReason = "error";
			finalizeOutputQuietly();
			// Preserve the retryable exit code for admission transport failures.
			process.exit(error instanceof AdmissionUnreachableError ? SERVER_UNREACHABLE_EXIT : 2);
		}
	}

	await run();
}

if (import.meta.main) {
	await runReviewJob();
}
