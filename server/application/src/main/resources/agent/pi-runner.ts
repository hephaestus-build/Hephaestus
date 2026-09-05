import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

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

import { errorText } from "./pi-error-text.ts";
import { buildGrepTool } from "./pi-grep-tool.ts";
import {
	citationMatchesArtifact,
	dedupeKeyForObservation,
	isRecord,
	type NormalizedObservation,
	normalizeObservation,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
} from "./pi-observation-normalize.ts";
import { PracticeCoverageLedger } from "./pi-practice-coverage.ts";
import { loadProviderConfig, registerHephaestusProvider } from "./pi-provider.ts";
import {
	buildReviewTree,
	mapConcurrent,
	missingPracticeSlugs,
	resolveReviewConcurrency,
} from "./pi-review-tree.ts";
import {
	ACTIONS,
	CHANNELS,
	type ComposedFeedbackEnvelope,
	type ComposedFeedbackUnit,
	type PreparedFeedbackTarget,
	undeliverableUnits,
	validateFeedbackEvidence,
} from "./pi-runner-composition.ts";
import { outputPath } from "./pi-runner-output.ts";
import { deriveTimeouts, deriveTurnTiming, deriveWorkstreamBudget } from "./pi-runner-timings.ts";
import {
	addAssistantUsage,
	extractUsageFromSession,
	newUsageLedger,
	type UsageReport,
} from "./pi-runner-usage.ts";
import { forkSessions } from "./pi-session-tree.ts";

// ── Reading what other processes wrote ───────────────────────────────────────
// Everything this runner is handed — the task envelope, the manifest, the practice index, the
// composition request, the staged history, the admission response — is JSON written by another process.
// None of it is typed by having been parsed, so each reader below states the shape it needs and checks
// for it, and the checks are the only reason the shapes are true.

/** JSON.parse with the return type it actually has. */
function parseJson(text: string): unknown {
	return JSON.parse(text);
}

/** The elements of a value the writer was supposed to send as an array, and none for anything else. */
function jsonArray(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [];
}

/**
 * A value nobody has checked, as log text. A string reads as itself; anything else is rendered as the
 * JSON it arrived as, because an object coerced to a string is "[object Object]" — and a line that
 * reports a rejected envelope that way has said nothing about the envelope.
 */
function logValue(value: unknown): string {
	if (typeof value === "string") return value;
	if (value === undefined) return "undefined";
	return JSON.stringify(value);
}

/**
 * An array field the SDK declares required, read as the empty list when it is not there.
 *
 * <p>These reads run over whatever a session has left behind — before a first turn, after an abort,
 * after a provider error — which is the state in which pi-runner-usage.ts records a message arriving
 * without the usage block its declaration promises. Neither a session with no transcript nor a
 * message with no parts is a reason to throw inside an event subscription, or to end a review that
 * has already measured something.
 */
function listOrEmpty<T>(items: T[] | undefined): T[] {
	return items ?? [];
}

/** One practice this run may report on, as inputs/practices/index.json describes it. */
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

/** inputs/feedback-composition.json, once its bounds have been clamped to what this run may do. */
interface CompositionRequest {
	channels: Record<Channel, ChannelBounds>;
	inContextPlacementKinds: PlacementKind[];
	minDistinctArtifacts: number;
}

/**
 * One citation of an observation Java has admitted.
 *
 * <p>Only `index` is named, because only `index` is checked. Everything else the server sends rides the
 * index signature: the runner copies those fields onward without reading them, and naming a type it
 * never verifies would be a claim about the server's payload that nothing here establishes.
 */
interface AdmittedCitation {
	index: number;
	[key: string]: unknown;
}

/**
 * An observation after Java has admitted it.
 *
 * <p>The server owns this shape and the runner re-emits it whole — work/composition/observations.json is
 * this object verbatim, because the composer's prompt reads fields the runner never looks at. So the
 * three fields the runner itself depends on are named and checked, and the index signature is what
 * carries the rest of the server's payload across untouched.
 */
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

// Overridable so a harness with no /workspace can drive the runner. Production never sets it.
const WORKSPACE_ROOT = "/workspace";
// The SDK's grep spawns ripgrep, which the sandbox forbids; the runner's own search tool takes its
// place in every session under the same name. "grep" stays listed: the SDK filters custom tools
// through this list too, and a listed custom definition replaces the built-in of that name.
const EVIDENCE_TOOLS = ["read", "grep"] as const;
const CWD = process.env.PI_RUNNER_CWD ?? WORKSPACE_ROOT;
const OUTPUT = `${CWD}/out`;
const RESULT_PATH = outputPath(OUTPUT, "result.json");
const REVIEW_STATE_PATH = outputPath(OUTPUT, "review-state.json");
const WATCHDOG_PATH = outputPath(OUTPUT, "watchdog-killed.json");
const USAGE_PATH = outputPath(OUTPUT, "usage.json");
const RUNNER_DEBUG_PATH = outputPath(OUTPUT, "runner-debug.json");
const PRACTICE_COVERAGE_PATH = outputPath(OUTPUT, "practice-coverage.json");
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
const {
	initialMs: INITIAL_TIMEOUT_MS,
	retryMs: RETRY_TIMEOUT_MS,
	compositionMs: COMPOSITION_TIMEOUT_MS,
} = deriveTimeouts(AGENT_BUDGET_MS, existsSync(`${CWD}/inputs/feedback-composition.json`));

