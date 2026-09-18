import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve as resolvePath } from "node:path";

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
	SEVERITY_VALUES,
	SEVERITY_DESCRIPTIONS,
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
import { loadProviderConfig, registerHephaestusProvider } from "./pi-provider.ts";
import { buildBrief } from "./pi-review-brief.ts";
import { deriveWindows, missingSlugs, planTurns, turnShare } from "./pi-review-turns.ts";
import {
	ACTIONS,
	CHANNELS,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	type PreparedFeedbackTarget,
	notReachedNote,
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

// One review is one session. The server captured the inputs; pi-change.ts derived the change view;
// this runner puts the brief in front of the model, walks the practices as turns of that one session,
// verifies every quote it is handed, carries the recorded observations to admission, and composes the
// feedback in the same context that measured it. Files under work/ are the durable memory: what was
// recorded survives compaction and is repeated at the top of every turn.

function parseJson(text: string): unknown {
	return JSON.parse(text);
}

function jsonArray(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [];
}

function logValue(value: unknown): string {
	if (typeof value === "string") return value;
	if (value === undefined) return "undefined";
	return JSON.stringify(value);
}

/** SDK event arrays may be absent before completion or after abort. */
function listOrEmpty<T>(items: T[] | undefined): T[] {
	return items ?? [];
}

/**
 * A list a tool was handed, as the array or as that array serialised into a string. A smaller model
 * sends the string form often, and when the schema offered the array alone it re-sent the same string
 * against the SDK's "must be array" a hundred times, since a schema refusal never reaches the runner.
 * So the string is accepted; one whose only fault is a closing brace too many or too few — the
 * common slip in a long single line — is repaired where the repair is unambiguous, and everything it
 * contains is still checked field by field afterwards; one that does not parse is answered with the
 * parse error, never with an empty list a session would read as success.
 */
function submittedList(value: unknown): { items: unknown[] } | { error: string } {
	if (typeof value === "string") {
		try {
			return { items: jsonArray(parseJson(repairedClosers(value))) };
		} catch (error) {
			return {
				error: `the list arrived as a string that is not a JSON array (${errorText(error)}); send the array itself, not a string`,
			};
		}
	}
	return { items: jsonArray(value) };
}

/**
 * The text with its closing brackets balanced, as far as the shape of a JSON list decides it. A closer
 * that closes nothing, or closes the wrong thing, is dropped. A `}` at an item's depth followed by
 * `, "key":` was one brace too many on a nested object — the key belongs to the item — and is dropped.
 * A `, {` inside an object is an item starting where an object was never closed — an object's members
 * are keys, never a bare object — and the closers owed down to the list are inserted before the
 * comma. A closer still owed at the end is appended. Strings are stepped over, so a brace inside a
 * quote is never touched. Any other fault is left for the parser to name.
 */
function repairedClosers(text: string): string {
	const owed: string[] = [];
	let out = "";
	let changed = false;
	let inString = false;
	let escaped = false;
	for (let index = 0; index < text.length; index++) {
		const char = text[index] ?? "";
		if (inString) {
			out += char;
			if (escaped) escaped = false;
			else if (char === "\\") escaped = true;
			else if (char === '"') inString = false;
			continue;
		}
		if (char === '"') {
			inString = true;
		} else if (char === "{" || char === "[") {
			if (char === "{" && owed.at(-1) === "}" && owed.includes("]")) {
				const comma = out.trimEnd().length - 1;
				if (out[comma] === ",") {
					let closers = "";
					while (owed.at(-1) === "}") closers += owed.pop();
					out = out.slice(0, comma) + closers + out.slice(comma);
					changed = true;
				}
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
	if (inString || (!changed && owed.length === 0)) return text;
	return out.trimEnd() + owed.toReversed().join("");
}

/** Whether `, "key":` is what comes next — a member of an object, not the next item of a list. */
function keyFollows(text: string, from: number): boolean {
	let index = from;
	const skipSpace = () => {
		while (index < text.length && /\s/.test(text[index] ?? "")) index++;
	};
	skipSpace();
	if (text[index] !== ",") return false;
	index++;
	skipSpace();
	if (text[index] !== '"') return false;
	index++;
	while (index < text.length && text[index] !== '"') {
		if (text[index] === "\\") index++;
		index++;
	}
	index++;
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
if (!AGENT_DIR) {
	throw new Error("PI_CODING_AGENT_DIR env var is required");
}
/** Practices a turn carries at once; a catalog group larger than this is split. */
const PRACTICES_PER_TURN = process.env.PI_PRACTICE_BATCH_SIZE
	? Number(process.env.PI_PRACTICE_BATCH_SIZE)
	: 6;
/**
 * Refused submissions a practice is allowed before the runner stops accepting any for it. A model
 * that keeps re-sending the same refused quote spends the whole budget on one practice; after this
 * many, that practice is reported as not reached and the turn is asked to move on. Eight leaves room
 * for a session that corrects one thing at a time — a wrong source, a wrong path, a wrong line, a
 * wrong quote — and still bounds the one that never corrects anything.
 */
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

/**
 * Which evidence sources this invocation actually staged, and which source each staged artifact came out
 * of. Both answers gate every citation the model makes, so a manifest this runner cannot read is not a
 * degraded review — it is a review that cannot tell whether it had the bytes it is quoting.
 */
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
		if (source.state.availability === "AVAILABLE") availableSourceKinds.add(source.kind);
		for (const artifact of jsonArray(source.artifacts)) {
			if (isRecord(artifact) && typeof artifact.path === "string") {
				artifactSources.set(artifact.path, source.kind);
			}
		}
	}
	return { availableSourceKinds, artifactSources };
}

/**
 * The practices this run may report on. Read once here rather than re-read on each use: the fan-out and
 * the retry scaffold used to parse this file again on every call, and a second read is a second answer
 * waiting to happen.
 */
function readPracticeIndex(): PracticeIndexEntry[] {
	const index = parseJson(readFileSync(INPUT_PATHS.practiceIndex, "utf8"));
	if (!Array.isArray(index)) throw new Error("the task-declared practice index: expected an array");
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
/**
 * One turn of the session as the trace records it: what it cost, what it called, what it recorded and
 * what was refused and why. With this in the job's output, friction is visible on the server without a
 * transcript: a histogram of refusal reasons over a week says what to fix next.
 */
interface TurnTrace {
	label: string;
	durationMs: number;
	calls: number;
	toolCalls: Record<string, number>;
	stored: number;
	refused: number;
	refusalReasons: string[];
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
						maximum: 2147483647,
						description:
							"The 1-based line of the quoted text in the artifact; for a quote of the change, the [L<n>] coordinate of work/change/diff.patch.",
					},
					endLine: {
						type: "integer",
						minimum: 1,
						maximum: 2147483647,
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
			maxLength: 120,
			description:
				"A short phrase identifying the specific behavior whose presence and contextual desirability you assess, such as 'Debug print left in the request handler'. " +
				"Never a single word and never the practice's own name.",
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
	if (reviewState.observations.length === 0) return false;
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
		if (write() !== false) written.add(path);
	};
	persist(REVIEW_STATE_PATH, persistReviewState);
	persist(RESULT_PATH, maybeWriteResultFile);
	persist(USAGE_PATH, persistUsage);
	persist(RUNNER_DEBUG_PATH, persistRunnerDebug);
	if (practiceCoverageLedger !== null) persist(PRACTICE_COVERAGE_PATH, persistPracticeCoverage);
	if (compositionAdmitted) persist(FEEDBACK_PATH, persistComposedFeedback);
	for (const entry of readdirSync(OUTPUT)) {
		const path = `${OUTPUT}/${entry}`;
		if (!written.has(path)) rmSync(path, { recursive: true, force: true });
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
	const observation = normalizeObservation(
		rawObservation,
		ruledOutCellsOf(slugOf(rawObservation).toLowerCase().replace(/_/g, "-")),
	);
	const notes: string[] = [];
	if (!admittedPractices.has(observation.practiceSlug))
		throw new Error(`unknown practice '${observation.practiceSlug}'`);
	// The manifest says which source staged an artifact; a citation that names the artifact under
	// another source kind is read as the manifest reads it, and the correction is echoed back.
	for (const citation of observation.evidence.citations) {
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
	// Admission refuses a review whose observations decide nothing and never touched the change; an
	// observation that decides nothing therefore shows it read the change — by a citation of it, or
	// by naming it among the sources its warrant consulted — unless one already recorded did.
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
		// A repository citation is read from the checkout — the working tree at HEAD, or the blob at the
		// named revision through its .git — so the session gets its correction here, by the same rule
		// admission applies. A quote of the change is read from the diff this container derived.
		const content =
			citation.sourceKind === "scm.repository.tree"
				? citation.revision === undefined
					? readCheckoutFile(citation.path)
					: readRevisionFile(citation.path, citation.revision)
				: citation.sourceKind === "scm.pull-request.diff"
					? readFileSync(`${CWD}/${CHANGE_ROOT}/diff.patch`, "utf8")
					: readFileSync(`${CWD}/${citation.artifactPath}`, "utf8");
		const resolved =
			content === null
				? {
						mismatch:
							citation.revision === undefined
								? "no such file in the checkout"
								: `no such file at revision ${citation.revision} in the checkout's history`,
					}
				: content === BINARY
					? {
							mismatch:
								"that file is binary and has no lines to quote; cite the change that adds it (work/change/files.json) or the text that embeds it",
						}
					: resolveOnEitherSide(citation, content);
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

/**
 * A change citation that names no side is tried on NEW first, then OLD, and records the side its
 * text was found on; a side that was named is the only one tried. Admission requires the side, so
 * one is recorded either way.
 */
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

/** The bytes as text, or BINARY: a quote of a binary file cannot be verified, here or at admission. */
function asText(bytes: Buffer): string | typeof BINARY {
	return bytes.subarray(0, 8000).includes(0) ? BINARY : bytes.toString("utf8");
}

/** The blob at a repository-relative path in a revision of the checkout's history, or null. */
function readRevisionFile(path: string, revision: string): string | typeof BINARY | null {
	if (path.startsWith("/") || path.split("/").includes("..")) return null;
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
	const file = resolvePath(INPUT_PATHS.repositoryRoot, path);
	if (!file.startsWith(`${INPUT_PATHS.repositoryRoot}/`)) return null;
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

/** Calls of the recording tool one turn may make before recording anything; past it, the turn ends. */
const MAX_RECORDING_ATTEMPTS_PER_TURN = 24;

/** The session the turn events belong to, once it exists; what the loop bound aborts. */
let activeSession: AgentSession | null = null;

/** Refused submissions per practice; a practice past MAX_REFUSALS_PER_PRACTICE accepts no more. */
const refusals = new Map<string, number>();
const blockedPractices = new Set<string>();

function countRefusal(slug: string): void {
	const count = (refusals.get(slug) ?? 0) + 1;
	refusals.set(slug, count);
	if (count >= MAX_REFUSALS_PER_PRACTICE) blockedPractices.add(slug);
}

function slugOf(raw: unknown): string {
	return isRecord(raw) && typeof raw.practiceSlug === "string" ? raw.practiceSlug : "unknown";
}

/**
 * Records one submitted observation, or says why not. A refusal is counted against its practice, so
 * a session that keeps resubmitting the same refused quote runs out of tries, not out of budget.
 */
function record(raw: unknown): Recorded {
	const slug = slugOf(raw);
	if (blockedPractices.has(slug)) {
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
	if (reviewState.observationKeys.includes(key)) return { kind: "duplicate", slug };
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
		mkdirSync(dirname(NOTES_PATH), { recursive: true });
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
	if (reviewState.observations.length === 0) return "Nothing recorded yet.";
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
		currentTurn.refused++;
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
 * The schema as the session reads it, with the shape and the documentation and none of the rules.
 * The SDK validates a call against its schema before the tool runs, and that check is all or
 * nothing: one summary a few characters long in one of six observations and the whole call is
 * refused, the other five with it, after a generation that took minutes. The tool's contract is per
 * item — each observation is stored or refused with its own reason — so every rule the schema
 * stated is applied by normalizeAndValidateObservation, one observation at a time, and the
 * vocabulary an enum listed is kept in the description.
 */
function documentedShape(schema: unknown): unknown {
	if (Array.isArray(schema)) return schema.map(documentedShape);
	if (!isRecord(schema)) return schema;
	const out: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(schema)) {
		if (RULE_KEYWORDS.has(key)) continue;
		if (key === "enum" && Array.isArray(value)) {
			const listed = value.map((item) => (item === null ? "null" : String(item))).join(", ");
			const description = typeof schema.description === "string" ? schema.description : "";
			if (!description.includes(listed.split(", ")[0] ?? "")) {
				out.description = description ? `${description} One of: ${listed}.` : `One of: ${listed}.`;
			}
			continue;
		}
		if (key === "description" && typeof out.description === "string") continue;
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
		execute: (_toolCallId, params): Promise<AgentToolResult<ReportObservationDetails>> => {
			if (measurementClosed) {
				const text = "Measurement is closed; this turn may only compose feedback.";
				return Promise.resolve({
					content: [{ type: "text", text }],
					details: {
						inserted: 0,
						duplicates: 0,
						refused: 0,
						totalObservations: reviewState.observations.length,
						remainingPractices: [],
					},
				});
			}
			const submitted = submittedList(isRecord(params) ? params.observations : null);
			if ("error" in submitted) {
				// Counted against every practice of the turn, so a session that keeps sending the same
				// unparseable string runs out of tries like any other refusal.
				for (const slug of currentTurnSlugs) countRefusal(slug);
				logRefusal("(unparsed list)", submitted.error);
				return Promise.reject(new Error(`observations refused — ${submitted.error}`));
			}
			const outcomes = submitted.items.map(record);
			const stored = outcomes.filter((outcome) => outcome.kind === "stored");
			for (const outcome of outcomes) {
				if (outcome.kind === "refused") logRefusal(outcome.slug, outcome.reason);
			}
			if (currentTurn) currentTurn.stored += stored.length;
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
				if (outcome.kind === "stored")
					return `${head} stored${outcome.negative ? " (negative)" : ""}.${outcome.filled.map((line) => `\n   ${line}`).join("")}`;
				if (outcome.kind === "duplicate")
					return `${head} duplicate of one already stored; skipped.`;
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
			if (stored.length === 0 && details.duplicates === 0) return Promise.reject(new Error(text));
			return Promise.resolve({ content: [{ type: "text", text }], details });
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
	if (practiceCoverageLedger === null)
		throw new Error("practice coverage ledger is not initialized");
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

const PERSIST_DISCIPLINE =
	`Record the outcome the evidence supports, positive or negative or not applicable; there is no quota ` +
	`and no next step to write. Use tools only from this point onward; no planning prose.`;

/**
 * The review is finished and only the call carrying it home did not arrive. The server keeps the
 * work queued for another attempt when it sees this, so the measurement is repeated against a
 * server the next sandbox can reach rather than recorded as a review that produced nothing.
 */
const SERVER_UNREACHABLE_EXIT = 75;
/**
 * No practice was reached, and every model call this run made failed. That is the same kind of fact
 * as an unreachable server — nothing about the reviewed work was measured, and nothing was learned
 * that a second attempt would repeat — so the server queues the review again rather than recording it
 * as one that found nothing.
 */
const PROVIDER_UNREACHABLE_EXIT = 76;

function readTaskEnvelope(): TaskEnvelope {
	let raw: string;
	try {
		raw = readFileSync(TASK_PATH, "utf8");
	} catch (err) {
		console.error(`[pi-runner] Failed to read ${TASK_PATH}: ${errorText(err)}`);
		process.exit(ENVELOPE_MISMATCH_EXIT);
	}
	let parsed: unknown;
	try {
		parsed = parseJson(raw);
	} catch (err) {
		console.error(`[pi-runner] Failed to parse ${TASK_PATH}: ${errorText(err)}`);
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

function boundedLead(text: string): string | undefined {
	if (text.length <= LEAD_MAX_LENGTH) return text;
	const prefix = text.slice(0, LEAD_MAX_LENGTH + 1);
	let end = -1;
	for (const match of prefix.matchAll(/[.!?](?=\s|$)/g)) end = match.index;
	return end < 0 ? undefined : prefix.slice(0, end + 1).trim();
}

interface ReportSummaryDetails {
	stored: number;
}

function buildSummaryTool() {
	const refuse = (text: string): Promise<AgentToolResult<ReportSummaryDetails>> =>
		Promise.resolve({ content: [{ type: "text", text }], details: { stored: 0 } });

	return defineTool({
		name: "report_summary",
		label: "Report Summary",
		description:
			"Write how this review opens, in your own words. One call, no counts, no quotes. Skip it and " +
			"the review opens on its first finding.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["lead"],
			properties: {
				lead: {
					type: "string",
					minLength: 1,
					description: "One or two sentences orienting the reader in this change.",
				},
			},
		},
		execute: (_toolCallId, params): Promise<AgentToolResult<ReportSummaryDetails>> => {
			if (!compositionAdmitted) {
				return refuse(
					"Feedback composition opens only after Java admits the completed observations.",
				);
			}
			const trimmed = optionalString((params as { lead?: unknown }).lead)?.trim() ?? "";
			if (!trimmed) {
				return refuse("A lead needs one or two sentences; skip the call instead.");
			}
			const lead = boundedLead(trimmed);
			if (!lead) {
				return refuse(
					`A lead is at most ${LEAD_MAX_LENGTH} characters and needs a complete sentence within that limit.`,
				);
			}
			composedFeedback.lead = lead;
			persistComposedFeedback();
			return Promise.resolve({
				content: [{ type: "text", text: "Stored the opening line." }],
				details: { stored: 1 },
			});
		},
	});
}

/**
 * One unit of feedback as report_feedback receives it.
 *
 * <p>Written out rather than derived from the tool's JSON schema, because that schema is assembled at
 * runtime from the lanes and placements this particular run may write for. {@link validateUnit} is what
 * holds an incoming unit to this shape; nothing here is true until it has run.
 */
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
 * The observations after Java admitted them, which is a different shape from the ones measured: the
 * server assigns the id every feedback unit references and decides which citations can carry a note.
 *
 * <p>Kept apart from reviewState.observations rather than spliced over it. The composer tool captures
 * this array when the session is built and reads it when the model calls, long after admission has
 * filled it.
 */
const admittedObservations: AdmittedObservation[] = [];

function isPlacementKind(value: unknown): value is PlacementKind {
	return value === "DIFF" || value === "ARTIFACT";
}

function loadCompositionRequest(): CompositionRequest | null {
	try {
		if (!existsSync(COMPOSITION_REQUEST_PATH)) return null;
		const parsed = parseJson(readFileSync(COMPOSITION_REQUEST_PATH, "utf8"));
		if (!isRecord(parsed) || parsed.enabled !== true) return null;
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
		if (!CHANNELS.some((channel) => channels[channel].enabled && channels[channel].maxUnits > 0))
			return null;
		const inContextPlacementKinds = jsonArray(parsed.inContextPlacementKinds).filter(
			isPlacementKind,
		);
		if (channels.IN_CONTEXT.enabled && inContextPlacementKinds.length === 0) return null;
		return {
			channels,
			inContextPlacementKinds,
			minDistinctArtifacts: Math.max(2, Number(parsed.minDistinctArtifacts) || 2),
		};
	} catch (e) {
		console.error(`[pi-runner] composition request unreadable: ${errorText(e)}`);
		return null;
	}
}

function composablePracticeSlugs(): string[] {
	return [...admittedPractices].toSorted();
}

// Supersession is limited to unread thread keys present in this snapshot.
function stagedPreparedTargets(): PreparedFeedbackTarget[] {
	try {
		if (!existsSync(PREPARED_FEEDBACK_PATH)) return [];
		const prepared = parseJson(readFileSync(PREPARED_FEEDBACK_PATH, "utf8"));
		const entries = isRecord(prepared) ? jsonArray(prepared.prepared) : [];
		return entries.flatMap((entry) => {
			if (!isRecord(entry)) return [];
			const { threadKey, channel, practiceSlug } = entry;
			return typeof threadKey === "string" &&
				threadKey.trim() &&
				isChannel(channel) &&
				typeof practiceSlug === "string" &&
				practiceSlug.trim()
				? [{ threadKey, channel, practiceSlug }]
				: [];
		});
	} catch (e) {
		console.error(`[pi-runner] prepared feedback unreadable for composition: ${errorText(e)}`);
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
	writeFileSync(FEEDBACK_PATH, JSON.stringify(composedFeedback, null, 2));
}

// The composer references admitted observations; it cannot author verdicts, citations, or locations.
/** What report_feedback reports back: a refusal stores nothing and names no lane. */
interface ReportFeedbackDetails {
	stored: number;
	channel?: Channel;
	total?: number;
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
	const store = (value: unknown): string => {
		const unit = asFeedbackUnit(value);
		if (!unit)
			return "a feedback unit needs a channel, a practiceSlug, an action and basedOn; skipped.";
		const observationsById = new Map(
			observations.map((observation) => [observation.id, observation]),
		);
		const bounds = request.channels[unit.channel];
		if (!bounds.enabled) return `${unit.channel} is not a lane this run may write for; skipped.`;
		const key = `${unit.channel}:${unit.practiceSlug}`;
		if (seen.has(key))
			return `already have a ${unit.channel} unit for ${unit.practiceSlug}; skipped.`;
		const delivers = unit.action !== "WITHHOLD";
		if (delivers && usedPerChannel[unit.channel] >= bounds.maxUnits) {
			return `${unit.channel} cap of ${bounds.maxUnits} reached; skipped.`;
		}
		const rejection = validateUnit(unit, observationsById, preparedTargets, placementKinds);
		if (rejection) return rejection;
		// The pattern bar the composer prompt states, enforced from what the runner can count: a card
		// on the practice pages rests on the same practice being NEGATIVE on several separate pieces of
		// work, this one and the person's history. One occurrence is a note on the work, never a card.
		if (unit.channel === "IN_APP" && delivers) {
			const pieces = negativePiecesOfWork(unit.practiceSlug, observations);
			if (pieces < request.minDistinctArtifacts) {
				return (
					`IN_APP needs a pattern across at least ${request.minDistinctArtifacts} pieces of work, and ` +
					`${unit.practiceSlug} is NEGATIVE on ${pieces} (this work and the history); WITHHOLD it ` +
					`with BELOW_BAR, and keep the note on the work. Skipped.`
				);
			}
		}
		seen.add(key);
		if (delivers) usedPerChannel[unit.channel]++;
		composedFeedback.units.push(unit);
		return `stored a ${unit.channel} unit for ${unit.practiceSlug} (${unit.action}); ${usedPerChannel[unit.channel]}/${bounds.maxUnits} used on that lane.`;
	};

	return defineTool({
		name: "report_feedback",
		label: "Report Feedback",
		description:
			"Persist feedback units, one per channel and practice. Send every unit you have ready in one " +
			"call; each is stored or skipped on its own, with the reason. This is an intervention, not a " +
			"measurement: it takes no presence, assessment, severity or confidence, and no citation you typed yourself.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["units"],
			properties: {
				units: listSchema(
					{
						type: "object",
						additionalProperties: false,
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
								maxLength: 64,
								description:
									"Required for SUPERSEDE: the threadKey of an entry in the task-declared prepared-feedback file. " +
									"You may not name a key that is not in that file.",
							},
							withholdReason: { type: "string", enum: WITHHOLD_REASONS },
							title: {
								type: "string",
								maxLength: 255,
								description: "Names the issue in a few words. Never names the person.",
							},
							body: {
								type: "string",
								maxLength: 8000,
								description:
									"IN_APP only: explain the cross-artifact work pattern grounded in current observations; never quote a line or claim change over time from the pre-run history. Markdown, read verbatim.",
							},
							nextStep: {
								type: "string",
								maxLength: 2000,
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
										maxLength: 4000,
										description:
											"What you saw: factual, specific, the artifacts named. Your words about them, " +
											"not words for them - third person, never addressed to the developer as 'you', " +
											"and never a judgement of the person.",
									},
									capability: {
										type: "string",
										maxLength: 2000,
										description:
											"The understanding or self-check this conversation should support. State the capability, not a solution such as a required heading/template, and not a question, script, diagnosis, or fixed tactic.",
									},
									evidenceSummary: {
										type: "string",
										maxLength: 4000,
										description:
											"A concise account of the artifacts and observations that ground this note. " +
											"Summarise rather than inventing a quote; the original observation evidence is " +
											"staged separately for the mentor to inspect.",
									},
									inConversationSignal: {
										type: "string",
										maxLength: 2000,
										description:
											"A sign detectable before the conversation ends: a distinction, decision, question, or self-check the developer can articulate. Not a promise, future artifact, message text, or compliance target.",
									},
									alreadySaid: {
										type: "string",
										maxLength: 2000,
										description:
											"Optional. Where this has already been put to the developer and what has moved without help, from the feedback history. Omit it when the history has nothing on this practice: absent means nothing has been said yet, which the mentor reads differently from nothing to say.",
									},
								},
							},
							placement: {
								description:
									"IN_CONTEXT only. DIFF places a note at one verified observation citation. " +
									"ARTIFACT places it in the issue or change summary without inventing a line.",
								oneOf: placementKinds.map((kind) =>
									kind === "DIFF"
										? {
												type: "object",
												additionalProperties: false,
												required: ["kind", "observationId", "citationIndex"],
												properties: {
													kind: { type: "string", enum: ["DIFF"] },
													observationId: { type: "string", minLength: 1 },
													citationIndex: { type: "integer", minimum: 0 },
												},
											}
										: {
												type: "object",
												additionalProperties: false,
												required: ["kind"],
												properties: { kind: { type: "string", enum: ["ARTIFACT"] } },
											},
								),
							},
						},
					},
					"units",
				),
			},
		},
		// Nothing here waits on anything; Pi takes the result as a promise either way.
		execute: (_toolCallId, params): Promise<AgentToolResult<ReportFeedbackDetails>> => {
			if (!compositionAdmitted) {
				return Promise.resolve({
					content: [
						{
							type: "text",
							text: "Feedback composition opens only after Java admits the completed observations.",
						},
					],
					details: { stored: 0 },
				});
			}
			const before = composedFeedback.units.length;
			const submitted = submittedList(isRecord(params) ? params.units : null);
			if ("error" in submitted) {
				return Promise.reject(new Error(`units refused — ${submitted.error}`));
			}
			const lines = submitted.items.map((unit, index) => `#${index + 1}: ${store(unit)}`);
			const stored = composedFeedback.units.length - before;
			if (stored > 0) persistComposedFeedback();
			return Promise.resolve({
				content: [{ type: "text", text: lines.join("\n") }],
				details: { stored, total: composedFeedback.units.length },
			});
		},
	});
}

/**
 * Read a report_feedback argument as a unit, or null when it is missing one of the four fields the tool
 * schema marks required.
 *
 * <p>The schema is assembled per run — the lanes and placements it offers depend on what this run may
 * write for — so the SDK cannot hand back a typed argument and this is where the shape becomes true.
 * Only the four required fields decide admission; every optional field is carried through when it has
 * the type the schema asked for and dropped when it does not, leaving {@link validateUnit} to answer
 * with the message it already has for a unit that is missing one.
 */
function asFeedbackUnit(value: unknown): FeedbackUnit | null {
	if (!isRecord(value)) return null;
	const { channel, action, practiceSlug } = value;
	if (!isChannel(channel) || !isFeedbackAction(action) || typeof practiceSlug !== "string")
		return null;
	// minItems: 1 in the schema, and the reader drops a unit that cites nothing. Admitting one here
	// would tell the model it succeeded and then deliver nothing.
	const basedOn = jsonArray(value.basedOn).filter(
		(reference): reference is string => typeof reference === "string" && reference.length > 0,
	);
	if (basedOn.length === 0) return null;
	const notes = isRecord(value.notes)
		? {
				situation: optionalString(value.notes.situation),
				capability: optionalString(value.notes.capability),
				evidenceSummary: optionalString(value.notes.evidenceSummary),
				inConversationSignal: optionalString(value.notes.inConversationSignal),
				alreadySaid: optionalString(value.notes.alreadySaid),
			}
		: undefined;
	const placement = isRecord(value.placement)
		? {
				kind: optionalString(value.placement.kind) ?? "",
				observationId: optionalString(value.placement.observationId),
				citationIndex:
					typeof value.placement.citationIndex === "number"
						? value.placement.citationIndex
						: undefined,
			}
		: undefined;
	return {
		channel,
		practiceSlug,
		action,
		basedOn,
		supersedesThreadKey: optionalString(value.supersedesThreadKey),
		withholdReason: optionalString(value.withholdReason),
		title: optionalString(value.title),
		body: optionalString(value.body),
		nextStep: optionalString(value.nextStep),
		notes,
		placement,
	};
}

// Enforce snapshot-dependent constraints here for fast model correction; Java rechecks them.
/**
 * How many distinct pieces of work carry a NEGATIVE observation for a practice: this one, when a
 * current admitted observation says so, plus every other artifact the staged history records one on.
 */
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
	const historyPath = `${dirname(PREPARED_FEEDBACK_PATH)}/observations.json`;
	if (!existsSync(historyPath)) return pieces.size;
	const history = parseJson(readFileSync(historyPath, "utf8"));
	const entries =
		isRecord(history) && Array.isArray(history.observations) ? history.observations : [];
	for (const entry of entries) {
		if (!isRecord(entry) || entry.practiceSlug !== practiceSlug || entry.outcome !== "NEGATIVE")
			continue;
		const artifact = isRecord(entry.artifact) ? entry.artifact : {};
		const name = [artifact.url, artifact.number, artifact.title].find(
			(value) => typeof value === "string" || typeof value === "number",
		);
		if (name !== undefined)
			pieces.add(`${typeof artifact.kind === "string" ? artifact.kind : ""}:${name}`);
	}
	return pieces.size;
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
	if (evidenceError) return evidenceError;
	if (unit.action === "WITHHOLD") {
		if (!unit.withholdReason) return "WITHHOLD needs a withholdReason; skipped.";
		return null;
	}
	if (!unit.title?.trim()) return "A unit that is not a WITHHOLD needs a title; skipped.";
	if (unit.action === "SUPERSEDE") {
		if (!unit.supersedesThreadKey) return "SUPERSEDE needs a supersedesThreadKey; skipped.";
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
		if (unit.body || unit.nextStep) {
			return "IN_CHAT takes notes{situation,capability,evidenceSummary,inConversationSignal}, not body/nextStep - nothing on this lane is read out; skipped.";
		}
		const { notes } = unit;
		if (!notes?.situation?.trim()) return "IN_CHAT needs notes.situation; skipped.";
		if (!notes.capability?.trim()) return "IN_CHAT needs notes.capability; skipped.";
		if (!notes.evidenceSummary?.trim()) return "IN_CHAT needs notes.evidenceSummary; skipped.";
		if (!notes.inConversationSignal?.trim())
			return "IN_CHAT needs notes.inConversationSignal; skipped.";
		if (unit.placement) return "Only IN_CONTEXT units may carry a placement; skipped.";
		return null;
	}
	if (unit.notes) return "Only IN_CHAT units may carry a notes block; skipped.";
	if (!unit.nextStep?.trim()) return `${unit.channel} needs a nextStep; skipped.`;
	if (unit.channel === "IN_APP") {
		if (!unit.body?.trim()) return "IN_APP needs a body; skipped.";
		if (unit.placement) return "Only IN_CONTEXT units may carry a placement; skipped.";
		const normalizedBody = normalizeQuotedText(unit.body);
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
	if (unit.body) return "IN_CONTEXT takes title, placement, and nextStep only; skipped.";
	if (!unit.placement) return "IN_CONTEXT needs a DIFF or ARTIFACT placement; skipped.";
	const placement = unit.placement;
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
	if (placement.kind !== "DIFF") return "Unknown IN_CONTEXT placement kind; skipped.";
	const { observationId, citationIndex } = placement;
	if (!observationId || citationIndex === undefined || !Number.isInteger(citationIndex)) {
		return "DIFF placement needs observationId and citationIndex; skipped.";
	}
	const observation = observationsById.get(observationId);
	if (!observation) return `No observation '${observationId}' in this run; skipped.`;
	const citation = observation.citations[citationIndex];
	if (!citation)
		return `Observation '${observation.id}' has no citation ${citationIndex}; skipped.`;
	if (!citation.anchorable) {
		return `Citation ${citationIndex} of '${observation.id}' is not on this change's diff, so no note can be placed on it. Skipped.`;
	}
	return null;
}

function normalizeQuotedText(value: string): string {
	return value
		.normalize("NFKC")
		.replace(/[“”„‟]/g, '"')
		.replace(/[‘’‚‛]/g, "'")
		.replace(/\s+/g, " ")
		.trim()
		.toLowerCase();
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
	const admitted = JSON.stringify({ observations }, null, 1);
	const shown = (label: string, path: string, limit = 32_000): string => {
		if (!existsSync(path)) return "";
		const text = readFileSync(path, "utf8").trim();
		return text.length > limit
			? `### ${label} — too large to show here; read \`${path}\`\n`
			: `### ${label}\n\`\`\`json\n${text}\n\`\`\`\n`;
	};
	const historyRoot = dirname(PREPARED_FEEDBACK_PATH);
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
The review just finished. Its ${observations.length} admitted measurement(s) follow; ${anchorable} of them cite a line inside this change and can therefore carry a note on the work. They are also in \`work/composition/observations.json\`. The history follows them; both are shown whole.

\`\`\`json
${admitted}
\`\`\`

${context}
Lanes open this turn: ${lanes}.${closedNote}${placementNote}
A pattern claim needs at least ${request.minDistinctArtifacts} distinct pieces of work.

${coverageNote}Persist the units with report_feedback — every unit you have in one call — and call report_summary once for how the review opens. Writing nothing on a lane is a correct and common outcome; say in one line why, and stop.`;
}

/** A transport failure or a server that is not answering yet; a refusal is a decision, not a blip. */
class AdmissionUnreachable extends Error {}

/** The cause behind a wrapped error, in parentheses, or nothing when the error carries none. */
function causeText(error: unknown): string {
	return error instanceof Error && error.cause !== undefined ? ` (${errorText(error.cause)})` : "";
}

/**
 * What a blip costs. The proxy address is the app server's IP as it was when this container started,
 * so a server that came back somewhere else is not something waiting can reach — and a deployment
 * outlasts any wait that fits here anyway, its healthcheck allowing itself 90s to come up. These
 * attempts buy the failures that clear in place: a reset connection, a socket refused while the
 * process is coming back. An attempt that is merely slow is not repeated — it is the server working.
 */
const ADMISSION_ATTEMPTS = 4;

// Admission verifies every cited blob against the Git object, in a container the server starts, so
// one attempt may take minutes; the cap only bounds a server that went silent without closing.
const ADMISSION_ATTEMPT_TIMEOUT_MS = 10 * 60_000;

/**
 * What the server said about an answer it refused, as text a reader can act on. A body that cannot be
 * read is not worth failing over twice: the status alone still says a refusal happened.
 */
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
				...(process.env.TRACEPARENT ? { traceparent: process.env.TRACEPARENT } : {}),
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
		throw new AdmissionUnreachable(
			`observation admission could not be sent: ${errorText(error)}${causeText(error)}`,
		);
	}
	if (isRetryableStatus(response.status)) {
		throw new AdmissionUnreachable(`observation admission failed: HTTP ${response.status}`);
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
		throw new AdmissionUnreachable(
			`observation admission answer was cut short: ${errorText(error)}${causeText(error)}`,
		);
	}
}

async function admitObservations() {
	// The measurement is finished by the time this runs and exists nowhere else, so the one call that
	// carries it out of the sandbox is worth repeating when it did not arrive. Repeating it is safe:
	// the payload is frozen once the measurement is closed, and the server replays an identical one.
	const admitted: unknown = await retrying(
		postAdmission,
		(error) => error instanceof AdmissionUnreachable,
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

function openTurnTrace(label: string): TurnTrace {
	currentTurn = {
		label,
		durationMs: Date.now(),
		calls: 0,
		toolCalls: {},
		stored: 0,
		refused: 0,
		refusalReasons: [],
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
			`${trace.compactions ? `, compactions=${trace.compactions}` : ""}${trace.providerRetries ? `, providerRetries=${trace.providerRetries}` : ""}`,
	);
}

function scheduleDeadline(timeoutMs: number, onTimeout: () => void) {
	let release = () => {};
	// Racing `elapsed` says the wait is over, not why: the caller reads this to tell an answer from a
	// budget that ran out.
	const state = { expired: false };
	const elapsed = new Promise<void>((resolve) => {
		release = resolve;
	});
	const timer = setTimeout(() => {
		state.expired = true;
		onTimeout();
		release();
	}, timeoutMs);
	return { elapsed, timer, state };
}

/**
 * Timers request cancellation; the returned promise settles once the session is idle again. The SDK
 * refuses a prompt while the aborted call is still ending, so a turn that aborts waits for this
 * before the next turn is sent — otherwise every later turn fails as "already processing".
 */
function abortSession(session: AgentSession): Promise<void> {
	session.clearQueue();
	return session.abort().catch((error) => {
		console.error(`[pi-runner] session abort failed: ${errorText(error)}`);
	});
}

/** How long an aborted turn waits for the session to go idle before the next turn is sent. */
const ABORT_SETTLE_MS = 30_000;

function criteriaFileOf(slug: string): string | null {
	const file = `${dirname(INPUT_PATHS.practiceIndex)}/${slug}.md`;
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
	// The only instructions the session carries beyond its turns are the orchestrator the server
	// staged in the agent dir, handed over by name below; the SDK's own discovery is turned off
	// (pi-agent-sandbox.ts) and is not the channel a run's instructions arrive on. A run without
	// this file does not start.
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
	if (!registered || !providerConfig?.modelId) {
		throw new Error(
			"Hephaestus provider is not configured — pi-provider.json and proxy credentials are required",
		);
	}
	const model = modelRuntime.getModel("hephaestus", providerConfig.modelId);
	if (!model) throw new Error(`Hephaestus model was not registered: ${providerConfig.modelId}`);
	// The window is logged because everything downstream is measured against it: a workspace that
	// declares it wrong says so in the first lines of every transcript.
	console.error(
		`[pi-runner] registered hephaestus provider: apiProtocol=${providerConfig.apiProtocol} ` +
			`model=${providerConfig.modelId} contextWindow=${model.contextWindow}`,
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
	const subscribeSession = (trackedSession: AgentSession) => {
		return trackedSession.subscribe((event: AgentSessionEvent) => {
			const label = measuring ? "review" : "composer";
			if (event.type === "tool_execution_start") {
				console.error(`[pi-runner] ${label} tool: ${event.toolName}`);
				if (currentTurn) {
					const count = (currentTurn.toolCalls[event.toolName] ?? 0) + 1;
					currentTurn.toolCalls[event.toolName] = count;
					// A call the SDK refuses on its schema never reaches the tool, so the refusal cap cannot
					// end a session that re-sends it; this bound does. A turn that has recorded nothing after
					// this many attempts at the recording tool is spending its share on the same mistake.
					if (
						event.toolName === "report_observation" &&
						count >= MAX_RECORDING_ATTEMPTS_PER_TURN &&
						currentTurn.stored === 0 &&
						activeSession
					) {
						console.error(
							`[pi-runner] ${label}: ${count} report_observation calls without a record — aborting this turn`,
						);
						void abortSession(activeSession);
					}
				}
			}
			if (event.type === "compaction_end") {
				console.error(
					`[pi-runner] ${label} context compacted (${event.reason})${event.errorMessage ? `: ${event.errorMessage}` : ""}`,
				);
				if (currentTurn && !event.aborted) currentTurn.compactions++;
			}
			// The SDK rides out a retryable provider failure on its own. A run that took longer, or that
			// gave up after several of these, says so here rather than looking like an idle session.
			if (event.type === "auto_retry_start") {
				if (currentTurn) currentTurn.providerRetries++;
				console.error(
					`[pi-runner] ${label} provider call failed, retrying in ${event.delayMs}ms ` +
						`(attempt ${event.attempt}/${event.maxAttempts}): ${event.errorMessage}`,
				);
			}
			if (event.type === "auto_retry_end" && !event.success) {
				const finalError = event.finalError ?? "no error given";
				// A call the provider never answered, after the SDK spent its whole budget on it. Only the
				// measuring turns count: composition can fail without costing a practice, and what this
				// number decides is whether a review that recorded nothing was cut off or had nothing to record.
				if (measuring) providerFailures++;
				console.error(
					`[pi-runner] ${label} provider call failed for good after ${event.attempt} retries: ${finalError}`,
				);
			}
			if (event.type === "message_end" && event.message.role === "assistant") {
				addAssistantUsage(streamUsage, event.message);
				if (currentTurn) currentTurn.calls++;
				const stopReason = event.message.stopReason;
				const types = listOrEmpty(event.message.content).map((c) => c.type);
				const toolCalls = types.filter((t) => t === "toolCall").length;
				// A turn that ended in an error or ran out of room said why, and without it the
				// transcript shows a session that simply stopped answering — the one thing a reader
				// cannot diagnose afterwards.
				const rawStopReason = event.message.rawStopReason;
				// A call the provider answered with an error the SDK does not retry — no deployment for
				// the model, a rejected key, a gateway fault — is as much a provider failure as one it gave
				// up retrying: nothing about the work was read. Counted so a review that recorded nothing
				// is queued again rather than recorded as a review that found nothing.
				if (stopReason === "error" && measuring) providerFailures++;
				const failure =
					stopReason === "error" || stopReason === "length"
						? `, error=${event.message.errorMessage ?? "none given"}${rawStopReason ? `, rawStopReason=${rawStopReason}` : ""}`
						: "";
				console.error(
					`[pi-runner] ${label} assistant msg: stopReason=${stopReason}, toolCalls=${toolCalls}, ` +
						`types=[${types.join(",")}]${failure}`,
				);
			}
		});
	};

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
		// Setting up took the whole budget: a session would only be aborted at its first turn.
		console.error(`[pi-runner] FAILED: the review budget was spent before the session could start`);
		hardAborted = true;
	}

	const customTools = [buildReportObservationTool()];
	if (feedbackTool) customTools.push(feedbackTool, buildSummaryTool());
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
	});
	for (const error of extensionsResult.errors) {
		console.error(`[pi-runner] extension error: ${error.path}: ${error.error}`);
	}
	activeSession = session;
	const unsubscribe = subscribeSession(session);

	/** One prompt of the session under a fair share of what is left; true when it ran to its own end. */
	async function runTurn(label: string, text: string, remainingTurns: number): Promise<boolean> {
		const remainingMs = measureEnd - Date.now();
		const share = turnShare(Math.max(0, remainingMs), remainingTurns);
		if (share.hardMs <= 0) {
			console.error(`[pi-runner] ${label}: no budget left — skipped`);
			hardAborted = true;
			return false;
		}
		const trace = openTurnTrace(label);
		const softTimer = setTimeout(() => {
			softTimeoutFired = true;
			trace.softTimeoutFired = true;
			console.error(`[pi-runner] ${label}: share nearly spent — nudging to record`);
			session
				.steer(
					`This turn has used most of its share of the review budget. Stop exploring and record what the inspected evidence supports for the listed practices, in one report_observation call. ${PERSIST_DISCIPLINE}`,
				)
				.catch((err) => console.error(`[pi-runner] steer failed: ${errorText(err)}`));
		}, share.softMs);
		let aborting: Promise<void> | undefined;
		const hard = scheduleDeadline(share.hardMs, () => {
			hardAborted = true;
			console.error(`[pi-runner] ${label}: share exhausted — aborting this turn`);
			aborting = abortSession(session);
		});
		try {
			await Promise.race([session.prompt(text), hard.elapsed]);
			return !hard.state.expired;
		} catch (error) {
			console.error(`[pi-runner] ${label} failed: ${errorText(error)}`);
			return false;
		} finally {
			clearTimeout(softTimer);
			clearTimeout(hard.timer);
			if (aborting) {
				const settled = await Promise.race([
					aborting.then(() => true),
					new Promise<boolean>((resolve) => {
						setTimeout(() => resolve(false), ABORT_SETTLE_MS);
					}),
				]);
				if (!settled) {
					console.error(
						`[pi-runner] ${label}: the aborted call has not ended after ${ABORT_SETTLE_MS}ms`,
					);
				}
			}
			trace.hardAborted = hard.state.expired;
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
	let prevUsage: UsageReport | null = measureUsage;
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

	// Measurement is closed: what was recorded goes to the server for admission, and the same session
	// then composes from what came back, with every quote it read still in its context.
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
			try {
				await Promise.race([
					session.prompt(
						`${instructions}\n\n${buildCompositionTurn(compositionRequest, admittedObservations, notReached)}`,
					),
					deadline.elapsed,
				]);
			} catch (error) {
				console.error(`[pi-runner] composition failed: ${errorText(error)}`);
			} finally {
				clearTimeout(deadline.timer);
				trace.hardAborted = deadline.state.expired;
				closeTurnTrace(trace);
				persistComposedFeedback();
			}
			const combinedUsage = extractUsageFromSession(session.state, streamUsage);
			accumulateUsage(prevUsage, combinedUsage);
			prevUsage = combinedUsage;
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

main().catch((err: unknown) => {
	console.error(`[pi-runner] FATAL: ${errorText(err)}\n${err instanceof Error ? err.stack : ""}`);
	finalizeOutputQuietly();
	// A server this container never reached is not a defect in the review, and the attempts above have
	// already ridden out the failures that clear in place. Saying so distinctly is what lets the server
	// try the same work again instead of ending it.
	process.exit(err instanceof AdmissionUnreachable ? SERVER_UNREACHABLE_EXIT : 2);
});
