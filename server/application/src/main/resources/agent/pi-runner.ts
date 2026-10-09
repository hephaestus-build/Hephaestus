import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import nodePath from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { isDeepStrictEqual } from "node:util";

import {
	type AgentSession,
	type AgentSessionEvent,
	type AgentToolResult,
	createAgentSession,
	createCodemodeExtension,
	DefaultResourceLoader,
	defineTool,
	ModelRuntime,
	SessionManager,
	SettingsManager,
	type ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import {
	PUBLIC_REVIEW_RESOURCE_LOADER_OPTIONS,
	PUBLIC_REVIEW_TOOLS,
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { assessmentCacheExtension } from "./pi-assessment-cache.ts";
import { CHANGE_ROOT, checkedOutCommit, pinnedDiff, readPinnedBlob } from "./pi-change.ts";
import { errorText } from "./pi-error-text.ts";
import { folderCitationIndex } from "./pi-folder-index.ts";
import {
	CONVERSATION_THREAD,
	OUTCOME_VALUES,
	OUTCOME_DESCRIPTIONS,
	MAX_SUMMARY_CHARS,
	SEVERITY_VALUES,
	SEVERITY_DESCRIPTIONS,
	citesReviewedTurn,
	describeVocabulary,
	isRecord,
	type NormalizedCitation,
	type NormalizedObservation,
	type Outcome,
	normalizeObservation,
	normalizePracticeSlug,
	resolveQuote,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
} from "./pi-observation-normalize.ts";
import { PracticeCoverageLedger } from "./pi-practice-coverage.ts";
import { loadProviderConfig, reasoningSetting, registerHephaestusProvider } from "./pi-provider.ts";
import {
	buildBrief,
	buildPrimarySourceReference,
	buildPublicReviewHistory,
	buildSameWorkContext,
} from "./pi-review-brief.ts";
import {
	type Work,
	deriveWindows,
	missingSlugs,
	planTurns,
	shouldRecordNow,
	spent,
	turnBudget,
} from "./pi-review-turns.ts";
import {
	ACTIONS,
	CHANNELS,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	type ComposedReview,
	PRIOR_ADVICE_REASONS,
	PRIVATE_CHANNELS,
	type PreparedFeedbackTarget,
	type OwnPriorFeedback,
	READ_PRACTICE_TOOL_DESCRIPTION,
	type ReviewPractice,
	REVIEW_CONTRACT_VERSION,
	REVIEW_TOOL_DESCRIPTION,
	type ReviewedObservation,
	STANDARD_REFERENCE,
	WITHHOLD_REASONS,
	buildReviewTurn,
	consultedStandard,
	decidedByReview,
	notReachedNote,
	practiceStandard,
	priorAdviceWitnesses,
	priorPublicFeedback,
	publicObservations,
	readPracticeParameters,
	readReview,
	prepareReviewArguments,
	readablePractices,
	reviewToolParameters,
	uncertainOutcomes,
	undeliverableUnits,
	validateFeedbackEvidence,
	workIdentity,
} from "./pi-runner-composition.ts";
import { outputPath } from "./pi-runner-output.ts";
import { isRetryableStatus, isTimeoutAbort, retrying } from "./pi-runner-retry.ts";
import {
	addAssistantUsage,
	extractUsageFromSession,
	newUsageLedger,
	outputTokensOf,
	type UsageReport,
} from "./pi-runner-usage.ts";
import { stopSession } from "./pi-session-lifecycle.ts";
import { SUPPORTED_SCHEMA_VERSION, taskPaths, resolveTaskPaths } from "./pi-task-paths.ts";
import { hasText, isBlank } from "./pi-text.ts";
import { prepareObservationArguments } from "./pi-tool-arguments.ts";
import { prepareTurnText } from "./pi-turn-context.ts";

// Staged work notes survive isolated sessions and context compaction.

function parseJson(text: string): unknown {
	return JSON.parse(text);
}

function jsonArray(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [];
}

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

/** Accept array, single-item and serialized-list forms; validate each parsed item separately. */
function submittedList(
	value: unknown,
	itemKeys: ReadonlySet<string>,
): { items: unknown[]; repaired: boolean } | { error: string } {
	if (typeof value === "string") {
		// Only a string that does not parse is repaired: valid JSON is read as written.
		try {
			return { items: listOrItem(parseJson(value)), repaired: false };
		} catch {
			// Repaired below.
		}
		const repaired = repairedClosers(value, itemKeys);
		try {
			return { items: listOrItem(parseJson(repaired)), repaired: true };
		} catch (error) {
			// Where the parse stopped, shown: resending the same text cannot fix a fault nobody located.
			const at = /position (?<position>\d+)/u.exec(errorText(error))?.groups?.position;
			const near =
				at === undefined
					? ""
					: `; it breaks here: ${JSON.stringify(repaired.slice(Math.max(0, Number(at) - 60), Number(at)))} ⟵ ${JSON.stringify(repaired.slice(Number(at), Number(at) + 20))}`;
			return {
				error: `the list arrived as a string that is not valid JSON (${errorText(error)})${near}. Fix that spot — usually a quote inside a text left unescaped — or send the array itself`,
			};
		}
	}
	return { items: listOrItem(value), repaired: false };
}

/** The keys of a feedback unit that never belong to an object inside it. */
const UNIT_ITEM_KEYS: ReadonlySet<string> = new Set([
	"channel",
	"practiceSlug",
	"action",
	"basedOn",
	"title",
	"body",
	"nextStep",
	"withholdReason",
	"supersedesThreadKey",
]);

/** The list, or the one item that was sent where a list of them was asked for. */
function listOrItem(value: unknown): unknown[] {
	if (Array.isArray(value)) {
		return value;
	}
	return isRecord(value) ? [value] : [];
}

/**
 * Repair missing or unmatched closers without changing quoted strings; JSON.parse rejects other faults.
 * `itemKeys` are keys that belong to an item of the list and never to an object inside one: met inside
 * a nested object, they mean that object was left open, and it is closed before them.
 */
function repairedClosers(text: string, itemKeys: ReadonlySet<string>): string {
	const owed: string[] = [];
	let out = "";
	let changed = false;
	let inString = false;
	let escaped = false;
	for (let index = 0; index < text.length; index += 1) {
		const char = text[index] ?? "";
		if (inString) {
			out += char;
			if (escaped) {
				escaped = false;
			} else if (char === "\\") {
				escaped = true;
			} else if (char === '"') {
				inString = false;
			}
			continue;
		}
		if (char === '"') {
			inString = true;
		} else if (char === "," && owed.length > 2 && owed[0] === "]") {
			const key = followingKey(text, index);
			if (key !== null && itemKeys.has(key)) {
				while (owed.length > 2) {
					out += owed.pop() ?? "";
				}
				changed = true;
			}
		} else if (char === "{" || char === "[") {
			const comma = out.trimEnd().length - 1;
			if (char === "{" && owed.at(-1) === "}" && owed.includes("]") && out[comma] === ",") {
				let closers = "";
				while (owed.at(-1) === "}") {
					closers += owed.pop();
				}
				out = out.slice(0, comma) + closers + out.slice(comma);
				changed = true;
			}
			owed.push(char === "{" ? "}" : "]");
		} else if (char === "}" || char === "]") {
			if (owed.at(-1) !== char) {
				changed = true;
				continue;
			}
			if (
				char === "}" &&
				owed.length === 2 &&
				owed[0] === "]" &&
				followingKey(text, index + 1) !== null
			) {
				changed = true;
				continue;
			}
			owed.pop();
		}
		out += char;
	}
	if (inString || (!changed && owed.length === 0)) {
		return text;
	}
	return out.trimEnd() + owed.toReversed().join("");
}

/**
 * The key, when `, "key":` is what comes next — a member of an object, not the next item of a list;
 * null otherwise.
 */
function followingKey(text: string, from: number): string | null {
	let index = from;
	const skipSpace = () => {
		while (index < text.length && /\s/u.test(text[index] ?? "")) {
			index += 1;
		}
	};
	skipSpace();
	if (text[index] !== ",") {
		return null;
	}
	index += 1;
	skipSpace();
	if (text[index] !== '"') {
		return null;
	}
	const start = index + 1;
	index += 1;
	while (index < text.length && text[index] !== '"') {
		if (text[index] === "\\") {
			index += 1;
		}
		index += 1;
	}
	const key = text.slice(start, index);
	index += 1;
	skipSpace();
	return text[index] === ":" ? key : null;
}

/** The schema for such a list: the array, or the same array as a JSON string. */
function listSchema(items: unknown, what: string) {
	return {
		anyOf: [
			{ type: "array", minItems: 1, maxItems: 10, items },
			{ type: "string", minLength: 2, description: `The ${what} as a JSON-encoded array` },
		],
	};
}

interface PracticeIndexEntry {
	slug: string;
	group?: string;
	readsSources: string[];
	exhaustiveSources: string[];
	/** What the practice revision is about, shown to the review on the work as explanatory context. */
	name: string;
	whyItMatters?: string;
	knownLimitations: string[];
	revisionId?: number;
}

/** The task this runner was started for. */
interface TaskEnvelope {
	schemaVersion: number;
	jobId: unknown;
	workspaceId: unknown;
	paths: ReturnType<typeof taskPaths>;
	prompt: string;
	repositoryFullName: unknown;
	pullRequestNumber: unknown;
}

/** How much feedback this run may compose, per lane. */
interface ChannelBounds {
	enabled: boolean;
	maxUnits: number;
}

/** Where an IN_CONTEXT note may be placed on this artifact. */
type PlacementKind = "DIFF" | "ARTIFACT";

/** The task-declared composition request, once its bounds have been clamped to what this run may do. */
interface CompositionRequest {
	channels: Record<Channel, ChannelBounds>;
	inContextPlacementKinds: PlacementKind[];
	minDistinctArtifacts: number;
}

interface AdmittedCitation {
	index: number;
	[key: string]: unknown;
}

/** Validate consumed fields; preserve other server fields for the composer. */
interface AdmittedObservation {
	id: string;
	practiceSlug: string;
	outcome: Outcome;
	citations: AdmittedCitation[];
	[key: string]: unknown;
}

function isAdmittedCitation(value: unknown): value is AdmittedCitation {
	return isRecord(value) && typeof value.index === "number";
}

function isAdmittedObservation(value: unknown): value is AdmittedObservation {
	return (
		isRecord(value) &&
		typeof value.id === "string" &&
		typeof value.practiceSlug === "string" &&
		OUTCOME_VALUES.some((outcome) => outcome === value.outcome) &&
		Array.isArray(value.citations) &&
		value.citations.every(isAdmittedCitation)
	);
}

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
/** The runner's own record of what this review has recorded so far; read back after a compaction. */
const NOTES_PATH = `${CWD}/work/notes/review.md`;
/** The server sets every one of these; a missing one stops the runner before it does any work. */
function requiredEnv(name: string): string {
	const value = process.env[name];
	if (!hasText(value)) {
		throw new Error(`${name} env var is required`);
	}
	return value;
}
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
/** Practices a turn carries at once; a catalog group larger than this is split. */
const PRACTICES_PER_TURN = hasText(process.env.PI_PRACTICE_BATCH_SIZE)
	? Number(process.env.PI_PRACTICE_BATCH_SIZE)
	: 6;
/** Per-practice refusal cap; an exhausted practice remains NOT_REACHED. */
const MAX_REFUSALS_PER_PRACTICE = 8;
/** What one practice may spend in a turn; a turn is owed {@link turnBudget} of it. Set by the server. */
const PER_PRACTICE_WORK: Work = {
	modelCalls: positiveIntegerEnv("PI_PRACTICE_MODEL_CALLS"),
	outputTokens: positiveIntegerEnv("PI_PRACTICE_OUTPUT_TOKENS"),
};
/**
 * How long a turn may go without a single session event — no streamed token, no tool progress — before
 * it counts as stalled and is aborted. Liveness, not a budget: the stream-idle bound Claude Code and
 * Codex both default to.
 */
const STALL_MS = 300_000;
/**
 * The most output tokens one model call may write, from the model's registration; a call that reaches
 * it was cut off, and the tool it called received it incomplete.
 */
let outputLimit = Number.POSITIVE_INFINITY;
/** A turn whose calls run into the output limit this often is stuck writing one runaway call. */
const CUT_OFF_ABORT = 2;

/** How often the stall watchdog looks: a clock read, so often enough that a stall is caught promptly. */
const STALL_CHECK_MS = 1000;
const WINDOWS = deriveWindows(AGENT_BUDGET_MS, existsSync(INPUT_PATHS.compositionRequest));

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

/** Snapshot the eligible practices once for the whole review. */
function readPracticeIndex(): PracticeIndexEntry[] {
	const index = parseJson(readFileSync(INPUT_PATHS.practiceIndex, "utf8"));
	if (!Array.isArray(index)) {
		throw new Error("the task-declared practice index: expected an array");
	}
	return jsonArray(index).map((practice): PracticeIndexEntry => {
		if (!isRecord(practice) || typeof practice.slug !== "string") {
			throw new Error("the task-declared practice index: every practice needs a string slug");
		}
		return {
			slug: practice.slug,
			group:
				typeof practice.group === "string" && practice.group !== "" ? practice.group : undefined,
			readsSources: jsonArray(practice.readsSources).filter(
				(kind): kind is string => typeof kind === "string",
			),
			exhaustiveSources: jsonArray(practice.exhaustiveSources).filter(
				(kind): kind is string => typeof kind === "string",
			),
			name: typeof practice.name === "string" ? practice.name : practice.slug,
			whyItMatters:
				typeof practice.whyItMatters === "string" && practice.whyItMatters.trim() !== ""
					? practice.whyItMatters
					: undefined,
			knownLimitations: jsonArray(practice.knownLimitations).filter(
				(limitation): limitation is string => typeof limitation === "string",
			),
			revisionId:
				typeof practice.revisionId === "number" &&
				Number.isSafeInteger(practice.revisionId) &&
				practice.revisionId > 0
					? practice.revisionId
					: undefined,
		};
	});
}

const folderIndex = parseJson(readFileSync(INPUT_PATHS.manifest, "utf8"));
const { availableSourceKinds, artifactSources } = folderCitationIndex(folderIndex);
const availableSourceKindValues = [...availableSourceKinds].toSorted();
const practiceIndex = readPracticeIndex();
const admittedPractices = new Set(practiceIndex.map((practice) => practice.slug));
// ABSENT is sound only over sources the practice declares exhaustive.
const practiceExhaustiveSources = new Map(
	practiceIndex.map((practice) => [practice.slug, new Set(practice.exhaustiveSources)]),
);
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
interface TurnTrace {
	modelError: boolean;
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
interface PublicHistoryReceipt {
	readAt: string;
	workCapturedAt: string | null;
	entries: {
		id: unknown;
		deliveredAt: unknown;
		eligibleForAlreadySaid: boolean;
		eligibleForPriorAdvice: boolean;
	}[];
	omissions: { oversizedEntries: number; budgetEntries: number };
}
const runnerDebug: {
	attempts: AttemptDebug[];
	turns: TurnTrace[];
	usageTotals: UsageTotals;
	publicHistory?: PublicHistoryReceipt;
} = {
	attempts: [],
	turns: [],
	usageTotals,
};
/** The turn the session events are charged to; null between turns. */
let currentTurn: TurnTrace | null = null;

/** How much of a refusal reason the trace keeps: the kind of failure, not the whole excerpt. */
const TRACE_REASON_CHARS = 140;
const reviewState: { observations: NormalizedObservation[] } = { observations: [] };
const searchSchema = {
	type: "object",
	additionalProperties: false,
	required: ["consulted", "lookedFor", "boundary"],
	properties: {
		consulted: {
			type: "array",
			minItems: 1,
			items: { type: "string", enum: availableSourceKindValues },
			description: "Evidence source kinds you actually searched, e.g. scm.review-threads.",
		},
		lookedFor: {
			type: "string",
			minLength: 1,
			description:
				"The specific behavior whose absence you report, matching the behavior assessed in the summary and rationale.",
		},
		boundary: {
			type: "string",
			minLength: 1,
			description:
				"What this search did NOT cover, so a reader can judge how far the absence reaches.",
		},
	},
} as const;
const inapplicabilitySchema = {
	type: "object",
	additionalProperties: false,
	required: ["consulted", "subject", "ruledOutBy"],
	properties: {
		consulted: {
			type: "array",
			minItems: 1,
			items: { type: "string", enum: availableSourceKindValues },
			description:
				"Evidence source kinds you read to reach this conclusion, e.g. scm.pull-request.diff.",
		},
		subject: {
			type: "string",
			minLength: 1,
			description: "The subject this practice's criteria judge.",
		},
		ruledOutBy: {
			type: "string",
			minLength: 1,
			description: "The fact about THIS work that rules out that subject.",
		},
	},
} as const;
const undecidabilitySchema = {
	type: "object",
	additionalProperties: false,
	required: ["openQuestion", "wouldSettleIt"],
	properties: {
		openQuestion: {
			type: "string",
			minLength: 1,
			description: "The question the evidence you actually read left open, in one sentence.",
		},
		wouldSettleIt: {
			type: "string",
			minLength: 1,
			description:
				"The EVIDENCE that would have decided it — something that already exists and you could not " +
				"read, named concretely: 'the body of issue #7', 'the test file the description says covers " +
				"this'. NOT what the author should have written: advice belongs to a later step, and " +
				"answering with it leaves nobody any wiser about which source this practice is missing.",
		},
	},
} as const;
const evidenceSchema = {
	type: "object",
	additionalProperties: false,
	required: ["citations"],
	description:
		"citations always: the lines that decide the outcome. Beside them, at most the one branch the " +
		"outcome takes: search for a MET or NOT_MET that rests on something missing — what you looked " +
		"for, where, and what the search did not cover; inapplicability for NOT_APPLICABLE; " +
		"undecidability for UNDETERMINED.",
	properties: {
		search: searchSchema,
		inapplicability: inapplicabilitySchema,
		undecidability: undecidabilitySchema,
		citations: {
			type: "array",
			minItems: 1,
			items: {
				type: "object",
				additionalProperties: false,
				required: ["sourceKind", "artifactPath", "path", "startLine"],
				properties: {
					sourceKind: { type: "string", enum: availableSourceKindValues },
					// Membership is checked when the observation is recorded (validateEvidenceSources): a large capture
					// enumerated here produces a declaration the SDK's schema compiler cannot compile.
					artifactPath: {
						type: "string",
						minLength: 1,
						description:
							"The captured artifact the citation reads, exactly as the capture manifest names it.",
					},
					path: { type: "string", minLength: 1 },
					side: {
						type: "string",
						enum: ["OLD", "NEW"],
						description:
							"For a quote of the change: artifactPath names the pinned change (change.json), path is the file as named on that side, and the lines are the [L<n>] coordinates of work/change/diff.patch.",
					},
					revision: {
						type: "string",
						pattern: "^(?:[0-9a-f]{40}|[0-9a-f]{64})$",
						description:
							"For repository text: artifactPath names the captured .git/HEAD, path is repository-relative, and revision optionally selects a full commit SHA; omission means the captured HEAD.",
					},
					startLine: {
						type: "integer",
						minimum: 1,
						maximum: 2_147_483_647,
						description:
							"The 1-based line of the quoted text in the artifact; for a quote of the change, the [L<n>] coordinate of work/change/diff.patch.",
					},
					endLine: {
						type: "integer",
						minimum: 1,
						maximum: 2_147_483_647,
						description: "The last line of the quote, at least startLine; omitted means one line.",
					},
					quote: {
						type: "string",
						description:
							"The text at those lines, copied as shown; a diff marker, a [L<n>] prefix or a non-breaking space read as a space are tolerated. Omit it to cite the whole of the lines: what was recorded is echoed back.",
					},
				},
			},
		},
	},
} as const;
const observationSchema: ToolDefinition["parameters"] = {
	type: "object",
	additionalProperties: false,
	required: [
		"revises",
		"practiceSlug",
		"summary",
		"outcome",
		"severity",
		"evidence",
		"evidenceRationale",
	],
	properties: {
		revises: {
			type: ["string", "null"],
			minLength: 1,
			description:
				"Use null for the first draft. To correct a draft already recorded in this review, copy its returned draft reference here and resend the complete observation. A refused correction leaves the previous draft unchanged.",
		},
		practiceSlug: { type: "string", minLength: 1 },
		summary: {
			type: "string",
			minLength: 1,
			maxLength: MAX_SUMMARY_CHARS,
			description:
				`A short phrase of at most ${MAX_SUMMARY_CHARS} characters identifying the specific behavior whose conformance to the practice standard you assess, such as 'Debug print left in the request handler'. ` +
				"Never a single word and never the practice's own name; the reasons, titles and quotes go in evidenceRationale. " +
				"A longer summary is refused, not shortened.",
		},
		outcome: {
			type: "string",
			enum: OUTCOME_VALUES,
			description: describeVocabulary(OUTCOME_VALUES, OUTCOME_DESCRIPTIONS),
		},
		severity: {
			type: ["string", "null"],
			enum: [...SEVERITY_VALUES, null],
			description: `Required for a NOT_MET outcome, from the practice's Severity section; null otherwise. ${describeVocabulary(SEVERITY_VALUES, SEVERITY_DESCRIPTIONS)}`,
		},
		evidence: {
			...evidenceSchema,
			properties: {
				citations: evidenceSchema.properties.citations,
				search: searchSchema,
				inapplicability: inapplicabilitySchema,
				undecidability: undecidabilitySchema,
			},
		},
		evidenceRationale: {
			type: "string",
			minLength: 1,
			description:
				"A concise explanation a developer can read: the source facts that decide whether this work holds " +
				"this criterion's subject and occasion, then, when it does, how the cited evidence meets or falls " +
				"short of what this criterion judges, leaving to other practices what it assigns to them. Describe " +
				"evidence, not advice, intent, confidence, or hidden chain-of-thought.",
		},
	},
} as const;

function persistUsage() {
	writeFileSync(USAGE_PATH, JSON.stringify(usageTotals, null, 2));
}
function persistRunnerDebug() {
	writeFileSync(RUNNER_DEBUG_PATH, JSON.stringify(runnerDebug, null, 2));
}
function persistReviewState() {
	writeFileSync(
		REVIEW_STATE_PATH,
		JSON.stringify({ observations: reviewState.observations }, null, 2),
	);
}

function maybeWriteResultFile(): boolean {
	if (reviewState.observations.length === 0) {
		return false;
	}
	writeFileSync(
		RESULT_PATH,
		JSON.stringify(
			{
				observations: reviewState.observations,
				...(admissionDigest === null ? {} : { admissionDigest }),
			},
			null,
			2,
		),
	);
	return true;
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
	if (compositionAdmitted) {
		persist(FEEDBACK_PATH, persistComposedFeedback);
	}
	for (const entry of readdirSync(OUTPUT)) {
		const path = `${OUTPUT}/${entry}`;
		if (!written.has(path)) {
			rmSync(path, { recursive: true, force: true });
		}
	}
}

function hasPersistedReviewState(): boolean {
	return reviewState.observations.length > 0;
}

/** A checked observation, and what the check changed about its citations, for the session to see. */
interface Validated {
	observation: NormalizedObservation;
	notes: string[];
}

/** Resolve only the path placeholders owned by the task envelope. */
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

function normalizeAndValidateObservation(rawObservation: unknown): Validated {
	const notes: string[] = [];
	const observation = normalizeObservation(rawObservation, notes, (artifact) =>
		artifactSources.get(artifact),
	);
	if (!admittedPractices.has(observation.practiceSlug)) {
		throw new Error(
			`unknown practice '${observation.practiceSlug}'; this turn's practices are ${currentTurnSlugs.join(", ")}`,
		);
	}
	const fenced = outsideActivePractice(observation.practiceSlug);
	if (fenced !== null) {
		throw new Error(fenced);
	}
	// The manifest says which source staged an artifact; a citation that names the artifact under
	// another source kind is read as the manifest reads it, and the correction is echoed back.
	for (const citation of observation.evidence.citations) {
		// A quote of a staged record (description.md, comments.json) named as its path under the pinned
		// change is a quote of that record: the artifact is the path.
		const stagedAtPath = artifactSources.get(citation.path);
		if (stagedAtPath !== undefined && citation.artifactPath !== citation.path) {
			notes.push(
				`${citation.path} is an artifact of its own, staged by ${stagedAtPath}; recorded against it, not ${citation.artifactPath}`,
			);
			citation.artifactPath = citation.path;
			citation.sourceKind = stagedAtPath;
		}
		const staged = artifactSources.get(citation.artifactPath);
		if (staged !== undefined && staged !== citation.sourceKind) {
			notes.push(
				`${citation.artifactPath} is staged by ${staged}, not ${citation.sourceKind}; recorded as ${staged}`,
			);
			citation.sourceKind = staged;
		}
		// Once the source is settled: a side selects a line of the change and a revision a commit of the
		// checkout, and neither says anything of another source. Admission refuses either one elsewhere, and
		// with it every observation the review recorded, so neither leaves the runner on any other source.
		if (citation.sourceKind !== DIFF_SOURCE && citation.side !== undefined) {
			notes.push(`${citation.path}: side dropped — only a line of the change has a side`);
			delete citation.side;
		}
		if (citation.sourceKind !== TREE_SOURCE && citation.revision !== undefined) {
			notes.push(
				`${citation.path}: revision dropped — only a repository file is read at a revision`,
			);
			delete citation.revision;
		}
	}
	validateEvidenceSources(observation, availableSourceKinds, artifactSources);
	validateSearchScope(
		observation,
		practiceExhaustiveSources.get(observation.practiceSlug) ?? new Set(),
		availableSourceKinds,
	);
	validateInapplicabilityScope(observation, availableSourceKinds);
	// A practice that reads the change grounds a decision of nothing in it, unless another observation
	// already consulted it; a practice judging comments or history alone owes the change nothing.
	if (
		(observation.outcome === "NOT_APPLICABLE" || observation.outcome === "UNDETERMINED") &&
		availableSourceKinds.has(DIFF_SOURCE) &&
		practiceIndex
			.find((practice) => practice.slug === observation.practiceSlug)
			?.readsSources.includes(DIFF_SOURCE) === true &&
		![
			...reviewState.observations.filter(
				(previous) => previous.practiceSlug !== observation.practiceSlug,
			),
			observation,
		].some(readTheChange)
	) {
		throw new Error(
			"an observation that decides nothing must show it read the change: cite a line of " +
				`${CHANGE_ROOT}/diff.patch (sourceKind ${DIFF_SOURCE}), or list ${DIFF_SOURCE} among the ` +
				"sources consulted in evidence.search or evidence.inapplicability",
		);
	}
	for (const citation of observation.evidence.citations) {
		// Match admission: repository quotes use Git blobs; change quotes use the derived diff.
		const content = citedContent(citation);
		const resolved = resolveCited(citation, content);
		if ("mismatch" in resolved) {
			throw new Error(
				`citation does not match ${citation.path}:${citation.startLine}-${citation.endLine} ` +
					`(${citation.side ?? "text"}) in '${citation.artifactPath}': ${resolved.mismatch}. Copy the exact ` +
					`artifact text and, for the change, the [L<n>] coordinates and OLD/NEW side of ${CHANGE_ROOT}/diff.patch ` +
					"(the numbers sed -n or grep -n print are lines of diff.patch, not these coordinates)",
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
	}
	// A conversation is reviewed once for each participant; a lapse must be that participant's own.
	const thread = `${CWD}/${CONVERSATION_THREAD}`;
	if (
		observation.outcome === "NOT_MET" &&
		existsSync(thread) &&
		!citesReviewedTurn(observation.evidence.citations, readFileSync(thread, "utf8"))
	) {
		throw new Error(
			`a NOT_MET of this conversation review must cite a turn of the participant under review: quote a ` +
				`line inside a turn of ${CONVERSATION_THREAD} marked "underReview": true. The other turns are ` +
				"context, and a lapse that only they show is not this participant's",
		);
	}
	return { observation, notes };
}

/** Infer an omitted diff side by exact quote match, preferring NEW; never override a supplied side. */
function resolveOnEitherSide(
	citation: NormalizedCitation,
	content: string,
): ReturnType<typeof resolveQuote> {
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

const DIFF_SOURCE = "scm.pull-request.diff";
const TREE_SOURCE = "scm.repository.tree";

/** Whether an observation cites the change or names it among the sources its warrant consulted. */
function readTheChange(observation: NormalizedObservation): boolean {
	if (observation.evidence.citations.some((citation) => citation.sourceKind === DIFF_SOURCE)) {
		return true;
	}
	const consulted = [
		...(observation.evidence.search?.consulted ?? []),
		...(observation.evidence.inapplicability?.consulted ?? []),
	];
	return consulted.includes(DIFF_SOURCE);
}

/** What a repository read returns for a file that is not text: git's own rule, a NUL in the opening bytes. */
const BINARY = Symbol("binary");

/** What a citation quotes: the checkout at HEAD or at the named revision, or the derived change view. */
function citedContent(citation: NormalizedCitation): string | typeof BINARY | null {
	if (citation.sourceKind === TREE_SOURCE) {
		return citation.revision === undefined
			? readCheckoutFile(citation.path, citationRepository(citation))
			: readRevisionFile(citation.path, citation.revision, citationRepository(citation));
	}
	if (citation.sourceKind === DIFF_SOURCE) {
		return readFileSync(`${CWD}/${CHANGE_ROOT}/diff.patch`, "utf8");
	}
	return readFileSync(`${CWD}/${citation.artifactPath}`, "utf8");
}

/** The citation resolved against what it quotes, or the mismatch that says why it cannot be. */
function resolveCited(
	citation: NormalizedCitation,
	content: string | typeof BINARY | null,
): ReturnType<typeof resolveOnEitherSide> {
	if (content === null) {
		return {
			mismatch:
				citation.revision === undefined
					? "no such file in the checkout"
					: `no such file at revision ${citation.revision} in the checkout's history`,
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

/** The bytes as text, or BINARY: a quote of a binary file cannot be verified, here or at admission. */
function asText(bytes: Buffer): string | typeof BINARY {
	return bytes.subarray(0, 8000).includes(0) ? BINARY : bytes.toString("utf8");
}

/** The artifact index, not the task's primary checkout, determines which repository a citation reads. */
function citationRepository(citation: NormalizedCitation): string {
	const match = /^repos\/(?<repository>[A-Za-z0-9_-]+)\/\.git\/HEAD$/u.exec(citation.artifactPath);
	if (!match?.groups || typeof match.groups.repository !== "string") {
		throw new Error("Repository citation requires a captured repository HEAD");
	}
	return nodePath.resolve(CWD, "repos", match.groups.repository);
}

/** The blob at a repository-relative path in a revision of the checkout's history, or null. */
function readRevisionFile(
	path: string,
	revision: string,
	repository: string,
): string | typeof BINARY | null {
	if (path.startsWith("/") || path.split("/").includes("..")) {
		return null;
	}
	const child = spawnSync("git", ["-C", repository, "--no-pager", "show", `${revision}:${path}`], {
		maxBuffer: 64 * 1024 * 1024,
		env: { ...process.env, GIT_TERMINAL_PROMPT: "0", GIT_OPTIONAL_LOCKS: "0" },
	});
	return child.status === 0 ? asText(child.stdout) : null;
}

/** The file at a repository-relative path in the checkout, or null when there is none. */
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

/** A draft disposition; its practice slug is its stable reference within this review. */
type Recorded =
	| (({ kind: "stored" } | { kind: "revised" }) & {
			slug: string;
			negative: boolean;
			filled: string[];
	  })
	| { kind: "duplicate"; slug: string }
	| { kind: "refused"; slug: string; reason: string };

/** Set once the recorded observations have gone to admission; no observation is accepted after it. */
let measurementClosed = false;

/** Calls of the recording tools one turn may make before recording anything; past it, the turn ends. */
const MAX_RECORDING_ATTEMPTS_PER_TURN = 24;

/** Per-turn thresholds for nudging and aborting identical tool calls. */
const REPEATED_CALL_NUDGE = 3;
const REPEATED_CALL_ABORT = 6;
/**
 * The same thresholds for a recording call: its answer is a function of its arguments, so the second
 * identical call already shows a loop, and a nudge has never broken one.
 */
const REPEATED_RECORDING_NUDGE = 2;
const REPEATED_RECORDING_ABORT = 3;

/** Avoid logging SDK error events again when the tool already logged its refusal. */
const answeredRefusals = new Set<string>();

/** A refusal the tool itself is answering: logged where it was decided, and once. */
async function refusal<T>(toolCallId: string, text: string): Promise<AgentToolResult<T>> {
	answeredRefusals.add(toolCallId);
	throw new Error(text);
}

/**
 * The tools a turn exists to call: what it records with them is what it owes. read_practice stores nothing, but its
 * answer is a function of its arguments like theirs, so it shares their repeat and attempt bounds.
 */
const RECORDING_TOOLS: ReadonlySet<string> = new Set([
	"report_observation",
	"report_feedback",
	"read_practice",
	"report_review",
]);

/** The tool the composition turn under way records with: the review on the work, or the private lanes. */
let composerTool: "report_review" | "report_feedback" = "report_feedback";

/** The text of a tool result as the model reads it; empty when the result carries none. */
function toolResultText(result: unknown): string {
	return jsonArray(isRecord(result) ? result.content : null)
		.map((block) => (isRecord(block) && typeof block.text === "string" ? block.text : ""))
		.filter(Boolean)
		.join("\n");
}

/** How much of a tool error the log and the trace keep: the kind of failure, not the whole excerpt. */
const TOOL_ERROR_CHARS = 240;

/** A reason on one line, bounded: the SDK puts what it refused on the line after its heading. */
function firstLine(text: string): string {
	const line = text.replaceAll(/\s+/gu, " ").trim() || "(no reason given)";
	return line.length > TOOL_ERROR_CHARS ? `${line.slice(0, TOOL_ERROR_CHARS)}…` : line;
}

/** The session the turn events belong to, once it exists; what the loop bound aborts. */
let activeSession: AgentSession | null = null;

/**
 * What the turn in flight still owes and how it is told to record it; set and cleared with its trace.
 * Measuring owes one observation per practice, composition one decision per NOT_MET practice.
 * `endsWhenPaid` marks a turn that exists only to pay what it owes, so it ends once nothing is owed.
 */
interface TurnDemand {
	owed: () => number;
	nudge: string;
	endsWhenPaid?: true;
}
let turnDemand: TurnDemand | null = null;

/**
 * Output tokens the model spends per observation it records, averaged over its recording messages: what
 * a turn must keep back to record what it owes. The prior is a slow open model's pace until the session
 * has recorded once.
 */
let tokensPerObservation = 1500;
/** Output tokens of the last assistant message, when that message carries a recording call. */
let recordingMessageTokens = 0;
/** When the session last showed any sign of life; what the stall watchdog reads. */
let lastEventAt = Date.now();

/** How many observations a report_observation call carried, stored, duplicate or refused alike. */
function observationsIn(result: unknown): number {
	const details = isRecord(result) ? result.details : undefined;
	if (!isRecord(details)) {
		return 0;
	}
	return [details.inserted, details.duplicates, details.refused]
		.map((count) => (typeof count === "number" ? count : 0))
		.reduce((sum, count) => sum + count, 0);
}

/**
 * Charges each model call to the turn in flight. The turn is told once to record what it owes when its
 * remaining work only pays for that, and is ended between model calls — after the tools of the call that
 * spent the budget have run, so a recording in that call lands — never within one.
 */
function chargeWork(event: AgentSessionEvent): void {
	lastEventAt = Date.now();
	const turn = currentTurn;
	if (event.type === "message_end" && event.message.role === "assistant") {
		const output = outputTokensOf(event.message);
		recordingMessageTokens = listOrEmpty(event.message.content).some(
			(c) => c.type === "toolCall" && c.name === "report_observation",
		)
			? output
			: 0;
		if (!turn || !turnDemand) {
			return;
		}
		turn.calls += 1;
		turn.outputTokens += output;
		noteCutOff(turn, event.message.stopReason === "length" || output >= outputLimit);
		const used: Work = { modelCalls: turn.calls, outputTokens: turn.outputTokens };
		if (
			!turn.askedToRecord &&
			turn.stoppedBy === null &&
			shouldRecordNow(used, turn.budget, turnDemand.owed(), tokensPerObservation)
		) {
			turn.askedToRecord = true;
			console.error(
				`[pi-runner] ${turn.label}: ${turn.calls} of ${turn.budget.modelCalls} calls and ` +
					`${turn.outputTokens} of ${turn.budget.outputTokens} output tokens spent — nudging to record`,
			);
			if (activeSession) {
				void steer(activeSession, turnDemand.nudge);
			}
		}
		return;
	}
	if (event.type === "tool_execution_end" && event.toolName === "report_observation") {
		const written = observationsIn(event.result);
		if (written > 0 && recordingMessageTokens > 0) {
			tokensPerObservation = Math.round(
				(tokensPerObservation + recordingMessageTokens / written) / 2,
			);
		}
		recordingMessageTokens = 0;
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

/**
 * A call cut off at the output limit reaches its tool incomplete, and the tool's error reads like the
 * model's mistake: the model is told it was the cut, once; a second cut in the turn ends it.
 */
function noteCutOff(turn: TurnTrace, cutOff: boolean): void {
	if (!cutOff) {
		return;
	}
	turn.cutOff += 1;
	sessionGuards.cutOff += 1;
	if (sessionGuards.cutOff >= CUT_OFF_ABORT) {
		stopTurn(
			"loop",
			`${sessionGuards.cutOff} calls in this session ran into the ${outputLimit}-token output limit — aborting this turn`,
		);
		return;
	}
	console.error(`[pi-runner] ${turn.label}: a call ran into the output limit — telling the model`);
	if (activeSession) {
		void steer(
			activeSession,
			`Your last call was cut off at the ${outputLimit}-token output limit, so its tool received it ` +
				"incomplete and failed: the error it reports is the cut, not your syntax. Send a short call " +
				"instead — a search with a pattern rather than a list of every value. Keep each recording " +
				"call complete.",
		);
	}
}

/** End the turn in flight for a reason of the runner's own; the first reason is the one the trace keeps. */
function stopTurn(reason: StopReason, why: string): void {
	const turn = currentTurn;
	console.error(`[pi-runner] ${turn?.label ?? "review"}: ${why}`);
	if (turn && turn.stoppedBy === null) {
		turn.stoppedBy = reason;
	}
	if (activeSession) {
		void abortSession(activeSession);
	}
}

/** Refused submissions per practice; a practice past MAX_REFUSALS_PER_PRACTICE accepts no more. */
const refusals = new Map<string, number>();
/**
 * Practices this review asks no more about: past the refusal limit, too large for the context, or ended on their
 * own again after the one request to record. Without a draft, each stays not reached.
 */
const blockedPractices = new Set<string>();

/** Whether the practice was closed by its refused submissions, not by another reason. */
function refusalLimited(slug: string): boolean {
	return (refusals.get(slug) ?? 0) >= MAX_REFUSALS_PER_PRACTICE;
}

function countRefusal(slug: string): void {
	const count = (refusals.get(slug) ?? 0) + 1;
	refusals.set(slug, count);
	if (count >= MAX_REFUSALS_PER_PRACTICE) {
		blockedPractices.add(slug);
	}
}

function slugOf(raw: unknown): string {
	return (isRecord(raw) ? normalizePracticeSlug(raw.practiceSlug) : "") || "unknown";
}

/**
 * The items refused in this turn, as sent, with why: an identical resend gets the same answer, so it is
 * told so at once rather than checked again; cleared with the turn.
 */
const refusedItems = new Map<string, { reason: string; resent: number }>();

/** An identical refused item sent this many times more ends the turn: the circuit breaker. */
const IDENTICAL_REFUSAL_ABORT = 2;

function record(raw: unknown): Recorded {
	const slug = slugOf(raw);
	// Answered before anything is counted: an item for a known practice outside the session's own neither
	// records, revises, nor spends that practice's refusals.
	const fenced = admittedPractices.has(slug) ? outsideActivePractice(slug) : null;
	if (fenced !== null) {
		return { kind: "refused", slug, reason: fenced };
	}
	// An item with no slug is answered with what it lacks, never with the bound of a practice named
	// "unknown": the refusal it is counted under is a bookkeeping name, not one the session sent.
	if (slug !== "unknown" && blockedPractices.has(slug)) {
		return {
			kind: "refused",
			slug,
			reason: refusalLimited(slug)
				? `${MAX_REFUSALS_PER_PRACTICE} submissions for '${slug}' were refused; no more are accepted for it, so it is not owed any more. Record the other practices.`
				: `this review asks no more about '${slug}'; no more submissions are accepted for it.`,
		};
	}
	const sent = JSON.stringify(raw);
	const earlier = refusedItems.get(sent);
	if (earlier !== undefined) {
		earlier.resent += 1;
		countRefusal(slug);
		if (earlier.resent >= IDENTICAL_REFUSAL_ABORT) {
			stopTurn(
				"loop",
				`the same refused ${slug} observation sent ${earlier.resent + 1} times — aborting this turn`,
			);
		}
		return {
			kind: "refused",
			slug,
			reason:
				`identical to an item this turn already refused, so the answer is the same: ${earlier.reason}. ` +
				"Change what that names, or leave this practice and record the others",
		};
	}
	const revises = isRecord(raw) ? raw.revises : undefined;
	const index = reviewState.observations.findIndex((draft) => draft.practiceSlug === slug);
	if (
		index === -1 ? revises !== null : revises !== null && revises !== undefined && revises !== slug
	) {
		countRefusal(slug);
		return {
			kind: "refused",
			slug,
			// Before a first draft no reference was ever returned, so naming one cannot be the correction.
			reason:
				index === -1
					? `No draft reference has been issued for '${slug}': use revises: null for its first observation.`
					: `revises must name this practice's draft reference, '${slug}'.`,
		};
	}
	const candidate = isRecord(raw) ? { ...raw } : raw;
	if (isRecord(candidate)) {
		delete candidate.revises;
	}
	let validated: Validated;
	try {
		validated = normalizeAndValidateObservation(candidate);
	} catch (error) {
		countRefusal(slug);
		const reason = errorText(error);
		refusedItems.set(sent, { reason, resent: 0 });
		return { kind: "refused", slug, reason };
	}
	const { observation, notes } = validated;
	const previous = reviewState.observations[index];
	if (previous !== undefined && isDeepStrictEqual(previous, observation)) {
		return { kind: "duplicate", slug };
	}
	if (previous !== undefined && revises !== slug) {
		countRefusal(slug);
		return {
			kind: "refused",
			slug,
			reason: `Draft '${slug}' already exists. To correct it, resend the complete observation with revises: '${slug}'.`,
		};
	}
	if (previous === undefined) {
		reviewState.observations.push(observation);
	} else {
		reviewState.observations[index] = observation;
	}
	return {
		kind: previous === undefined ? "stored" : "revised",
		slug,
		negative: observation.outcome === "NOT_MET",
		filled: notes,
	};
}

/** Rebuild the notes from current drafts instead of retaining replaced results. */
function persistRecordedNotes(): void {
	try {
		mkdirSync(nodePath.dirname(NOTES_PATH), { recursive: true });
		writeFileSync(NOTES_PATH, `# Current observation drafts\n\n${recordedSoFar()}\n`);
	} catch (error) {
		console.error(`[pi-runner] notes could not be written: ${errorText(error)}`);
	}
}

/** One line per current draft, of the given practices or of all, for a session's opening and the review's notes. */
function recordedSoFar(only?: readonly string[]): string {
	const drafts = reviewState.observations.filter(
		(observation) => only === undefined || only.includes(observation.practiceSlug),
	);
	if (drafts.length === 0) {
		return "Nothing recorded yet.";
	}
	return drafts
		.map((observation) => {
			const verdict = observation.outcome;
			const cited = [...new Set(observation.evidence.citations.map((citation) => citation.path))];
			return `- ${observation.practiceSlug}: ${verdict} — ${observation.summary} (cites ${cited.join(", ")})`;
		})
		.join("\n");
}

interface ReportObservationDetails {
	inserted: number;
	revised: number;
	duplicates: number;
	refused: number;
	totalObservations: number;
	remainingPractices: string[];
}

const MAX_REFUSAL_LOG_CHARS = 500;

function logRefusal(slug: string, reason: string): void {
	const line = `[pi-runner] observation refused for ${slug}: ${reason}`;
	console.error(
		line.length > MAX_REFUSAL_LOG_CHARS ? `${line.slice(0, MAX_REFUSAL_LOG_CHARS)}…` : line,
	);
	if (currentTurn) {
		currentTurn.refused += 1;
		currentTurn.refusalReasons.push(
			reason.length > TRACE_REASON_CHARS ? `${reason.slice(0, TRACE_REASON_CHARS)}…` : reason,
		);
	}
}

/** The practices the current session asked about; a recorded result for one of them is what it owes. */
let currentTurnSlugs: readonly string[] = [];

/**
 * The one practice the measuring session in flight assesses, or null outside measurement. Each practice gets
 * a session of its own, and an item for another practice is refused before it can record or revise anything.
 */
let activePractice: string | null = null;

/** Why an item for this practice falls outside the session's own, or null when it does not. */
function outsideActivePractice(slug: string): string | null {
	return activePractice === null || slug === activePractice
		? null
		: `this session assesses only '${activePractice}'; '${slug}' is recorded and revised only in its own session`;
}

/** The given practices, by default the session's, with no recorded result that may still get one. */
function owedPractices(slugs: readonly string[] = currentTurnSlugs): string[] {
	const observed = new Set(reviewState.observations.map((item) => item.practiceSlug));
	return slugs.filter((slug) => !observed.has(slug) && !blockedPractices.has(slug));
}

/** JSON Schema keywords that refuse; what they say is applied per unit instead. */
const RULE_KEYWORDS = new Set([
	"required",
	"additionalProperties",
	"minLength",
	"maxLength",
	"minItems",
	"maxItems",
	"pattern",
	"minimum",
	"maximum",
]);

/**
 * Pi validates a whole tool call before execution. For a tool that takes a batch of independent units, keep shape
 * hints here and let the tool validate each unit, so one invalid unit does not discard the rest of the batch.
 */
function documentedShape(schema: unknown): unknown {
	if (Array.isArray(schema)) {
		return schema.map(documentedShape);
	}
	if (!isRecord(schema)) {
		return schema;
	}
	const out: Record<string, unknown> = {};
	// Container types go, so a list may arrive serialized and each unit is answered on its own; scalar
	// types stay, because they tell the model what a value is.
	const structural = schema.type === "object" || schema.type === "array";
	const noted = (note: string) => {
		const description = typeof out.description === "string" ? out.description : "";
		out.description = description ? `${description} ${note}` : note;
	};
	if (typeof schema.description === "string") {
		out.description = schema.description;
	}
	for (const [key, value] of Object.entries(schema)) {
		if (key === "description") {
			continue;
		}
		// What the schema required is still said, in words: the rule is applied by the tool, but the
		// session is told which fields a call cannot do without.
		if (key === "required" && Array.isArray(value) && value.length > 0) {
			noted(`Required: ${value.map(String).join(", ")}.`);
			continue;
		}
		if (RULE_KEYWORDS.has(key) || (key === "type" && structural)) {
			continue;
		}
		if (key === "enum" && Array.isArray(value)) {
			const listed = value.map((item) => (item === null ? "null" : String(item))).join(", ");
			const description = typeof out.description === "string" ? out.description : "";
			if (!description.includes(listed.split(", ")[0] ?? "")) {
				noted(`One of: ${listed}.`);
			}
			continue;
		}
		out[key] = documentedShape(value);
	}
	return out;
}

function buildReportObservationTool() {
	return defineTool({
		name: "report_observation",
		exposure: "model-only",
		label: "Report Observation",
		description:
			"Record one complete evidenced observation for this session’s active practice in local review state, " +
			"for server admission after measuring. The arguments are that one observation. Its draft reference is " +
			"the practice slug. Correct it explicitly with revises and a complete observation.",
		parameters: observationSchema,
		prepareArguments: prepareObservationArguments,
		execute: async (toolCallId, params): Promise<AgentToolResult<ReportObservationDetails>> => {
			if (measurementClosed) {
				const text = "Measurement is closed; this turn may only compose feedback.";
				return {
					content: [{ type: "text", text }],
					details: {
						inserted: 0,
						revised: 0,
						duplicates: 0,
						refused: 0,
						totalObservations: reviewState.observations.length,
						remainingPractices: [],
					},
				};
			}
			// The observation is a typed value, never a second JSON document to parse or repair.
			if (!isRecord(params) || Array.isArray(params)) {
				const reason = "the arguments must be one observation object";
				logRefusal("(not an object)", reason);
				return refusal(toolCallId, `observation refused — ${reason}. No draft changed.`);
			}
			const outcomes = [record(params)];
			const stored = outcomes.filter(
				(outcome) => outcome.kind === "stored" || outcome.kind === "revised",
			);
			for (const outcome of outcomes) {
				if (outcome.kind === "refused") {
					logRefusal(outcome.slug, outcome.reason);
				}
			}
			if (currentTurn) {
				currentTurn.stored += stored.length;
				sessionGuards.stored += stored.length;
			}
			if (stored.length > 0) {
				persistReviewState();
				maybeWriteResultFile();
				persistPracticeCoverage();
				persistRecordedNotes();
			}
			// A practice past its refusal limit is not owed any more: listing it as owed invites the resend
			// it can no longer accept.
			const remainingPractices = owedPractices();
			const closed = currentTurnSlugs.filter((slug) => blockedPractices.has(slug));
			// A practice closed before its first draft is owed nothing and still has no result.
			const recorded = new Set(reviewState.observations.map((item) => item.practiceSlug));
			const exhausted = closed.filter((slug) => !recorded.has(slug));
			const lines = outcomes.map((outcome) => {
				const head = `${outcome.slug}:`;
				if (outcome.kind === "stored" || outcome.kind === "revised") {
					return `${head} ${outcome.kind}${outcome.negative ? " (negative)" : ""}. Draft reference: '${outcome.slug}'.${outcome.filled.map((line) => `\n   ${line}`).join("")}`;
				}
				if (outcome.kind === "duplicate") {
					return `${head} already recorded; this item changed nothing, so do not send it again.`;
				}
				return `${head} refused — ${outcome.reason}`;
			});
			if (remainingPractices.length > 0) {
				lines.push(`No recorded result yet for: ${remainingPractices.join(", ")}.`);
			}
			if (exhausted.length > 0) {
				lines.push(`No recorded result for: ${exhausted.join(", ")}.`);
			}
			if (remainingPractices.length === 0 && exhausted.length === 0) {
				lines.push("Every practice of this turn has a recorded result.");
			}
			const limited = closed.filter(refusalLimited);
			if (limited.length > 0) {
				lines.push(`No longer accepted (refusal limit): ${limited.join(", ")}.`);
			}
			const closedOtherwise = closed.filter((slug) => !refusalLimited(slug));
			if (closedOtherwise.length > 0) {
				lines.push(`No longer accepted: ${closedOtherwise.join(", ")}.`);
			}
			const text = lines.join("\n");
			const details: ReportObservationDetails = {
				inserted: outcomes.filter((outcome) => outcome.kind === "stored").length,
				revised: outcomes.filter((outcome) => outcome.kind === "revised").length,
				duplicates: outcomes.filter((outcome) => outcome.kind === "duplicate").length,
				refused: outcomes.filter((outcome) => outcome.kind === "refused").length,
				totalObservations: reviewState.observations.length,
				remainingPractices,
			};
			// A call that stored nothing is an error the session must correct — a resend of what is already
			// recorded included, or it reads as success and is sent again.
			if (stored.length === 0) {
				return refusal(toolCallId, text);
			}
			// The turn owes nothing more once each of its practices has a result; ending the run here
			// spares the wrap-up calls a session otherwise makes after its last recording.
			return {
				content: [{ type: "text", text }],
				details,
				terminate: remainingPractices.length === 0,
			};
		},
	});
}

function accumulateUsage(prev: UsageReport | null, curr: UsageReport): void {
	usageTotals.model = curr.model ?? usageTotals.model;
	usageTotals.inputTokens += Math.max(0, curr.inputTokens - (prev?.inputTokens ?? 0));
	usageTotals.outputTokens += Math.max(0, curr.outputTokens - (prev?.outputTokens ?? 0));
	usageTotals.reasoningTokens += Math.max(0, curr.reasoningTokens - (prev?.reasoningTokens ?? 0));
	usageTotals.cacheReadTokens += Math.max(0, curr.cacheReadTokens - (prev?.cacheReadTokens ?? 0));
	usageTotals.cacheWriteTokens += Math.max(
		0,
		curr.cacheWriteTokens - (prev?.cacheWriteTokens ?? 0),
	);
	usageTotals.costUsd += Math.max(0, curr.costUsd - (prev?.costUsd ?? 0));
	usageTotals.totalCalls += Math.max(0, curr.totalCalls - (prev?.totalCalls ?? 0));
}

function loadPracticeSlugs(): string[] {
	return practiceIndex.map((practice) => practice.slug).filter(Boolean);
}

let practiceCoverageLedger: PracticeCoverageLedger | null = null;

function persistPracticeCoverage() {
	if (practiceCoverageLedger === null) {
		throw new Error("practice coverage ledger is not initialized");
	}
	return practiceCoverageLedger.markEvaluated(
		reviewState.observations.map((item) => item.practiceSlug),
	);
}

function logPracticeCoverage() {
	const coverage = persistPracticeCoverage();
	const ratio =
		coverage.eligible === 0 ? "n/a" : (coverage.evaluated / coverage.eligible).toFixed(4);
	console.error(
		`[pi-runner] Practice coverage: evaluated=${coverage.evaluated}, eligible=${coverage.eligible}, ratio=${ratio}`,
	);
}

/** What a private composer that reads instead of writing is told, at the exploration bound and near the budget's end. */
const COMPOSITION_NUDGE =
	`Stop reading: the admitted observations and the history are in this turn's prompt and in ` +
	`work/composition/observations.json, and nothing else decides a unit. Persist the units you have ` +
	`with report_feedback now — and a WITHHOLD with its reason for each NOT_MET practice you decided ` +
	`to stay quiet about. Use tools only from this point onward; no planning prose.`;

/** What the composer of the review on the work is told near its budget's end. */
const REVIEW_NUDGE =
	`Everything this review may rest on is in this session. Store the final review now with one report_review call: ` +
	`first one decision for each NOT_MET observation, RAISE or a withholding reason, then the complete summary and ` +
	`any line notes, which speak about each raised observation and no withheld one. ` +
	`Read a MET practice's complete reference with read_practice first only if the review acknowledges it and that ` +
	`reference is not yet in view. No prose outside the calls.`;

/** Calls a composition may make before its first recording call; at this one it is nudged to persist. */
const COMPOSITION_EXPLORATION_NUDGE = 12;

const PERSIST_DISCIPLINE =
	`Record the outcome the evidence supports, MET, NOT_MET, NOT_APPLICABLE, or UNDETERMINED; there is no quota ` +
	`and no next step to write. Use tools only from this point onward; no planning prose.`;

/** What a turn is told once its remaining work only pays for recording what it owes. */
const RECORD_NUDGE = `This turn has the work left to write its observations and no more. Stop exploring. Record what the inspected evidence supports for this session's practice with report_observation. ${PERSIST_DISCIPLINE}`;

/**
 * The one request a measuring session gets when it ended on its own with its practice unrecorded. It goes to the
 * same session, which still holds what it read, and leaves a collection gap unrecorded.
 */
function recordOnceMoreText(slug: string): string {
	return (
		`## Not recorded\nThis session ended without an observation for ${slug}: text outside a ` +
		`report_observation call records nothing. Record the outcome its criterion and the evidence you read ` +
		`support, in one report_observation call. If a required source is missing, truncated or blocked, that ` +
		`is a collection gap: record nothing.`
	);
}

/** Tells the server to retry a review whose admission endpoint was unreachable. */
const SERVER_UNREACHABLE_EXIT = 75;
/** Tells the server to retry a review that recorded nothing after provider failures. */
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
		parsed = parseJson(raw);
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
		jobId: envelope.jobId,
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

const COMPOSITION_REQUEST_PATH = INPUT_PATHS.compositionRequest;
const FEEDBACK_PATH = outputPath(OUTPUT, "feedback.json");
const COMPOSER_PROMPT_PATH = `${CWD}/feedback-composer.md`;
const REVIEW_COMPOSER_PROMPT_PATH = `${CWD}/review-composer.md`;
/** The writing style both compositions share, ahead of each one's own instructions. */
const FEEDBACK_STYLE_PATH = `${CWD}/feedback-style.md`;
const PREPARED_FEEDBACK_PATH = INPUT_PATHS.preparedFeedback;
const COMPOSITION_OBSERVATIONS_PATH = `${CWD}/work/composition/observations.json`;
const IN_APP_SUPPORT_PATH = `${CWD}/work/composition/in-app-support.json`;

/** One occurrence an IN_APP card may cite, as the server selected it after admission. */
interface InAppSupportOccurrence {
	observationId: string;
	artifactKind: string;
	artifactId: number;
	outcome: "NOT_MET";
	origin: "LIVE" | "MANUAL" | "BACKFILL";
	summary: string;
	evidenceRationale: string | null;
	evidence: unknown;
	practiceRevisionId: number | null;
	observedAt: string;
}

/** The server's dated IN_APP support read; null until admission, and null when it arrived malformed. */
type InAppSupport =
	| {
			state: "COMPLETE";
			readAt: string;
			practices: { practiceSlug: string; occurrences: InAppSupportOccurrence[] }[];
	  }
	| { state: "UNAVAILABLE" | "REFUSED"; readAt: string; refusal: string | null };
let inAppSupport: InAppSupport | null = null;

function readSupportOccurrence(value: unknown): InAppSupportOccurrence | null {
	if (
		!isRecord(value) ||
		typeof value.observationId !== "string" ||
		value.observationId === "" ||
		typeof value.artifactKind !== "string" ||
		value.artifactKind === "" ||
		typeof value.artifactId !== "number" ||
		!Number.isSafeInteger(value.artifactId) ||
		value.artifactId <= 0 ||
		value.outcome !== "NOT_MET" ||
		(value.origin !== "LIVE" && value.origin !== "MANUAL" && value.origin !== "BACKFILL") ||
		typeof value.summary !== "string" ||
		(value.evidenceRationale !== undefined &&
			value.evidenceRationale !== null &&
			typeof value.evidenceRationale !== "string") ||
		(value.practiceRevisionId !== undefined &&
			value.practiceRevisionId !== null &&
			(typeof value.practiceRevisionId !== "number" ||
				!Number.isSafeInteger(value.practiceRevisionId) ||
				value.practiceRevisionId <= 0)) ||
		typeof value.observedAt !== "string" ||
		!Number.isFinite(Date.parse(value.observedAt))
	) {
		return null;
	}
	return {
		observationId: value.observationId,
		artifactKind: value.artifactKind,
		artifactId: value.artifactId,
		outcome: value.outcome,
		origin: value.origin,
		summary: value.summary,
		evidenceRationale: value.evidenceRationale ?? null,
		evidence: value.evidence ?? null,
		practiceRevisionId: value.practiceRevisionId ?? null,
		observedAt: value.observedAt,
	};
}

/** Validates the support read beside the admission; anything else is no support context at all. */
function readInAppSupport(
	value: unknown,
	current: readonly AdmittedObservation[],
): InAppSupport | null {
	if (
		!isRecord(value) ||
		typeof value.readAt !== "string" ||
		!Number.isFinite(Date.parse(value.readAt))
	) {
		return null;
	}
	if (value.state === "UNAVAILABLE" || value.state === "REFUSED") {
		if (
			value.refusal !== undefined &&
			value.refusal !== null &&
			typeof value.refusal !== "string"
		) {
			return null;
		}
		return { state: value.state, readAt: value.readAt, refusal: value.refusal ?? null };
	}
	if (value.state !== "COMPLETE" || !Array.isArray(value.practices)) {
		return null;
	}
	const practices: { practiceSlug: string; occurrences: InAppSupportOccurrence[] }[] = [];
	const expected = new Set(notMetPractices(current));
	for (const practice of value.practices) {
		if (
			!isRecord(practice) ||
			typeof practice.practiceSlug !== "string" ||
			!expected.delete(practice.practiceSlug) ||
			!Array.isArray(practice.occurrences)
		) {
			return null;
		}
		const occurrences = practice.occurrences.map(readSupportOccurrence);
		if (!occurrences.every((occurrence) => occurrence !== null)) {
			return null;
		}
		practices.push({ practiceSlug: practice.practiceSlug, occurrences });
	}
	if (expected.size > 0) {
		return null;
	}
	return { state: "COMPLETE", readAt: value.readAt, practices };
}
const PUBLIC_FEEDBACK_HISTORY_PATH = `${CWD}/work/composition/public-feedback-history.json`;
let compositionAdmitted = false;
let admissionDigest: string | null = null;

type Channel = (typeof CHANNELS)[number];
type PrivateChannel = (typeof PRIVATE_CHANNELS)[number];
type FeedbackAction = (typeof ACTIONS)[number];

function isChannel(value: unknown): value is Channel {
	return CHANNELS.some((channel) => channel === value);
}

function isFeedbackAction(value: unknown): value is FeedbackAction {
	return ACTIONS.some((action) => action === value);
}

/** A string the model sent, or nothing — used for every optional free-text field on a feedback unit. */
function optionalString(value: unknown): string | undefined {
	return typeof value === "string" ? value : undefined;
}

/** The notes an IN_CHAT unit carries TO the mentor. Kept in step with ConversationBrief by Java. */
interface ConversationNotes {
	situation?: string;
	capability?: string;
	evidenceSummary?: string;
	inConversationSignal?: string;
	alreadySaid?: string;
}

/** A private-lane unit as read; per-unit validation in readFeedbackUnit/validateUnit. */
interface FeedbackUnit extends ComposedFeedbackUnit {
	channel: PrivateChannel;
	practiceSlug: string;
	basedOn: string[];
	action: FeedbackAction;
	supersedesThreadKey?: string;
	withholdReason?: string;
	title?: string;
	body?: string;
	nextStep?: string;
	notes?: ConversationNotes;
}

/** The observation fields the composer is shown: enough to reference one, never enough to author one. */
interface LeanCitation {
	index: number;
	sourceKind: unknown;
	path: unknown;
	side: unknown;
	startLine: unknown;
	endLine: unknown;
	anchorable: unknown;
}

interface LeanObservation {
	id: string;
	practiceSlug: string;
	outcome: unknown;
	severity: unknown;
	anchorable: unknown;
	citations: LeanCitation[];
}

/** What gets written to feedback.json, with the fields the reader resolves references against. */
type CompositionPhase = "PUBLIC_REVIEW" | "PRIVATE_FEEDBACK";
type CompositionFailureReason =
	| "MODEL_ERROR"
	| "RUNTIME_ERROR"
	| "BUDGET"
	| "STALL"
	| "SAFETY"
	| "LOOP"
	| "NO_DECISION";
interface CompositionFailure {
	phase: CompositionPhase;
	reason: CompositionFailureReason;
}

interface ComposedFeedback extends ComposedFeedbackEnvelope {
	compositionFailures: CompositionFailure[];
	contractVersion: number;
	admissionDigest: string | null;
	observations: LeanObservation[];
	preparedTargets: PreparedFeedbackTarget[];
	units: FeedbackUnit[];
	review: ComposedReview | null;
}

// Echo the exact composition inputs so Java validates references against the same snapshot.
const composedFeedback: ComposedFeedback = {
	compositionFailures: [],
	contractVersion: REVIEW_CONTRACT_VERSION,
	admissionDigest: null,
	observations: [],
	preparedTargets: [],
	units: [],
	review: null,
};

/**
 * Admission assigns observation IDs and anchor eligibility. Populate this captured array in place
 * because the composer tool is created before admission.
 */
const admittedObservations: AdmittedObservation[] = [];

function isPlacementKind(value: unknown): value is PlacementKind {
	return value === "DIFF" || value === "ARTIFACT";
}

function loadCompositionRequest(): CompositionRequest | null {
	try {
		if (!existsSync(COMPOSITION_REQUEST_PATH)) {
			return null;
		}
		const parsed = parseJson(readFileSync(COMPOSITION_REQUEST_PATH, "utf8"));
		if (!isRecord(parsed) || parsed.enabled !== true) {
			return null;
		}
		const declared: Record<string, unknown> = isRecord(parsed.channels) ? parsed.channels : {};
		const boundsFor = (channel: Channel): ChannelBounds => {
			const bounds: Record<string, unknown> = isRecord(declared[channel]) ? declared[channel] : {};
			return {
				enabled: bounds.enabled === true,
				maxUnits: Math.max(0, Math.min(Number(bounds.maxUnits) || 0, 10)),
			};
		};
		// Spelled out rather than folded into the CHANNELS loop, so that Record<Channel, …> is what makes
		// every lane present: adding a channel to CHANNELS then fails to compile until it is bounded here.
		const channels: Record<Channel, ChannelBounds> = {
			IN_CONTEXT: boundsFor("IN_CONTEXT"),
			IN_APP: boundsFor("IN_APP"),
			IN_CHAT: boundsFor("IN_CHAT"),
		};
		if (!CHANNELS.some((channel) => channels[channel].enabled && channels[channel].maxUnits > 0)) {
			return null;
		}
		const inContextPlacementKinds = jsonArray(parsed.inContextPlacementKinds).filter(
			isPlacementKind,
		);
		if (channels.IN_CONTEXT.enabled && inContextPlacementKinds.length === 0) {
			return null;
		}
		return {
			channels,
			inContextPlacementKinds,
			minDistinctArtifacts: Math.max(2, Number(parsed.minDistinctArtifacts) || 2),
		};
	} catch (error) {
		console.error(`[pi-runner] composition request unreadable: ${errorText(error)}`);
		return null;
	}
}

function composablePracticeSlugs(): string[] {
	return [...admittedPractices].toSorted();
}

// Supersession is limited to unread thread keys present in this snapshot.
function stagedPreparedTargets(): PreparedFeedbackTarget[] {
	try {
		if (!existsSync(PREPARED_FEEDBACK_PATH)) {
			return [];
		}
		const prepared = parseJson(readFileSync(PREPARED_FEEDBACK_PATH, "utf8"));
		const entries = isRecord(prepared) ? jsonArray(prepared.prepared) : [];
		return entries.flatMap((entry) => {
			if (!isRecord(entry)) {
				return [];
			}
			const { threadKey, channel, practiceSlug } = entry;
			return typeof threadKey === "string" &&
				threadKey.trim() &&
				isChannel(channel) &&
				typeof practiceSlug === "string" &&
				practiceSlug.trim()
				? [{ threadKey, channel, practiceSlug }]
				: [];
		});
	} catch (error) {
		console.error(`[pi-runner] prepared feedback unreadable for composition: ${errorText(error)}`);
		return [];
	}
}

/**
 * The `channel:practice` pairs the staged delivered feedback can show as already said. The server chose the person
 * and the records this review may read; a record counts only when it is current, not withdrawn, carries its staged
 * words and a delivery time, and rests on a NOT_MET observation of the practice. Prepared feedback reached no one, and
 * a history that cannot be read does not establish prior communication.
 */
function stagedDeliveredPractices(): Set<string> {
	const file = `${nodePath.dirname(PREPARED_FEEDBACK_PATH)}/feedback.json`;
	try {
		if (!existsSync(file)) {
			return new Set();
		}
		const history = parseJson(readFileSync(file, "utf8"));
		if (
			!isRecord(history) ||
			history.recordRole !== "RECORDED_DELIVERY" ||
			!Array.isArray(history.feedback)
		) {
			return new Set();
		}
		return new Set(
			history.feedback.flatMap((entry: unknown) => {
				if (!isRecord(entry)) {
					return [];
				}
				const { channel, recordedClaimCurrentness, withdrawn, body, deliveredAt } = entry;
				if (
					(channel !== "IN_APP" && channel !== "IN_CHAT") ||
					recordedClaimCurrentness !== "CURRENT" ||
					(withdrawn !== undefined && withdrawn !== false) ||
					typeof body !== "string" ||
					body.trim() === "" ||
					typeof deliveredAt !== "string" ||
					!Number.isFinite(Date.parse(deliveredAt))
				) {
					return [];
				}
				return jsonArray(entry.basedOn).flatMap((support) =>
					isRecord(support) && support.outcome === "NOT_MET"
						? [`${channel}:${normalizePracticeSlug(support.practiceSlug)}`]
						: [],
				);
			}),
		);
	} catch (error) {
		console.error(`[pi-runner] delivered feedback unreadable for composition: ${errorText(error)}`);
		return new Set();
	}
}

// Inline placement requires a citation inside the current diff.
function leanObservations(observations: readonly AdmittedObservation[]): LeanObservation[] {
	return observations.map((observation) => ({
		id: observation.id,
		practiceSlug: observation.practiceSlug,
		outcome: observation.outcome,
		severity: observation.severity,
		anchorable: observation.anchorable,
		citations: observation.citations.map((citation): LeanCitation => ({
			index: citation.index,
			sourceKind: citation.sourceKind,
			path: citation.path,
			side: citation.side,
			startLine: citation.startLine,
			endLine: citation.endLine,
			anchorable: citation.anchorable,
		})),
	}));
}

function persistComposedFeedback(): void {
	for (const unit of undeliverableUnits(composedFeedback)) {
		const { channel, practiceSlug, supersedesThreadKey } = unit;
		console.error(
			channel === undefined || practiceSlug === undefined || supersedesThreadKey === undefined
				? `[pi-runner] composed feedback the server cannot deliver: a SUPERSEDE unit lacks the channel, ` +
						`practice or thread it replaces: ${JSON.stringify({ channel, practiceSlug, supersedesThreadKey })}`
				: `[pi-runner] composed feedback the server cannot deliver: ${channel}/${practiceSlug} ` +
						`supersedes '${supersedesThreadKey}', which this envelope does not list as staged`,
		);
	}
	// The server reads at most 30 units. Keep deliverable feedback ahead of WITHHOLD records.
	const units = composedFeedback.units.toSorted(
		(a, b) => Number(a.action === "WITHHOLD") - Number(b.action === "WITHHOLD"),
	);
	writeFileSync(FEEDBACK_PATH, JSON.stringify({ ...composedFeedback, units }, null, 2));
}

// The composer references admitted observations; it cannot author verdicts, citations, or locations.
/** What report_feedback reports back: a refusal stores nothing and names no lane. */
interface ReportFeedbackDetails {
	stored: number;
	channel?: Channel;
	total?: number;
}

/** A unit the tool did not store, with the reason the session is told. */
function skipped(text: string): { stored: boolean; text: string } {
	return { stored: false, text };
}

function buildFeedbackTool(
	practiceSlugs: string[],
	request: CompositionRequest,
	observations: readonly AdmittedObservation[],
	preparedTargets: PreparedFeedbackTarget[],
	deliveredPractices: ReadonlySet<string>,
) {
	// The reader resolves supersession against the envelope's copy of this list and drops any unit
	// naming a thread outside it, so the vocabulary is recorded here, where it is decided.
	composedFeedback.preparedTargets = preparedTargets;
	const enabledChannels = PRIVATE_CHANNELS.filter((channel) => request.channels[channel].enabled);
	const usedPerChannel: Record<PrivateChannel, number> = { IN_APP: 0, IN_CHAT: 0 };
	const seen = new Set<string>();

	/** One unit stored, or the reason it was not. */
	const store = (value: unknown): { stored: boolean; text: string } => {
		const repairs: string[] = [];
		const read = readFeedbackUnit(withholdBasis(value, observations, repairs), practiceSlugs);
		if (typeof read === "string") {
			return { stored: false, text: read };
		}
		const unit = read;
		// Built per call: the tool is defined before admission fills the observations it reads.
		const observationsById = new Map(
			observations.map((observation) => [observation.id, observation]),
		);
		const bounds = request.channels[unit.channel];
		if (!bounds.enabled) {
			return skipped(
				`${unit.channel} is not a lane this run may write for (open: ${enabledChannels.join(", ")}); skipped.`,
			);
		}
		const key = `${unit.channel}:${unit.practiceSlug}`;
		if (seen.has(key)) {
			return skipped(
				`already have a ${unit.channel} unit for ${unit.practiceSlug}, and the first one stands; nothing to resend for it. Skipped.`,
			);
		}
		const delivers = unit.action !== "WITHHOLD";
		if (delivers && usedPerChannel[unit.channel] >= bounds.maxUnits) {
			return skipped(
				`${unit.channel} holds at most ${bounds.maxUnits} unit(s) and is full: this one does not fit — WITHHOLD it with BELOW_BAR or leave it out. Skipped.`,
			);
		}
		const rejection = validateUnit(unit, observationsById, preparedTargets, deliveredPractices);
		if (rejection !== null) {
			return skipped(rejection);
		}
		// IN_APP pattern feedback requires NOT_MET observations on distinct pieces of work.
		if (unit.channel === "IN_APP" && delivers) {
			const pieces = negativePiecesOfWork(unit.practiceSlug);
			if (pieces < request.minDistinctArtifacts) {
				return skipped(
					`IN_APP needs a pattern across at least ${request.minDistinctArtifacts} pieces of work, and ` +
						`${unit.practiceSlug} has support on ${pieces} (the IN_APP support read); WITHHOLD it ` +
						`with BELOW_BAR. Skipped.`,
				);
			}
		}
		seen.add(key);
		if (delivers) {
			usedPerChannel[unit.channel] += 1;
		}
		composedFeedback.units.push(unit);
		return {
			stored: true,
			text: `stored a ${unit.channel} unit for ${unit.practiceSlug} (${unit.action}); ${usedPerChannel[unit.channel]}/${bounds.maxUnits} used on that lane.${repairs.map((repair) => ` ${repair}.`).join("")}`,
		};
	};

	return defineTool({
		name: "report_feedback",
		exposure: "model-only",
		label: "Report Feedback",
		description:
			"Persist feedback units for the developer's practice pages and the mentor conversation, one per " +
			"channel and practice. Send every unit you have ready in one call; each is stored or skipped on " +
			"its own, with the reason, and a skipped unit can be sent again corrected without re-sending the " +
			"stored ones. This is an intervention, not a measurement: it takes no outcome, severity or " +
			"confidence, and no citation you typed yourself.",
		// See documentedShape: validate feedback per unit, not per tool call.
		parameters: {
			type: "object",
			required: ["units"],
			properties: {
				units: documentedShape(
					listSchema(
						{
							type: "object",
							required: ["channel", "practiceSlug", "basedOn", "action"],
							properties: {
								channel: {
									type: "string",
									enum: enabledChannels,
									description:
										"Which surface this unit is for. Each has its own level and its own rules.",
								},
								practiceSlug: {
									type: "string",
									enum: practiceSlugs,
									description:
										"The primary practice whose intervention this unit advances. Related observations may support it.",
								},
								basedOn: {
									type: "array",
									minItems: 1,
									items: { type: "string", minLength: 1 },
									description:
										"What this rests on: admitted observation ids from this run. Include related practices only when they describe the same underlying event.",
								},
								action: {
									type: "string",
									enum: ACTIONS,
									description:
										"NEW to say something; SUPERSEDE to replace prepared feedback that has not been delivered; " +
										"WITHHOLD to record, with a reason, that you decided to stay quiet.",
								},
								supersedesThreadKey: {
									type: "string",
									maxLength: FEEDBACK_TEXT_BOUNDS.supersedesThreadKey,
									description:
										"Required for SUPERSEDE: the threadKey of an entry in the task-declared prepared-feedback file. " +
										"You may not name a key that is not in that file.",
								},
								withholdReason: { type: "string", enum: WITHHOLD_REASONS },
								title: {
									type: "string",
									maxLength: FEEDBACK_TEXT_BOUNDS.title,
									description: "Names the issue in a few words. Never names the person.",
								},
								body: {
									type: "string",
									maxLength: FEEDBACK_TEXT_BOUNDS.body,
									description:
										"IN_APP only: the evidenced pattern across the work and why it matters; the future way of working belongs to nextStep. Grounded in current observations; never quote a line or claim change over time from the pre-run history. Markdown, read verbatim.",
								},
								nextStep: {
									type: "string",
									maxLength: FEEDBACK_TEXT_BOUNDS.nextStep,
									description:
										"IN_APP only: the one repeatable way of working for the next piece of work; the body does not state it. Name the missing decision, not a heading/template unless the practice requires one; never provide paste-ready prose.",
								},
								notes: {
									type: "object",
									additionalProperties: false,
									required: ["situation", "capability", "evidenceSummary", "inConversationSignal"],
									description:
										"IN_CHAT only. Notes TO the mentor, which composes the whole turn itself, later, " +
										"with the live conversation in front of it. Write what it needs to know, never a " +
										"sentence for it to say: anything phrased as a line of dialogue will be spoken, and " +
										"will sound like a script.",
									properties: {
										situation: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.situation,
											description:
												"The concern the observations have in common, as it can be observed, stated once rather than " +
												"listing each artifact again. Your words about it, not words for it - third person, never " +
												"addressed to the developer as 'you', and never a judgement of the person.",
										},
										capability: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.capability,
											description:
												"The self-check of the assessed behaviour this conversation should support: how the developer can tell whether their own work shows it, not only whether something is wired up. State the capability, not a solution such as a required heading/template, and not a question, script, diagnosis, or fixed tactic.",
										},
										evidenceSummary: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.evidenceSummary,
											description:
												"Where the concern occurred, compactly: each supporting work and observation located once. " +
												"Summarise rather than inventing a quote; the original observation evidence is " +
												"staged separately for the mentor to inspect.",
										},
										inConversationSignal: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.inConversationSignal,
											description:
												"Understanding the developer articulates in a way that can be observed before the conversation ends: a distinction, decision, question, or self-check they say. Not a promise, future artifact, message text, or compliance target.",
										},
										alreadySaid: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.alreadySaid,
											description:
												"Optional. Relevant prior communication the shown history records, and movement the shown evidence supports. Omit when none is shown. Partial or missing history leaves coverage unknown: it never proves that nothing was said or that a concern was resolved.",
										},
									},
								},
							},
						},
						"units",
					),
				),
			},
		},
		execute: async (toolCallId, params): Promise<AgentToolResult<ReportFeedbackDetails>> => {
			if (!compositionAdmitted) {
				return {
					content: [
						{
							type: "text",
							text: "Feedback composition opens only after Java admits the completed observations.",
						},
					],
					details: { stored: 0 },
				};
			}
			const submitted = submittedList(isRecord(params) ? params.units : null, UNIT_ITEM_KEYS);
			if ("error" in submitted) {
				return refusal(toolCallId, `units refused — ${submitted.error}`);
			}
			if (submitted.items.length === 0) {
				return refusal(
					toolCallId,
					"units refused — the list is empty; send every unit you have ready in it",
				);
			}
			const outcomes = submitted.items.map(store);
			const stored = outcomes.filter((outcome) => outcome.stored).length;
			if (currentTurn) {
				currentTurn.stored += stored;
				sessionGuards.stored += stored;
			}
			if (stored > 0) {
				persistComposedFeedback();
			}
			const text = outcomes.map((outcome, index) => `#${index + 1}: ${outcome.text}`).join("\n");
			// A call that stored nothing is an error the session must correct — the reasons are in it;
			// one that stored some of what it sent is an answer, with the skips named per unit.
			if (stored === 0) {
				return refusal(toolCallId, text);
			}
			return {
				content: [{ type: "text", text }],
				details: { stored, total: composedFeedback.units.length },
				terminate: turnDemand?.endsWhenPaid === true && turnDemand.owed() === 0,
			};
		},
	});
}

interface ReportReviewDetails {
	stored: number;
}

interface ReadPracticeDetails {
	shown: boolean;
}

/**
 * Once final, both tools refuse every call: one response can carry several calls after the final one.
 * A tool answer reaches a subsequent model decision, never another call in the same batch: what an answer shows is
 * pending until the next turn starts, and a completed compaction takes everything out of view.
 */
interface PublicReviewState {
	final: boolean;
	reviewContext: "LOST" | "RESTORING" | "HELD";
	/** Complete MET grounds and standards shown by read_practice, for restoration. */
	consulted: Map<string, string>;
	/** The practices whose complete MET reference the model could read when it made its current decision. */
	inView: Set<string>;
	/** Shown in the current batch; in view from the next turn. */
	pending: Set<string>;
}

function reviewableById(
	reviewable: readonly Record<string, unknown>[],
): Map<string, ReviewedObservation> {
	return new Map(
		reviewable.map((observation) => [
			String(observation.id),
			{
				practiceSlug: String(observation.practiceSlug),
				summaryOnly: observation.summaryOnly === true,
				outcome: observation.outcome,
				citations: Array.isArray(observation.citations)
					? observation.citations.filter((citation: unknown) => isRecord(citation))
					: [],
			},
		]),
	);
}

const REVIEW_FINAL = "The review on this work is final; this composition accepts nothing more.";

/**
 * Shows one MET practice's complete reference beside its permitted MET observations, and
 * nothing else: no path, no other practice, nothing private. A read that is missing, fails or is empty shows that
 * and puts nothing in view.
 */
function buildPracticeTool(
	reviewable: readonly Record<string, unknown>[],
	standardOf: (slug: string) => { text: string; whole: boolean },
	context: { practices: readonly ReviewPractice[]; history: readonly OwnPriorFeedback[] },
	state: PublicReviewState,
	restore: () => string,
) {
	const readable = new Set(readablePractices(reviewable));
	return defineTool({
		name: "read_practice",
		exposure: "model-only",
		label: "Read Practice",
		description: READ_PRACTICE_TOOL_DESCRIPTION,
		parameters: readPracticeParameters([...readable]),
		execute: async (toolCallId, params): Promise<AgentToolResult<ReadPracticeDetails>> => {
			if (!compositionAdmitted) {
				return refusal<ReadPracticeDetails>(
					toolCallId,
					"Feedback composition opens only after Java admits the completed observations.",
				);
			}
			if (state.final) {
				return refusal<ReadPracticeDetails>(toolCallId, REVIEW_FINAL);
			}
			const slug =
				isRecord(params) && typeof params.practiceSlug === "string"
					? params.practiceSlug.trim()
					: "";
			if (!readable.has(slug)) {
				const offered = readable.size > 0 ? [...readable].join(", ") : "none";
				return refusal<ReadPracticeDetails>(
					toolCallId,
					`${slug === "" ? "practiceSlug is required" : `${slug} is not a practice with a MET observation this review may acknowledge`}; read_practice shows only: ${offered}`,
				);
			}
			const restored = state.reviewContext === "LOST" ? `${restore()}\n\n` : "";
			const standard = standardOf(slug);
			const consulted = consultedStandard(
				standard.text,
				slug,
				reviewable,
				context.practices,
				context.history,
			);
			if (standard.whole) {
				state.consulted.set(slug, consulted);
				state.pending.add(slug);
			}
			return {
				content: [
					{
						type: "text",
						text: standard.whole
							? `${restored}${STANDARD_REFERENCE}\n${consulted}\nThis standard is in view from your next turn.`
							: `${restored}${standard.text}\nWithout its standard, no acknowledgement of this practice can be stored.`,
					},
				],
				details: { shown: standard.whole },
			};
		},
	});
}

/**
 * Reads the review against only the observations admission marked publicEligible, the statements shown as already
 * said, and the standards in view when the model wrote it.
 */
function buildReviewTool(
	lineNotes: boolean,
	restable: ReadonlyMap<string, ReviewedObservation>,
	witnesses: ReturnType<typeof priorAdviceWitnesses>,
	state: PublicReviewState,
	restore: () => string,
) {
	// Every witness a decision can name; readReview checks which reason each one may support.
	const eligible = [...witnesses].filter(([, witness]) => witness.eligibleForAlreadySaid);
	return defineTool({
		name: "report_review",
		prepareArguments: prepareReviewArguments,
		exposure: "model-only",
		label: "Report Review",
		description: REVIEW_TOOL_DESCRIPTION,
		parameters: reviewToolParameters(
			restable,
			lineNotes,
			eligible.map(([id]) => id),
		),
		execute: async (toolCallId, params): Promise<AgentToolResult<ReportReviewDetails>> => {
			if (!compositionAdmitted) {
				return refusal<ReportReviewDetails>(
					toolCallId,
					"Feedback composition opens only after Java admits the completed observations.",
				);
			}
			if (state.final) {
				return refusal<ReportReviewDetails>(toolCallId, REVIEW_FINAL);
			}
			if (state.reviewContext !== "HELD") {
				const restored = state.reviewContext === "LOST" ? `\n${restore()}\n` : "";
				state.reviewContext = "RESTORING";
				return refusal<ReportReviewDetails>(
					toolCallId,
					`review refused, nothing was stored: the session's context was compacted. Use the restored review reference in the next model turn before storing the review.${restored}\nSend the whole review again.`,
				);
			}
			const read = readReview(params, restable, lineNotes, {
				witnesses,
				standardsInView: state.inView,
			});
			if ("errors" in read) {
				return refusal<ReportReviewDetails>(
					toolCallId,
					`review refused, nothing was stored:\n${read.errors.map((error) => `- ${error}`).join("\n")}`,
				);
			}
			// Final only once persisted: a failed write leaves the review unaccepted, so it is never delivered.
			const previous = composedFeedback.review;
			composedFeedback.review = read.review;
			try {
				persistComposedFeedback();
			} catch (error) {
				composedFeedback.review = previous;
				throw error;
			}
			state.final = true;
			if (currentTurn) {
				currentTurn.stored += 1;
				sessionGuards.stored += 1;
			}
			const { summary, inline, withheld } = read.review;
			const said = summary
				? `a summary resting on ${summary.basedOn.length} observation(s)`
				: "no summary";
			return {
				content: [
					{
						type: "text",
						text: `Stored the review: ${said}, ${inline.length} line note(s), ${withheld.length} withholding decision(s). It is final.`,
					},
				],
				details: { stored: 1 },
				terminate: true,
			};
		},
	});
}

/** The NOT_MET observations the review may rest on that it neither says nor withholds. */
function undecidedByReview(reviewable: readonly Record<string, unknown>[]): string[] {
	const decided = decidedByReview(composedFeedback.review);
	return reviewable
		.filter((observation) => observation.outcome === "NOT_MET")
		.map((observation) => String(observation.id))
		.filter((id) => !decided.has(id));
}

function finishReviewText(undecided: readonly string[]): string {
	const owed =
		undecided.length > 0
			? `## Undecided\nThe review leaves these NOT_MET observations undecided: ${undecided.join(", ")}.`
			: "## Unfinished\nThe review on this work is not final yet.";
	return `${owed}\nStore the final review with one report_review call. A review that says nothing is still one final report_review call. No prose outside the calls.`;
}

/** Explanatory context on each practice the review's observations were measured against, from the staged index. */
function practiceContext(observations: readonly Record<string, unknown>[]): ReviewPractice[] {
	const slugs = new Set(observations.map((observation) => String(observation.practiceSlug)));
	return practiceIndex
		.filter((practice) => slugs.has(practice.slug))
		.map((practice) => ({
			slug: practice.slug,
			name: practice.name,
			...(practice.whyItMatters === undefined ? {} : { whyItMatters: practice.whyItMatters }),
			knownLimitations: practice.knownLimitations,
			...(practice.revisionId === undefined ? {} : { revisionId: practice.revisionId }),
		}));
}

/** Match ComposedFeedbackUnit bounds so the model can correct a unit before server admission. */
const FEEDBACK_TEXT_BOUNDS = {
	title: 255,
	body: 8000,
	nextStep: 2000,
	supersedesThreadKey: 64,
	situation: 4000,
	capability: 2000,
	evidenceSummary: 4000,
	inConversationSignal: 2000,
	alreadySaid: 2000,
} as const;

const UNIT_FIELDS = [
	"channel",
	"practiceSlug",
	"basedOn",
	"action",
	"supersedesThreadKey",
	"withholdReason",
	"title",
	"body",
	"nextStep",
	"notes",
] as const;

const NOTE_FIELDS = [
	"situation",
	"capability",
	"evidenceSummary",
	"inConversationSignal",
	"alreadySaid",
] as const;

/** Accept case and hyphen variants; reject values outside the declared vocabulary. */
function vocabularyWord<T extends string>(
	value: unknown,
	vocabulary: readonly T[],
	field: string,
): T | string {
	if (typeof value !== "string" || value.trim() === "") {
		return `${field} is required: one of ${vocabulary.join(", ")}`;
	}
	const word = value.trim().toUpperCase().replaceAll("-", "_");
	const match = vocabulary.find((candidate) => candidate === word);
	return match ?? `${field} must be one of ${vocabulary.join(", ")} (received '${value}')`;
}

/** Enforce text length here; validateUnit handles lane-specific required fields. */
function boundedText(
	value: unknown,
	field: keyof typeof FEEDBACK_TEXT_BOUNDS,
): { text: string | undefined } | { error: string } {
	if (value === undefined || value === null) {
		return { text: undefined };
	}
	if (typeof value !== "string") {
		return { error: `${field} must be a string` };
	}
	const text = value.trim();
	if (text === "") {
		return { text: undefined };
	}
	const bound = FEEDBACK_TEXT_BOUNDS[field];
	if (text.length > bound) {
		return {
			error: `${field} must be at most ${bound} characters; this one is ${text.length}. Shorten it and send the unit again`,
		};
	}
	return { text };
}

/** Validate one feedback item; the outer tool schema intentionally permits partial batch success. */
/**
 * A WITHHOLD sent with no basedOn rests on its practice's NOT_MET observations, the only ones it can
 * withhold: they are filled in, and said so.
 */
function withholdBasis(
	value: unknown,
	observations: readonly AdmittedObservation[],
	repairs: string[],
): unknown {
	if (
		!isRecord(value) ||
		String(value.action).toUpperCase() !== "WITHHOLD" ||
		listOrSingle(value.basedOn).length > 0
	) {
		return value;
	}
	const slug = normalizePracticeSlug(value.practiceSlug);
	const ids = observations
		.filter((observation) => observation.practiceSlug === slug && observation.outcome === "NOT_MET")
		.map((observation) => observation.id);
	if (ids.length === 0) {
		return value;
	}
	repairs.push(`basedOn filled in with ${slug}'s NOT_MET observation(s) ${ids.join(", ")}`);
	return { ...value, basedOn: ids };
}

function readFeedbackUnit(value: unknown, practiceSlugs: readonly string[]): FeedbackUnit | string {
	if (!isRecord(value)) {
		return `each item of units is one unit object with channel, practiceSlug, basedOn and action (received ${typeof value}); skipped.`;
	}
	const unknownFields = Object.keys(value).filter(
		(key) => !UNIT_FIELDS.some((field) => field === key),
	);
	if (unknownFields.length > 0) {
		return `unknown unit field(s): ${unknownFields.join(", ")} — a unit takes ${UNIT_FIELDS.join(", ")}; skipped.`;
	}
	const channel = vocabularyWord(value.channel, CHANNELS, "channel");
	if (!isChannel(channel)) {
		return `${channel}; skipped.`;
	}
	if (channel === "IN_CONTEXT") {
		return "the review on the work is written in its own turn, not as a unit here; skipped.";
	}
	const action = vocabularyWord(value.action, ACTIONS, "action");
	if (!isFeedbackAction(action)) {
		return `${action}; skipped.`;
	}
	const practiceSlug =
		typeof value.practiceSlug === "string"
			? value.practiceSlug.trim().toLowerCase().replaceAll("_", "-")
			: "";
	if (!practiceSlug) {
		return "practiceSlug is required; skipped.";
	}
	if (!practiceSlugs.includes(practiceSlug)) {
		return `practiceSlug '${practiceSlug}' is not a practice with an admitted observation in this run (those are: ${practiceSlugs.join(", ")}); skipped.`;
	}
	// One id sent bare is the list of one; the reader drops a unit that cites nothing, so admitting an
	// empty list here would tell the session it succeeded and then deliver nothing.
	const basedOn = listOrSingle(value.basedOn).filter(
		(reference): reference is string => typeof reference === "string" && reference.trim() !== "",
	);
	if (basedOn.length === 0) {
		return "basedOn is required: the admitted observation id(s) this unit rests on; skipped.";
	}
	let withholdReason: string | undefined;
	if (value.withholdReason !== undefined && value.withholdReason !== null) {
		const word = vocabularyWord(value.withholdReason, WITHHOLD_REASONS, "withholdReason");
		if (!WITHHOLD_REASONS.some((reason) => reason === word)) {
			return `${word}; skipped.`;
		}
		withholdReason = word;
	}
	const texts: Partial<Record<keyof typeof FEEDBACK_TEXT_BOUNDS, string>> = {};
	for (const field of ["supersedesThreadKey", "title", "body", "nextStep"] as const) {
		const read = boundedText(value[field], field);
		if ("error" in read) {
			return `${read.error}; skipped.`;
		}
		texts[field] = read.text;
	}
	let notes: ConversationNotes | undefined;
	if (value.notes !== undefined && value.notes !== null) {
		if (!isRecord(value.notes)) {
			return "notes must be an object; skipped.";
		}
		const unknownNotes = Object.keys(value.notes).filter(
			(key) => !NOTE_FIELDS.some((field) => field === key),
		);
		if (unknownNotes.length > 0) {
			return `unknown notes field(s): ${unknownNotes.join(", ")} — notes take ${NOTE_FIELDS.join(", ")}; skipped.`;
		}
		notes = {};
		for (const field of NOTE_FIELDS) {
			const read = boundedText(value.notes[field], field);
			if ("error" in read) {
				return `notes.${read.error}; skipped.`;
			}
			notes[field] = read.text;
		}
	}
	return {
		channel,
		practiceSlug,
		action,
		basedOn: basedOn.map((reference) => reference.trim()),
		supersedesThreadKey: texts.supersedesThreadKey,
		withholdReason,
		title: texts.title,
		body: texts.body,
		nextStep: texts.nextStep,
		notes,
	};
}

/** A list as sent, or the one value sent bare where a list was asked for. */
function listOrSingle(value: unknown): unknown[] {
	if (Array.isArray(value)) {
		return value;
	}
	return value === undefined || value === null ? [] : [value];
}

const THIS_WORK = ((): string | undefined => {
	const metadataPath = nodePath.join(INPUT_PATHS.contextRoot, "metadata.json");
	if (!existsSync(metadataPath) || !isRecord(folderIndex)) {
		return undefined;
	}
	const metadata = parseJson(readFileSync(metadataPath, "utf8"));
	if (!isRecord(metadata)) {
		return undefined;
	}
	const kind = folderIndex.artifactKind;
	if (kind !== "scm.pull_request" && kind !== "scm.issue") {
		return undefined;
	}
	return workIdentity(kind, metadata[kind === "scm.pull_request" ? "pr_url" : "html_url"]);
})();

// Enforce snapshot-dependent constraints here for fast model correction; Java rechecks them.
/**
 * Count the distinct pieces of work the server's IN_APP support read selected for the practice. Two occurrences on
 * one piece of work are one piece, as the server counts it (InAppFeedbackRouter.distinctArtifacts).
 */
function negativePiecesOfWork(practiceSlug: string): number {
	if (inAppSupport?.state !== "COMPLETE") {
		return 0;
	}
	const support = inAppSupport.practices.find((practice) => practice.practiceSlug === practiceSlug);
	return new Set(
		(support?.occurrences ?? []).map(
			(occurrence) => `${occurrence.artifactKind}:${occurrence.artifactId}`,
		),
	).size;
}

/** The IN_CHAT lane: notes to the mentor, and nothing that would be read out. */
function validateChatUnit(unit: FeedbackUnit): string | null {
	if (hasText(unit.body) || hasText(unit.nextStep)) {
		return "IN_CHAT takes notes{situation,capability,evidenceSummary,inConversationSignal}, not body/nextStep - nothing on this lane is read out; skipped.";
	}
	const { notes } = unit;
	for (const field of [
		"situation",
		"capability",
		"evidenceSummary",
		"inConversationSignal",
	] as const) {
		if (isBlank(notes?.[field])) {
			return `IN_CHAT needs notes.${field}; skipped.`;
		}
	}
	return null;
}

/** The IN_APP lane: a body about a pattern across work, never a quote of this work. */
function validateAppUnit(
	unit: FeedbackUnit,
	observationsById: ReadonlyMap<string, AdmittedObservation>,
): string | null {
	const { body } = unit;
	if (!hasText(body?.trim())) {
		return "IN_APP needs a body; skipped.";
	}
	const normalizedBody = normalizeQuotedText(body);
	const repeatsCurrentEvidence = unit.basedOn.some((id) =>
		(observationsById.get(id)?.citations ?? []).some((citation) => {
			const quote = normalizeQuotedText(optionalString(citation.quote) ?? "");
			return quote.length >= 12 && normalizedBody.includes(quote);
		}),
	);
	if (repeatsCurrentEvidence) {
		return "IN_APP describes a cross-artifact pattern; do not copy a current artifact quote into it. Skipped.";
	}
	return null;
}

function validateUnit(
	unit: FeedbackUnit,
	observationsById: ReadonlyMap<string, AdmittedObservation>,
	preparedTargets: readonly PreparedFeedbackTarget[],
	deliveredPractices: ReadonlySet<string>,
): string | null {
	const evidenceError = validateFeedbackEvidence(unit.practiceSlug, unit.basedOn, observationsById);
	if (evidenceError !== null) {
		return evidenceError;
	}
	if (unit.action === "WITHHOLD") {
		const reason = unit.withholdReason;
		if (!hasText(reason)) {
			return "WITHHOLD needs a withholdReason; skipped.";
		}
		// The withholding settles each NOT_MET practice in basedOn (undecidedPrivatePractices), so each needs a record here.
		const unrecorded = notMetPractices(
			unit.basedOn.flatMap((id) => {
				const observation = observationsById.get(id);
				return observation === undefined ? [] : [observation];
			}),
		).filter((slug) => !deliveredPractices.has(`${unit.channel}:${slug}`));
		if ([...PRIOR_ADVICE_REASONS].some((prior) => prior === reason) && unrecorded.length > 0) {
			return (
				`${reason} rests on feedback recorded as delivered on ${unit.channel} for ${unrecorded.join(", ")} in the ` +
				`delivered-feedback history: current, not withdrawn, with its words, for each NOT_MET practice in basedOn. The captured history does not establish that prerequisite; prepared ` +
				`feedback and other channels do not count. Decide this lane on its own evidence: the unit it supports, ` +
				`or WITHHOLD with BELOW_BAR. Skipped.`
			);
		}
		return null;
	}
	if (isBlank(unit.title)) {
		return "A unit that is not a WITHHOLD needs a title; skipped.";
	}
	if (unit.action === "SUPERSEDE") {
		if (!hasText(unit.supersedesThreadKey)) {
			return "SUPERSEDE needs a supersedesThreadKey; skipped.";
		}
		if (
			!preparedTargets.some(
				(target) =>
					target.threadKey === unit.supersedesThreadKey &&
					target.channel === unit.channel &&
					target.practiceSlug === unit.practiceSlug,
			)
		) {
			return `No queued message has threadKey '${unit.supersedesThreadKey}'; it must come from the task-declared prepared-feedback file. Skipped.`;
		}
	}
	if (unit.channel === "IN_CHAT") {
		return validateChatUnit(unit);
	}
	if (unit.notes) {
		return "Only IN_CHAT units may carry a notes block; skipped.";
	}
	if (isBlank(unit.nextStep)) {
		return `${unit.channel} needs a nextStep; skipped.`;
	}
	return validateAppUnit(unit, observationsById);
}

function normalizeQuotedText(value: string): string {
	return value
		.normalize("NFKC")
		.replaceAll(/[“”„‟]/gu, '"')
		.replaceAll(/[‘’‚‛]/gu, "'")
		.replaceAll(/\s+/gu, " ")
		.trim()
		.toLowerCase();
}

/** Omit duplicated quotes and verification digests; the full admission record remains on disk. */
function composerView(observation: AdmittedObservation): Record<string, unknown> {
	// Abstentions cannot support claims. MET counterevidence keeps the same qualifications as NOT_MET.
	if (observation.outcome !== "NOT_MET" && observation.outcome !== "MET") {
		const { id, practiceSlug, outcome, summary } = observation;
		return { id, practiceSlug, outcome, summary };
	}
	const { evidence, citations, ...rest } = observation;
	const { citations: _measured, ...branches } = isRecord(evidence) ? evidence : {};
	return {
		...rest,
		...(Object.keys(branches).length > 0 ? { evidence: branches } : {}),
		citations: citations.map(
			({ quote: _quote, verification: _verification, ...citation }) => citation,
		),
	};
}

const COMPOSITION_BLOCK_LIMIT = 32_000;

/** A staged file as a titled block of the composition turn, or a pointer to it when it is too large. */
function shown(label: string, file: string, limit = COMPOSITION_BLOCK_LIMIT): string {
	if (!existsSync(file)) {
		return "";
	}
	const text = readFileSync(file, "utf8").trim();
	return text.length > limit
		? `### ${label} — too large to show here; read \`${file}\`\n`
		: `### ${label}\n\`\`\`json\n${text}\n\`\`\`\n`;
}

/**
 * The complete staged criteria of each practice with a NOT_MET observation: feedback addresses the standard that was
 * assessed. The measuring leads and source directives belong to assessment and stay out. Each is shown whole, named
 * with where to read it when too large, or named as not staged or not readable; never cut.
 */
function notMetCriteria(
	observations: readonly AdmittedObservation[],
	limit = COMPOSITION_BLOCK_LIMIT,
): string {
	return notMetPractices(observations)
		.map((slug) => {
			const file = criteriaPathOf(slug);
			if (!existsSync(file)) {
				return `### Criteria of \`${slug}\` — not staged for this review; nothing is known about them here\n`;
			}
			let text: string;
			try {
				text = readFileSync(file, "utf8").trim();
			} catch {
				return `### Criteria of \`${slug}\` — \`${file}\` could not be read; nothing is known about them here\n`;
			}
			if (text.length === 0) {
				return `### Criteria of \`${slug}\` — \`${file}\` is empty; no standard is available here\n`;
			}
			if (text.length > limit) {
				return `### Criteria of \`${slug}\` — too large to show here; read \`${file}\`\n`;
			}
			const longestRun = Math.max(
				0,
				...[...text.matchAll(/`+/gu)].map((backticks) => backticks[0].length),
			);
			const fence = "`".repeat(Math.max(3, longestRun + 1));
			return `### Criteria of \`${slug}\`\n${fence}markdown\n${text}\n${fence}\n`;
		})
		.join("\n");
}

/** The producer owns body eligibility. Keep its facts here and locate only bodies it actually staged. */
function priorFeedbackFacts(label: string, file: string, key: "feedback" | "prepared"): string {
	if (!existsSync(file)) {
		return "";
	}
	const pointer = `### ${label} — read \`${file}\` for the recorded history; its contents are not shown here\n`;
	let history: unknown;
	try {
		history = parseJson(readFileSync(file, "utf8"));
	} catch {
		return pointer;
	}
	if (!isRecord(history) || !Array.isArray(history[key])) {
		return pointer;
	}
	const entries = jsonArray(history[key]);
	if (
		entries.some(
			(entry) => !isRecord(entry) || (entry.body != null && typeof entry.body !== "string"),
		)
	) {
		return pointer;
	}
	const projected = entries.map((entry, index) => {
		if (!isRecord(entry)) {
			return entry;
		}
		const { body, ...facts } = entry;
		return {
			...facts,
			...(typeof body === "string"
				? { bodyAt: `${file} → ${key}[${index}].body (${body.length} characters)` }
				: {}),
		};
	});
	const text = JSON.stringify({ ...history, [key]: projected }, null, 1);
	return text.length > COMPOSITION_BLOCK_LIMIT
		? pointer
		: `### ${label} — bodies remain at their \`bodyAt\` in the unchanged file; read them when earlier wording matters\n\`\`\`json\n${text}\n\`\`\`\n`;
}

/** The accepted public draft is persisted here, but has not passed delivery and is not prior advice. */
function plannedReviewFacts(): string {
	const { review } = composedFeedback;
	if (!review) {
		return "";
	}
	const stored = existsSync(FEEDBACK_PATH);
	const bodyAt = (field: string) =>
		stored ? { bodyAt: `${FEEDBACK_PATH} → review.${field}` } : {};
	const facts = {
		summary: review.summary ? { basedOn: review.summary.basedOn, ...bodyAt("summary.body") } : null,
		inline: review.inline.map(({ body: _body, ...note }, index) => ({
			...note,
			...bodyAt(`inline[${index}].body`),
		})),
		withheld: review.withheld,
	};
	return `### The review planned for this work (a draft, not delivered)\nThis accepted draft may still be withheld at delivery. It is not prior communication. Private channels remain independently useful and may make the same supported point. ${stored ? "Read the located bodies if their wording matters." : "Its wording is not available as a staged file here."}\n\`\`\`json\n${JSON.stringify(facts, null, 1)}\n\`\`\`\n`;
}

function buildCompositionTurn(
	request: CompositionRequest,
	observations: readonly AdmittedObservation[],
	notReached: readonly string[] = [],
): string {
	const lanes = PRIVATE_CHANNELS.filter((channel) => request.channels[channel].enabled)
		.map((channel) => `${channel} (at most ${request.channels[channel].maxUnits})`)
		.join(", ");
	const closed = PRIVATE_CHANNELS.filter((channel) => !request.channels[channel].enabled);
	const closedNote =
		closed.length > 0 ? ` Closed this turn, so write nothing for them: ${closed.join(", ")}.` : "";
	const coverageNote = notReachedNote(notReached);
	const admitted = JSON.stringify({ observations: observations.map(composerView) }, null, 1);
	const onTheWork = plannedReviewFacts();
	const historyRoot = nodePath.dirname(PREPARED_FEEDBACK_PATH);
	const context = [
		shown("The composition request (lanes, caps, placements)", COMPOSITION_REQUEST_PATH),
		shown("What earlier reviews recorded about this person", `${historyRoot}/observations.json`),
		request.channels.IN_APP.enabled
			? shown("IN_APP support: occurrences a new card may cite at this read", IN_APP_SUPPORT_PATH)
			: "",
		priorFeedbackFacts("Recorded delivered feedback", `${historyRoot}/feedback.json`, "feedback"),
		priorFeedbackFacts(
			"Prepared feedback (not delivered; supersession targets)",
			PREPARED_FEEDBACK_PATH,
			"prepared",
		),
	]
		.filter((block) => block !== "")
		.join("\n");
	return `## This turn
The review just finished. The criteria its NOT_MET practices were assessed against come first, as staged; its ${observations.length} admitted measurement(s) follow them. The MET and NOT_MET ones carry their rationale and their citations by coordinates, the abstentions only what they recorded; the full record, quoted lines included, is \`work/composition/observations.json\`. The history follows them.

${notMetCriteria(observations)}
\`\`\`json
${admitted}
\`\`\`

${context}
${onTheWork}
Lanes open this turn: ${lanes}.${closedNote}
A pattern claim needs at least ${request.minDistinctArtifacts} distinct pieces of work.

${coverageNote}Persist the units with report_feedback — every unit you have in one call. Writing nothing on a lane is a correct and common outcome; say in one line why, and stop.`;
}

/** A transport failure or a server that is not answering yet; a refusal is a decision, not a blip. */
class AdmissionUnreachableError extends Error {
	name = "AdmissionUnreachableError";
}

/** The cause behind a wrapped error, in parentheses, or nothing when the error carries none. */
function causeText(error: unknown): string {
	return error instanceof Error && error.cause !== undefined ? ` (${errorText(error.cause)})` : "";
}

/** Retry transport failures, not slow admission or explicit refusals. */
const ADMISSION_ATTEMPTS = 4;

// Admission verifies every cited blob against the Git object, in a container the server starts, so
// one attempt may take minutes; the cap only bounds a server that went silent without closing.
const ADMISSION_ATTEMPT_TIMEOUT_MS = 10 * 60_000;

/** Preserve an HTTP refusal even when its body is unreadable. */
async function answerText(response: Response): Promise<string> {
	try {
		const body = await response.text();
		const problem: unknown = body.trim().startsWith("{") ? parseJson(body) : null;
		const detail = isRecord(problem) ? problem.detail : null;
		return typeof detail === "string" && detail.length > 0 ? detail : body.slice(0, 500);
	} catch {
		return "the server gave no readable reason";
	}
}

/** One admission attempt: the answer it returns is the parsed body, unvalidated. */
async function postAdmission(): Promise<unknown> {
	let response: Response;
	try {
		response = await fetch(`${LLM_PROXY_URL}/admit-observations`, {
			method: "POST",
			signal: AbortSignal.timeout(ADMISSION_ATTEMPT_TIMEOUT_MS),
			headers: {
				authorization: `Bearer ${LLM_PROXY_TOKEN}`,
				"content-type": "application/json",
				...(hasText(process.env.TRACEPARENT) ? { traceparent: process.env.TRACEPARENT } : {}),
			},
			body: JSON.stringify({ schemaVersion: 1, observations: reviewState.observations }),
		});
	} catch (error) {
		if (isTimeoutAbort(error)) {
			// The server is still verifying; a second attempt would only queue the same work behind it.
			throw new Error(
				`observation admission did not answer within ${ADMISSION_ATTEMPT_TIMEOUT_MS}ms`,
				{ cause: error },
			);
		}
		// Node reports every transport failure as `TypeError: fetch failed`; only the cause says which
		// one it was, and a report that has just the message cannot tell a restart from a wrong URL.
		throw new AdmissionUnreachableError(
			`observation admission could not be sent: ${errorText(error)}${causeText(error)}`,
		);
	}
	if (isRetryableStatus(response.status)) {
		throw new AdmissionUnreachableError(`observation admission failed: HTTP ${response.status}`);
	}
	if (!response.ok) {
		// The server has decided, and it said why. Asking again puts the same question, so the run ends
		// here — with the reason in the transcript, which is the only place a reader can find it.
		throw new Error(
			`observation admission was refused: HTTP ${response.status} — ${await answerText(response)}`,
		);
	}
	try {
		return await response.json();
	} catch (error) {
		// A server that dies while writing its answer ends the body mid-stream. The admission may well
		// have been recorded; asking again replays it rather than admitting it twice.
		throw new AdmissionUnreachableError(
			`observation admission answer was cut short: ${errorText(error)}${causeText(error)}`,
		);
	}
}

async function admitObservations() {
	// Admission retries are idempotent: measurement is frozen and the server replays identical payloads.
	const admitted: unknown = await retrying(
		postAdmission,
		(error) => error instanceof AdmissionUnreachableError,
		{ attempts: ADMISSION_ATTEMPTS },
		(attempt, error, delayMs) =>
			console.error(
				`[pi-runner] admission attempt ${attempt}/${ADMISSION_ATTEMPTS} did not arrive (${errorText(error)}); retrying in ${delayMs}ms`,
			),
	);
	if (
		!isRecord(admitted) ||
		admitted.schemaVersion !== 1 ||
		typeof admitted.admissionDigest !== "string" ||
		!Array.isArray(admitted.observations) ||
		!admitted.observations.every(isAdmittedObservation)
	) {
		throw new Error("observation admission returned an invalid contract");
	}
	admittedObservations.splice(0, admittedObservations.length, ...admitted.observations);
	admissionDigest = admitted.admissionDigest;
	compositionAdmitted = true;
	composedFeedback.admissionDigest = admissionDigest;
	composedFeedback.observations = leanObservations(admittedObservations);
	mkdirSync(`${CWD}/work/composition`, { recursive: true });
	writeFileSync(
		COMPOSITION_OBSERVATIONS_PATH,
		JSON.stringify({ observations: admittedObservations }, null, 2),
	);
	// The support read is checked only after the admission it accompanies; it never changes what was admitted.
	inAppSupport = readInAppSupport(admitted.inAppSupport, admittedObservations);
	if (inAppSupport?.state === "COMPLETE") {
		writeFileSync(IN_APP_SUPPORT_PATH, JSON.stringify(inAppSupport, null, 2));
	}
}

/**
 * IN_APP rests on the server's support read. Without a complete one the lane closes for this run: a refusal is the
 * recipient policy's answer, anything else is IN_APP feedback that could not be composed. Other lanes go on.
 */
function closeInAppWithoutSupport(request: CompositionRequest): void {
	if (
		!request.channels.IN_APP.enabled ||
		notMetPractices(admittedObservations).length === 0 ||
		inAppSupport?.state === "COMPLETE"
	) {
		return;
	}
	request.channels.IN_APP.enabled = false;
	console.error(
		`[pi-runner] IN_APP closed for this run: support context ${inAppSupport === null ? "missing or malformed" : inAppSupport.state}`,
	);
	if (inAppSupport?.state !== "REFUSED") {
		recordCompositionFailure("PRIVATE_FEEDBACK", "RUNTIME_ERROR");
	}
}

/** One read of the delivered public history; any refusal or bad answer is final, never an older or empty history. */
async function postPublicHistoryRead(): Promise<unknown> {
	let response: Response;
	try {
		response = await fetch(`${LLM_PROXY_URL}/public-feedback-history`, {
			method: "POST",
			signal: AbortSignal.timeout(
				Math.max(1, Math.min(60_000, AGENT_BUDGET_MS - (Date.now() - PROCESS_START_MS))),
			),
			headers: {
				authorization: `Bearer ${LLM_PROXY_TOKEN}`,
				...(hasText(process.env.TRACEPARENT) ? { traceparent: process.env.TRACEPARENT } : {}),
			},
		});
	} catch (error) {
		throw new AdmissionUnreachableError(
			`public feedback history could not be read: ${errorText(error)}${causeText(error)}`,
		);
	}
	if (isRetryableStatus(response.status)) {
		throw new AdmissionUnreachableError(
			`public feedback history read failed: HTTP ${response.status}`,
		);
	}
	if (!response.ok) {
		throw new Error(
			`public feedback history read was refused: HTTP ${response.status} — ${await answerText(response)}`,
		);
	}
	let body: string;
	try {
		body = await response.text();
	} catch (error) {
		throw new AdmissionUnreachableError(
			`public feedback history body could not be read: ${errorText(error)}${causeText(error)}`,
		);
	}
	return JSON.parse(body);
}

/**
 * The delivered feedback on this work as it stands now, which may include feedback delivered after the work was
 * captured. Kept beside the staged history rather than over it: the staged file is what the review was prepared from.
 */
async function readPublicFeedbackHistory(): Promise<{
	readAt: string;
	history: { feedback: unknown[] };
}> {
	const answer: unknown = await retrying(
		postPublicHistoryRead,
		(error) => error instanceof AdmissionUnreachableError,
		{ attempts: ADMISSION_ATTEMPTS },
		(attempt, error, delayMs) =>
			console.error(
				`[pi-runner] public history read ${attempt}/${ADMISSION_ATTEMPTS} did not arrive (${errorText(error)}); retrying in ${delayMs}ms`,
			),
	);
	const history = isRecord(answer) && isRecord(answer.history) ? answer.history : null;
	if (
		!isRecord(answer) ||
		answer.schemaVersion !== 1 ||
		typeof answer.readAt !== "string" ||
		!Number.isFinite(Date.parse(answer.readAt)) ||
		history === null ||
		!Array.isArray(history.feedback) ||
		!history.feedback.every(
			(entry: unknown) =>
				isRecord(entry) &&
				typeof entry.id === "string" &&
				entry.channel === "IN_CONTEXT" &&
				entry.publicEligible === true &&
				(entry.deliveredAt === null ||
					(typeof entry.deliveredAt === "string" &&
						Number.isFinite(Date.parse(entry.deliveredAt)))) &&
				isRecord(entry.artifact) &&
				typeof entry.artifact.kind === "string" &&
				(entry.artifact.url === undefined ||
					entry.artifact.url === null ||
					typeof entry.artifact.url === "string") &&
				(entry.recordedClaimCurrentness === "CURRENT" ||
					entry.recordedClaimCurrentness === "STALE") &&
				(entry.body === undefined || entry.body === null || typeof entry.body === "string") &&
				(entry.withdrawn === undefined || typeof entry.withdrawn === "boolean"),
		)
	) {
		throw new Error("public feedback history read returned an invalid contract");
	}
	const read = { readAt: answer.readAt, history: { ...history, feedback: history.feedback } };
	writeFileSync(PUBLIC_FEEDBACK_HISTORY_PATH, JSON.stringify(read, null, 2));
	return read;
}

function noteToolCall(turn: TurnTrace, toolName: string, args: unknown, measuring: boolean): void {
	turn.toolCalls[toolName] = (turn.toolCalls[toolName] ?? 0) + 1;
	if (!activeSession) {
		return;
	}
	const calls = Object.values(turn.toolCalls).reduce((sum, count) => sum + count, 0);
	if (
		!measuring &&
		!RECORDING_TOOLS.has(toolName) &&
		sessionGuards.recordingCalls === 0 &&
		calls === COMPOSITION_EXPLORATION_NUDGE
	) {
		console.error(
			`[pi-runner] composition: ${COMPOSITION_EXPLORATION_NUDGE} calls without a recording call — nudging to persist`,
		);
		void steer(activeSession, composerTool === "report_review" ? REVIEW_NUDGE : COMPOSITION_NUDGE);
	}
	const signature = `${toolName}:${JSON.stringify(args)}`;
	const repeats = (repeatedCalls.get(signature) ?? 0) + 1;
	repeatedCalls.set(signature, repeats);
	turn.repeatedCalls = Math.max(turn.repeatedCalls, repeats);
	const recording = RECORDING_TOOLS.has(toolName);
	if (repeats === (recording ? REPEATED_RECORDING_NUDGE : REPEATED_CALL_NUDGE)) {
		console.error(
			`[pi-runner] ${turn.label}: the same ${toolName} call ${repeats} times — nudging to record`,
		);
		// Naming what is still owed matters most when the repeated call is the recording itself: "record"
		// alone reads as "send that again".
		const owed = measuring ? owedPractices() : [];
		const next =
			owed.length > 0
				? `Still owed: ${owed.join(", ")}. Record what the evidence you have read supports for these`
				: "Record what the evidence you have read supports";
		// The review's next tool depends on what it still lacks, which REVIEW_NUDGE already says.
		let correction = `Correct what its answer names and send one ${recording ? toolName : composerTool} call.`;
		if (composerTool === "report_review") {
			correction = `Correct what its answer names. ${REVIEW_NUDGE}`;
		}
		void steer(
			activeSession,
			measuring
				? `You have run the same ${toolName} call ${repeats} times; its result will not change. ${next}, in one report_observation call. ${PERSIST_DISCIPLINE}`
				: `You have run the same ${toolName} call ${repeats} times; its result will not change. ${correction}`,
		);
	}
	if (repeats >= (recording ? REPEATED_RECORDING_ABORT : REPEATED_CALL_ABORT)) {
		stopTurn("loop", `the same ${toolName} call ${repeats} times — aborting this turn`);
	}
	// SDK schema refusals bypass tool execution and its per-practice cap; count attempts here too.
	if (RECORDING_TOOLS.has(toolName)) {
		turn.recordingCalls += 1;
		sessionGuards.recordingCalls += 1;
		if (
			sessionGuards.recordingCalls >= MAX_RECORDING_ATTEMPTS_PER_TURN &&
			sessionGuards.stored === 0
		) {
			stopTurn(
				"loop",
				`${sessionGuards.recordingCalls} recording calls without a record — aborting this turn`,
			);
		}
	}
}

/** Guard memory belongs to one native session; the turn trace still accumulates its shared work. */
const repeatedCalls = new Map<string, number>();
let sessionGuards = { cutOff: 0, recordingCalls: 0, stored: 0 };
function resetSessionGuards(): void {
	repeatedCalls.clear();
	refusedItems.clear();
	recordingMessageTokens = 0;
	sessionGuards = { cutOff: 0, recordingCalls: 0, stored: 0 };
}

function openTurnTrace(label: string, budget: Work, demand: TurnDemand): TurnTrace {
	resetSessionGuards();
	lastEventAt = Date.now();
	currentTurn = {
		modelError: false,
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
	turnDemand = demand;
	return currentTurn;
}

function closeTurnTrace(trace: TurnTrace): void {
	trace.durationMs = Date.now() - trace.durationMs;
	runnerDebug.turns.push(trace);
	currentTurn = null;
	turnDemand = null;
	persistRunnerDebug();
	console.error(
		`[pi-runner] ${trace.label}: ${(trace.durationMs / 1000).toFixed(1)}s, calls=${trace.calls}/${trace.budget.modelCalls}, ` +
			`outputTokens=${trace.outputTokens}/${trace.budget.outputTokens}${trace.stoppedBy ? `, stoppedBy=${trace.stoppedBy}` : ""}, ` +
			`tools=${JSON.stringify(trace.toolCalls)}, stored=${trace.stored}, refused=${trace.refused}` +
			`${trace.toolErrors ? `, toolErrors=${trace.toolErrors}` : ""}${trace.compactions ? `, compactions=${trace.compactions}` : ""}${trace.providerRetries ? `, providerRetries=${trace.providerRetries}` : ""}`,
	);
}

function scheduleDeadline(timeoutMs: number, onTimeout: () => void) {
	// Read timer state through a function so TypeScript does not retain a pre-await narrowing.
	let expired = false;
	const { promise: elapsed, resolve: release } = Promise.withResolvers<undefined>();
	const timer = setTimeout(() => {
		expired = true;
		onTimeout();
		release(undefined);
	}, timeoutMs);
	return { elapsed, timer, expired: () => expired };
}

/** A mid-turn nudge; the turn goes on either way, so a steer that fails is only logged. */
async function steer(session: AgentSession, message: string): Promise<void> {
	try {
		await session.steer(message);
	} catch (error) {
		console.error(`[pi-runner] steer failed: ${errorText(error)}`);
	}
}

/** Abort and wait for idle; Pi refuses a new prompt while the previous run is still active. */
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

/** How long an aborted turn waits for the session to go idle before the next turn is sent. */
const ABORT_SETTLE_MS = 30_000;

/** Return false if the previous operation does not become idle within maxMs. */
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

/** Where the practice's criteria are staged for this review. */
function criteriaPathOf(slug: string): string {
	return `${nodePath.dirname(INPUT_PATHS.practiceIndex)}/${slug}.md`;
}

function criteriaFileOf(slug: string): string | null {
	const file = criteriaPathOf(slug);
	return existsSync(file) ? readFileSync(file, "utf8") : null;
}

/** The leads the practice's precompute script derived, when it ran; empty when it did not. */
function precomputeSectionOf(slug: string): string {
	const file = `${CWD}/work/precompute-out/${slug}.md`;
	const section = existsSync(file) ? readFileSync(file, "utf8").trim() : "";
	return section
		? `\n\n#### Precomputed leads for \`${slug}\` — starting points to check against the criteria, not verdicts\n${section}`
		: "";
}

/** The criteria of the turn's practices, inlined exactly as staged: the turn carries what it asks about. */
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

/**
 * Whether the task, the brief and the measuring illustrations are still in the current session's context. A fresh
 * session holds none of them, so its first turn carries them; a compaction summarizes them away, so the turn after
 * it carries them again, and the model quotes the exact text rather than a summary of it.
 */
let openingInContext = false;

/** The shared user context, before any practice-specific criterion, task or recorded draft. */
function assessmentOpening(brief: string): string {
	return `${prompt}\n\n${brief}\n\n`;
}

/**
 * The task and the brief, when the context no longer holds them; nothing otherwise. A measuring session sees only
 * its own practice's draft: other practices' results are not its context. The composer records no observation, so
 * it gets the task and the brief alone.
 */
function openingIfNeeded(brief: string, composing = false): string {
	if (openingInContext) {
		return "";
	}
	const ownDrafts = reviewState.observations.some((observation) =>
		currentTurnSlugs.includes(observation.practiceSlug),
	);
	return composing
		? assessmentOpening(brief)
		: `${assessmentOpening(brief)}${
				ownDrafts ? `## Recorded so far\n${recordedSoFar(currentTurnSlugs)}\n\n` : ""
			}`;
}

/**
 * Separate single-observation examples with this review's artifact paths. Alternative outcomes keep
 * the call shape consistent with one practice per session without making an outcome the expected one.
 */
const OBSERVATION_EXAMPLE = (() => {
	const context = taskEnvelope.paths.contextRoot;
	const example = [
		{
			revises: null,
			practiceSlug: "<the practice's slug>",
			summary: "Added test asserts that malformed date input is rejected",
			outcome: "MET",
			severity: null,
			evidenceRationale:
				'The added assertion passes "not-a-date" to DateParser.parse and expects an error.',
			evidence: {
				citations: [
					{
						sourceKind: "scm.pull-request.diff",
						artifactPath: `${context}/change.json`,
						path: "Tests/DateParserTests.swift",
						side: "NEW",
						startLine: 12,
						endLine: 12,
						quote: 'XCTAssertThrowsError(try DateParser.parse("not-a-date"))',
					},
				],
			},
		},
		{
			revises: null,
			practiceSlug: "<the practice's slug>",
			summary: "New error branch of the parser ships without a test",
			outcome: "NOT_MET",
			severity: "MINOR",
			evidenceRationale:
				"The change adds a branch that rejects an empty string; no added test feeds the parser one.",
			evidence: {
				citations: [
					{
						sourceKind: "scm.pull-request.diff",
						artifactPath: `${context}/change.json`,
						path: "App/DateParser.swift",
						side: "NEW",
						startLine: 30,
						endLine: 30,
						quote: "guard !text.isEmpty else { throw ParseError.empty }",
					},
				],
				search: {
					consulted: ["scm.pull-request.diff"],
					lookedFor: "an added test that passes an empty string to the parser",
					boundary: "the added test files of this change; tests outside the change were not read",
				},
			},
		},
		{
			revises: null,
			practiceSlug: "<the practice's slug>",
			summary: "No completed merge is recorded",
			outcome: "NOT_APPLICABLE",
			severity: null,
			evidenceRationale: "The captured provider record states that this work has not merged.",
			evidence: {
				citations: [
					{
						sourceKind: "scm.pull-request.core",
						artifactPath: `${context}/metadata.json`,
						path: `${context}/metadata.json`,
						startLine: 3,
						endLine: 3,
						quote: '"is_merged": false',
					},
				],
				inapplicability: {
					consulted: ["scm.pull-request.core"],
					subject: "work that has merged",
					ruledOutBy: "the provider records is_merged as false",
				},
			},
		},
	];
	return `## How report_observation takes observations
The arguments are one complete observation object for this session's practice, not a list and not a JSON-encoded string. Each block below is a whole alternative call, with an illustrative outcome, not evidence from this work:
${example
	.map((observation) => `\`\`\`json\n${JSON.stringify(observation, null, 1)}\n\`\`\``)
	.join("\n\n")}`;
})();

/**
 * A turn of one practice, after the opening when the context does not hold it: its whole criterion first, then
 * the generic illustrations when the context does not hold them, the precomputed leads, and the task. Its work
 * budget is not stated: told its call budget up front, an open model read less and missed more not-met practices.
 */
function practiceTurnText(heading: string, slug: string, brief: string): string {
	const illustrations = openingInContext ? "" : `\n\n${OBSERVATION_EXAMPLE}`;
	return `${openingIfNeeded(brief)}${heading}

${criteriaOf([slug])}${illustrations}${precomputeSectionOf(slug)}

Record one observation for ${slug} with report_observation: the outcome its criterion above decides through its Occasion, Judge and Defer, as the evidence supports it, NOT_APPLICABLE and UNDETERMINED included. What the brief shows is yours to quote; read more when the criterion needs evidence beyond it. This session records and revises only this practice.`;
}

/** The practices with an admitted NOT_MET observation: what the composer has a decision to record on. */
function notMetPractices(observations: readonly AdmittedObservation[]): string[] {
	return [
		...new Set(
			observations
				.filter((observation) => observation.outcome === "NOT_MET")
				.map((observation) => observation.practiceSlug),
		),
	].toSorted();
}

/**
 * Composition's budget: a unit per NOT_MET practice, but never more than a full turn's worth — the
 * lanes cap what it can write, so work beyond that is a loop, not more feedback.
 */
function compositionBudget(notMet: number): Work {
	return turnBudget(Math.min(Math.max(1, notMet), PRACTICES_PER_TURN), PER_PRACTICE_WORK);
}

/** Existing private decision obligation: a stored unit or withholding may fold several practices in. */
function undecidedPrivatePractices(): string[] {
	const practiceOf = new Map(
		admittedObservations.map((observation) => [observation.id, observation.practiceSlug]),
	);
	const decided = new Set(
		composedFeedback.units.flatMap((unit) => [
			unit.practiceSlug,
			...unit.basedOn.flatMap((id) => practiceOf.get(id) ?? []),
		]),
	);
	return notMetPractices(admittedObservations).filter((slug) => !decided.has(slug));
}

function recordCompositionFailure(phase: CompositionPhase, reason: CompositionFailureReason): void {
	if (!composedFeedback.compositionFailures.some((failure) => failure.phase === phase)) {
		composedFeedback.compositionFailures.push({ phase, reason });
	}
}

function incompleteCompositionReason(
	trace: TurnTrace,
	safetyExpired: boolean,
): CompositionFailureReason {
	const stops = { budget: "BUDGET", stall: "STALL", safety: "SAFETY", loop: "LOOP" } as const;
	if (trace.stoppedBy !== null) {
		return stops[trace.stoppedBy];
	}
	if (safetyExpired) {
		return "SAFETY";
	}
	if (trace.modelError) {
		return "MODEL_ERROR";
	}
	if (trace.runtimeError) {
		return "RUNTIME_ERROR";
	}
	return "NO_DECISION";
}

/** Retry undecided composition once, under its own work budget and the run's safety line. */
async function askComposerOnceMore(
	session: AgentSession,
	notMet: readonly string[],
	undecided: () => readonly string[],
	request: CompositionRequest,
	buildText: () => string,
	safety: ReturnType<typeof scheduleDeadline>,
): Promise<TurnTrace> {
	console.error(
		`[pi-runner] composition left ${notMet.length} NOT_MET practice(s) undecided — asking once more`,
	);
	const trace = openTurnTrace("composition once more", compositionBudget(notMet.length), {
		owed: () => undecided().length,
		nudge: COMPOSITION_NUDGE,
		endsWhenPaid: true,
	});
	try {
		if (!(await settleSession(session, "composition", ABORT_SETTLE_MS))) {
			throw new Error("the session was still busy when the composition was asked once more");
		}
		if (!safety.expired()) {
			await Promise.race([
				(async () => {
					const prepared = await prepareTurnText(
						session,
						() => `${buildText()}

${finishCompositionText(notMet, request)}`,
					);
					if (safety.expired()) {
						return;
					}
					if (prepared === null) {
						throw new Error("essential composition input exceeds the context window");
					}
					await session.prompt(prepared, {
						preflightResult: (disposition) => {
							if (disposition === "started") {
								openingInContext = true;
							}
						},
					});
				})(),
				safety.elapsed,
			]);
		}
	} catch (error) {
		trace.runtimeError = trace.stoppedBy === null && !safety.expired();
		console.error(`[pi-runner] composition failed: ${errorText(error)}`);
	} finally {
		if (trace.stoppedBy !== null) {
			await settleSession(session, "composition", ABORT_SETTLE_MS);
		}
		closeTurnTrace(trace);
	}
	return trace;
}

function finishCompositionText(notMet: readonly string[], request: CompositionRequest): string {
	// The lanes as they stand: a retry that does not know a lane is full writes units that are skipped.
	const room = PRIVATE_CHANNELS.filter((channel) => request.channels[channel].enabled)
		.map((channel) => {
			const { maxUnits } = request.channels[channel];
			const used = composedFeedback.units.filter(
				(unit) => unit.channel === channel && unit.action !== "WITHHOLD",
			).length;
			return `${channel} ${Math.max(0, maxUnits - used)} of ${maxUnits}`;
		})
		.join(", ");
	return (
		`## Undecided\nThe turn ended with no unit and no WITHHOLD for these practices, each with a ` +
		`NOT_MET observation: ${notMet.join(", ")}. For each, persist the unit you decided on, or a ` +
		`WITHHOLD with its reason (NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR), in one report_feedback ` +
		`call. Room left for new units: ${room}; a lane with none left takes only a WITHHOLD. The ` +
		`admitted observations are in \`work/composition/observations.json\`. Use tools only from this ` +
		`point onward; no prose.`
	);
}

async function main() {
	console.error(`[pi-runner] Review: one fresh session per practice, then composition`);
	console.error(
		`[pi-runner] Work per practice: ${PER_PRACTICE_WORK.modelCalls} model calls, ${PER_PRACTICE_WORK.outputTokens} output tokens; ` +
			`safety ceiling ${AGENT_BUDGET_MS}ms, measuring hands in by ${WINDOWS.measureMs}ms`,
	);

	// pi-agent-sandbox.ts has the rationale for running untrusted; both Pi runners in this image
	// share it.
	const settingsManager = SettingsManager.create(CWD, AGENT_DIR, SANDBOX_SETTINGS_MANAGER_OPTIONS);
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

	const compositionRequest = loadCompositionRequest();
	const streamUsage = newUsageLedger();
	let providerFailures = 0;
	const measurementReply = { received: false };
	let measuring = true;
	const subscribeSession = (trackedSession: AgentSession) =>
		trackedSession.subscribe((event: AgentSessionEvent) => {
			chargeWork(event);
			const label = measuring ? "review" : "composer";
			if (event.type === "tool_execution_start") {
				// A call a codemode script makes is the script's work, not a call the model sent: the loop
				// guards count only the latter, and the trace keeps the former apart.
				if (event.parentToolCallId === undefined) {
					if (measuring) {
						measurementReply.received = true;
					}
					console.error(`[pi-runner] ${label} tool: ${event.toolName}`);
					if (currentTurn) {
						noteToolCall(currentTurn, event.toolName, event.args, measuring);
					}
				} else {
					console.error(`[pi-runner] ${label} tool: codemode → ${event.toolName}`);
					if (currentTurn) {
						const key = `codemode.${event.toolName}`;
						currentTurn.toolCalls[key] = (currentTurn.toolCalls[key] ?? 0) + 1;
					}
				}
			}
			// SDK validation errors do not reach the tool, so log them from the session event.
			if (
				event.type === "tool_execution_end" &&
				event.isError &&
				!answeredRefusals.delete(event.toolCallId)
			) {
				const result: unknown = event.result;
				const reason = firstLine(toolResultText(result));
				const tool =
					event.parentToolCallId === undefined ? event.toolName : `codemode → ${event.toolName}`;
				console.error(`[pi-runner] ${label} tool error: ${tool} — ${reason}`);
				if (currentTurn) {
					currentTurn.toolErrors += 1;
					currentTurn.toolErrorReasons.push(`${tool}: ${reason}`);
				}
			}
			if (event.type === "compaction_end") {
				console.error(
					`[pi-runner] ${label} context compacted (${event.reason})${hasText(event.errorMessage) ? `: ${event.errorMessage}` : ""}`,
				);
				if (!event.aborted && !hasText(event.errorMessage)) {
					openingInContext = false;
				}
				if (currentTurn && !event.aborted) {
					currentTurn.compactions += 1;
				}
			}
			// The SDK rides out a retryable provider failure on its own. A run that took longer, or that
			// gave up after several of these, says so here rather than looking like an idle session.
			if (event.type === "auto_retry_start") {
				if (currentTurn) {
					currentTurn.providerRetries += 1;
				}
				console.error(
					`[pi-runner] ${label} provider call failed, retrying in ${event.delayMs}ms ` +
						`(attempt ${event.attempt}/${event.maxAttempts}): ${event.errorMessage}`,
				);
			}
			if (event.type === "auto_retry_end" && !event.success) {
				if (currentTurn) {
					currentTurn.modelError = true;
				}
				const finalError = event.finalError ?? "no error given";
				// Only measurement failures can make a review eligible for provider retry.
				if (measuring) {
					providerFailures += 1;
				}
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
					measuring &&
					((stopReason !== "error" && stopReason !== "aborted") ||
						event.message.content.some(
							(content) =>
								content.type === "toolCall" || (content.type === "text" && !isBlank(content.text)),
						))
				) {
					measurementReply.received = true;
				}
				if (currentTurn) {
					currentTurn.modelError = stopReason === "error";
				}
				const types = listOrEmpty(event.message.content).map((c) => c.type);
				const toolCalls = types.filter((t) => t === "toolCall").length;
				const { rawStopReason } = event.message;
				// An error can leave a review unanswered even when the SDK does not retry it.
				if (stopReason === "error" && measuring) {
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
		});

	const allSlugs = loadPracticeSlugs();
	practiceCoverageLedger = new PracticeCoverageLedger(PRACTICE_COVERAGE_PATH, allSlugs);
	persistRecordedNotes();
	const turns = planTurns(practiceIndex, PRACTICES_PER_TURN);
	const brief = buildBrief(CWD, {
		contextRoot: taskEnvelope.paths.contextRoot,
		repositoryRoot: taskEnvelope.paths.repositoryRoot,
	});
	console.error(
		`[pi-runner] Review: ${allSlugs.length} practice(s) in ${turns.length} turn(s); brief=${brief.length} chars`,
	);

	const measureEnd = PROCESS_START_MS + WINDOWS.measureMs;
	const startMs = Date.now();
	const tooLate = Date.now() >= measureEnd;
	if (tooLate) {
		console.error(
			`[pi-runner] FAILED: the run reached its safety ceiling before the session could start`,
		);
	}

	const reportObservationTool = buildReportObservationTool();
	if (tooLate) {
		logPracticeCoverage();
		finalizeOutput();
		process.exit(1);
	}

	const cacheExtension = assessmentCacheExtension(
		providerConfig,
		modelRuntime,
		assessmentOpening(brief),
		taskEnvelope.workspaceId,
		taskEnvelope.jobId,
	);

	async function assessmentLoader() {
		// Extension bindings belong to the native session that uses them.
		const resourceLoader = new DefaultResourceLoader({
			cwd: CWD,
			agentDir: AGENT_DIR,
			settingsManager,
			...SANDBOX_RESOURCE_LOADER_OPTIONS,
			systemPrompt: orchestrator,
			agentsFilesOverride: () => ({ agentsFiles: [] }),
			// "on" keeps every tool callable directly as well; scripts get no model catalog to call.
			extensionFactories: [createCodemodeExtension({ mode: "on", models: false }), cacheExtension],
		});
		await resourceLoader.reload();
		return resourceLoader;
	}

	type SessionOptions = NonNullable<Parameters<typeof createAgentSession>[0]>;
	/**
	 * A fresh native session in this sandbox, with the orchestrator, the evidence tools and the given recording
	 * tool. Reported native assistant usage events go to one stream ledger across these sessions.
	 */
	async function openSession(
		tools: string[],
		customTools: SessionOptions["customTools"],
		resourceLoader?: SessionOptions["resourceLoader"],
	) {
		const { session: fresh, extensionsResult } = await createAgentSession({
			cwd: CWD,
			agentDir: AGENT_DIR,
			tools,
			customTools,
			sessionManager: SessionManager.create(CWD, `${CWD}/.sessions`),
			settingsManager,
			resourceLoader: resourceLoader ?? (await assessmentLoader()),
			modelRuntime,
			model,
			thinkingLevel,
		});
		for (const error of extensionsResult.errors) {
			console.error(`[pi-runner] extension error: ${error.path}: ${error.error}`);
		}
		resetSessionGuards();
		openingInContext = false;
		lastEventAt = Date.now();
		activeSession = fresh;
		return { session: fresh, unsubscribe: subscribeSession(fresh) };
	}

	/** Keep settlement events subscribed until the native session stops before another scope starts. */
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
			if (activeSession === opened.session) {
				activeSession = null;
			}
		}
	}

	// Liveness, not a budget: a turn that shows no sign of life for STALL_MS is aborted.
	const stallWatch = setInterval(() => {
		if (currentTurn?.stoppedBy === null && Date.now() - lastEventAt >= STALL_MS) {
			if (measuring) {
				providerFailures += 1;
			}
			stopTurn("stall", `no model or tool event for ${STALL_MS / 1000}s — aborting this turn`);
		}
	}, STALL_CHECK_MS);
	stallWatch.unref();

	/**
	 * One scope — a catalog group's turn, or the practices no turn recorded — under the one work budget its
	 * practice count earns, whatever the scopes before it spent. The budget is spent across one fresh session per
	 * practice, in order: each receives the task, the brief and that practice's complete criteria, and records only
	 * that practice. Once the budget, the safety line or a stall ends the scope, its remaining practices are not
	 * reached. Returns why the runner ended it, or null when every session ended on its own.
	 */
	async function runScope(
		label: string,
		heading: string,
		slugs: readonly string[],
	): Promise<StopReason | null> {
		const safetyMs = measureEnd - Date.now();
		if (safetyMs <= 0) {
			console.error(`[pi-runner] ${label}: the run is near its safety ceiling — skipped`);
			return "safety";
		}
		const budget = turnBudget(slugs.length, PER_PRACTICE_WORK);
		console.error(
			`[pi-runner] ${label}: ${slugs.length} practice(s), up to ${budget.modelCalls} calls and ${budget.outputTokens} output tokens (${tokensPerObservation} tokens per observation so far)`,
		);
		const trace = openTurnTrace(label, budget, {
			owed: () => owedPractices(slugs).length,
			nudge: RECORD_NUDGE,
		});
		const safety = scheduleDeadline(safetyMs, () =>
			stopTurn("safety", "the run is near its safety ceiling — aborting this turn"),
		);
		// Native events may stop a turn while awaited work settles or another session opens.
		const stoppedBy = () => trace.stoppedBy;
		const pastSafety = () => safety.expired() || Date.now() >= measureEnd;
		/** The model, not the runner, a provider error or the safety line, ended the session with its practice owed. */
		const endedUnrecorded = (slug: string) =>
			owedPractices([slug]).length > 0 &&
			stoppedBy() === null &&
			!pastSafety() &&
			!trace.modelError;
		async function promptPractice(session: AgentSession, text: () => string, slug: string) {
			if (pastSafety()) {
				return;
			}
			const prepared = await prepareTurnText(session, text);
			if (pastSafety()) {
				return;
			}
			if (prepared === null) {
				blockedPractices.add(slug);
				console.error(
					`[pi-runner] ${label}: essential input for ${slug} exceeds the context window — not reached`,
				);
				return;
			}
			await session.prompt(prepared, {
				preflightResult: (disposition) => {
					if (disposition === "started") {
						openingInContext = true;
					}
				},
			});
		}
		/**
		 * Asks a session that ended on its own with its practice owed to record it, once, in the session that holds
		 * what it read and within this scope's budget; a fresh session would read it all again. A second ending on
		 * its own closes the practice.
		 */
		async function askOnceMore(session: AgentSession, text: () => string, slug: string) {
			if (
				!endedUnrecorded(slug) ||
				spent({ modelCalls: trace.calls, outputTokens: trace.outputTokens }, trace.budget)
			) {
				return;
			}
			console.error(
				`[pi-runner] ${label}: the ${slug} session ended without a recorded result — asking once more`,
			);
			if (!(await settleSession(session, label, ABORT_SETTLE_MS))) {
				throw new Error("the session was still busy when it was asked once more");
			}
			// After a compaction the request carries the practice turn again, as any next turn does.
			const onceMore = () =>
				`${openingInContext ? "" : `${text()}\n\n`}${recordOnceMoreText(slug)}`;
			await Promise.race([promptPractice(session, onceMore, slug), safety.elapsed]);
			if (endedUnrecorded(slug)) {
				blockedPractices.add(slug);
				console.error(
					`[pi-runner] ${label}: the ${slug} session ended again without a recorded result — not reached`,
				);
			}
		}
		try {
			for (const [index, slug] of slugs.entries()) {
				if (stoppedBy() !== null || pastSafety()) {
					break;
				}
				if (owedPractices([slug]).length === 0) {
					continue;
				}
				activePractice = slug;
				currentTurnSlugs = [slug];
				// The nudge names the session's practice, so a later session may be told again.
				trace.askedToRecord = false;
				const opened = await openSession(
					[...PRACTICE_TOOLS, "report_observation"],
					[reportObservationTool],
				);
				const text = () =>
					practiceTurnText(
						`${heading}\nPractice ${index + 1} of ${slugs.length} in this turn.`,
						slug,
						brief,
					);
				try {
					await Promise.race([promptPractice(opened.session, text, slug), safety.elapsed]);
					await askOnceMore(opened.session, text, slug);
				} catch (error) {
					console.error(`[pi-runner] ${label} (${slug}) failed: ${errorText(error)}`);
				} finally {
					await closeSession(opened);
				}
			}
		} finally {
			clearTimeout(safety.timer);
			activePractice = null;
			currentTurnSlugs = slugs;
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

	let measureUsage: UsageReport;
	try {
		let stalls = 0;
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
		const missing = missingSlugs(
			allSlugs,
			reviewState.observations.map((item) => item.practiceSlug),
		);
		const unfinished = missing.filter((slug) => !blockedPractices.has(slug));
		if (unfinished.length > 0 && stalls < 2 && Date.now() < measureEnd) {
			console.error(
				`[pi-runner] Finishing ${unfinished.length} practice(s): ${unfinished.join(", ")}`,
			);
			await runScope(
				"finish",
				"## Unfinished practices\nNo turn recorded a result for this practice; evaluate it now.",
				unfinished,
			);
		}
	} finally {
		measuring = false;
		const measureDurationMs = Date.now() - startMs;
		measureUsage = extractUsageFromSession({}, streamUsage);
		accumulateUsage(null, measureUsage);
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
					.flatMap((turn) => (turn.stoppedBy === null ? [] : [`${turn.label}: ${turn.stoppedBy}`]))
					.join("; ")}], observations=${reviewState.observations.length}`,
		);
	}

	const notReached = missingSlugs(
		allSlugs,
		reviewState.observations.map((item) => item.practiceSlug),
	);
	logPracticeCoverage();
	if (notReached.length > 0) {
		console.error(
			`[pi-runner] PARTIAL: ${notReached.length} of ${allSlugs.length} practice(s) not reached: ${notReached.join(", ")}`,
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
	await admitObservations();
	if (compositionRequest) {
		closeInAppWithoutSupport(compositionRequest);
	}
	persistComposedFeedback();
	maybeWriteResultFile();
	if (compositionRequest && admittedObservations.length > 0) {
		const markRemaining = (reason: CompositionFailureReason) => {
			if (
				compositionRequest.channels.IN_CONTEXT.enabled &&
				publicObservations(admittedObservations).length > 0 &&
				composedFeedback.review === null
			) {
				recordCompositionFailure("PUBLIC_REVIEW", reason);
			}
			if (
				PRIVATE_CHANNELS.some((channel) => compositionRequest.channels[channel].enabled) &&
				undecidedPrivatePractices().length > 0
			) {
				recordCompositionFailure("PRIVATE_FEEDBACK", reason);
			}
		};
		const safetyMs = AGENT_BUDGET_MS - (Date.now() - PROCESS_START_MS);
		if (safetyMs <= 0) {
			markRemaining("SAFETY");
			console.error(
				"[pi-runner] The run reached its safety ceiling before composition — preserving admitted observations",
			);
		} else {
			const safety = scheduleDeadline(safetyMs, () => {
				stopTurn(
					"safety",
					"the run is near its safety ceiling — preserving observations and composed feedback so far",
				);
			});
			try {
				// Only an observation that decided something can carry a claim about the work; a review of
				// uncertainty alone has nothing to say there, and opens no session.
				const reviewable = publicObservations(admittedObservations);
				if (compositionRequest.channels.IN_CONTEXT.enabled && reviewable.length > 0) {
					await composeReview(compositionRequest, reviewable, notReached, safety);
				}
				if (notMetPractices(admittedObservations).length > 0 && !safety.expired()) {
					await composePrivately(compositionRequest, safety);
				}
			} catch (error) {
				markRemaining(safety.expired() ? "SAFETY" : "RUNTIME_ERROR");
				throw error;
			} finally {
				if (safety.expired()) {
					markRemaining("SAFETY");
				}
				clearTimeout(safety.timer);
				persistComposedFeedback();
				// Every session's calls are in the one stream ledger; no single session's messages hold them all.
				const combinedUsage = extractUsageFromSession({}, streamUsage);
				accumulateUsage(measureUsage, combinedUsage);
				persistUsage();
			}
		}
	}
	console.error(
		`[pi-runner] SUCCESS: result.json holds ${reviewState.observations.length} observation(s)`,
	);
	finalizeOutput();
	process.exit(0);

	/**
	 * The review on the work, in a fresh session of its own whose only tools show a MET practice's staged standard and
	 * store the whole review, with every other input inline. Private history is not staged into it, and it can read
	 * nothing else from the workspace.
	 */
	async function composeReview(
		request: CompositionRequest,
		reviewable: readonly Record<string, unknown>[],
		notReachedSlugs: readonly string[],
		safety: ReturnType<typeof scheduleDeadline>,
	): Promise<void> {
		const lineNotes = request.inContextPlacementKinds.includes("DIFF");
		const reviewLoader = new DefaultResourceLoader({
			cwd: CWD,
			agentDir: AGENT_DIR,
			settingsManager,
			...PUBLIC_REVIEW_RESOURCE_LOADER_OPTIONS,
			systemPrompt: `${readFileSync(FEEDBACK_STYLE_PATH, "utf8")}\n\n${readFileSync(REVIEW_COMPOSER_PROMPT_PATH, "utf8")}`,
			agentsFilesOverride: () => ({ agentsFiles: [] }),
			extensionFactories: [],
		});
		await reviewLoader.reload();
		// Read now, not at capture: feedback delivered while this review ran was said here too. A failed read writes no
		// review rather than one that could repeat what was just delivered.
		let read: Awaited<ReturnType<typeof readPublicFeedbackHistory>>;
		try {
			read = await readPublicFeedbackHistory();
		} catch (error) {
			console.error(`[pi-runner] The review on the work was not composed: ${errorText(error)}`);
			recordCompositionFailure("PUBLIC_REVIEW", safety.expired() ? "SAFETY" : "RUNTIME_ERROR");
			return;
		}
		const framing = {
			repositoryFullName: taskEnvelope.repositoryFullName,
			pullRequestNumber: taskEnvelope.pullRequestNumber,
		};
		const captured = buildPublicReviewHistory(
			CWD,
			taskEnvelope.paths.contextRoot,
			folderIndex,
			framing,
		);
		const alreadySaid = priorPublicFeedback(
			read.history,
			THIS_WORK,
			captured.capturedAt,
			undefined,
			read.readAt,
		);
		runnerDebug.publicHistory = {
			readAt: read.readAt,
			workCapturedAt: captured.capturedAt,
			entries: alreadySaid.feedback.map(
				({ id, deliveredAt, eligibleForAlreadySaid, eligibleForPriorAdvice }) => ({
					id,
					deliveredAt,
					eligibleForAlreadySaid,
					eligibleForPriorAdvice,
				}),
			),
			omissions: alreadySaid.omissions,
		};
		persistRunnerDebug();
		const restable = reviewableById(reviewable);
		const state: PublicReviewState = {
			final: false,
			reviewContext: "LOST",
			consulted: new Map(),
			inView: new Set(),
			pending: new Set(),
		};
		// One staged read per practice for the whole composition: what a turn shows and what counts as shown agree.
		const reads = new Map<string, string | null | Error>();
		const stagedOnce = (slug: string): string | null => {
			if (!reads.has(slug)) {
				try {
					reads.set(slug, criteriaFileOf(slug));
				} catch (error) {
					reads.set(slug, error instanceof Error ? error : new Error(String(error)));
				}
			}
			const staged = reads.get(slug);
			if (staged instanceof Error) {
				throw staged;
			}
			return staged ?? null;
		};
		const practices = practiceContext(reviewable);
		/** The opening turn and every standard read since, whole: what a compaction removed. */
		const reference = () => {
			const consulted = [...state.consulted.values()];
			return consulted.length === 0
				? text
				: `${text}\n\n### Standards read earlier in this session\n${STANDARD_REFERENCE}\n${consulted.join("\n")}`;
		};
		const restore = () => {
			state.reviewContext = "RESTORING";
			for (const slug of state.consulted.keys()) {
				state.pending.add(slug);
			}
			return reference();
		};
		const { session: reviewSession } = await createAgentSession({
			cwd: CWD,
			agentDir: AGENT_DIR,
			tools: PUBLIC_REVIEW_TOOLS,
			customTools: [
				buildPracticeTool(
					reviewable,
					(slug) => {
						const standard = practiceStandard(slug, stagedOnce);
						const opening = reviewable.some(
							(entry) =>
								entry.publicEligible === true &&
								entry.outcome === "NOT_MET" &&
								entry.practiceSlug === slug,
						);
						return standard.whole && opening
							? {
									text: `The whole standard of \`${slug}\` is shown with its concern in the opening reference.\n`,
									whole: true,
								}
							: standard;
					},
					{ practices, history: alreadySaid.feedback },
					state,
					restore,
				),
				buildReviewTool(
					lineNotes,
					restable,
					priorAdviceWitnesses(alreadySaid.feedback, captured.statements),
					state,
					restore,
				),
			],
			sessionManager: SessionManager.create(CWD, `${CWD}/.sessions`),
			settingsManager,
			resourceLoader: reviewLoader,
			modelRuntime,
			model,
			thinkingLevel,
		});
		// Pi requires every tool to terminate a mixed batch, then AgentSession drains queued nudges.
		// Preserve its boundary; a persisted final review also ends this isolated session's internal queue.
		const { finishTurn } = reviewSession.agent;
		reviewSession.agent.finishTurn = async (turn, signal) => {
			const decision = await finishTurn?.(turn, signal);
			if (state.final) {
				reviewSession.clearQueue();
				return { action: "end" };
			}
			return decision ?? undefined;
		};
		const unsubscribeReview = subscribeSession(reviewSession);
		const unsubscribeContext = reviewSession.subscribe((event) => {
			if (event.type === "compaction_end" && !event.aborted && !hasText(event.errorMessage)) {
				state.reviewContext = "LOST";
				state.inView.clear();
				state.pending.clear();
			} else if (event.type === "turn_start") {
				// Pi begins the next model turn after the current tool batch has ended: only then can the model read
				// what that batch's answers showed.
				if (state.reviewContext === "RESTORING") {
					state.reviewContext = "HELD";
				}
				for (const slug of state.pending) {
					state.inView.add(slug);
				}
				state.pending.clear();
			}
		});
		activeSession = reviewSession;
		composerTool = "report_review";
		// Admission's own rows: the projection the review rests on drops the digests a source is checked against.
		const repository = nodePath.resolve(CWD, taskEnvelope.paths.repositoryRoot);
		const primarySource = buildPrimarySourceReference(
			CWD,
			taskEnvelope.paths.contextRoot,
			taskEnvelope.paths.repositoryRoot,
			folderIndex,
			admittedObservations,
			{
				blob: (revision, file, limit) => readPinnedBlob(repository, revision, file, limit),
				diff: (base, head, limit) => pinnedDiff(repository, base, head, limit),
				checkedOut: () => checkedOutCommit(repository),
			},
		);
		const text = buildReviewTurn({
			sameWork: [
				buildSameWorkContext(CWD, taskEnvelope.paths.contextRoot, folderIndex, framing),
				primarySource,
			]
				.filter((part) => part !== "")
				.join("\n\n"),
			observations: reviewable,
			undecided: uncertainOutcomes(
				admittedObservations.filter((observation) => observation.publicEligible === true),
			),
			alreadySaid: alreadySaid.feedback,
			ownHistoryOmissions: alreadySaid.omissions,
			ownHistoryReadAt: read.readAt,
			captured,
			practices,
			notReached: notReachedSlugs,
			lineNotes,
			stagedCriteria: stagedOnce,
		});
		// The composition owes one final review, whatever it decides: an all-MET review is final only when sent.
		const owed = () => (state.final ? 0 : Math.max(1, undecidedByReview(reviewable).length));
		const budget = compositionBudget(owed());
		let started = false;
		const wasStarted = () => started;
		const trace = openTurnTrace("review composition", budget, { owed, nudge: REVIEW_NUDGE });
		let finalTrace = trace;
		try {
			try {
				if (safety.expired()) {
					trace.stoppedBy = "safety";
					throw new Error("the run reached its safety ceiling before the review was due");
				}
				await Promise.race([
					(async () => {
						const prepared = await prepareTurnText(reviewSession, () => text);
						if (safety.expired()) {
							return;
						}
						if (prepared === null) {
							throw new Error("essential review input exceeds the context window");
						}
						await reviewSession.prompt(prepared, {
							preflightResult: (disposition) => {
								if (disposition === "started") {
									started = true;
									state.reviewContext = "HELD";
								}
							},
						});
					})(),
					safety.elapsed,
				]);
			} catch (error) {
				trace.runtimeError = trace.stoppedBy === null && !safety.expired();
				console.error(`[pi-runner] review composition failed: ${errorText(error)}`);
			} finally {
				if (trace.stoppedBy !== null) {
					await settleSession(reviewSession, "review composition", ABORT_SETTLE_MS);
				}
				closeTurnTrace(trace);
			}
			const left = undecidedByReview(reviewable);
			if (
				wasStarted() &&
				!trace.modelError &&
				!trace.runtimeError &&
				(trace.stoppedBy === null || trace.stoppedBy === "loop") &&
				!state.final &&
				(await settleSession(reviewSession, "review composition", ABORT_SETTLE_MS)) &&
				!safety.expired()
			) {
				console.error(
					left.length > 0
						? `[pi-runner] the review left ${left.length} NOT_MET observation(s) undecided — asking once more`
						: "[pi-runner] the review is not final — asking once more",
				);
				const retry = openTurnTrace("review composition once more", budget, {
					owed,
					nudge: REVIEW_NUDGE,
					endsWhenPaid: true,
				});
				finalTrace = retry;
				try {
					await Promise.race([
						(async () => {
							// Decided when the prompt is prepared, which may compact: only a prompt that carries the
							// reference puts its standards back in view.
							let restoring = false;
							const prepared = await prepareTurnText(reviewSession, () => {
								restoring = state.reviewContext !== "HELD";
								return `${restoring ? `${reference()}\n\n` : ""}${finishReviewText(left)}`;
							});
							if (safety.expired()) {
								return;
							}
							if (prepared === null) {
								throw new Error("essential review input exceeds the context window");
							}
							await reviewSession.prompt(prepared, {
								preflightResult: (disposition) => {
									if (disposition === "started") {
										state.reviewContext = "HELD";
										if (restoring) {
											for (const slug of state.consulted.keys()) {
												state.inView.add(slug);
											}
										}
									}
								},
							});
						})(),
						safety.elapsed,
					]);
				} catch (error) {
					retry.runtimeError = retry.stoppedBy === null && !safety.expired();
					console.error(`[pi-runner] review composition failed: ${errorText(error)}`);
				} finally {
					if (retry.stoppedBy !== null) {
						await settleSession(reviewSession, "review composition", ABORT_SETTLE_MS);
					}
					closeTurnTrace(retry);
				}
			}
		} finally {
			try {
				await stopBeforeSwitching(reviewSession);
				if (!state.final) {
					recordCompositionFailure(
						"PUBLIC_REVIEW",
						incompleteCompositionReason(finalTrace, safety.expired()),
					);
				}
				persistComposedFeedback();
			} finally {
				unsubscribeReview();
				unsubscribeContext();
			}
			activeSession = null;
			composerTool = "report_feedback";
		}
	}

	/**
	 * The private lanes, in a fresh session opened after measurement ended: it receives the task, the brief, the
	 * admitted observations and the person's authorized history, reads with the evidence tools, and records only
	 * with report_feedback. No measuring transcript is in its context.
	 */
	async function composePrivately(
		request: CompositionRequest,
		safety: ReturnType<typeof scheduleDeadline>,
	): Promise<void> {
		if (!PRIVATE_CHANNELS.some((channel) => request.channels[channel].enabled)) {
			return;
		}
		const feedbackTool = buildFeedbackTool(
			composablePracticeSlugs(),
			request,
			admittedObservations,
			stagedPreparedTargets(),
			stagedDeliveredPractices(),
		);
		const privateLoader = new DefaultResourceLoader({
			cwd: CWD,
			agentDir: AGENT_DIR,
			settingsManager,
			...SANDBOX_RESOURCE_LOADER_OPTIONS,
			systemPrompt: `${readFileSync(FEEDBACK_STYLE_PATH, "utf8")}\n\n${readFileSync(COMPOSER_PROMPT_PATH, "utf8")}`,
			agentsFilesOverride: () => ({ agentsFiles: [] }),
			extensionFactories: [createCodemodeExtension({ mode: "on", models: false })],
		});
		await privateLoader.reload();
		composerTool = "report_feedback";
		// A lane closed after admission (IN_APP without its support read) leaves only the lanes still open.
		{
			currentTurnSlugs = [];
			const opened = await openSession(
				[...PRACTICE_TOOLS, "report_feedback"],
				[feedbackTool],
				privateLoader,
			);
			const { session } = opened;
			let finalTrace: TurnTrace;
			try {
				finalTrace = await composeInto(session);
			} finally {
				await closeSession(opened);
			}
			if (undecidedPrivatePractices().length > 0) {
				recordCompositionFailure(
					"PRIVATE_FEEDBACK",
					incompleteCompositionReason(finalTrace, safety.expired()),
				);
			}
		}

		async function composeInto(session: AgentSession): Promise<TurnTrace> {
			const notMet = notMetPractices(admittedObservations);
			const undecided = undecidedPrivatePractices;
			let started = false;
			const wasStarted = () => started;
			const trace = openTurnTrace("composition", compositionBudget(notMet.length), {
				owed: () => undecided().length,
				nudge: COMPOSITION_NUDGE,
			});
			let finalTrace = trace;
			try {
				if (safety.expired()) {
					trace.stoppedBy = "safety";
					throw new Error("the run reached its safety ceiling before composition was due");
				}
				const compositionTurn = buildCompositionTurn(request, admittedObservations, notReached);
				await Promise.race([
					(async () => {
						const compositionText = await prepareTurnText(
							session,
							() => `${openingIfNeeded(brief, true)}${compositionTurn}`,
						);
						if (safety.expired()) {
							return;
						}
						if (compositionText === null) {
							throw new Error("essential composition input exceeds the context window");
						}
						await session.prompt(compositionText, {
							preflightResult: (disposition) => {
								if (disposition === "started") {
									openingInContext = true;
									started = true;
								}
							},
						});
					})(),
					safety.elapsed,
				]);
			} catch (error) {
				trace.runtimeError = trace.stoppedBy === null && !safety.expired();
				console.error(`[pi-runner] composition failed: ${errorText(error)}`);
			} finally {
				if (trace.stoppedBy !== null) {
					await settleSession(session, "composition", ABORT_SETTLE_MS);
				}
				closeTurnTrace(trace);
			}
			// A composer that ended on its own, or was cut off by a loop guard, is asked once more for the
			// NOT_MET practices it left undecided; otherwise they have no composed next step.
			const left = undecided();
			if (
				wasStarted() &&
				!trace.modelError &&
				!trace.runtimeError &&
				(trace.stoppedBy === null || trace.stoppedBy === "loop") &&
				left.length > 0 &&
				!safety.expired()
			) {
				finalTrace = await askComposerOnceMore(
					session,
					left,
					undecided,
					request,
					() =>
						openingInContext
							? ""
							: `${openingIfNeeded(brief, true)}${buildCompositionTurn(request, admittedObservations, notReached)}`,
					safety,
				);
			}
			return finalTrace;
		}
	}
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
		finalizeOutputQuietly();
		// Preserve the retryable exit code for admission transport failures.
		process.exit(error instanceof AdmissionUnreachableError ? SERVER_UNREACHABLE_EXIT : 2);
	}
}

void run();