setTimeout(() => {
	console.error(`[pi-runner] Watchdog: ${AGENT_BUDGET_MS + 30_000}ms elapsed, hard-exiting`);
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
	const manifest = parseJson(readFileSync(`${CWD}/inputs/manifest.json`, "utf8"));
	if (!isRecord(manifest) || !Array.isArray(manifest.sources)) {
		throw new Error("inputs/manifest.json: expected a sources array");
	}
	const availableSourceKinds = new Set<string>();
	const artifactSources = new Map<string, string>();
	for (const source of jsonArray(manifest.sources)) {
		if (!isRecord(source) || typeof source.kind !== "string" || !isRecord(source.state)) {
			throw new Error("inputs/manifest.json: every source needs a string kind and a state");
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
	const index = parseJson(readFileSync(`${CWD}/inputs/practices/index.json`, "utf8"));
	if (!Array.isArray(index)) throw new Error("inputs/practices/index.json: expected an array");
	return jsonArray(index).map((practice): PracticeIndexEntry => {
		if (!isRecord(practice) || typeof practice.slug !== "string") {
			throw new Error("inputs/practices/index.json: every practice needs a string slug");
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
const runnerDebug: { attempts: AttemptDebug[]; usageTotals: UsageTotals } = {
	attempts: [],
	usageTotals,
};
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
			description: "The concrete thing whose absence you are reporting.",
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
				required: ["sourceKind", "artifactPath", "path", "startLine", "quote"],
				properties: {
					sourceKind: { type: "string", enum: availableSourceKindValues },
					artifactPath: { type: "string", enum: stagedArtifactPaths },
					path: { type: "string", minLength: 1 },
					side: { type: "string", enum: ["OLD", "NEW"] },
					startLine: { type: "integer", minimum: 1 },
					endLine: { type: "integer", minimum: 1 },
					quote: { type: "string", minLength: 1 },
				},
			},
		},
	},
} as const;
const observationSchema = {
	type: "object",
	additionalProperties: false,
	required: ["practiceSlug", "summary", "outcome", "evidence", "evidenceRationale"],
	properties: {
		practiceSlug: { type: "string", minLength: 1 },
		summary: {
			type: "string",
			minLength: 1,
			maxLength: 120,
			description:
				"A short phrase naming what you observed, such as 'Debug print left in the request handler'. " +
				"Never a single word and never the practice's own name.",
		},
		outcome: {
			type: "string",
			enum: [
				"BEHAVIOR_PRESENT_GOOD",
				"BEHAVIOR_PRESENT_BAD_MINOR",
				"BEHAVIOR_PRESENT_BAD_MAJOR",
				"BEHAVIOR_PRESENT_BAD_CRITICAL",
				"BEHAVIOR_ABSENT_GOOD",
				"BEHAVIOR_ABSENT_BAD_MINOR",
				"BEHAVIOR_ABSENT_BAD_MAJOR",
				"BEHAVIOR_ABSENT_BAD_CRITICAL",
				"NO_REVIEW_OCCASION",
				"INSUFFICIENT_EVIDENCE",
			],
			description:
				"Choose a BEHAVIOR result whenever the practice has something to judge. An absent target behaviour is BEHAVIOR_ABSENT, never NO_REVIEW_OCCASION. NO_REVIEW_OCCASION means a prerequisite situation explicitly named by the practice did not occur.",
		},
		evidence: {
			...evidenceSchema,
			properties: {
				citations: evidenceSchema.properties.citations,
				exhaustiveSearch: searchSchema,
				exclusion: inapplicabilitySchema,
				missingEvidence: undecidabilitySchema,
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

const OUTCOME_VALUES = observationSchema.properties.outcome.enum;

/** Validates the model-authored wire shape before normalization. */
function isValidObservation(candidate: unknown): boolean {
	if (!isRecord(candidate)) return false;
	if (typeof candidate.practiceSlug !== "string" || !candidate.practiceSlug.trim()) return false;
	if (typeof candidate.summary !== "string" || !candidate.summary.trim()) return false;
	if (typeof candidate.outcome !== "string") return false;
	return OUTCOME_VALUES.some((outcome) => outcome === candidate.outcome);
}

interface ObservationsPayload {
	observations: unknown[];
}

function isValidObservationsPayload(payload: unknown): payload is ObservationsPayload {
	return (
		isRecord(payload) &&
		Array.isArray(payload.observations) &&
		payload.observations.length > 0 &&
		payload.observations.every(isValidObservation)
	);
}

const CONTROL_CHARACTER_ESCAPES = new Map([
	["\n", "\\n"],
	["\r", "\\r"],
	["\t", "\\t"],
]);
const LAST_CONTROL_CODE_POINT = 0x1f;
const DELETE_CODE_POINT = 0x7f;

function lenientJsonParse(text: string): unknown {
	try {
		return parseJson(text);
	} catch {
		// Retry after escaping raw control characters.
	}
	let cleaned = "";
	for (const character of text) {
		const codePoint = character.codePointAt(0) ?? 0;
		if (codePoint > LAST_CONTROL_CODE_POINT && codePoint !== DELETE_CODE_POINT) {
			cleaned += character;
			continue;
		}
		cleaned += CONTROL_CHARACTER_ESCAPES.get(character) ?? "";
	}
	return parseJson(cleaned);
}

function checkResultFile(): boolean {
	if (!existsSync(RESULT_PATH)) return false;
	try {
		const data = lenientJsonParse(readFileSync(RESULT_PATH, "utf8"));
		if (!isValidObservationsPayload(data)) {
			const observations = isRecord(data) ? jsonArray(data.observations) : [];
			const validCount = observations.filter(isValidObservation).length;
			console.error(
				`[pi-runner] result.json validation failed: observations=${observations.length}, valid=${validCount}`,
			);
			return false;
		}
		const normalized = data.observations.map(normalizeAndValidateObservation);
		writeFileSync(RESULT_PATH, JSON.stringify({ observations: normalized }, null, 2));
		return true;
	} catch (e) {
		console.error(`[pi-runner] result.json parse error: ${errorText(e)}`);
		return false;
	}
}

function maybeWriteResultFile(): boolean {
	if (reviewState.observations.length === 0) return false;
	writeFileSync(RESULT_PATH, JSON.stringify({ observations: reviewState.observations }, null, 2));
	return true;
}

function hasPersistedReviewState(): boolean {
	return reviewState.observations.length > 0;
}

function resolveResultFile(): "agent" | "tool-state" | null {
	if (checkResultFile()) return "agent";
	if (maybeWriteResultFile()) return "tool-state";
	return null;
}

function appendObservations(observations: unknown[]): {
	inserted: number;
	duplicates: number;
	negatives: number;
} {
	let inserted = 0;
	let duplicates = 0;
	let negatives = 0;
	const seen = new Set(reviewState.observationKeys);
	for (const rawObservation of observations) {
		const observation = normalizeAndValidateObservation(rawObservation);
		if (observation.assessment === "BAD") negatives++;
		const key = dedupeKeyForObservation(observation);
		if (seen.has(key)) {
			duplicates++;
			continue;
		}
		seen.add(key);
		reviewState.observationKeys.push(key);
		reviewState.observations.push(observation);
		inserted++;
	}
	persistReviewState();
	maybeWriteResultFile();
	persistPracticeCoverage();
	return { inserted, duplicates, negatives };
}

function normalizeAndValidateObservation(rawObservation: unknown): NormalizedObservation {
	const observation = normalizeObservation(rawObservation);
	if (!admittedPractices.has(observation.practiceSlug))
		throw new Error(`unknown practice '${observation.practiceSlug}'`);
	validateEvidenceSources(observation, availableSourceKinds, artifactSources);
	validateSearchScope(
		observation,
		practiceExhaustiveSources.get(observation.practiceSlug) ?? new Set(),
		availableSourceKinds,
	);
	validateInapplicabilityScope(observation, availableSourceKinds);
	for (const citation of observation.evidence.citations) {
		const content = readFileSync(`${CWD}/${citation.artifactPath}`, "utf8");
		if (!citationMatchesArtifact(citation, content)) {
			throw new Error(
				`citation does not match ${citation.path}:${citation.startLine}-${citation.endLine} ` +
					`(${citation.side ?? "text"}) in '${citation.artifactPath}'; copy the exact artifact text ` +
					`and, for a diff, use its [L<n>] coordinates and OLD/NEW side`,
			);
		}
	}
	return observation;
}

let measurementClosed = false;

interface ReportObservationDetails {
	inserted: number;
	duplicates?: number;
	totalObservations?: number;
	measurementClosed?: boolean;
	remainingPractices?: string[];
}

function buildReportObservationTool(allowedPracticeSlugs?: readonly string[]) {
	const allowed = allowedPracticeSlugs ? new Set(allowedPracticeSlugs) : null;
	const scopedObservationSchema = allowedPracticeSlugs
		? {
				...observationSchema,
				properties: {
					...observationSchema.properties,
					practiceSlug: { type: "string", enum: allowedPracticeSlugs },
				},
			}
		: observationSchema;
	return defineTool({
		name: "report_observation",
		label: "Report Observation",
		description:
			"Persist exactly one structured observation immediately so it survives retries and timeouts. Call this as soon as one observation is ready. Do not wait to batch observations.",
		parameters: scopedObservationSchema,
		execute: (_toolCallId, params): Promise<AgentToolResult<ReportObservationDetails>> => {
			if (measurementClosed) {
				return Promise.resolve({
					content: [
						{
							type: "text",
							text: "Measurement is closed; this turn may only compose feedback.",
						},
					],
					details: { inserted: 0, measurementClosed: true },
				});
			}
			const normalized = normalizeObservation(params);
			if (allowed && !allowed.has(normalized.practiceSlug)) {
				return Promise.resolve({
					content: [
						{
							type: "text",
							text: `This observer is scoped to ${[...allowed].join(", ")}, not '${normalized.practiceSlug}'.`,
						},
					],
					details: { inserted: 0, remainingPractices: [...allowed] },
				});
			}
			const { inserted, duplicates, negatives } = appendObservations([params]);
			const observed = new Set(reviewState.observations.map((item) => item.practiceSlug));
			const remainingPractices = allowed
				? [...allowed].filter((practiceSlug) => !observed.has(practiceSlug))
				: [];
			return Promise.resolve({
				content: [
					{
						type: "text",
						text: `Stored ${inserted} observation${duplicates > 0 ? ` (${duplicates} duplicate skipped)` : ""}. Negative observations in this call: ${negatives}. ${
							allowed
								? remainingPractices.length > 0
									? `Still required from this group: ${remainingPractices.join(", ")}.`
									: "This group is complete."
								: ""
						}`,
					},
				],
				details: {
					inserted,
					duplicates,
					totalObservations: reviewState.observations.length,
					remainingPractices,
				},
			});
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
	`There is no target count and no quota. ` +
	`Record what you saw; you are not asked for a next step, so do not write one. ` +
	`Only keep GOOD observations that add real review value. ` +
	`Do not add derivative low-signal observations when a stronger observation already covers the problem. ` +
	`Use tools only from this point onward. Do not write planning prose or plain-text commentary.`;

const ENVELOPE_MISMATCH_EXIT = 42;
const SUPPORTED_SCHEMA_VERSION = 1;
const SUPPORTED_KIND = "practice_review";
const TASK_PATH = `${CWD}/task.json`;

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
				`Server/image version drift — rebuild the agent-pi image or roll back the server.`,
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
	return {
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

const taskEnvelope = readTaskEnvelope();
const prompt = taskEnvelope.task.prompt.trim();
console.error(
	`[pi-runner] Task envelope loaded: kind=${taskEnvelope.task.kind}, ` +
		`jobId=${logValue(taskEnvelope.jobId)}, workspaceId=${logValue(taskEnvelope.workspaceId)}, ` +
		`repository=${logValue(taskEnvelope.task.repositoryFullName ?? "?")}, ` +
		`prNumber=${logValue(taskEnvelope.task.pullRequestNumber ?? "?")}`,
);

const COMPOSITION_REQUEST_PATH = `${CWD}/inputs/feedback-composition.json`;
const FEEDBACK_PATH = outputPath(OUTPUT, "feedback.json");
const COMPOSER_PROMPT_PATH = `${CWD}/feedback-composer.md`;
const PREPARED_FEEDBACK_PATH = `${CWD}/inputs/history/prepared.json`;
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
	id: string;
	practiceSlug: string;
	assessment: unknown;
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
		practiceSlug: observation.practiceSlug,
		assessment: observation.assessment,
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
	const refuse = (text: string): Promise<AgentToolResult<ReportFeedbackDetails>> =>
		Promise.resolve({ content: [{ type: "text", text }], details: { stored: 0 } });

	return defineTool({
		name: "report_feedback",
		label: "Report Feedback",
		description:
			"Persist exactly one feedback unit for one channel. Call it as soon as one unit is ready, one call " +
			"per unit. This is an intervention, not a measurement: it takes no presence, assessment, severity " +
			"or confidence, and no citation you typed yourself.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["unit"],
			properties: {
				unit: {
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
								"Required for SUPERSEDE: the threadKey of an entry in inputs/history/prepared.json. " +
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
			},
		},
		// Nothing here waits on anything; Pi takes the result as a promise either way.
		execute: (_toolCallId, params): Promise<AgentToolResult<ReportFeedbackDetails>> => {
			if (!compositionAdmitted) {
				return refuse(
					"Feedback composition opens only after Java admits the completed observations.",
				);
			}
			// The schema this tool advertises is assembled per run, so the SDK cannot type what comes back
			// from it. validateUnit() below is the check that makes the shape true; everything before it
			// reads only the two fields the lane bookkeeping needs, and rejects a unit that lacks them.
			const unit = asFeedbackUnit(params.unit);
			if (!unit) {
				return refuse(
					"A feedback unit needs a channel, a practiceSlug, an action and basedOn; skipped.",
				);
			}
			const observationsById = new Map(
				observations.map((observation) => [observation.id, observation]),
			);
			const bounds = request.channels[unit.channel];
			if (!bounds.enabled) {
				return refuse(`${unit.channel} is not a lane this run may write for; skipped.`);
			}
			const key = `${unit.channel}:${unit.practiceSlug}`;
			if (seen.has(key)) {
				return refuse(`Already have a ${unit.channel} unit for ${unit.practiceSlug}; skipped.`);
			}
			const delivers = unit.action !== "WITHHOLD";
			if (delivers && usedPerChannel[unit.channel] >= bounds.maxUnits) {
				return refuse(`${unit.channel} cap of ${bounds.maxUnits} reached; skipped.`);
			}
			const rejection = validateUnit(unit, observationsById, preparedTargets, placementKinds);
			if (rejection) {
				return refuse(rejection);
			}
			seen.add(key);
			if (delivers) usedPerChannel[unit.channel]++;
			composedFeedback.units.push(unit);
			persistComposedFeedback();
			return Promise.resolve({
				content: [
					{
						type: "text",
						text: `Stored a ${unit.channel} unit for ${unit.practiceSlug} (${unit.action}). ${usedPerChannel[unit.channel]}/${bounds.maxUnits} used on that lane.`,
					},
				],
				details: { stored: 1, channel: unit.channel, total: composedFeedback.units.length },
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
			return `No queued message has threadKey '${unit.supersedesThreadKey}'; it must come from inputs/history/prepared.json. Skipped.`;
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
	return (
		`## This turn\n` +
		`The review just finished. Its ${observations.length} measurement(s) are in ` +
		`\`work/composition/observations.json\`; ${anchorable} of them cite a line inside this change and can ` +
		`therefore carry a note on the work. Read that file first, then the history.\n\n` +
		`Lanes open this turn: ${lanes}.${closedNote}${placementNote}` +
		`\nA pattern claim needs at least ${request.minDistinctArtifacts} distinct pieces of work.\n\n` +
		`Persist each unit with report_feedback as soon as it is ready, and call report_summary once for ` +
		`how the review opens. Writing nothing on a lane is a correct and common outcome; say in one line ` +
		`why, and stop.`
	);
}

async function admitObservations() {
	const response = await fetch(`${process.env.LLM_PROXY_URL}/admit-observations`, {
		method: "POST",
		headers: {
			authorization: `Bearer ${process.env.LLM_PROXY_TOKEN}`,
			"content-type": "application/json",
			...(process.env.TRACEPARENT ? { traceparent: process.env.TRACEPARENT } : {}),
		},
		body: JSON.stringify({ schemaVersion: 1, observations: reviewState.observations }),
	});
	if (!response.ok) throw new Error(`observation admission failed: HTTP ${response.status}`);
	const admitted: unknown = await response.json();
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

// What the timeouts did to this run. Each is set from a timer callback and read after the turn it
// interrupted, so it belongs to the run rather than to any one step of it.
let softTimeoutFired = false;
let hardAborted = false;
let retryAborted = false;

function scheduleDeadline(timeoutMs: number, onTimeout: () => void) {
	let release = () => {};
	const elapsed = new Promise<void>((resolve) => {
		release = resolve;
	});
	const timer = setTimeout(() => {
		onTimeout();
		release();
	}, timeoutMs);
	return { elapsed, timer };
}

function scheduleTurnTimers(
	session: AgentSession,
	turnNumber: number,
	turnCount: number,
	softNudgeMs: number,
	hardLimitMs: number,
) {
	const state = { softTimedOut: false, hardTimedOut: false };
	const softTimer = setTimeout(() => {
		state.softTimedOut = true;
		console.error(
			`[pi-runner] turn ${turnNumber}/${turnCount} fair share nearly spent — nudging agent to report`,
		);
		const remainingTurns = turnCount - turnNumber;
		const steerMessage =
			`This turn is using its fair share of the review budget; ${remainingTurns} focused turn(s) still need time. ` +
			`Stop exploring and persist an observation for every practice in this turn now, one report_observation call per practice. ${PERSIST_DISCIPLINE}`;
		session
			.steer(steerMessage)
			.catch((err) => console.error(`[pi-runner] steer failed: ${errorText(err)}`));
	}, softNudgeMs);
	const hard = scheduleDeadline(hardLimitMs, () => {
		state.hardTimedOut = true;
		console.error(
			`[pi-runner] turn ${turnNumber}/${turnCount} exhausted its fair share — aborting this turn`,
		);
		session.dispose();
	});
	return { softTimer, hardTimer: hard.timer, hardDeadline: hard.elapsed, state };
}

async function main() {
	console.error(`[pi-runner] Embedded SDK mode`);
	console.error(
		`[pi-runner] Budget: total=${AGENT_BUDGET_MS}ms, initial=${INITIAL_TIMEOUT_MS}ms, retry=${RETRY_TIMEOUT_MS}ms`,
	);

	// Pi filters custom tools through this allowlist; omit filesystem mutation tools.
	// Untrusted on purpose: a trusted project makes the SDK look for `.agents/skills` in every ancestor
	// of the working directory, up to `/`, and the sandbox lets Node read only /workspace and the SDK
	// itself (PiRunnerProfile), so that walk dies on the first ancestor with a permission error before
	// the model is ever called. Nothing project-local is wanted here anyway: every resource this run
	// uses is injected by the runner, never discovered from the checkout.
	const settingsManager = SettingsManager.create(CWD, AGENT_DIR, { projectTrusted: false });
	const grepTool = buildGrepTool(CWD);
	// The only instructions a session carries beyond its prompt are the orchestrator the server staged
	// in the agent dir. The SDK's own context-file discovery would climb from the working directory
	// to `/` looking for AGENTS.md and die on the first ancestor the allowlist forbids, and it would
	// read the reviewed checkout's AGENTS.md as instructions rather than as evidence; so discovery is
	// off and the staged file is handed over by name. A run without it does not start.
	const orchestratorPath = `${AGENT_DIR}/AGENTS.md`;
	const orchestrator = readFileSync(orchestratorPath, "utf8");
	// One loader per session: a loader owns the extension runtime a session binds its tools into, and
	// disposing one session must not pull that runtime out from under another.
	const loadResources = async () => {
		const loader = new DefaultResourceLoader({
			cwd: CWD,
			agentDir: AGENT_DIR ?? getAgentDir(),
			settingsManager,
			noContextFiles: true,
			agentsFilesOverride: () => ({
				agentsFiles: [{ path: orchestratorPath, content: orchestrator }],
			}),
		});
		await loader.reload();
		return loader;
	};
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
	console.error(
		`[pi-runner] registered hephaestus provider: apiProtocol=${providerConfig.apiProtocol} model=${providerConfig.modelId}`,
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
	const events: { type: string; timestamp: number }[] = [];
	const streamUsage = newUsageLedger();
	const subscribeSession = (trackedSession: AgentSession, label: string) =>
		trackedSession.subscribe((event: AgentSessionEvent) => {
			if (event.type === "tool_execution_start") {
				console.error(`[pi-runner] ${label} tool: ${event.toolName}`);
			}
			if (event.type === "message_end" && event.message.role === "assistant") {
				addAssistantUsage(streamUsage, event.message);
				const stopReason = event.message.stopReason;
				const types = listOrEmpty(event.message.content).map((c) => c.type);
				const toolCalls = types.filter((t) => t === "toolCall").length;
				console.error(
					`[pi-runner] ${label} assistant msg: stopReason=${stopReason}, toolCalls=${toolCalls}, ` +
						`types=[${types.join(",")}]`,
				);
			}
			events.push({ type: `${label}:${event.type}`, timestamp: Date.now() });
		});

	async function completeWithAdmittedComposition() {
		measurementClosed = true;
		await admitObservations();
		const parsed = parseJson(readFileSync(RESULT_PATH, "utf8"));
		const result: Record<string, unknown> = isRecord(parsed) ? parsed : {};
		result.admissionDigest = admissionDigest;
		writeFileSync(RESULT_PATH, JSON.stringify(result));
		if (!compositionRequest || !feedbackTool || admittedObservations.length === 0) return;
		const { session: composerSession, extensionsResult: composerExtensions } =
			await createAgentSession({
				cwd: CWD,
				agentDir: AGENT_DIR,
				tools: ["read", "grep", "report_feedback", "report_summary"],
				customTools: [grepTool, feedbackTool, buildSummaryTool()],
				sessionManager: SessionManager.inMemory(),
				settingsManager,
				resourceLoader: await loadResources(),
				modelRuntime,
				model,
			});
		for (const error of composerExtensions.errors) {
			console.error(`[pi-runner] composer extension error: ${error.path}: ${error.error}`);
		}
		const unsubscribeComposer = subscribeSession(composerSession, "composer");
		const instructions = readFileSync(COMPOSER_PROMPT_PATH, "utf8");
		const compositionDeadline = scheduleDeadline(COMPOSITION_TIMEOUT_MS, () => {
			console.error(
				`[pi-runner] Composition timeout — preserving observations and composed units so far`,
			);
			composerSession.dispose();
		});
		try {
			await Promise.race([
				composerSession.prompt(
					`${instructions}\n\n${buildCompositionTurn(compositionRequest, admittedObservations)}`,
				),
				compositionDeadline.elapsed,
			]);
		} finally {
			clearTimeout(compositionDeadline.timer);
			unsubscribeComposer();
		}
		persistComposedFeedback();
		const combinedUsage = extractUsageFromSession(composerSession.state, streamUsage);
		composerSession.dispose();
		accumulateUsage(prevUsage, combinedUsage);
		prevUsage = combinedUsage;
		persistUsage();
	}

	let prevUsage: UsageReport | null = null;
	const activeSessions = new Set<AgentSession>();
	const hardAbort = new AbortController();

	const hardTimer = setTimeout(() => {
		hardAborted = true;
		hardAbort.abort();
		console.error(`[pi-runner] Hard timeout — aborting ${activeSessions.size} active session(s)`);
		for (const activeSession of activeSessions) {
			activeSession.dispose();
		}
	}, INITIAL_TIMEOUT_MS);

	console.error(`[pi-runner] Starting initial analysis`);
	const startMs = Date.now();

	const allSlugs = loadPracticeSlugs();
	practiceCoverageLedger = new PracticeCoverageLedger(PRACTICE_COVERAGE_PATH, allSlugs);
	const groupCapacity = process.env.PI_PRACTICE_BATCH_SIZE
		? Number(process.env.PI_PRACTICE_BATCH_SIZE)
		: 6;
	const tree = buildReviewTree(practiceIndex, groupCapacity);
	const concurrency = resolveReviewConcurrency(
		process.env.PI_REVIEW_CONCURRENCY,
		tree.practiceCount,
	);
	const sessionDir = `${CWD}/.sessions`;
	const reviewDeadline = startMs + INITIAL_TIMEOUT_MS;
	console.error(
		`[pi-runner] Review tree: ${tree.practiceCount} practices, ${tree.groups.length} evidence group(s), concurrency=${concurrency}`,
	);

	const groupSessionFiles = new Map<string, string>();
	const groupSeedFiles = new Map<string, string>();
	if (!hardAborted) {
		const manager = SessionManager.create(CWD, sessionDir);
		const { session: reconSession } = await createAgentSession({
			cwd: CWD,
			agentDir: AGENT_DIR,
			tools: [...EVIDENCE_TOOLS],
			customTools: [grepTool],
			sessionManager: manager,
			settingsManager,
			resourceLoader: await loadResources(),
			modelRuntime,
			model,
		});
		const unsubscribeRecon = subscribeSession(reconSession, "recon:shared");
		activeSessions.add(reconSession);
		const reconBudgetMs = Math.min(
			180_000,
			Math.max(45_000, Math.floor(INITIAL_TIMEOUT_MS * 0.05)),
		);
		const reconDeadline = scheduleDeadline(reconBudgetMs, () => reconSession.dispose());
		try {
			const groupScope = tree.groups
				.map((group) => `${group.id} [${group.practiceSlugs.join(", ")}]`)
				.join("; ");
			await Promise.race([
				reconSession.prompt(
					`Build one factual reconnaissance map for this review. Read the manifest and the artifact summary first, then inspect only enough shared evidence to identify changed surfaces, review activity, linked work, tests, and likely code paths. Record exact artifact paths and diff coordinates. Do not evaluate a practice or claim GOOD/BAD. The following group sessions will continue from this checkpoint: ${groupScope}`,
				),
				reconDeadline.elapsed,
			]);
			const checkpointEntryId = reconSession.sessionManager.getLeafId();
			const seedSessionFile = reconSession.sessionManager.getSessionFile();
			if (!checkpointEntryId || !seedSessionFile)
				throw new Error("shared reconnaissance produced no persistent checkpoint");
			for (const fork of forkSessions({
				seedSessionFile,
				checkpointEntryId,
				keys: tree.groups.map((group) => group.id),
				sessionDir,
			})) {
				groupSeedFiles.set(fork.key, fork.sessionFile);
			}
		} catch (error) {
			console.error(`[pi-runner] shared reconnaissance failed: ${errorText(error)}`);
		} finally {
			clearTimeout(reconDeadline.timer);
			activeSessions.delete(reconSession);
			unsubscribeRecon();
			reconSession.dispose();
		}
	}

	try {
		let remainingGroups = tree.groups.length;
		await mapConcurrent(
			tree.groups,
			concurrency,
			async (group, index) => {
				const seedFile = groupSeedFiles.get(group.id);
				const manager = seedFile
					? SessionManager.open(seedFile, sessionDir)
					: SessionManager.create(CWD, sessionDir);
				const scopedTool = buildReportObservationTool(group.practiceSlugs);
				const { session: observerSession } = await createAgentSession({
					cwd: CWD,
					agentDir: AGENT_DIR,
					tools: [...EVIDENCE_TOOLS, "report_observation"],
					customTools: [grepTool, scopedTool],
					sessionManager: manager,
					settingsManager,
					resourceLoader: await loadResources(),
					modelRuntime,
					model,
				});
				const unsubscribeObserver = subscribeSession(observerSession, `observer:${group.id}`);
				activeSessions.add(observerSession);
				const remainingMs = Math.max(1, reviewDeadline - Date.now());
				const activeSlots = Math.min(concurrency, remainingGroups);
				const groupBudgetMs = deriveWorkstreamBudget(remainingMs, activeSlots, remainingGroups);
				const timing = deriveTurnTiming(groupBudgetMs, 1);
				const timers = scheduleTurnTimers(
					observerSession,
					index + 1,
					tree.groups.length,
					timing.softNudgeMs,
					timing.fairShareMs,
				);
				try {
					await Promise.race([
						observerSession.prompt(
							`${prompt}\n\n## Practice group: ${group.id}\nEvaluate exactly these practices: ${group.practiceSlugs.join(", ")}. ` +
								`Read their files under inputs/practices/, then start with the cheapest decisive practice. Persist each ` +
								`observation as soon as it is supported; do not finish a group-wide evidence map first. Reuse evidence ` +
								`already gathered when investigating the remaining practices. Persist at least one disposition for ` +
								`every listed practice, plus any distinct material problems a practice exposes. Use ` +
								`NO_REVIEW_OCCASION only after complete evidence proves the practice's explicit prerequisite did not ` +
								`occur; missing evidence is not an occasion that failed to happen. Do not skip a practice because its ` +
								`behavior is absent.`,
						),
						timers.hardDeadline,
					]);
				} catch (error) {
					console.error(`[pi-runner] observer ${group.id} failed: ${errorText(error)}`);
				} finally {
					const sessionFile = observerSession.sessionManager.getSessionFile();
					if (sessionFile) groupSessionFiles.set(group.id, sessionFile);
					softTimeoutFired ||= timers.state.softTimedOut;
					clearTimeout(timers.softTimer);
					clearTimeout(timers.hardTimer);
					activeSessions.delete(observerSession);
					unsubscribeObserver();
					observerSession.dispose();
					remainingGroups--;
				}
			},
			hardAbort.signal,
		);
	} finally {
		clearTimeout(hardTimer);
	}

	const initialDurationMs = Date.now() - startMs;
	const initialUsage = extractUsageFromSession({}, streamUsage);
	accumulateUsage(null, initialUsage);
	prevUsage = initialUsage;

	runnerDebug.attempts.push({
		label: "initial",
		durationMs: initialDurationMs,
		softTimeoutFired,
		hardAborted,
		assistantMessages: initialUsage.assistantMessages,
		stopReasons: initialUsage.stopReasons,
		usage: initialUsage,
		resultFilePresent: existsSync(RESULT_PATH),
	});
	persistRunnerDebug();
	persistUsage();

	console.error(
		`[pi-runner] Initial: ${(initialDurationMs / 1000).toFixed(1)}s, calls=${initialUsage.totalCalls}, softTimeout=${softTimeoutFired}, hardAbort=${hardAborted}, resultFile=${existsSync(RESULT_PATH)}, reviewState=${hasPersistedReviewState()}`,
	);

	const resultFileSource = resolveResultFile();
	const missingAfterInitial = missingPracticeSlugs(
		allSlugs,
		reviewState.observations.map((item) => item.practiceSlug),
	);
	if (missingAfterInitial.length === 0) logPracticeCoverage();

	if (resultFileSource === "agent" && missingAfterInitial.length === 0) {
		console.error(`[pi-runner] SUCCESS: result.json valid after initial run`);
		await completeWithAdmittedComposition();
		process.exit(0);
	}
	if (resultFileSource === "tool-state" && missingAfterInitial.length === 0) {
		console.error(
			`[pi-runner] SUCCESS: composed result.json from persisted tool state after initial run`,
		);
		await completeWithAdmittedComposition();
		process.exit(0);
	}

	console.error(
		`[pi-runner] Retrying ${missingAfterInitial.length} missing practice observer(s): ${missingAfterInitial.join(", ")}`,
	);

	const retryAbort = new AbortController();
	const retryTimer = setTimeout(() => {
		retryAborted = true;
		retryAbort.abort();
		console.error(`[pi-runner] Retry hard timeout — aborting`);
		for (const activeSession of activeSessions) {
			activeSession.dispose();
		}
	}, RETRY_TIMEOUT_MS);

	const retryStartMs = Date.now();

	try {
		const missingSet = new Set(missingAfterInitial);
		const retryGroups = tree.groups
			.map((group) => ({
				...group,
				practiceSlugs: group.practiceSlugs.filter((slug) => missingSet.has(slug)),
			}))
			.filter((group) => group.practiceSlugs.length > 0);
		let retriesRemaining = retryGroups.length;
		await mapConcurrent(
			retryGroups,
			concurrency,
			async (group) => {
				const retryTool = buildReportObservationTool(group.practiceSlugs);
				const priorSessionFile = groupSessionFiles.get(group.id);
				const { session: retrySession } = await createAgentSession({
					cwd: CWD,
					agentDir: AGENT_DIR,
					tools: [...EVIDENCE_TOOLS, "report_observation"],
					customTools: [grepTool, retryTool],
					sessionManager: priorSessionFile
						? SessionManager.open(priorSessionFile, sessionDir)
						: SessionManager.inMemory(),
					settingsManager,
					resourceLoader: await loadResources(),
					modelRuntime,
					model,
				});
				const unsubscribeRetry = subscribeSession(retrySession, `retry:${group.id}`);
				activeSessions.add(retrySession);
				const activeSlots = Math.min(concurrency, retriesRemaining);
				const retryBudgetMs = deriveWorkstreamBudget(
					RETRY_TIMEOUT_MS,
					activeSlots,
					retriesRemaining,
				);
				const retryDeadline = scheduleDeadline(retryBudgetMs, () => {
					retrySession.dispose();
				});
				try {
					await Promise.race([
						retrySession.prompt(
							`${prompt}\n\n## Recovery practice group\nThe earlier group did not persist: ${group.practiceSlugs.join(", ")}. ` +
								`Continue from its evidence and tool feedback. Persist one best-justified outcome for each missing ` +
								`practice now; read more only to resolve a specific validation failure or genuinely open evidence ` +
								`question. Evaluate no other practice. ${PERSIST_DISCIPLINE}`,
						),
						retryDeadline.elapsed,
					]);
				} catch (error) {
					console.error(`[pi-runner] retry ${group.id} failed: ${errorText(error)}`);
				} finally {
					clearTimeout(retryDeadline.timer);
					activeSessions.delete(retrySession);
					unsubscribeRetry();
					retrySession.dispose();
					retriesRemaining--;
				}
			},
			retryAbort.signal,
		);
	} finally {
		clearTimeout(retryTimer);
	}

	const retryDurationMs = Date.now() - retryStartMs;
	const retryUsage = extractUsageFromSession({}, streamUsage);
	accumulateUsage(prevUsage, retryUsage);
	prevUsage = retryUsage;

	runnerDebug.attempts.push({
		label: "retry",
		durationMs: retryDurationMs,
		hardAborted: retryAborted,
		assistantMessages: retryUsage.assistantMessages,
		stopReasons: retryUsage.stopReasons,
		usage: retryUsage,
		resultFilePresent: existsSync(RESULT_PATH),
	});
	persistRunnerDebug();
	persistUsage();

	console.error(
		`[pi-runner] Retry: ${(retryDurationMs / 1000).toFixed(1)}s, resultFile=${existsSync(RESULT_PATH)}, reviewState=${hasPersistedReviewState()}`,
	);
	const missingAfterRetry = missingPracticeSlugs(
		allSlugs,
		reviewState.observations.map((item) => item.practiceSlug),
	);
	logPracticeCoverage();
	if (missingAfterRetry.length > 0) {
		console.error(
			`[pi-runner] FAILED: ${missingAfterRetry.length} practice observer(s) still missing after retry: ${missingAfterRetry.join(", ")}`,
		);
		process.exit(1);
	}

	if (checkResultFile()) {
		console.error(`[pi-runner] SUCCESS: result.json valid after retry`);
		await completeWithAdmittedComposition();
		process.exit(0);
	}

	// As in resolveResultFile(): what maybeWriteResultFile() writes is already normalised and validated,
	// and re-reading it through checkResultFile() could only reject a measurement already admitted.
	if (maybeWriteResultFile()) {
		console.error(
			`[pi-runner] SUCCESS: composed result.json from persisted tool state after retry`,
		);
		await completeWithAdmittedComposition();
		process.exit(0);
	}

	console.error(
		`[pi-runner] FAILED: no complete persisted review output after initial attempt + recovery retry`,
	);
	process.exit(1);
}

process.on("uncaughtException", (err) => {
	console.error(`[pi-runner] FATAL: ${errorText(err)}`);
	persistRunnerDebug();
	persistUsage();
	process.exit(2);
});

process.on("unhandledRejection", (reason) => {
	console.error(`[pi-runner] UNHANDLED REJECTION: ${errorText(reason)}`);
	persistRunnerDebug();
	persistUsage();
	process.exit(2);
});

main().catch((err: unknown) => {
	console.error(`[pi-runner] FATAL: ${errorText(err)}\n${err instanceof Error ? err.stack : ""}`);
	persistRunnerDebug();
	persistUsage();
	process.exit(2);
});
