import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import nodePath from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

import {
	type AgentSession,
	type AgentSessionEvent,
	type AgentToolResult,
	createAgentSession,
	DefaultResourceLoader,
	getAgentDir,
	defineTool,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import {
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { CHANGE_ROOT } from "./pi-change.ts";
import { errorText } from "./pi-error-text.ts";
import {
	ASSESSMENT_STATUS_VALUES,
	ASSESSMENT_STATUS_DESCRIPTIONS,
	PRESENCE_VALUES,
	PRESENCE_DESCRIPTIONS,
	ASSESSMENT_VALUES,
	ASSESSMENT_DESCRIPTIONS,
	MAX_SUMMARY_CHARS,
	SEVERITY_VALUES,
	SEVERITY_DESCRIPTIONS,
	boundedAtSentenceEnd,
	cellsRuledOut,
	deriveOutcome,
	dedupeKeyForObservation,
	describeVocabulary,
	isRecord,
	type NormalizedCitation,
	type NormalizedObservation,
	normalizeObservation,
	resolveQuote,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
} from "./pi-observation-normalize.ts";
import { PracticeCoverageLedger } from "./pi-practice-coverage.ts";
import { loadProviderConfig, reasoningSetting, registerHephaestusProvider } from "./pi-provider.ts";
import { buildBrief } from "./pi-review-brief.ts";
import { deriveWindows, missingSlugs, planTurns, turnShare } from "./pi-review-turns.ts";
import {
	ACTIONS,
	CHANNELS,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	type PreparedFeedbackTarget,
	notReachedNote,
	sameLinesNote,
	undeliverableUnits,
	validateFeedbackEvidence,
} from "./pi-runner-composition.ts";
import { outputPath } from "./pi-runner-output.ts";
import { isRetryableStatus, isTimeoutAbort, retrying } from "./pi-runner-retry.ts";
import {
	addAssistantUsage,
	extractUsageFromSession,
	newUsageLedger,
	type UsageReport,
} from "./pi-runner-usage.ts";
import { stopSession } from "./pi-session-lifecycle.ts";
import { SUPPORTED_SCHEMA_VERSION, taskPaths, resolveTaskPaths } from "./pi-task-paths.ts";
import { hasText, isBlank } from "./pi-text.ts";

// One session measures and composes. Persisted work/ notes survive context compaction.

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
function submittedList(value: unknown): { items: unknown[] } | { error: string } {
	if (typeof value === "string") {
		try {
			return { items: listOrItem(parseJson(repairedClosers(value))) };
		} catch (error) {
			return {
				error: `the list arrived as a string that is not a JSON array (${errorText(error)}); send the array itself, not a string`,
			};
		}
	}
	return { items: listOrItem(value) };
}

/** The list, or the one item that was sent where a list of them was asked for. */
function listOrItem(value: unknown): unknown[] {
	if (Array.isArray(value)) {
		return value;
	}
	return isRecord(value) ? [value] : [];
}

/** Repair missing or unmatched closers without changing quoted strings; JSON.parse rejects other faults. */
function repairedClosers(text: string): string {
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
			if (char === "}" && owed.length === 2 && owed[0] === "]" && keyFollows(text, index + 1)) {
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

/** Whether `, "key":` is what comes next — a member of an object, not the next item of a list. */
function keyFollows(text: string, from: number): boolean {
	let index = from;
	const skipSpace = () => {
		while (index < text.length && /\s/u.test(text[index] ?? "")) {
			index += 1;
		}
	};
	skipSpace();
	if (text[index] !== ",") {
		return false;
	}
	index += 1;
	skipSpace();
	if (text[index] !== '"') {
		return false;
	}
	index += 1;
	while (index < text.length && text[index] !== '"') {
		if (text[index] === "\\") {
			index += 1;
		}
		index += 1;
	}
	index += 1;
	skipSpace();
	return text[index] === ":";
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
}

/** The task this runner was started for. */
interface TaskEnvelope {
	schemaVersion: number;
	jobId: unknown;
	workspaceId: unknown;
	paths: ReturnType<typeof taskPaths>;
	task: {
		kind: string;
		prompt: string;
		// Only ever logged, so they are carried exactly as written rather than validated into a shape.
		repositoryFullName: unknown;
		pullRequestNumber: unknown;
	};
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
		Array.isArray(value.citations) &&
		value.citations.every(isAdmittedCitation)
	);
}

const WORKSPACE_ROOT = "/workspace";
const EVIDENCE_TOOLS = ["read", "grep", "find", "ls"] as const;
const PRACTICE_TOOLS = [...EVIDENCE_TOOLS, "write", "edit", "bash"] as const;
const CWD = process.env.PI_RUNNER_CWD ?? WORKSPACE_ROOT;
const ENVELOPE_MISMATCH_EXIT = 42;
const SUPPORTED_KIND = "practice_review";
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
const AGENT_BUDGET_MS = Number(process.env.AGENT_BUDGET_MS);
if (!Number.isFinite(AGENT_BUDGET_MS) || AGENT_BUDGET_MS <= 0) {
	throw new Error(
		`AGENT_BUDGET_MS env var is required and must be a positive number, got: ${process.env.AGENT_BUDGET_MS}`,
	);
}
const AGENT_DIR = process.env.PI_CODING_AGENT_DIR;
if (!hasText(AGENT_DIR)) {
	throw new Error("PI_CODING_AGENT_DIR env var is required");
}
/** Practices a turn carries at once; a catalog group larger than this is split. */
const PRACTICES_PER_TURN = hasText(process.env.PI_PRACTICE_BATCH_SIZE)
	? Number(process.env.PI_PRACTICE_BATCH_SIZE)
	: 6;
/** Per-practice refusal cap; an exhausted practice remains NOT_REACHED. */
const MAX_REFUSALS_PER_PRACTICE = 8;
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

/** Citation ownership comes from the captured manifest; fail closed when it is unreadable. */
function readManifest(): {
	availableSourceKinds: Set<string>;
	artifactSources: Map<string, string>;
} {
	const manifest = parseJson(readFileSync(INPUT_PATHS.manifest, "utf8"));
	if (!isRecord(manifest) || !Array.isArray(manifest.sources)) {
		throw new Error("Task manifest: expected a sources array");
	}
	const availableSourceKinds = new Set<string>();
	const artifactSources = new Map<string, string>();
	for (const source of jsonArray(manifest.sources)) {
		if (!isRecord(source) || typeof source.kind !== "string" || !isRecord(source.state)) {
			throw new Error("Task manifest: every source needs a string kind and a state");
		}
		if (source.state.availability === "AVAILABLE") {
			availableSourceKinds.add(source.kind);
		}
		for (const artifact of jsonArray(source.artifacts)) {
			if (isRecord(artifact) && typeof artifact.path === "string") {
				artifactSources.set(artifact.path, source.kind);
			}
		}
	}
	return { availableSourceKinds, artifactSources };
}

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
		};
	});
}

const { availableSourceKinds, artifactSources } = readManifest();
const availableSourceKindValues = [...availableSourceKinds].toSorted();
const stagedArtifactPaths = [...artifactSources.keys()].toSorted();
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
	softTimeoutFired?: boolean;
	hardAborted: boolean;
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
	softTimeoutFired: boolean;
	hardAborted: boolean;
}
const runnerDebug: { attempts: AttemptDebug[]; turns: TurnTrace[]; usageTotals: UsageTotals } = {
	attempts: [],
	turns: [],
	usageTotals,
};
/** The turn the session events are charged to; null between turns. */
let currentTurn: TurnTrace | null = null;

/** How much of a refusal reason the trace keeps: the kind of failure, not the whole excerpt. */
const TRACE_REASON_CHARS = 140;
const reviewState: { observations: NormalizedObservation[]; observationKeys: string[] } = {
	observations: [],
	observationKeys: [],
};
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
			description:
				"What this practice looks for, e.g. error handling around outbound network calls.",
		},
		ruledOutBy: {
			type: "string",
			minLength: 1,
			description:
				"The fact about THIS work that means the subject cannot occur in it, e.g. the change touches " +
				"only Markdown documentation and makes no network calls.",
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
					artifactPath: { type: "string", enum: stagedArtifactPaths },
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
const observationSchema = {
	type: "object",
	additionalProperties: false,
	required: [
		"practiceSlug",
		"summary",
		"assessmentStatus",
		"presence",
		"assessment",
		"severity",
		"evidence",
		"evidenceRationale",
	],
	properties: {
		practiceSlug: { type: "string", minLength: 1 },
		summary: {
			type: "string",
			minLength: 1,
			maxLength: MAX_SUMMARY_CHARS,
			// documentedShape removes schema bounds, so include this limit in the model-facing description.
			description:
				`A short phrase of at most ${MAX_SUMMARY_CHARS} characters identifying the specific behavior whose presence and contextual desirability you assess, such as 'Debug print left in the request handler'. ` +
				"Never a single word and never the practice's own name; the reasons go in evidenceRationale.",
		},
		assessmentStatus: {
			type: "string",
			enum: ASSESSMENT_STATUS_VALUES,
			description: describeVocabulary(ASSESSMENT_STATUS_VALUES, ASSESSMENT_STATUS_DESCRIPTIONS),
		},
		presence: {
			type: ["string", "null"],
			enum: [...PRESENCE_VALUES, null],
			description: describeVocabulary(PRESENCE_VALUES, PRESENCE_DESCRIPTIONS),
		},
		assessment: {
			type: ["string", "null"],
			enum: [...ASSESSMENT_VALUES, null],
			description: `Only when ASSESSED, otherwise null. ${describeVocabulary(ASSESSMENT_VALUES, ASSESSMENT_DESCRIPTIONS)}`,
		},
		severity: {
			type: ["string", "null"],
			enum: [...SEVERITY_VALUES, null],
			description: `Only for a NEGATIVE outcome, otherwise null. ${describeVocabulary(SEVERITY_VALUES, SEVERITY_DESCRIPTIONS)}`,
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
				"A concise explanation of how the cited evidence warrants this outcome. Describe evidence, " +
				"not advice, intent, confidence, or hidden chain-of-thought.",
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

function normalizeAndValidateObservation(rawObservation: unknown): Validated {
	const notes: string[] = [];
	const observation = normalizeObservation(
		rawObservation,
		ruledOutCellsOf(slugOf(rawObservation).toLowerCase().replaceAll("_", "-")),
		notes,
	);
	if (!admittedPractices.has(observation.practiceSlug)) {
		throw new Error(`unknown practice '${observation.practiceSlug}'`);
	}
	// The manifest says which source staged an artifact; a citation that names the artifact under
	// another source kind is read as the manifest reads it, and the correction is echoed back.
	for (const citation of observation.evidence.citations) {
		// A quote of a staged record (description.md, comments.json) named as its path under the pinned
		// change is a quote of that record: the artifact is the path, and a diff side says nothing of it.
		const stagedAtPath = artifactSources.get(citation.path);
		if (stagedAtPath !== undefined && citation.artifactPath !== citation.path) {
			notes.push(
				`${citation.path} is an artifact of its own, staged by ${stagedAtPath}; recorded against it, not ${citation.artifactPath}`,
			);
			citation.artifactPath = citation.path;
			citation.sourceKind = stagedAtPath;
			delete citation.side;
		}
		const staged = artifactSources.get(citation.artifactPath);
		if (staged !== undefined && staged !== citation.sourceKind) {
			notes.push(
				`${citation.artifactPath} is staged by ${staged}, not ${citation.sourceKind}; recorded as ${staged}`,
			);
			citation.sourceKind = staged;
		}
	}
	validateEvidenceSources(observation, availableSourceKinds, artifactSources);
	validateSearchScope(
		observation,
		practiceExhaustiveSources.get(observation.practiceSlug) ?? new Set(),
		availableSourceKinds,
	);
	validateInapplicabilityScope(observation, availableSourceKinds);
	// Inapplicability must be grounded in the change unless another observation already consulted it.
	if (
		observation.assessmentStatus !== "ASSESSED" &&
		availableSourceKinds.has(DIFF_SOURCE) &&
		![...reviewState.observations, observation].some(readTheChange)
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
					`artifact text and, for the change, the [L<n>] coordinates and OLD/NEW side of ${CHANGE_ROOT}/diff.patch`,
			);
		}
		const cited = `${citation.path}:${citation.startLine}-${citation.endLine}`;
		if (!citation.quote.trim()) {
			notes.push(`${cited} recorded ${excerptOf(resolved.quote)}`);
		}
		if (resolved.startLine !== undefined && resolved.endLine !== undefined) {
			notes.push(
				`${cited} does not hold that text; it is at ${citation.path}:${resolved.startLine}-${resolved.endLine}, recorded there`,
			);
			citation.startLine = resolved.startLine;
			citation.endLine = resolved.endLine;
		}
		citation.quote = resolved.quote;
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
	if (citation.sourceKind === "scm.repository.tree") {
		return citation.revision === undefined
			? readCheckoutFile(citation.path)
			: readRevisionFile(citation.path, citation.revision);
	}
	if (citation.sourceKind === "scm.pull-request.diff") {
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
				"that file is binary and has no lines to quote; cite the change that adds it (work/change/files.json) or the text that embeds it",
		};
	}
	return resolveOnEitherSide(citation, content);
}

/** The bytes as text, or BINARY: a quote of a binary file cannot be verified, here or at admission. */
function asText(bytes: Buffer): string | typeof BINARY {
	return bytes.subarray(0, 8000).includes(0) ? BINARY : bytes.toString("utf8");
}

/** The blob at a repository-relative path in a revision of the checkout's history, or null. */
function readRevisionFile(path: string, revision: string): string | typeof BINARY | null {
	if (path.startsWith("/") || path.split("/").includes("..")) {
		return null;
	}
	const child = spawnSync(
		"git",
		["-C", INPUT_PATHS.repositoryRoot, "--no-pager", "show", `${revision}:${path}`],
		{
			maxBuffer: 64 * 1024 * 1024,
			env: { ...process.env, GIT_TERMINAL_PROMPT: "0", GIT_OPTIONAL_LOCKS: "0" },
		},
	);
	return child.status === 0 ? asText(child.stdout) : null;
}

/** The file at a repository-relative path in the checkout, or null when there is none. */
function readCheckoutFile(path: string): string | typeof BINARY | null {
	const file = nodePath.resolve(INPUT_PATHS.repositoryRoot, path);
	if (!file.startsWith(`${INPUT_PATHS.repositoryRoot}/`)) {
		return null;
	}
	try {
		return asText(readFileSync(file));
	} catch {
		return null;
	}
}

/** What one submitted observation came to: stored, a duplicate of one already stored, or refused. */
type Recorded =
	| { kind: "stored"; slug: string; negative: boolean; filled: string[] }
	| { kind: "duplicate"; slug: string }
	| { kind: "refused"; slug: string; reason: string };

/** Set once the recorded observations have gone to admission; no observation is accepted after it. */
let measurementClosed = false;

/** Calls of the recording tools one turn may make before recording anything; past it, the turn ends. */
const MAX_RECORDING_ATTEMPTS_PER_TURN = 24;

/** Per-turn thresholds for nudging and aborting identical tool calls. */
const REPEATED_CALL_NUDGE = 3;
const REPEATED_CALL_ABORT = 6;

/** Avoid logging SDK error events again when the tool already logged its refusal. */
const answeredRefusals = new Set<string>();

/** A refusal the tool itself is answering: logged where it was decided, and once. */
async function refusal<T>(toolCallId: string, text: string): Promise<AgentToolResult<T>> {
	answeredRefusals.add(toolCallId);
	throw new Error(text);
}

/** The tools a turn exists to call: what it records with them is what it owes. */
const RECORDING_TOOLS: ReadonlySet<string> = new Set([
	"report_observation",
	"report_feedback",
	"report_summary",
]);

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

/** Refused submissions per practice; a practice past MAX_REFUSALS_PER_PRACTICE accepts no more. */
const refusals = new Map<string, number>();
const blockedPractices = new Set<string>();

function countRefusal(slug: string): void {
	const count = (refusals.get(slug) ?? 0) + 1;
	refusals.set(slug, count);
	if (count >= MAX_REFUSALS_PER_PRACTICE) {
		blockedPractices.add(slug);
	}
}

function slugOf(raw: unknown): string {
	return isRecord(raw) && typeof raw.practiceSlug === "string" ? raw.practiceSlug : "unknown";
}

function record(raw: unknown): Recorded {
	const slug = slugOf(raw);
	// An item with no slug is answered with what it lacks, never with the bound of a practice named
	// "unknown": the refusal it is counted under is a bookkeeping name, not one the session sent.
	if (slug !== "unknown" && blockedPractices.has(slug)) {
		return {
			kind: "refused",
			slug,
			reason: `${MAX_REFUSALS_PER_PRACTICE} submissions for '${slug}' were refused; no more are accepted for it. Move on.`,
		};
	}
	let validated: Validated;
	try {
		validated = normalizeAndValidateObservation(raw);
	} catch (error) {
		countRefusal(slug);
		return { kind: "refused", slug, reason: errorText(error) };
	}
	const { observation, notes } = validated;
	const key = dedupeKeyForObservation(observation);
	if (reviewState.observationKeys.includes(key)) {
		return { kind: "duplicate", slug };
	}
	reviewState.observationKeys.push(key);
	reviewState.observations.push(observation);
	// What the check recorded for a citation by coordinates alone, and where it moved a citation
	// whose text was elsewhere, is echoed back so the session sees what its evidence became.
	return {
		kind: "stored",
		slug,
		negative: deriveOutcome(observation.presence, observation.assessment) === "NEGATIVE",
		filled: notes,
	};
}

/** Appends the observations a turn recorded to the notes file: the review's memory across compaction. */
function noteRecorded(observations: readonly NormalizedObservation[]): void {
	const lines = observations.map((observation) => {
		const cited = [...new Set(observation.evidence.citations.map((citation) => citation.path))];
		const verdict =
			observation.assessmentStatus === "ASSESSED"
				? `${observation.presence}/${observation.assessment}`
				: observation.assessmentStatus;
		return `- ${observation.practiceSlug}: ${verdict} — ${observation.summary} (cites ${cited.join(", ")})`;
	});
	try {
		mkdirSync(nodePath.dirname(NOTES_PATH), { recursive: true });
		const existing = existsSync(NOTES_PATH)
			? readFileSync(NOTES_PATH, "utf8")
			: "# Recorded observations\n\nOne line per observation this review has recorded, appended by the runner.\n";
		writeFileSync(NOTES_PATH, lines.length === 0 ? existing : `${existing}${lines.join("\n")}\n`);
	} catch (error) {
		console.error(`[pi-runner] notes could not be written: ${errorText(error)}`);
	}
}

/** One line per recorded observation, for the top of every later turn. */
function recordedSoFar(): string {
	if (reviewState.observations.length === 0) {
		return "Nothing recorded yet.";
	}
	return reviewState.observations
		.map((observation) => {
			const verdict =
				observation.assessmentStatus === "ASSESSED"
					? `${observation.presence}/${observation.assessment}`
					: observation.assessmentStatus;
			return `- ${observation.practiceSlug}: ${verdict} — ${observation.summary}`;
		})
		.join("\n");
}

interface ReportObservationDetails {
	inserted: number;
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

/** The practices the current turn asked about; a recorded result for one of them is what the turn owes. */
let currentTurnSlugs: readonly string[] = [];

/** JSON Schema keywords that refuse; what they say is applied per observation instead. */
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
 * Pi validates a whole tool call before execution. Keep shape hints here, but validate each item in
 * normalizeAndValidateObservation so one invalid item does not discard the rest of the batch.
 */
function documentedShape(schema: unknown): unknown {
	if (Array.isArray(schema)) {
		return schema.map(documentedShape);
	}
	if (!isRecord(schema)) {
		return schema;
	}
	const out: Record<string, unknown> = {};
	// Pi coerces scalar types; remove container types to permit serialized lists and per-item errors.
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
		label: "Report Observations",
		description:
			"Record one or more evidenced practice observations in local review state, for server admission " +
			"after the measuring turns. Send every observation you have ready in one call; each is stored or " +
			"refused on its own, with the reason.",
		parameters: {
			type: "object",
			required: ["observations"],
			properties: {
				observations: documentedShape(listSchema(observationSchema, "observations")),
			},
		},
		execute: async (toolCallId, params): Promise<AgentToolResult<ReportObservationDetails>> => {
			if (measurementClosed) {
				const text = "Measurement is closed; this turn may only compose feedback.";
				return {
					content: [{ type: "text", text }],
					details: {
						inserted: 0,
						duplicates: 0,
						refused: 0,
						totalObservations: reviewState.observations.length,
						remainingPractices: [],
					},
				};
			}
			const submitted = submittedList(isRecord(params) ? params.observations : null);
			if ("error" in submitted) {
				// Counted against every practice of the turn, so a session that keeps sending the same
				// unparseable string runs out of tries like any other refusal.
				for (const slug of currentTurnSlugs) {
					countRefusal(slug);
				}
				logRefusal("(unparsed list)", submitted.error);
				return refusal(toolCallId, `observations refused — ${submitted.error}`);
			}
			const outcomes = submitted.items.map(record);
			const stored = outcomes.filter((outcome) => outcome.kind === "stored");
			for (const outcome of outcomes) {
				if (outcome.kind === "refused") {
					logRefusal(outcome.slug, outcome.reason);
				}
			}
			if (currentTurn) {
				currentTurn.stored += stored.length;
			}
			if (stored.length > 0) {
				persistReviewState();
				maybeWriteResultFile();
				persistPracticeCoverage();
				noteRecorded(reviewState.observations.slice(-stored.length));
			}
			const observed = new Set(reviewState.observations.map((item) => item.practiceSlug));
			const remainingPractices = currentTurnSlugs.filter((slug) => !observed.has(slug));
			const lines = outcomes.map((outcome, index) => {
				const head = `#${index + 1} ${outcome.slug}:`;
				if (outcome.kind === "stored") {
					return `${head} stored${outcome.negative ? " (negative)" : ""}.${outcome.filled.map((line) => `\n   ${line}`).join("")}`;
				}
				if (outcome.kind === "duplicate") {
					return `${head} duplicate of one already stored; skipped.`;
				}
				return `${head} refused — ${outcome.reason}`;
			});
			lines.push(
				remainingPractices.length > 0
					? `No recorded result yet for: ${remainingPractices.join(", ")}.`
					: "Every practice of this turn has a recorded result.",
			);
			const text = lines.join("\n");
			const details: ReportObservationDetails = {
				inserted: stored.length,
				duplicates: outcomes.filter((outcome) => outcome.kind === "duplicate").length,
				refused: outcomes.filter((outcome) => outcome.kind === "refused").length,
				totalObservations: reviewState.observations.length,
				remainingPractices,
			};
			// A call that stored nothing is an error the session must correct; one that stored some of
			// what it sent is an answer, with the refusals named in it.
			if (stored.length === 0 && details.duplicates === 0) {
				return refusal(toolCallId, text);
			}
			return { content: [{ type: "text", text }], details };
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

/** What a composer that reads instead of writing is told, at the exploration bound and near the budget's end. */
const COMPOSITION_NUDGE =
	`Stop reading: the admitted observations and the history are in this turn's prompt and in ` +
	`work/composition/observations.json, and nothing else decides a unit. Persist the units you have ` +
	`with report_feedback now — and a WITHHOLD with its reason for each NEGATIVE practice you decided ` +
	`to stay quiet about — then call report_summary once. Use tools only from this point onward; no ` +
	`planning prose.`;

/** Calls a composition may make before its first recording call; at this one it is nudged to persist. */
const COMPOSITION_EXPLORATION_NUDGE = 12;

const PERSIST_DISCIPLINE =
	`Record the outcome the evidence supports, positive or negative or not applicable; there is no quota ` +
	`and no next step to write. Use tools only from this point onward; no planning prose.`;

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
	const task: Record<string, unknown> = isRecord(envelope.task) ? envelope.task : {};
	if (task.kind !== SUPPORTED_KIND) {
		console.error(
			`[pi-runner] Unknown task kind: got "${logValue(task.kind)}", expected "${SUPPORTED_KIND}". ` +
				`This runner only handles practice_review tasks.`,
		);
		process.exit(ENVELOPE_MISMATCH_EXIT);
	}
	if (typeof task.prompt !== "string" || task.prompt.trim() === "") {
		console.error(`[pi-runner] task.prompt is missing or blank in ${TASK_PATH}`);
		process.exit(ENVELOPE_MISMATCH_EXIT);
	}
	let paths: ReturnType<typeof taskPaths>;
	try {
		paths = taskPaths(envelope.paths);
	} catch (error) {
		console.error(`[pi-runner] ${errorText(error)}`);
		process.exit(ENVELOPE_MISMATCH_EXIT);
	}
	return {
		paths,
		schemaVersion: SUPPORTED_SCHEMA_VERSION,
		jobId: envelope.jobId,
		workspaceId: envelope.workspaceId,
		task: {
			kind: SUPPORTED_KIND,
			prompt: task.prompt,
			repositoryFullName: task.repositoryFullName,
			pullRequestNumber: task.pullRequestNumber,
		},
	};
}

const prompt = taskEnvelope.task.prompt.trim();
console.error(
	`[pi-runner] Task envelope loaded: kind=${taskEnvelope.task.kind}, ` +
		`jobId=${logValue(taskEnvelope.jobId)}, workspaceId=${logValue(taskEnvelope.workspaceId)}, ` +
		`repository=${logValue(taskEnvelope.task.repositoryFullName ?? "?")}, ` +
		`prNumber=${logValue(taskEnvelope.task.pullRequestNumber ?? "?")}`,
);

const COMPOSITION_REQUEST_PATH = INPUT_PATHS.compositionRequest;
const FEEDBACK_PATH = outputPath(OUTPUT, "feedback.json");
const COMPOSER_PROMPT_PATH = `${CWD}/feedback-composer.md`;
const PREPARED_FEEDBACK_PATH = INPUT_PATHS.preparedFeedback;
const COMPOSITION_OBSERVATIONS_PATH = `${CWD}/work/composition/observations.json`;
let compositionAdmitted = false;
let admissionDigest: string | null = null;
const WITHHOLD_REASONS = ["NO_MATERIAL_CHANGE", "ALREADY_SAID", "BELOW_BAR"] as const;

type Channel = (typeof CHANNELS)[number];
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

/** Where an IN_CONTEXT note is to be attached, as the model asked for it. */
interface Placement {
	kind: string;
	observationId?: string;
	citationIndex?: number;
}

/** The notes an IN_CHAT unit carries TO the mentor. Kept in step with ConversationBrief by Java. */
interface ConversationNotes {
	situation?: string;
	capability?: string;
	evidenceSummary?: string;
	inConversationSignal?: string;
	alreadySaid?: string;
}

const LEAD_MAX_LENGTH = 240;

interface ReportSummaryDetails {
	stored: number;
}

/** Refused leads before the tool answers that the review opens on its first finding and stays quiet. */
const MAX_LEAD_REFUSALS = 3;

function buildSummaryTool() {
	let leadRefusals = 0;

	return defineTool({
		name: "report_summary",
		label: "Report Summary",
		description:
			"Write how this review opens, in your own words: one or two sentences, at most " +
			`${LEAD_MAX_LENGTH} characters, no counts, no quotes. One call; a second call replaces the ` +
			"first. Skip it and the review opens on its first finding.",
		// Shape and documentation only; the bound is applied below, with the reason, like every rule of
		// the other recording tools.
		parameters: {
			type: "object",
			required: ["lead"],
			properties: {
				lead: documentedShape({
					type: "string",
					description: `One or two sentences orienting the reader in this change, within ${LEAD_MAX_LENGTH} characters.`,
				}),
			},
		},
		execute: async (toolCallId, params): Promise<AgentToolResult<ReportSummaryDetails>> => {
			const refuse = async (text: string) => refusal<ReportSummaryDetails>(toolCallId, text);
			if (!compositionAdmitted) {
				return refuse(
					"Feedback composition opens only after Java admits the completed observations.",
				);
			}
			// A session that cannot land a lead in three tries is spending the composition on it; the
			// review opens on its first finding, which is a fine opening, and the units are what matter.
			if (leadRefusals >= MAX_LEAD_REFUSALS) {
				return {
					content: [
						{
							type: "text",
							text: "The review opens on its first finding; do not call report_summary again — persist the units with report_feedback.",
						},
					],
					details: { stored: 0 },
				};
			}
			const raw = isRecord(params) ? params.lead : undefined;
			const trimmed = (typeof raw === "string" ? raw : "").replaceAll(/\s+/gu, " ").trim();
			if (!trimmed) {
				leadRefusals += 1;
				return refuse(
					"lead is required: one or two sentences as a string; skip the call instead of sending nothing.",
				);
			}
			const lead = boundedAtSentenceEnd(trimmed, LEAD_MAX_LENGTH);
			if (lead === undefined) {
				leadRefusals += 1;
				return refuse(
					`lead must be at most ${LEAD_MAX_LENGTH} characters, or end a sentence within them; this one is ${trimmed.length} with no sentence end inside the bound. Send one shorter sentence.`,
				);
			}
			composedFeedback.lead = lead;
			persistComposedFeedback();
			if (currentTurn) {
				currentTurn.stored += 1;
			}
			return {
				content: [
					{
						type: "text",
						text:
							lead === trimmed
								? "Stored the opening line."
								: `Stored the opening line up to its last sentence end within ${LEAD_MAX_LENGTH} characters: "${lead}"`,
					},
				],
				details: { stored: 1 },
			};
		},
	});
}

/** Runtime lane and placement choices require per-unit validation in readFeedbackUnit/validateUnit. */
interface FeedbackUnit extends ComposedFeedbackUnit {
	channel: Channel;
	practiceSlug: string;
	basedOn: string[];
	action: FeedbackAction;
	supersedesThreadKey?: string;
	withholdReason?: string;
	title?: string;
	body?: string;
	nextStep?: string;
	notes?: ConversationNotes;
	placement?: Placement;
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
	assessmentStatus: unknown;
	presence: unknown;
	id: string;
	practiceSlug: string;
	assessment: unknown;
	outcome: unknown;
	severity: unknown;
	anchorable: unknown;
	citations: LeanCitation[];
}

/** What gets written to feedback.json, with the fields the reader resolves references against. */
interface ComposedFeedback extends ComposedFeedbackEnvelope {
	admissionDigest: string | null;
	observations: LeanObservation[];
	preparedTargets: PreparedFeedbackTarget[];
	units: FeedbackUnit[];
	lead: string | null;
}

// Echo the exact composition inputs so Java validates references against the same snapshot.
const composedFeedback: ComposedFeedback = {
	admissionDigest: null,
	observations: [],
	preparedTargets: [],
	units: [],
	lead: null,
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

// Inline placement requires a citation inside the current diff.
function leanObservations(observations: readonly AdmittedObservation[]): LeanObservation[] {
	return observations.map((observation) => ({
		id: observation.id,
		assessmentStatus: observation.assessmentStatus,
		presence: observation.presence,
		practiceSlug: observation.practiceSlug,
		assessment: observation.assessment,
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
		console.error(
			`[pi-runner] composed feedback the server cannot deliver: ${unit.channel}/${unit.practiceSlug} ` +
				`supersedes '${unit.supersedesThreadKey}', which this envelope does not list as staged`,
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
) {
	// The reader resolves supersession against the envelope's copy of this list and drops any unit
	// naming a thread outside it, so the vocabulary is recorded here, where it is decided.
	composedFeedback.preparedTargets = preparedTargets;
	const enabledChannels = CHANNELS.filter((channel) => request.channels[channel].enabled);
	const placementKinds = request.inContextPlacementKinds;
	const usedPerChannel: Record<Channel, number> = { IN_CONTEXT: 0, IN_APP: 0, IN_CHAT: 0 };
	const seen = new Set<string>();

	/** One unit stored, or the reason it was not. */
	const store = (value: unknown): { stored: boolean; text: string } => {
		const read = readFeedbackUnit(value, practiceSlugs);
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
			return skipped(`already have a ${unit.channel} unit for ${unit.practiceSlug}; skipped.`);
		}
		const delivers = unit.action !== "WITHHOLD";
		if (delivers && usedPerChannel[unit.channel] >= bounds.maxUnits) {
			return skipped(`${unit.channel} cap of ${bounds.maxUnits} reached; skipped.`);
		}
		// WITHHOLD requires a NEGATIVE observation; otherwise there is nothing to withhold.
		if (
			!delivers &&
			!observations.some(
				(observation) =>
					observation.practiceSlug === unit.practiceSlug && observation.outcome === "NEGATIVE",
			)
		) {
			return skipped(
				`${unit.practiceSlug} has no NEGATIVE observation in this run, so there is nothing to withhold; skipped.`,
			);
		}
		const rejection = validateUnit(unit, observationsById, preparedTargets, placementKinds);
		if (rejection !== null) {
			return skipped(rejection);
		}
		// IN_APP pattern feedback requires NEGATIVE observations on distinct pieces of work.
		if (unit.channel === "IN_APP" && delivers) {
			const pieces = negativePiecesOfWork(unit.practiceSlug, observations);
			if (pieces < request.minDistinctArtifacts) {
				return skipped(
					`IN_APP needs a pattern across at least ${request.minDistinctArtifacts} pieces of work, and ` +
						`${unit.practiceSlug} is NEGATIVE on ${pieces} (this work and the history); WITHHOLD it ` +
						`with BELOW_BAR, and keep the note on the work. Skipped.`,
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
			text: `stored a ${unit.channel} unit for ${unit.practiceSlug} (${unit.action}); ${usedPerChannel[unit.channel]}/${bounds.maxUnits} used on that lane.`,
		};
	};

	return defineTool({
		name: "report_feedback",
		label: "Report Feedback",
		description:
			"Persist feedback units, one per channel and practice. Send every unit you have ready in one " +
			"call; each is stored or skipped on its own, with the reason, and a skipped unit can be sent " +
			"again corrected without re-sending the stored ones. This is an intervention, not a " +
			"measurement: it takes no presence, assessment, severity or confidence, and no citation you typed yourself.",
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
										"NEW to say something; SUPERSEDE to replace a message that is queued and unread; " +
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
										"IN_APP only: explain the cross-artifact work pattern grounded in current observations; never quote a line or claim change over time from the pre-run history. Markdown, read verbatim.",
								},
								nextStep: {
									type: "string",
									maxLength: FEEDBACK_TEXT_BOUNDS.nextStep,
									description:
										"IN_CONTEXT: one edit before merging. IN_APP: one repeatable habit for the next piece of work. Name the missing decision, not a heading/template unless the practice requires one; never provide paste-ready prose.",
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
												"What you saw: factual, specific, the artifacts named. Your words about them, " +
												"not words for them - third person, never addressed to the developer as 'you', " +
												"and never a judgement of the person.",
										},
										capability: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.capability,
											description:
												"The understanding or self-check this conversation should support. State the capability, not a solution such as a required heading/template, and not a question, script, diagnosis, or fixed tactic.",
										},
										evidenceSummary: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.evidenceSummary,
											description:
												"A concise account of the artifacts and observations that ground this note. " +
												"Summarise rather than inventing a quote; the original observation evidence is " +
												"staged separately for the mentor to inspect.",
										},
										inConversationSignal: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.inConversationSignal,
											description:
												"A sign detectable before the conversation ends: a distinction, decision, question, or self-check the developer can articulate. Not a promise, future artifact, message text, or compliance target.",
										},
										alreadySaid: {
											type: "string",
											maxLength: FEEDBACK_TEXT_BOUNDS.alreadySaid,
											description:
												"Optional. Where this has already been put to the developer and what has moved without help, from the feedback history. Omit it when the history has nothing on this practice: absent means nothing has been said yet, which the mentor reads differently from nothing to say.",
										},
									},
								},
								placement: {
									type: "object",
									description:
										"IN_CONTEXT only. DIFF places a note at one verified observation citation and " +
										"needs observationId and citationIndex; ARTIFACT places it in the issue or change " +
										"summary without inventing a line and takes no coordinates.",
									properties: {
										kind: { type: "string", enum: placementKinds },
										observationId: {
											type: "string",
											description:
												"DIFF only: the admitted observation whose citation carries the note.",
										},
										citationIndex: {
											type: "integer",
											description:
												"DIFF only: the index of that citation in the observation, from 0.",
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
			const submitted = submittedList(isRecord(params) ? params.units : null);
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
			};
		},
	});
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
	"placement",
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
	let placement: Placement | undefined;
	if (value.placement !== undefined && value.placement !== null) {
		if (!isRecord(value.placement)) {
			return "placement must be an object; skipped.";
		}
		const kind = vocabularyWord(value.placement.kind, ["DIFF", "ARTIFACT"], "placement.kind");
		if (!isPlacementKind(kind)) {
			return `${kind}; skipped.`;
		}
		const citationIndex = integerOrUndefined(value.placement.citationIndex);
		const observationId = optionalString(value.placement.observationId)?.trim();
		placement = {
			kind,
			...(hasText(observationId) ? { observationId } : {}),
			citationIndex,
		};
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
		placement,
	};
}

/** An integer as sent, as a number or as its digits in a string; anything else is nothing. */
function integerOrUndefined(value: unknown): number | undefined {
	if (typeof value === "number") {
		return value;
	}
	return typeof value === "string" && /^\d+$/u.test(value.trim()) ? Number(value) : undefined;
}

/** A list as sent, or the one value sent bare where a list was asked for. */
function listOrSingle(value: unknown): unknown[] {
	if (Array.isArray(value)) {
		return value;
	}
	return value === undefined || value === null ? [] : [value];
}

// Enforce snapshot-dependent constraints here for fast model correction; Java rechecks them.
/** Count distinct artifacts with NEGATIVE observations, including this review. */
function negativePiecesOfWork(
	practiceSlug: string,
	current: readonly AdmittedObservation[],
): number {
	const pieces = new Set<string>();
	if (
		current.some(
			(observation) =>
				observation.practiceSlug === practiceSlug && observation.outcome === "NEGATIVE",
		)
	) {
		pieces.add("this work");
	}
	const historyPath = `${nodePath.dirname(PREPARED_FEEDBACK_PATH)}/observations.json`;
	if (!existsSync(historyPath)) {
		return pieces.size;
	}
	const history = parseJson(readFileSync(historyPath, "utf8"));
	const entries =
		isRecord(history) && Array.isArray(history.observations) ? history.observations : [];
	for (const entry of entries) {
		if (!isRecord(entry) || entry.practiceSlug !== practiceSlug || entry.outcome !== "NEGATIVE") {
			continue;
		}
		const artifact = isRecord(entry.artifact) ? entry.artifact : {};
		const name = [artifact.url, artifact.number, artifact.title].find(
			(value) => typeof value === "string" || typeof value === "number",
		);
		if (name !== undefined) {
			pieces.add(`${typeof artifact.kind === "string" ? artifact.kind : ""}:${name}`);
		}
	}
	return pieces.size;
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
	if (unit.placement) {
		return "Only IN_CONTEXT units may carry a placement; skipped.";
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
	if (unit.placement) {
		return "Only IN_CONTEXT units may carry a placement; skipped.";
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
	placementKinds: readonly PlacementKind[],
): string | null {
	const evidenceError = validateFeedbackEvidence(
		unit.practiceSlug,
		unit.basedOn,
		new Map([...observationsById].map(([id, observation]) => [id, observation.practiceSlug])),
	);
	if (evidenceError !== null) {
		return evidenceError;
	}
	if (unit.action === "WITHHOLD") {
		if (!hasText(unit.withholdReason)) {
			return "WITHHOLD needs a withholdReason; skipped.";
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
	if (unit.channel === "IN_APP") {
		return validateAppUnit(unit, observationsById);
	}
	if (hasText(unit.body)) {
		return "IN_CONTEXT takes title, placement, and nextStep only; skipped.";
	}
	if (!unit.placement) {
		return "IN_CONTEXT needs a DIFF or ARTIFACT placement; skipped.";
	}
	const { placement } = unit;
	if (!placementKinds.some((kind) => kind === placement.kind)) {
		return `${placement.kind} placement is unavailable on this artifact; skipped.`;
	}
	if (unit.placement.kind === "ARTIFACT") {
		if (unit.placement.observationId != null || unit.placement.citationIndex != null) {
			return "ARTIFACT placement takes no observationId or citationIndex; skipped.";
		}
		const grounded = unit.basedOn.some(
			(id) => observationsById.get(id)?.practiceSlug === unit.practiceSlug,
		);
		if (!grounded) {
			return "ARTIFACT placement must be based on a current observation for this practice; skipped.";
		}
		return null;
	}
	if (placement.kind !== "DIFF") {
		return "Unknown IN_CONTEXT placement kind; skipped.";
	}
	const { observationId, citationIndex } = placement;
	if (!hasText(observationId) || citationIndex === undefined || !Number.isInteger(citationIndex)) {
		return "DIFF placement needs observationId and citationIndex; skipped.";
	}
	const observation = observationsById.get(observationId);
	if (!observation) {
		return `No observation '${observationId}' in this run; skipped.`;
	}
	const citation = observation.citations[citationIndex];
	if (!citation) {
		return `Observation '${observation.id}' has no citation ${citationIndex}; skipped.`;
	}
	if (citation.anchorable !== true) {
		return `Citation ${citationIndex} of '${observation.id}' is not on this change's diff, so no note can be placed on it. Skipped.`;
	}
	return null;
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

/** A staged file as a titled block of the composition turn, or a pointer to it when it is too large. */
function shown(label: string, file: string, limit = 32_000): string {
	if (!existsSync(file)) {
		return "";
	}
	const text = readFileSync(file, "utf8").trim();
	return text.length > limit
		? `### ${label} — too large to show here; read \`${file}\`\n`
		: `### ${label}\n\`\`\`json\n${text}\n\`\`\`\n`;
}

function buildCompositionTurn(
	request: CompositionRequest,
	observations: readonly AdmittedObservation[],
	notReached: readonly string[] = [],
): string {
	const lanes = CHANNELS.filter((channel) => request.channels[channel].enabled)
		.map((channel) => `${channel} (at most ${request.channels[channel].maxUnits})`)
		.join(", ");
	const closed = CHANNELS.filter((channel) => !request.channels[channel].enabled);
	const anchorable = observations.filter((observation) => Boolean(observation.anchorable)).length;
	const closedNote =
		closed.length > 0 ? ` Closed this turn, so write nothing for them: ${closed.join(", ")}.` : "";
	const placementNote = request.channels.IN_CONTEXT.enabled
		? ` IN_CONTEXT placements available here: ${request.inContextPlacementKinds.join(", ")}.`
		: "";
	const coverageNote = notReachedNote(notReached);
	const sameLines = sameLinesNote(observations);
	const admitted = JSON.stringify({ observations: observations.map(composerView) }, null, 1);
	const historyRoot = nodePath.dirname(PREPARED_FEEDBACK_PATH);
	const context = [
		shown("The composition request (lanes, caps, placements)", COMPOSITION_REQUEST_PATH),
		shown("What earlier reviews recorded about this person", `${historyRoot}/observations.json`),
		shown("What was already said to them", `${historyRoot}/feedback.json`),
		shown(
			"What is written for them and still unread (supersession targets)",
			PREPARED_FEEDBACK_PATH,
		),
	]
		.filter((block) => block !== "")
		.join("\n");
	return `## This turn
The review just finished. Its ${observations.length} admitted measurement(s) follow; ${anchorable} of them cite a line inside this change and can therefore carry a note on the work. The citations are shown by their coordinates; the full record, quoted lines included, is \`work/composition/observations.json\`. The history follows them.

\`\`\`json
${admitted}
\`\`\`

${context}
Lanes open this turn: ${lanes}.${closedNote}${placementNote}
A pattern claim needs at least ${request.minDistinctArtifacts} distinct pieces of work.

${sameLines}${coverageNote}Persist the units with report_feedback — every unit you have in one call — and call report_summary once for how the review opens. Writing nothing on a lane is a correct and common outcome; say in one line why, and stop.`;
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
		response = await fetch(`${process.env.LLM_PROXY_URL}/admit-observations`, {
			method: "POST",
			signal: AbortSignal.timeout(ADMISSION_ATTEMPT_TIMEOUT_MS),
			headers: {
				authorization: `Bearer ${process.env.LLM_PROXY_TOKEN}`,
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
		turn.recordingCalls === 0 &&
		calls === COMPOSITION_EXPLORATION_NUDGE
	) {
		console.error(
			`[pi-runner] composition: ${COMPOSITION_EXPLORATION_NUDGE} calls without a recording call — nudging to persist`,
		);
		void steer(activeSession, COMPOSITION_NUDGE);
	}
	const signature = `${toolName}:${JSON.stringify(args)}`;
	const repeats = (repeatedCalls.get(signature) ?? 0) + 1;
	repeatedCalls.set(signature, repeats);
	turn.repeatedCalls = Math.max(turn.repeatedCalls, repeats);
	if (repeats === REPEATED_CALL_NUDGE) {
		console.error(
			`[pi-runner] ${turn.label}: the same ${toolName} call ${repeats} times — nudging to record`,
		);
		void steer(
			activeSession,
			`You have run the same ${toolName} call ${repeats} times; its result will not change. Record what the evidence you have read supports, in one ${measuring ? "report_observation" : "report_feedback"} call. ${PERSIST_DISCIPLINE}`,
		);
	}
	if (repeats >= REPEATED_CALL_ABORT) {
		console.error(
			`[pi-runner] ${turn.label}: the same ${toolName} call ${repeats} times — aborting this turn`,
		);
		void abortSession(activeSession);
	}
	// SDK schema refusals bypass tool execution and its per-practice cap; count attempts here too.
	if (RECORDING_TOOLS.has(toolName)) {
		turn.recordingCalls += 1;
		if (turn.recordingCalls >= MAX_RECORDING_ATTEMPTS_PER_TURN && turn.stored === 0) {
			console.error(
				`[pi-runner] ${measuring ? "review" : "composer"}: ${turn.recordingCalls} recording calls without a record — aborting this turn`,
			);
			void abortSession(activeSession);
		}
	}
}

/** How often each call of the current turn has been made: the loop guard's memory, reset per turn. */
const repeatedCalls = new Map<string, number>();

function openTurnTrace(label: string): TurnTrace {
	repeatedCalls.clear();
	currentTurn = {
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
		softTimeoutFired: false,
		hardAborted: false,
	};
	return currentTurn;
}

function closeTurnTrace(trace: TurnTrace): void {
	trace.durationMs = Date.now() - trace.durationMs;
	runnerDebug.turns.push(trace);
	currentTurn = null;
	persistRunnerDebug();
	console.error(
		`[pi-runner] ${trace.label}: ${(trace.durationMs / 1000).toFixed(1)}s, calls=${trace.calls}, ` +
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
	if (!session.isStreaming) {
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

function criteriaFileOf(slug: string): string | null {
	const file = `${nodePath.dirname(INPUT_PATHS.practiceIndex)}/${slug}.md`;
	return existsSync(file) ? readFileSync(file, "utf8").trim() : null;
}

const ruledOutCells = new Map<string, Set<string>>();
/** The cells the practice's Judge section rules out, read once from its criteria. */
function ruledOutCellsOf(slug: string): Set<string> {
	let cells = ruledOutCells.get(slug);
	if (!cells) {
		cells = cellsRuledOut(criteriaFileOf(slug) ?? "");
		ruledOutCells.set(slug, cells);
	}
	return cells;
}

/** The criteria of the turn's practices, inlined: the turn carries what it asks about. */
function criteriaOf(slugs: readonly string[]): string {
	return slugs
		.map((slug) => {
			const criteria = criteriaFileOf(slug) ?? "(criteria file missing)";
			const exhaustive = [...(practiceExhaustiveSources.get(slug) ?? [])];
			const scope =
				exhaustive.length > 0
					? `Exhaustive sources (an ABSENT claim must have searched all of them): ${exhaustive.join(", ")}.\n\n`
					: "";
			return `### Practice \`${slug}\`\n${scope}${criteria}`;
		})
		.join("\n\n");
}

function measureTurnText(
	turn: { id: string; slugs: string[] },
	turnNumber: number,
	turnCount: number,
	brief: string,
): string {
	const opening =
		turnNumber === 1 ? `${prompt}\n\n${brief}\n\n` : `## Recorded so far\n${recordedSoFar()}\n\n`;
	return `${opening}## Turn ${turnNumber} of ${turnCount}: ${turn.id}
Evaluate these practices: ${turn.slugs.join(", ")}. Their criteria follow and decide the outcome. What the brief shows is yours to quote; read more only when a criterion needs it. Record one observation per practice — the outcome the criteria and the evidence support, NOT_APPLICABLE and UNDETERMINED included — with report_observation, every one that is ready in one call.

${criteriaOf(turn.slugs)}`;
}

/**
 * Include the incoming composition prompt in the space check. Pi's automatic preflight compaction
 * checks the previous assistant message, before appending this prompt. A failed manual compaction
 * leaves the SDK's normal overflow recovery available.
 */
async function makeRoomFor(
	session: AgentSession,
	model: { contextWindow: number; maxTokens?: number },
	text: string,
): Promise<boolean> {
	const held = session.getContextUsage()?.tokens ?? 0;
	const needed = Math.ceil(text.length / 4) + (model.maxTokens ?? 0);
	if (held + needed <= model.contextWindow) {
		return false;
	}
	console.error(
		`[pi-runner] composition: ${held} tokens held and ${needed} needed exceed the ${model.contextWindow} window — compacting first`,
	);
	try {
		await session.compact();
		return true;
	} catch (error) {
		console.error(`[pi-runner] composition: compaction failed: ${errorText(error)}`);
		return false;
	}
}

/** The practices with an admitted NEGATIVE observation: what the composer has a decision to record on. */
function negativePractices(observations: readonly AdmittedObservation[]): string[] {
	return [
		...new Set(
			observations
				.filter((observation) => observation.outcome === "NEGATIVE")
				.map((observation) => observation.practiceSlug),
		),
	].toSorted();
}

/** Retry undecided composition once, with a fresh loop guard and the original deadline. */
async function askComposerOnceMore(
	session: AgentSession,
	negatives: readonly string[],
	deadline: ReturnType<typeof scheduleDeadline>,
): Promise<void> {
	console.error(
		`[pi-runner] composition left ${negatives.length} NEGATIVE practice(s) undecided — asking once more`,
	);
	if (!(await settleSession(session, "composition", ABORT_SETTLE_MS))) {
		throw new Error("the session was still busy when the composition was asked once more");
	}
	if (deadline.expired()) {
		return;
	}
	repeatedCalls.clear();
	await Promise.race([session.prompt(finishCompositionText(negatives)), deadline.elapsed]);
}

function finishCompositionText(negatives: readonly string[]): string {
	return (
		`## Undecided\nThe turn ended with no unit and no WITHHOLD for these practices, each with a ` +
		`NEGATIVE observation: ${negatives.join(", ")}. For each, persist the unit you decided on, or a ` +
		`WITHHOLD with its reason (NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR), in one report_feedback ` +
		`call. The admitted observations are in \`work/composition/observations.json\`. Use tools only ` +
		`from this point onward; no prose.`
	);
}

function finishTurnText(missing: readonly string[]): string {
	return (
		`## Recorded so far\n${recordedSoFar()}\n\n## Unfinished practices\n` +
		`No observation was recorded for: ${missing.join(", ")}. Record the outcome the evidence you have ` +
		`read supports for each, in one report_observation call; read more only to settle a specific open ` +
		`question. Evaluate no other practice. ${PERSIST_DISCIPLINE}`
	);
}

async function main() {
	console.error(`[pi-runner] One-session review`);
	console.error(
		`[pi-runner] Budget: total=${AGENT_BUDGET_MS}ms, measure=${WINDOWS.measureMs}ms, composition=${WINDOWS.compositionMs}ms`,
	);

	// pi-agent-sandbox.ts has the rationale for running untrusted; both Pi runners in this image
	// share it.
	const settingsManager = SettingsManager.create(CWD, AGENT_DIR, SANDBOX_SETTINGS_MANAGER_OPTIONS);
	// Disable instruction discovery; load only the server-staged orchestrator (see pi-agent-sandbox.ts).
	const orchestratorPath = `${AGENT_DIR}/AGENTS.md`;
	const orchestrator = readFileSync(orchestratorPath, "utf8");
	const loader = new DefaultResourceLoader({
		cwd: CWD,
		agentDir: AGENT_DIR ?? getAgentDir(),
		settingsManager,
		...SANDBOX_RESOURCE_LOADER_OPTIONS,
		agentsFilesOverride: () => ({
			agentsFiles: [{ path: orchestratorPath, content: orchestrator }],
		}),
	});
	await loader.reload();
	const modelRuntime = await ModelRuntime.create({
		authPath: `${AGENT_DIR}/auth.json`,
		modelsPath: `${AGENT_DIR}/models.json`,
		allowModelNetwork: false,
	});

	const providerConfig = loadProviderConfig(CWD);
	const registered = registerHephaestusProvider(modelRuntime, providerConfig);
	if (!registered || !hasText(providerConfig?.modelId)) {
		throw new Error(
			"Hephaestus provider is not configured — pi-provider.json and proxy credentials are required",
		);
	}
	const model = modelRuntime.getModel("hephaestus", providerConfig.modelId);
	if (!model) {
		throw new Error(`Hephaestus model was not registered: ${providerConfig.modelId}`);
	}
	console.error(
		`[pi-runner] registered hephaestus provider: apiProtocol=${providerConfig.apiProtocol} ` +
			`model=${providerConfig.modelId} contextWindow=${model.contextWindow}`,
	);
	const { thinkingLevel } = reasoningSetting(providerConfig.reasoningEffort);
	console.error(
		`[pi-runner] reasoning effort: ${providerConfig.reasoningEffort?.toLowerCase() ?? "provider default"}`,
	);

	const compositionRequest = loadCompositionRequest();
	const feedbackTool = compositionRequest
		? buildFeedbackTool(
				composablePracticeSlugs(),
				compositionRequest,
				admittedObservations,
				stagedPreparedTargets(),
			)
		: null;
	const streamUsage = newUsageLedger();
	let providerFailures = 0;
	let measuring = true;
	const subscribeSession = (trackedSession: AgentSession) =>
		trackedSession.subscribe((event: AgentSessionEvent) => {
			const label = measuring ? "review" : "composer";
			if (event.type === "tool_execution_start") {
				console.error(`[pi-runner] ${label} tool: ${event.toolName}`);
				if (currentTurn) {
					noteToolCall(currentTurn, event.toolName, event.args, measuring);
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
				console.error(`[pi-runner] ${label} tool error: ${event.toolName} — ${reason}`);
				if (currentTurn) {
					currentTurn.toolErrors += 1;
					currentTurn.toolErrorReasons.push(`${event.toolName}: ${reason}`);
				}
			}
			if (event.type === "compaction_end") {
				console.error(
					`[pi-runner] ${label} context compacted (${event.reason})${hasText(event.errorMessage) ? `: ${event.errorMessage}` : ""}`,
				);
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
				if (currentTurn) {
					currentTurn.calls += 1;
				}
				const { stopReason } = event.message;
				const types = listOrEmpty(event.message.content).map((c) => c.type);
				const toolCalls = types.filter((t) => t === "toolCall").length;
				const { rawStopReason } = event.message;
				// Non-retryable provider errors also require a retry outcome if no practice was recorded.
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
	noteRecorded([]);
	const turns = planTurns(practiceIndex, PRACTICES_PER_TURN);
	const brief = buildBrief(CWD, {
		contextRoot: taskEnvelope.paths.contextRoot,
		repositoryRoot: taskEnvelope.paths.repositoryRoot,
	});
	console.error(
		`[pi-runner] Review: ${allSlugs.length} practice(s) in ${turns.length} turn(s); brief=${brief.length} chars`,
	);

	const measureEnd = PROCESS_START_MS + WINDOWS.measureMs;
	let hardAborted = false;
	let softTimeoutFired = false;
	const startMs = Date.now();
	if (Date.now() >= measureEnd) {
		console.error(`[pi-runner] FAILED: the review budget was spent before the session could start`);
		hardAborted = true;
	}

	const customTools = [buildReportObservationTool()];
	if (feedbackTool) {
		customTools.push(feedbackTool, buildSummaryTool());
	}
	if (hardAborted) {
		logPracticeCoverage();
		finalizeOutput();
		process.exit(1);
	}
	const { session, extensionsResult } = await createAgentSession({
		cwd: CWD,
		agentDir: AGENT_DIR,
		tools: [
			...PRACTICE_TOOLS,
			"report_observation",
			...(feedbackTool ? ["report_feedback", "report_summary"] : []),
		],
		customTools,
		sessionManager: SessionManager.create(CWD, `${CWD}/.sessions`),
		settingsManager,
		resourceLoader: loader,
		modelRuntime,
		model,
		thinkingLevel,
	});
	for (const error of extensionsResult.errors) {
		console.error(`[pi-runner] extension error: ${error.path}: ${error.error}`);
	}
	activeSession = session;
	const unsubscribe = subscribeSession(session);

	/** One prompt of the session under a fair share of what is left; true when it ran to its own end. */
	async function runTurn(label: string, text: string, remainingTurns: number): Promise<boolean> {
		const turnStart = Date.now();
		const remainingMs = measureEnd - turnStart;
		let share = turnShare(Math.max(0, remainingMs), remainingTurns);
		if (share.hardMs <= 0) {
			console.error(`[pi-runner] ${label}: no budget left — skipped`);
			hardAborted = true;
			return false;
		}
		if (!(await settleSession(session, label, share.hardMs))) {
			console.error(`[pi-runner] ${label}: session still busy — skipped`);
			hardAborted = true;
			return false;
		}
		// Waiting for the previous turn spends this turn's share, not the composition reserve.
		const hardMs = Math.max(0, share.hardMs - (Date.now() - turnStart));
		share = turnShare(hardMs, 1);
		if (share.hardMs <= 0) {
			hardAborted = true;
			return false;
		}
		const trace = openTurnTrace(label);
		const softTimer = setTimeout(() => {
			softTimeoutFired = true;
			trace.softTimeoutFired = true;
			console.error(`[pi-runner] ${label}: share nearly spent — nudging to record`);
			void steer(
				session,
				`This turn has used most of its share of the review budget. Stop exploring and record what the inspected evidence supports for the listed practices, in one report_observation call. ${PERSIST_DISCIPLINE}`,
			);
		}, share.softMs);
		let aborting: Promise<void> | undefined;
		const hard = scheduleDeadline(share.hardMs, () => {
			hardAborted = true;
			console.error(`[pi-runner] ${label}: share exhausted — aborting this turn`);
			aborting = abortSession(session);
		});
		try {
			await Promise.race([session.prompt(text), hard.elapsed]);
			return !hard.expired();
		} catch (error) {
			console.error(`[pi-runner] ${label} failed: ${errorText(error)}`);
			return false;
		} finally {
			clearTimeout(softTimer);
			clearTimeout(hard.timer);
			if (aborting) {
				await settleSession(session, label, ABORT_SETTLE_MS);
			}
			trace.hardAborted = hard.expired();
			closeTurnTrace(trace);
		}
	}

	try {
		for (const [index, turn] of turns.entries()) {
			currentTurnSlugs = turn.slugs;
			await runTurn(
				`turn ${index + 1}/${turns.length} (${turn.id})`,
				measureTurnText(turn, index + 1, turns.length, brief),
				turns.length - index,
			);
		}
		const missing = missingSlugs(
			allSlugs,
			reviewState.observations.map((item) => item.practiceSlug),
		);
		const unfinished = missing.filter((slug) => !blockedPractices.has(slug));
		if (unfinished.length > 0 && Date.now() < measureEnd) {
			console.error(
				`[pi-runner] Finishing ${unfinished.length} practice(s): ${unfinished.join(", ")}`,
			);
			currentTurnSlugs = unfinished;
			await runTurn("finish", finishTurnText(unfinished), 1);
		}
	} finally {
		measuring = false;
	}

	const measureDurationMs = Date.now() - startMs;
	const measureUsage = extractUsageFromSession({}, streamUsage);
	accumulateUsage(null, measureUsage);
	runnerDebug.attempts.push({
		label: "measure",
		durationMs: measureDurationMs,
		softTimeoutFired,
		hardAborted,
		assistantMessages: measureUsage.assistantMessages,
		stopReasons: measureUsage.stopReasons,
		usage: measureUsage,
		resultFilePresent: hasPersistedReviewState(),
	});
	persistRunnerDebug();
	persistUsage();
	console.error(
		`[pi-runner] Measured: ${(measureDurationMs / 1000).toFixed(1)}s, calls=${measureUsage.totalCalls}, ` +
			`softTimeout=${softTimeoutFired}, hardAbort=${hardAborted}, observations=${reviewState.observations.length}`,
	);

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
		await stopSession(session);
		unsubscribe();
		if (providerFailures > 0) {
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
	persistComposedFeedback();
	maybeWriteResultFile();
	if (compositionRequest && feedbackTool && admittedObservations.length > 0) {
		const remainingMs = AGENT_BUDGET_MS - (Date.now() - PROCESS_START_MS);
		const compositionMs = Math.min(
			remainingMs,
			WINDOWS.compositionMs + Math.max(0, measureEnd - Date.now()),
		);
		if (compositionMs <= 0) {
			console.error("[pi-runner] Composition budget exhausted — preserving admitted observations");
		} else {
			const instructions = readFileSync(COMPOSER_PROMPT_PATH, "utf8");
			const deadline = scheduleDeadline(compositionMs, () => {
				console.error(
					`[pi-runner] Composition timeout — preserving observations and composed units so far`,
				);
				void abortSession(session);
			});
			const trace = openTurnTrace("composition");
			const softTimer = setTimeout(
				() => {
					trace.softTimeoutFired = true;
					console.error(`[pi-runner] composition: budget nearly spent — nudging to persist`);
					void steer(session, COMPOSITION_NUDGE);
				},
				Math.floor(compositionMs * 0.6),
			);
			try {
				if (!(await settleSession(session, "composition", compositionMs)) || deadline.expired()) {
					throw new Error("the session was still busy when the composition budget ran out");
				}
				const compositionText = `${instructions}\n\n${buildCompositionTurn(compositionRequest, admittedObservations, notReached)}`;
				await makeRoomFor(session, model, compositionText);
				if (deadline.expired()) {
					throw new Error("the composition budget ran out while the session was compacted");
				}
				await Promise.race([session.prompt(compositionText), deadline.elapsed]);
				// Ask once more for undecided negatives; otherwise they have no composed next step.
				const undecided = negativePractices(admittedObservations).filter(
					(slug) => !composedFeedback.units.some((unit) => unit.practiceSlug === slug),
				);
				if (!deadline.expired() && undecided.length > 0) {
					await askComposerOnceMore(session, undecided, deadline);
				}
			} catch (error) {
				console.error(`[pi-runner] composition failed: ${errorText(error)}`);
			} finally {
				clearTimeout(softTimer);
				clearTimeout(deadline.timer);
				trace.hardAborted = deadline.expired();
				closeTurnTrace(trace);
				persistComposedFeedback();
			}
			const combinedUsage = extractUsageFromSession(session.state, streamUsage);
			accumulateUsage(measureUsage, combinedUsage);
			persistUsage();
		}
	}
	await stopSession(session);
	unsubscribe();
	console.error(
		`[pi-runner] SUCCESS: result.json holds ${reviewState.observations.length} observation(s)`,
	);
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
			`[pi-runner] FATAL: ${errorText(error)}\n${error instanceof Error ? error.stack : ""}`,
		);
		finalizeOutputQuietly();
		// Preserve the retryable exit code for admission transport failures.
		process.exit(error instanceof AdmissionUnreachableError ? SERVER_UNREACHABLE_EXIT : 2);
	}
}

void run();
