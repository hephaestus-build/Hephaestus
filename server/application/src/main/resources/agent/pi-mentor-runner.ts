// Long-lived JSON-RPC runner; frame definitions live in pi-mentor-protocol.ts.
// Java sends a thread's saved session with open_thread; restored as .sessions/<threadId>.jsonl, SessionManager
// resumes it without replay RPCs.

import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { setTimeout as delay } from "node:timers/promises";

import type {
	AgentSessionEvent,
	AgentToolResult,
	CompactionResult,
	CreateAgentSessionRuntimeFactory,
} from "@earendil-works/pi-coding-agent";
import type * as PiSdkModule from "@earendil-works/pi-coding-agent";

import {
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { errorText } from "./pi-error-text.ts";
import {
	MENTOR_ERROR_CODES as ERR,
	JSONRPC_VERSION,
	FETCH_CONTEXT_ALLOWED,
	isFetchContextKey,
	type JsonRpcId,
	MENTOR_PROTOCOL_VERSION,
	MENTOR_TOOL_NAMES,
	type MentorErrorCode,
	type MentorMethod,
	type MentorOutboundFrame,
	type MentorResult,
	type MentorWireEvent,
	type ServerCallbackRequest,
} from "./pi-mentor-protocol.ts";
import { loadProviderConfig, reasoningSetting, registerHephaestusProvider } from "./pi-provider.ts";
import { hasText } from "./pi-text.ts";

/** The Pi SDK module, resolved from `<workspace>/node_modules` by bare specifier at runtime. */
type PiSdk = typeof PiSdkModule;

// Pi SDK is loaded lazily so the protocol layer (framing, JSON-RPC dispatch, fetch_context
// callback plumbing) can be exercised in test environments without an LLM proxy. Set
// `MENTOR_RUNNER_PROTOCOL_ONLY=1` to swap the SDK-backed runtime for `createStubRuntime`, which
// satisfies the same `MentorRuntime` contract and emits deterministic events sufficient for
// protocol tests. Production builds never set this.
const PROTOCOL_ONLY = process.env.MENTOR_RUNNER_PROTOCOL_ONLY === "1";

// PROTOCOL_ONLY swaps the Pi SDK for a deterministic stub. Catching a config drift that flips
// this on in prod is critical — every prompt would return `stub: <text>` instead of an LLM
// response, with no other surface signal. Log loudly on startup; the runner has no signed
// flag to refuse to run, but ops should see this in the container's first stderr line.
if (PROTOCOL_ONLY) {
	process.stderr.write(
		"[pi-mentor-runner] WARN MENTOR_RUNNER_PROTOCOL_ONLY=1 — Pi SDK disabled, all prompts will be stubbed. " +
			"This must never be set in production. Unset MENTOR_RUNNER_PROTOCOL_ONLY to use the real Pi runtime.\n",
	);
}

// PROTOCOL_ONLY tests / CI smoke-test invocations run the runner as a plain Node process where
// /workspace is unwritable; MENTOR_RUNNER_* env overrides let callers point at a tmpdir without
// forking the runner code. The workspace literals below are pinned by `SandboxLayoutSyncTest` —
// keep them quoted strings, not template expressions, so the grep stays exact.
const WORKSPACE_ROOT = "/workspace";
// SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH
const MENTOR_SYSTEM_PROMPT_PATH = "agent/mentor/system.md";
// SandboxLayout.PI_AGENT_DIR
const PI_AGENT_DIR = "/workspace/.pi";
const CWD = process.env.MENTOR_RUNNER_CWD ?? WORKSPACE_ROOT;
const SESSIONS_DIR = process.env.MENTOR_RUNNER_SESSIONS_DIR ?? `${WORKSPACE_ROOT}/.sessions`;
const SYSTEM_PROMPT_PATH =
	process.env.MENTOR_RUNNER_SYSTEM_PROMPT_PATH ?? `${WORKSPACE_ROOT}/${MENTOR_SYSTEM_PROMPT_PATH}`;
// PiRuntimeFactory always sets PI_CODING_AGENT_DIR in the sandbox, so this is populated in
// production. Only local runs leave it unset and fall back to the SDK's own default.
const AGENT_DIR_OVERRIDE = process.env.PI_CODING_AGENT_DIR ?? null;
const PROTOCOL_VERSION = MENTOR_PROTOCOL_VERSION;

// SandboxLayout.EXIT_ENVELOPE_MISMATCH — exit code on protocol-version / image / config drift
// that makes this runner incompatible with the calling Java side. Java's launcher distinguishes
// this exit from a generic crash so deploy regressions surface as a structured failure.
const ENVELOPE_MISMATCH_EXIT = 42;

// Startup envelope check: if the Java launcher pins an expected protocol version via env,
// fail-fast with a structured exit so the deploy doesn't silently downgrade to a stub.
{
	const expectedRaw = process.env.MENTOR_RUNNER_EXPECTED_PROTOCOL_VERSION;
	if (expectedRaw !== undefined && expectedRaw !== "") {
		const expected = Number(expectedRaw);
		if (!Number.isFinite(expected) || expected !== PROTOCOL_VERSION) {
			process.stderr.write(
				`[pi-mentor-runner] FATAL envelope mismatch: ` +
					`MENTOR_RUNNER_EXPECTED_PROTOCOL_VERSION=${expectedRaw}, runner PROTOCOL_VERSION=${PROTOCOL_VERSION}. ` +
					`Exiting ${ENVELOPE_MISMATCH_EXIT}.\n`,
			);
			process.exit(ENVELOPE_MISMATCH_EXIT);
		}
	}
}

/** How long a tool waits for the server to answer its callback. */
const CALLBACK_TIMEOUT_MS = 10_000;
const TURN_BUDGET_MS = (() => {
	const raw = Number(process.env.MENTOR_TURN_BUDGET_MS);
	return Number.isFinite(raw) && raw > 0 ? raw : 120_000;
})();
// 30 s production grace; small overrides are test-only so watchdog rebind scenarios run in ms.
const TURN_GRACE_MS = (() => {
	const raw = Number(process.env.MENTOR_TURN_GRACE_MS);
	return Number.isFinite(raw) && raw > 0 ? raw : 30_000;
})();
// How long a timed-out turn waits for Pi's abort to settle before it fails anyway; never longer than
// the grace, so the failure still reaches the server while it waits for one.
const ABORT_SETTLE_MS = Math.min(10_000, TURN_GRACE_MS);

function logText(value: unknown): string {
	if (value instanceof Error) {
		return `${value.message}\n${value.stack ?? ""}`;
	}
	if (typeof value === "string") {
		return value;
	}
	return JSON.stringify(value);
}

function log(...args: unknown[]) {
	const ts = new Date().toISOString();
	const msg = args.map((a) => logText(a)).join(" ");
	process.stderr.write(`[pi-mentor-runner ${ts}] ${msg}\n`);
}

/** Narrowing predicate for anything that arrived as parsed JSON, used instead of a cast. */
function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/** Do not coerce objects or arrays into non-empty strings that pass required-field checks. */
function jsonText(value: unknown): string {
	if (typeof value === "string") {
		return value;
	}
	if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") {
		return String(value);
	}
	return "";
}

/** JSON-RPC IDs are strings, numbers or null; malformed IDs cannot be correlated by Java. */
function asJsonRpcId(value: unknown): JsonRpcId | undefined {
	return typeof value === "string" || typeof value === "number" || value === null
		? value
		: undefined;
}

// LF-only line splitter. JSON.stringify leaves U+2028/U+2029 unescaped (nodejs/node-v0.x-archive
// #8221) so any splitter that treats Unicode line separators as newlines would corrupt JSON
// payloads. We split on 0x0a only (CRLF tolerant); this is what Node `readline` does too, but
// rolling our own keeps the framing rule trivially auditable and shared with the test fixture.
function createLineSplitter(onLine: (line: string) => void): (chunk: Buffer) => void {
	let buffer: Buffer = Buffer.alloc(0);
	// 8 MiB hard cap; context JSONs are tiny but be safe
	const MAX_LINE_BYTES = 8 * 1024 * 1024;
	return (chunk: Buffer) => {
		buffer = buffer.length === 0 ? chunk : Buffer.concat([buffer, chunk]);
		for (;;) {
			const nl = buffer.indexOf(0x0a);
			if (nl === -1) {
				if (buffer.length > MAX_LINE_BYTES) {
					log(`oversized line dropped at ${buffer.length} bytes`);
					buffer = Buffer.alloc(0);
				}
				return;
			}
			let lineBuf = buffer.subarray(0, nl);
			buffer = buffer.subarray(nl + 1);
			if (lineBuf.length > 0 && lineBuf.at(-1) === 0x0d) {
				lineBuf = lineBuf.subarray(0, -1);
			}
			if (lineBuf.length === 0) {
				continue;
			}
			try {
				onLine(lineBuf.toString("utf8"));
			} catch (error) {
				log("line handler threw:", error);
			}
		}
	};
}

/** The four frame shapes in `MentorOutboundFrame` are everything this runner ever writes. */
function writeFrame(frame: MentorOutboundFrame) {
	process.stdout.write(`${JSON.stringify(frame)}\n`);
}

/** Pause dispatch when stdout backs up past this size; prevents OOM under slow SSE consumers. */
const STDOUT_BACKPRESSURE_THRESHOLD_BYTES = 256 * 1024;

function sendResult(id: JsonRpcId | undefined, result: MentorResult) {
	// JSON-RPC 2.0 §4: `id` absent (undefined here) = notification → MUST NOT respond.
	// `id: null` is a valid request id — DO respond (used by §6 batch-rejection paths).
	if (id === undefined) {
		return;
	}
	writeFrame({ jsonrpc: JSONRPC_VERSION, id, result });
}

function sendError(
	id: JsonRpcId | undefined,
	code: MentorErrorCode,
	message: string,
	data?: unknown,
) {
	// Same rule as sendResult: only skip when id is genuinely absent (notification). `null`
	// is a valid id and JSON-RPC §6 explicitly requires it for batch-error / parse-error
	// responses where the server cannot determine which request id was at fault.
	if (id === undefined) {
		return;
	}
	writeFrame({
		jsonrpc: JSONRPC_VERSION,
		id,
		error: data === undefined ? { code, message } : { code, message, data },
	});
}

function sendEvent(threadId: string | null, event: MentorWireEvent) {
	writeFrame({ jsonrpc: JSONRPC_VERSION, method: "event", params: { threadId, event } });
}

//
// Strategy: hold ONE AgentSessionRuntime. Switch sessions per thread via `runtime.switchSession`
// (re-subscribing on each switch per SDK docs).

/** Shared by the SDK adapter and protocol test stub; createPiRuntime checks adapter compatibility. */
interface MentorAgentSession {
	subscribe: (listener: (event: AgentSessionEvent) => void) => () => void;
	prompt: (text: string) => Promise<void>;
	steer: (text: string) => Promise<void>;
	abort: () => Promise<void>;
	compact: () => Promise<CompactionResult>;
	/** A compaction is a model call of its own that `abort()` leaves running. */
	abortCompaction: () => void;
}

interface MentorRuntime {
	readonly session: MentorAgentSession;
	switchSession: (sessionPath: string) => Promise<{ cancelled: boolean }>;
	dispose: () => Promise<void>;
	/**
	 * Whether a context of `tokens`, or the session's own when absent, is past the working trigger.
	 */
	compactionDue: (tokens?: number) => boolean;
}

/** Structured details attached to a `fetch_context` tool result, for logs and UI rendering. */
interface FetchContextDetails {
	ok: true;
	length: number;
	truncated: boolean;
	originalLength: number;
}

type FetchContextToolResult = AgentToolResult<FetchContextDetails>;

/** One in-flight server callback (`fetch_context`, `link_observation`), keyed by its JSON-RPC id in `ThreadState`. */
interface PendingCallback {
	resolve: (result: unknown) => void;
	reject: (reason: Error) => void;
	timer: ReturnType<typeof setTimeout>;
}

const threads = new Map<string, ThreadState>();

interface ThreadState {
	readonly threadId: string;
	readonly sessionPath: string;
	inFlight: boolean;
	lastAgentEnd: Extract<AgentSessionEvent, { type: "agent_end" }> | null;
	watchdogTimer: ReturnType<typeof setTimeout> | null;
	readonly pendingCallbacks: Map<string, PendingCallback>;
	unsubscribe: (() => void) | null;
	/** The caller aborted this turn. */
	abortRequested: boolean;
	/** The watchdog owns this turn's outcome: nothing Pi emits for it any longer reaches the server. */
	timedOut: boolean;
}

function newThreadState(threadId: string, sessionPath: string): ThreadState {
	return {
		threadId,
		sessionPath,
		inFlight: false,
		lastAgentEnd: null,
		watchdogTimer: null,
		pendingCallbacks: new Map(),
		unsubscribe: null,
		abortRequested: false,
		timedOut: false,
	};
}

/**
 * Read through a call rather than inline: a test of `state.inFlight` narrows the property for the
 * rest of the function, which would make the same check after an await dead to the type system
 * while the turn can settle during it.
 */
function hasTurnInFlight(state: ThreadState): boolean {
	return state.inFlight;
}

/** Set when a native abort rejected: the session may still be running, so no later turn may use it. */
let runtimeUnusable = false;

// Currently-bound thread on the AgentSessionRuntime (since runtime is single-session at a time).
let activeThreadId: string | null = null;

let runtime: MentorRuntime | null = null;
let runtimeInitPromise: Promise<MentorRuntime> | null = null;
// Cached after the first read.
let systemPrompt: string | null = null;

/** Fail-fast cooldown for runtime init: re-loading the SDK on every inbound frame is wasteful. */
let runtimeInitFailure: { err: unknown; at: number } | null = null;
const RUNTIME_INIT_COOLDOWN_MS = 30_000;

// Serialised dispatch chain — every stdin frame AND watchdog rebind appends here so callers
// cannot race `runtime.switchSession` against each other.
let dispatchQueue = Promise.resolve();

// The tail of the chain is not a useful handle for any caller here —
// every task is fire-and-forget and its failure is already logged below — and handing one out
// invites an `await` that would deadlock a task queued from inside another task.
function enqueue(fn: () => unknown): void {
	dispatchQueue = runAfter(dispatchQueue, fn);
}

async function runAfter(previous: Promise<void>, fn: () => unknown): Promise<void> {
	try {
		await previous;
		// Pause for stdout drain before running the next task if writes are backing up.
		// Awaiting here naturally pauses the inbound pipe (since stdin frames also queue
		// through enqueue), which is the correct backpressure target: don't accept more
		// Pi events than we can ship to Java.
		if (process.stdout.writableLength > STDOUT_BACKPRESSURE_THRESHOLD_BYTES) {
			// `events.once` rejects on an `error` emitted while it waits, so an EPIPE here lands in the
			// catch below.
			await once(process.stdout, "drain");
		}
		await fn();
	} catch (error) {
		log("dispatch queue swallowed:", errorText(error));
	}
}

function cacheSystemPrompt() {
	if (!existsSync(SYSTEM_PROMPT_PATH)) {
		if (PROTOCOL_ONLY) {
			return;
		}
		throw new Error(`mentor system prompt is missing: ${SYSTEM_PROMPT_PATH}`);
	}
	try {
		systemPrompt = readFileSync(SYSTEM_PROMPT_PATH, "utf8");
		log(`loaded system prompt: ${systemPrompt.length} bytes`);
	} catch (error) {
		throw new Error(`mentor system prompt could not be read: ${errorText(error)}`, {
			cause: error,
		});
	}
}

async function createPiRuntime(sdk: PiSdk, agentDir: string): Promise<MentorRuntime> {
	const {
		createAgentSessionRuntime,
		createAgentSessionFromServices,
		createAgentSessionServices,
		SessionManager,
		SettingsManager,
		ModelRuntime,
		shouldCompact,
		estimateTokens,
	} = sdk;

	const fetchContextTool = defineFetchContextTool(sdk);
	const linkObservationTool = defineLinkObservationTool(sdk);
	// Same sandbox posture as the practice runner: this runner shares the image, the SDK and the
	// working directory with it, so the SDK's ancestor-walking discovery hits the same denied read
	// (pi-agent-sandbox.ts) unless a mentor session opts out of it too.
	const settingsManager = SettingsManager.create(CWD, agentDir, SANDBOX_SETTINGS_MANAGER_OPTIONS);

	const sharedModelRuntime = await ModelRuntime.create({
		authPath: `${agentDir}/auth.json`,
		modelsPath: `${agentDir}/models.json`,
		allowModelNetwork: false,
	});
	const providerConfig = loadProviderConfig(CWD);
	if (
		providerConfig === null ||
		!hasText(providerConfig.modelId) ||
		!registerHephaestusProvider(sharedModelRuntime, providerConfig)
	) {
		throw new Error(
			"Hephaestus provider is not configured — pi-provider.json and proxy credentials are required",
		);
	}
	const model = sharedModelRuntime.getModel("hephaestus", providerConfig.modelId);
	if (!model) {
		throw new Error(`Hephaestus model was not registered: ${providerConfig.modelId}`);
	}
	log(
		`registered hephaestus provider: apiProtocol=${providerConfig.apiProtocol} model=${providerConfig.modelId}`,
	);
	const compaction = mentorCompaction(
		model.contextWindow,
		settingsManager.getCompactionReserveTokens(),
		settingsManager.getCompactionKeepRecentTokens(),
	);
	log(
		`compaction: window=${model.contextWindow} trigger=${model.contextWindow - compaction.reserveTokens} ` +
			`reserve=${compaction.reserveTokens} keepRecent=${compaction.keepRecentTokens}`,
	);
	const { thinkingLevel } = reasoningSetting(providerConfig.reasoningEffort);
	log(`reasoning effort: ${providerConfig.reasoningEffort?.toLowerCase() ?? "provider default"}`);

	const mentorSystemPrompt = systemPrompt;
	if (mentorSystemPrompt === null) {
		throw new Error("mentor system prompt was not loaded");
	}
	const resourceLoaderOptions = { systemPromptOverride: () => mentorSystemPrompt };

	const createRuntime: CreateAgentSessionRuntimeFactory = async ({
		cwd,
		agentDir: sessionAgentDir,
		sessionManager,
		sessionStartEvent,
	}) => {
		const services = await createAgentSessionServices({
			cwd,
			agentDir: sessionAgentDir,
			modelRuntime: sharedModelRuntime,
			settingsManager,
			resourceLoaderOptions: { ...resourceLoaderOptions, ...SANDBOX_RESOURCE_LOADER_OPTIONS },
		});
		// Least-privilege mentor surface: context is exposed through fetch_context, not
		// filesystem spelunking. This keeps the model on the typed context contract and
		// avoids path drift between mounted files and tool resource names.
		const result = await createAgentSessionFromServices({
			services,
			sessionManager,
			sessionStartEvent,
			customTools: [fetchContextTool, linkObservationTool],
			tools: [...MENTOR_TOOL_NAMES],
			model,
			thinkingLevel,
		});
		// Building the session reloads its settings from disk, which drops earlier overrides, and every session
		// switch builds one; the mentor's working policy is applied to each once it is built.
		settingsManager.applyOverrides({ compaction: { enabled: true, ...compaction } });
		return { ...result, services, diagnostics: services.diagnostics };
	};

	const sessionRuntime = await createAgentSessionRuntime(createRuntime, {
		cwd: CWD,
		agentDir,
		sessionManager: SessionManager.inMemory(),
	});
	return {
		// A getter: the runtime replaces its session on every switch.
		get session() {
			return sessionRuntime.session;
		},
		switchSession: async (sessionPath) => sessionRuntime.switchSession(sessionPath),
		dispose: async () => sessionRuntime.dispose(),
		compactionDue: (tokens) => {
			const { session } = sessionRuntime;
			// Pi's usage is unknown after a compaction until a new reply; the messages' own estimate, the one
			// Pi reports as a compaction's estimatedTokensAfter, stands in for it.
			const known =
				tokens ??
				session.getContextUsage()?.tokens ??
				session.messages.reduce((sum, message) => sum + estimateTokens(message), 0);
			return shouldCompact(known, model.contextWindow, settingsManager.getCompactionSettings());
		},
	};
}

/** A conversation is compacted past this many tokens, when the model's window allows it. */
const MENTOR_WORKING_TOKENS = 49_152;

/**
 * The mentor's working policy in Pi's own terms, for a model whose real window is `window`. Pi compacts once
 * the context passes `window - reserveTokens`, so the reserve sets the trigger; it also caps Pi's summary
 * output, which the model's own output limit caps in turn. The window itself is never changed.
 *
 * `keepRecentTokens` is Pi's native target for recent messages kept unsummarised, not a bound: Pi cuts at a message
 * boundary, keeps a tool call with its results, and may split a long active turn. On a small window the target is
 * held to half the trigger, leaving nominal headroom for the summary.
 */
function mentorCompaction(
	window: number,
	nativeReserve: number,
	nativeKeepRecent: number,
): { reserveTokens: number; keepRecentTokens: number } {
	if (!Number.isInteger(window) || window <= 1) {
		throw new Error(`the model's context window is not a usable size: ${window}`);
	}
	const minimumReserve = Math.min(nativeReserve, Math.floor(window / 2));
	const trigger = Math.min(MENTOR_WORKING_TOKENS, window - minimumReserve);
	return {
		reserveTokens: window - trigger,
		keepRecentTokens: Math.min(nativeKeepRecent, Math.floor(trigger / 2)),
	};
}

async function ensureRuntime(): Promise<MentorRuntime> {
	if (runtime) {
		return runtime;
	}
	if (runtimeInitPromise) {
		return runtimeInitPromise;
	}
	if (runtimeInitFailure && Date.now() - runtimeInitFailure.at < RUNTIME_INIT_COOLDOWN_MS) {
		throw runtimeInitFailure.err;
	}

	runtimeInitPromise = (async () => {
		mkdirSync(SESSIONS_DIR, { recursive: true });
		const sdk = PROTOCOL_ONLY ? null : await import("@earendil-works/pi-coding-agent");
		const agentDir = AGENT_DIR_OVERRIDE ?? sdk?.getAgentDir() ?? PI_AGENT_DIR;
		cacheSystemPrompt();
		const r = sdk === null ? createStubRuntime() : await createPiRuntime(sdk, agentDir);
		runtime = r;
		log("runtime initialised");
		return r;
	})();
	try {
		const r = await runtimeInitPromise;
		runtimeInitFailure = null;
		return r;
	} catch (error) {
		runtimeInitFailure = { err: error, at: Date.now() };
		throw error;
	} finally {
		runtimeInitPromise = null;
	}
}

const ITEM_RESOURCES =
	"For one item, copy the `resource` value exactly as a context file gives it: a pull request's from its entry in " +
	"recent_authored_work.json or merge_readiness.json, an observation's from its row in observations_history.json. " +
	"Never build one from a pull request number or another id.";

function defineFetchContextTool(sdk: PiSdk) {
	const { defineTool } = sdk;
	return defineTool({
		name: "fetch_context",
		label: "Fetch Context",
		description:
			"Fetch a Hephaestus mentor context JSON resource from the server by its exact path. Fixed paths: " +
			`${[...FETCH_CONTEXT_ALLOWED].join(", ")}. ${ITEM_RESOURCES}`,
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["path"],
			properties: {
				path: {
					type: "string",
					minLength: 1,
					description: "A fixed path, or a `resource` value copied exactly from a context file.",
				},
			},
		},
		execute: async (_toolCallId, params): Promise<FetchContextToolResult> => {
			const contextKey = jsonText(params.path).trim();
			// Pi treats THROWN errors as the tool's failure signal — a returned `isError:true`
			// is ignored by the runtime, so throw to flag the call as failed.
			if (!isFetchContextKey(contextKey)) {
				throw new Error(
					`fetch_context: "${contextKey}" is not a context resource. ${ITEM_RESOURCES}`,
				);
			}
			const { result } = await askServer(
				(threadId, id) => ({
					jsonrpc: JSONRPC_VERSION,
					id,
					method: "fetch_context",
					params: { threadId, path: contextKey },
				}),
				contextKey,
			);
			return fetchContextResult(result);
		},
	});
}

/**
 * Sends the callback `request` builds for the active thread to the server and waits for its answer, which a tool
 * turns into its result, with the thread it asked for. A server error or a timeout rejects, so Pi records the tool
 * call as failed.
 */
async function askServer(
	request: (threadId: string, id: string) => ServerCallbackRequest,
	subject: string,
): Promise<{ threadId: string; result: unknown }> {
	const threadId = activeThreadId;
	if (threadId === null) {
		throw new Error(`server callback for ${subject}: no active thread bound to the runtime`);
	}
	const state = threads.get(threadId);
	if (!state) {
		throw new Error(`server callback for ${subject}: thread state lost for ${threadId}`);
	}
	const callbackId = `cb-${randomUUID()}`;
	const frame = request(threadId, callbackId);
	const { method } = frame;
	const { promise, resolve, reject } = Promise.withResolvers<unknown>();
	const timer = setTimeout(() => {
		if (state.pendingCallbacks.delete(callbackId)) {
			log(`${method} timed out: thread=${threadId} subject=${subject} id=${callbackId}`);
			reject(new Error(`${method}(${subject}) timed out after ${CALLBACK_TIMEOUT_MS}ms`));
		}
	}, CALLBACK_TIMEOUT_MS);
	state.pendingCallbacks.set(callbackId, { resolve, reject, timer });
	writeFrame(frame);
	return { threadId, result: await promise };
}

function defineLinkObservationTool(sdk: PiSdk) {
	const { defineTool } = sdk;
	return defineTool({
		name: "link_observation",
		label: "Link Observation",
		description:
			"Give the developer your feedback about one Hephaestus practice observation. `text` is shown to them " +
			"as part of your reply, exactly where you call this, so write it to them in your own words and do not " +
			"repeat it in your answer. Only feedback shown this way counts as raised with them.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["observationId", "text"],
			properties: {
				observationId: { type: "string", minLength: 1 },
				text: { type: "string", minLength: 1 },
			},
		},
		execute: async (
			_toolCallId,
			params,
			signal,
		): Promise<AgentToolResult<{ observationId: string }>> => {
			const observationId = jsonText(params.observationId).trim();
			const text = jsonText(params.text).trim();
			if (!observationId || !text) {
				throw new Error("link_observation: observationId and text are required");
			}
			// The server decides which observation a reply may show feedback about: nothing is shown or reported
			// as shown until it admits this one, and a refusal fails the call.
			const { threadId, result } = await askServer(
				(thread, id) => ({
					jsonrpc: JSONRPC_VERSION,
					id,
					method: "link_observation",
					params: { threadId: thread, observationId },
				}),
				observationId,
			);
			if (signal?.aborted === true) {
				throw new Error("link_observation: the turn was stopped, so nothing was shown");
			}
			// Only an answer admitting this very observation is an admission.
			if (!isRecord(result) || result.observationId !== observationId) {
				throw new Error(
					`link_observation: nothing was shown, the server did not admit observation ${observationId}`,
				);
			}
			sendEvent(threadId, { type: "link_observation", observationId, text });
			return {
				content: [
					{
						type: "text",
						text: `Shown to the developer: feedback on observation ${observationId}`,
					},
				],
				details: { observationId },
			};
		},
	});
}

function handleHello(id: JsonRpcId | undefined) {
	// Java validates protocolOnly so a stub runtime cannot answer production traffic.
	sendResult(id, { protocolVersion: PROTOCOL_VERSION, protocolOnly: PROTOCOL_ONLY });
}

async function prewarmRuntime() {
	try {
		await ensureRuntime();
	} catch (error) {
		log("prewarm ensureRuntime failed (will retry on demand):", error);
	}
}

/** Untrusted JSON-RPC parameters. */
type MentorParams = Record<string, unknown>;

/** An undefined id identifies a JSON-RPC notification and receives no response. */
type MethodHandler = (id: JsonRpcId | undefined, params: MentorParams) => void | Promise<void>;

// Prevent thread IDs from escaping SESSIONS_DIR.
const THREAD_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;

function normalizeThreadId(params: MentorParams) {
	return jsonText(params.threadId).trim().toLowerCase();
}

async function handleOpenThread(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	if (!threadId) {
		sendError(id, ERR.INVALID_REQUEST, "threadId is required");
		return;
	}
	if (!THREAD_ID_PATTERN.test(threadId)) {
		sendError(id, ERR.INVALID_REQUEST, "threadId must be a canonical UUID");
		return;
	}
	try {
		await ensureRuntime();
	} catch (error) {
		log("runtime init failed:", error);
		sendError(id, ERR.PI_ERROR, `runtime init failed: ${errorText(error)}`);
		return;
	}

	let state = threads.get(threadId);
	if (!state) {
		const sessionPath = path.join(SESSIONS_DIR, `${threadId}.jsonl`);
		// Defence-in-depth: even with the regex above, assert the resolved path stays inside
		// SESSIONS_DIR. `path.resolve` collapses any residual `..` or symlink hop.
		const resolvedSessions = path.resolve(SESSIONS_DIR) + path.sep;
		if (!path.resolve(sessionPath).startsWith(resolvedSessions)) {
			sendError(id, ERR.INVALID_REQUEST, "threadId resolves outside sessions dir");
			return;
		}
		state = newThreadState(threadId, sessionPath);
		threads.set(threadId, state);
	}

	try {
		restoreSession(state, jsonText(params.session));
		await bindThread(state);
		sendResult(id, { threadId, sessionPath: state.sessionPath });
	} catch (error) {
		log(`open_thread failed for ${threadId}:`, error);
		sendError(id, ERR.PI_ERROR, `open_thread failed: ${errorText(error)}`);
	}
}

/** A session this runner already holds for the thread is the one it last answered from, so it is kept. */
function restoreSession(state: ThreadState, session: string) {
	if (session === "" || existsSync(state.sessionPath)) {
		return;
	}
	writeFileSync(state.sessionPath, session);
	log(`restored session for thread ${state.threadId}`);
}

// Detach previous thread (unsubscribe), switch the runtime session file, re-subscribe.
// SDK docs §163-171 are explicit that listeners bind to a specific AgentSession; missing the
// rebind would silently drop events after the first switch.
async function bindThread(state: ThreadState): Promise<MentorRuntime> {
	// Asking for the runtime rather than reading the module-level handle keeps the "a bound thread
	// implies an initialised runtime" invariant inside one function instead of spread across every
	// caller. `ensureRuntime` is a no-op once initialised.
	const rt = await ensureRuntime();
	// Already bound.
	if (activeThreadId === state.threadId && state.unsubscribe) {
		return rt;
	}
	// Switch FIRST so a switchSession failure doesn't leave the previous session unsubscribed
	// with no path back: if we tore down `prev.unsubscribe` first and switch threw, the
	// previous thread would lose its event stream permanently. SDK guarantees the prior
	// session's listeners are invalidated as a side effect of a successful switchSession.
	const prevState = activeThreadId === null ? null : threads.get(activeThreadId);
	const { cancelled } = await rt.switchSession(state.sessionPath);
	if (cancelled) {
		throw new Error(`switchSession cancelled by extension hook for thread ${state.threadId}`);
	}
	if (prevState) {
		dropSubscription(prevState);
	}
	activeThreadId = state.threadId;
	state.unsubscribe = rt.session.subscribe((event) => forwardEvent(state, event));
	log(`bound thread ${state.threadId} → ${state.sessionPath}`);
	return rt;
}

function forwardEvent(state: ThreadState, event: AgentSessionEvent) {
	if (state.timedOut) {
		return;
	}
	if (process.env.MENTOR_RUNNER_DEBUG_EVENTS === "1") {
		const detail = event.type === "message_update" ? `/${event.assistantMessageEvent.type}` : "";
		log(`event: ${event.type}${detail}`);
		if (event.type === "message_end" && event.message.role === "assistant") {
			log(`assistant end: ${event.message.stopReason}`);
		}
	}
	// agent_end is attempt-level; expose only the final attempt after agent_settled.
	if (event.type === "agent_end") {
		state.lastAgentEnd = event;
		return;
	}

	if (event.type === "compaction_end") {
		sendEvent(state.threadId, event);
		// A finished compaction is a checkpoint worth keeping even if the turn goes on to fail, and the
		// server keeps only what it received before the turn's outcome.
		if (event.result !== undefined && !event.aborted) {
			emitSessionPersisted(state, false);
		}
		return;
	}

	if (event.type === "agent_settled") {
		emitSessionPersisted(state, true);
		const finalAgentEnd = state.lastAgentEnd;
		state.lastAgentEnd = null;
		if (finalAgentEnd) {
			sendEvent(state.threadId, finalAgentEnd);
		} else {
			sendEvent(state.threadId, {
				type: "pi_error",
				message: "Pi settled without an agent_end event",
			});
			sendEvent(state.threadId, { type: "agent_end", messages: [], willRetry: false });
		}
		clearTurnWatchdog(state);
		state.inFlight = false;
		maybePostTurnGc();
		return;
	}
	sendEvent(state.threadId, event);
}

/**
 * Sends the thread's native session file. A failure is reported as `pi_error` only where it may end the
 * turn; elsewhere it is logged and the server keeps the last checkpoint it received.
 */
function emitSessionPersisted(state: ThreadState, reportFailure: boolean) {
	if (!existsSync(state.sessionPath)) {
		// Legitimate case: PROTOCOL_ONLY stub never persists. In production this is anomalous —
		// surface it so Java logs a warning rather than silently caching stale bytes.
		if (PROTOCOL_ONLY) {
			return;
		}
		log(`session file missing for thread=${state.threadId}`);
		if (reportFailure) {
			sendEvent(state.threadId, {
				type: "pi_error",
				message: "session file missing at settlement",
			});
		}
		return;
	}
	try {
		const bytes = readFileSync(state.sessionPath, "utf8");
		if (bytes.length === 0) {
			return;
		}
		sendEvent(state.threadId, { type: "session_persisted", jsonl: bytes });
	} catch (error) {
		log(`emitSessionPersisted failed for thread=${state.threadId}: ${errorText(error)}`);
		if (reportFailure) {
			sendEvent(state.threadId, {
				type: "pi_error",
				message: `session_persist_read_failed: ${errorText(error)}`,
			});
		}
	}
}

/** Post-turn major GC fires only above this heap watermark (requires `--expose-gc`). */
const POST_TURN_GC_HEAP_THRESHOLD_BYTES = 64 * 1024 * 1024;

function maybePostTurnGc() {
	// Captured rather than re-read inside the callback: `global.gc` only exists when the runtime
	// was started with --expose-gc (MentorRunnerProfile passes it), and holding the reference
	// means the guard and the call can never disagree.
	const { gc } = global;
	if (typeof gc !== "function") {
		return;
	}
	if (process.memoryUsage().heapUsed < POST_TURN_GC_HEAP_THRESHOLD_BYTES) {
		return;
	}
	setImmediate(() => {
		try {
			gc();
		} catch (error) {
			log("post-turn gc threw:", error);
		}
	});
}

async function handlePrompt(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	const text = jsonText(params.text);
	if (!threadId || !text) {
		sendError(id, ERR.INVALID_REQUEST, "threadId and text are required");
		return;
	}
	const state = threads.get(threadId);
	if (!state) {
		sendError(id, ERR.THREAD_NOT_OPEN, `thread ${threadId} is not open`);
		return;
	}
	// Java discards a runner that answers this code, and the next turn restores the stored session.
	if (runtimeUnusable) {
		sendError(
			id,
			ERR.INVALID_STATE,
			"the runtime did not stop a timed-out turn and cannot take another",
		);
		return;
	}
	// A timed-out turn whose abort has not settled still holds the session.
	if (state.inFlight || state.timedOut) {
		sendError(id, ERR.TURN_IN_FLIGHT, `thread ${threadId} already has a turn in flight`);
		return;
	}

	let rt: MentorRuntime;
	try {
		rt = await bindThread(state);
	} catch (error) {
		sendError(id, ERR.PI_ERROR, `bind failed: ${errorText(error)}`);
		return;
	}

	state.inFlight = true;
	state.lastAgentEnd = null;
	state.abortRequested = false;
	state.timedOut = false;
	startTurnWatchdog(state);

	// Accept-and-stream: respond to the prompt RPC immediately; the actual turn is observed
	// via subscribed events. This mirrors the SDK's own RPC mode semantics (rpc.md §44-77).
	sendResult(id, { accepted: true });

	void runTurn(rt, state, text);
}

/** The turn itself, observed through the subscribed events; a rejection ends it as a failed turn. */
async function runTurn(rt: MentorRuntime, state: ThreadState, text: string) {
	const { threadId } = state;
	try {
		await compactBeforePrompt(rt, state);
		if (state.abortRequested || state.timedOut) {
			throw new Error("the turn was aborted before its prompt was sent");
		}
		await rt.session.prompt(text);
		log(`prompt resolved: thread=${threadId}`);
	} catch (error) {
		log(`prompt rejected for thread ${threadId}: ${errorText(error)}`);
		// The watchdog ends a turn it claimed itself, after its checkpoint.
		if (!state.inFlight || state.timedOut) {
			return;
		}
		sendEvent(threadId, { type: "pi_error", error: errorText(error) });
		sendEvent(threadId, { type: "agent_end", messages: [], willRetry: false });
		clearTurnWatchdog(state);
		state.inFlight = false;
	}
}

/**
 * A restored conversation past the working trigger is compacted before its prompt, inside the accepted
 * turn, so the summary is billed to that turn and spends its budget. Pi's own check before a prompt reads
 * only its last reply's usage; the session's estimate also counts what came after it. A compaction that
 * fails, or leaves the conversation past the trigger — Pi keeps a recent tool batch whole — ends the turn
 * rather than sending the history the trigger exists to keep out.
 */
async function compactBeforePrompt(rt: MentorRuntime, state: ThreadState) {
	if (!rt.compactionDue()) {
		return;
	}
	log(`compacting thread=${state.threadId} before its prompt`);
	let result: CompactionResult;
	try {
		result = await rt.session.compact();
	} catch (error) {
		throw new Error(`This conversation could not be shortened: ${errorText(error)}`, {
			cause: error,
		});
	}
	if (rt.compactionDue(result.estimatedTokensAfter)) {
		throw new Error(
			`This conversation is still too long after shortening it (${result.estimatedTokensAfter} estimated tokens).`,
		);
	}
}

async function handleSteer(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	const text = jsonText(params.text);
	if (!threadId || !text) {
		sendError(id, ERR.INVALID_REQUEST, "threadId and text are required");
		return;
	}
	const state = threads.get(threadId);
	if (!state) {
		sendError(id, ERR.THREAD_NOT_OPEN, `thread ${threadId} is not open`);
		return;
	}
	try {
		const rt = await bindThread(state);
		await rt.session.steer(text);
		sendResult(id, { accepted: true });
	} catch (error) {
		sendError(id, ERR.PI_ERROR, `steer failed: ${errorText(error)}`);
	}
}

async function handleAbort(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	if (!threadId) {
		sendError(id, ERR.INVALID_REQUEST, "threadId is required");
		return;
	}
	const state = threads.get(threadId);
	if (!state) {
		sendError(id, ERR.THREAD_NOT_OPEN, `thread ${threadId} is not open`);
		return;
	}
	if (!hasTurnInFlight(state)) {
		sendError(id, ERR.INVALID_STATE, "no turn in flight for this thread");
		return;
	}
	state.abortRequested = true;
	// A stopped turn shows nothing more, so no server answer may complete one of its tool calls.
	rejectPendingCallbacks(state, "turn stopped before the server answered");
	try {
		const rt = await bindThread(state);
		rt.session.abortCompaction();
		await rt.session.abort();
		sendResult(id, { aborted: true });
	} catch (error) {
		sendError(id, ERR.PI_ERROR, `abort failed: ${errorText(error)}`);
	}
}

function handleCloseThread(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	if (!threadId) {
		sendError(id, ERR.INVALID_REQUEST, "threadId is required");
		return;
	}
	const state = threads.get(threadId);
	if (!state) {
		// Idempotent close.
		sendResult(id, { closed: false });
		return;
	}
	cleanupThread(state);
	threads.delete(threadId);
	if (activeThreadId === threadId) {
		activeThreadId = null;
	}
	sendResult(id, { closed: true });
}

/** A shutdown that has not drained by here is wedged; losing the frame beats never exiting. */
const DRAIN_DEADLINE_MS = 5000;

/** Let stdout drain before exit; process.exit would discard queued replies. */
function exitWhenDrained(code: number): void {
	// First failure wins: a later clean shutdown must not mask a crash's code.
	if (code !== 0 || process.exitCode === undefined) {
		process.exitCode = code;
	}
	process.stdin.pause();
	setTimeout(() => {
		// Say what was lost. Exiting quietly on a wedged pipe is the defect this function exists for.
		const unsent = process.stdout.writableLength;
		if (unsent > 0) {
			log(`drain deadline exceeded — exiting with ${unsent} bytes unsent`);
		}
		process.exit(code);
	}, DRAIN_DEADLINE_MS).unref();
}

async function handleShutdown(id: JsonRpcId | undefined) {
	sendResult(id, { shuttingDown: true });
	// Reject pending server callbacks (Pi flushes a clean is-error tool result) and
	// tear down sessions. cleanupThread is sync, so a plain loop is enough.
	for (const state of threads.values()) {
		cleanupThread(state);
	}
	threads.clear();
	activeThreadId = null;
	try {
		await runtime?.dispose();
	} catch (error) {
		log(`runtime.dispose during shutdown failed: ${errorText(error)}`);
	}
	log("shutdown requested — exiting");
	exitWhenDrained(0);
}

// Max characters of context surfaced to the LLM per fetch_context call. Context JSONs
// occasionally balloon (e.g. `observations.json` for a heavy reviewer); without a cap, a single
// tool call can blow the model's context window. 200 K chars ≈ 50 K tokens at ~4 chars/token —
// comfortably below the configured model's context window. Counted in JS string length (UTF-16 code
// units), not bytes; context JSONs are ASCII-dominant so the variance is small.
const FETCH_CONTEXT_MAX_CHARS = 200_000;

/**
 * A callback failure reported by Java, carrying the JSON-RPC code alongside the message.
 * Pi surfaces the thrown message to the model; the code stays attached for server-side
 * diagnostics that survive the rethrow → LLM tool-error round-trip.
 */
class ServerCallbackError extends Error {
	readonly code: number | string;

	constructor(code: number | string, detail: string) {
		super(`server error [${code}]: ${detail}`);
		this.name = "ServerCallbackError";
		this.code = code;
	}

	/** Java always sends `{code: int, message: string}`; anything else is reported as unknown. */
	static from(error: unknown): ServerCallbackError {
		const body = isRecord(error) ? error : {};
		const code =
			typeof body.code === "number" || typeof body.code === "string" ? body.code : "unknown";
		const message =
			typeof body.message === "string" && body.message.length > 0 ? body.message : "unknown error";
		return new ServerCallbackError(code, message);
	}
}

/** The context document as the model reads it: a string as sent, anything else serialised once. */
function contextText(content: unknown): string {
	if (content == null) {
		return "{}";
	}
	return typeof content === "string" ? content : JSON.stringify(content);
}

/** The context document Java answered a `fetch_context` callback with, as the tool's result. */
function fetchContextResult(result: unknown): FetchContextToolResult {
	// Pi tool results accept `content: [{type:"text", text: string}]` (verified against
	// pi-mono SDK tool-result type). Java sends the context document as parsed JSON, so we
	// stringify ONCE; a plain string passes through untouched. Double-stringifying a
	// string ("\"foo\"" → "\\\"foo\\\"") would leak an extra layer of JSON escaping into
	// the LLM prompt.
	let text = contextText(isRecord(result) ? result.content : undefined);
	const originalLength = text.length;
	let truncated = false;
	if (text.length > FETCH_CONTEXT_MAX_CHARS) {
		// Hard-cut the JSON; the marker rides on a separate content part so a model
		// that parses the first part as JSON never has to skip our truncation prose.
		text = text.slice(0, FETCH_CONTEXT_MAX_CHARS);
		truncated = true;
	}
	const parts: FetchContextToolResult["content"] = [{ type: "text", text }];
	if (truncated) {
		parts.push({
			type: "text",
			text: `[truncated ${originalLength - FETCH_CONTEXT_MAX_CHARS} chars from response]`,
		});
	}
	return { content: parts, details: { ok: true, length: text.length, truncated, originalLength } };
}

// Callback responses (Java → runner)
function handleCallbackResponse(frame: Record<string, unknown>) {
	const callbackId = jsonText(frame.id);
	if (!callbackId) {
		log("callback response missing id; dropping");
		return;
	}
	// Search every thread for the matching pending callback (small N).
	for (const state of threads.values()) {
		const pending = state.pendingCallbacks.get(callbackId);
		if (!pending) {
			continue;
		}
		state.pendingCallbacks.delete(callbackId);
		clearTimeout(pending.timer);
		if (frame.error == null) {
			pending.resolve(frame.result);
		} else {
			// Reject so Pi records this tool call as failed (agent-loop.ts §632-638). Echo the
			// JSON-RPC error code in the rejection so server-side diagnostics survive the
			// rethrow → LLM tool-error round-trip.
			pending.reject(ServerCallbackError.from(frame.error));
		}
		return;
	}
	log(`callback response had no matching pending callback: id=${callbackId}`);
}

function startTurnWatchdog(state: ThreadState) {
	clearTurnWatchdog(state);
	// Serialize rebinding with RPC-driven session switches.
	state.watchdogTimer = setTimeout(() => {
		enqueue(async () => runWatchdogRebind(state));
	}, TURN_BUDGET_MS + TURN_GRACE_MS);
}

async function runWatchdogRebind(state: ThreadState) {
	// Thread closed between timer fire and queue drain — rebinding would leak a subscription.
	if (!threads.has(state.threadId)) {
		log(`watchdog rebind skipped: thread=${state.threadId} already closed`);
		return;
	}
	if (!state.inFlight) {
		log(`watchdog rebind skipped: thread=${state.threadId} turn already completed`);
		return;
	}
	log(`watchdog fired: rebuilding session for thread=${state.threadId}`);
	// Claimed before the abort: the settlement it provokes must not finish the turn as a success.
	state.timedOut = true;
	// Read once: the abort below yields, and every step after it must act on the same runtime.
	const rt = runtime;
	let settled = true;
	try {
		// Reject callbacks before the rebound session can reuse their ids.
		rejectPendingCallbacks(state, "turn aborted by watchdog before the server answered");
		if (rt) {
			settled = await abortWithin(rt, state);
		}
		// The session as the aborted turn left it, sent before the failure that ends the turn on the server.
		emitSessionPersisted(state, false);
		sendEvent(state.threadId, { type: "turn_watchdog_fired", threadId: state.threadId });
		endTurn(state);
		// Pi can go on after an abort — it may start compacting once the aborted run ends — and a session
		// still busy cannot be switched, so it is rebound only once settled.
		if (rt && settled) {
			dropSubscription(state);
			// A runtime has one active session; remove its prior thread subscription before rebinding.
			try {
				const prev =
					activeThreadId === null || activeThreadId === state.threadId
						? undefined
						: threads.get(activeThreadId);
				if (prev) {
					dropSubscription(prev);
				}
				await rt.switchSession(state.sessionPath);
				activeThreadId = state.threadId;
				state.unsubscribe = rt.session.subscribe((event) => forwardEvent(state, event));
			} catch (error) {
				log(`watchdog rebind failed for thread=${state.threadId}: ${errorText(error)}`);
				if (activeThreadId === state.threadId) {
					activeThreadId = null;
				}
			}
		}
	} finally {
		endTurn(state);
		if (settled) {
			state.timedOut = false;
		}
	}
}

/**
 * Aborts the session, compaction included, and waits for it to settle for at most ABORT_SETTLE_MS. A session
 * still busy then stays claimed by the timed-out turn until it settles, so none of its events reach a later
 * one. An abort that rejects says nothing about whether Pi stopped, so the runtime takes no further turn.
 */
async function abortWithin(rt: MentorRuntime, state: ThreadState): Promise<boolean> {
	rt.session.abortCompaction();
	const abort = abortSession(rt);
	const outcome = await Promise.race([abort, delay(ABORT_SETTLE_MS, "pending" as const)]);
	if (outcome === "pending") {
		log(
			`abort during watchdog did not settle within ${ABORT_SETTLE_MS}ms: thread=${state.threadId}`,
		);
		void releaseWhenSettled(abort, state);
	}
	return outcome === "settled";
}

async function abortSession(rt: MentorRuntime): Promise<"settled" | "rejected"> {
	try {
		await rt.session.abort();
		return "settled";
	} catch (error) {
		log(`abort during watchdog failed; the runtime takes no further turn: ${errorText(error)}`);
		runtimeUnusable = true;
		return "rejected";
	}
}

async function releaseWhenSettled(abort: Promise<"settled" | "rejected">, state: ThreadState) {
	if ((await abort) === "settled") {
		state.timedOut = false;
		log(`timed-out turn settled: thread=${state.threadId}`);
	}
}

/** Ends a turn the watchdog claimed, once: an empty final agent_end after its failure. */
function endTurn(state: ThreadState) {
	if (!hasTurnInFlight(state)) {
		return;
	}
	state.lastAgentEnd = null;
	sendEvent(state.threadId, { type: "agent_end", messages: [], willRetry: false });
	state.inFlight = false;
}

function clearTurnWatchdog(state: ThreadState) {
	if (state.watchdogTimer) {
		clearTimeout(state.watchdogTimer);
	}
	state.watchdogTimer = null;
}

/** Detaches a thread's listener from the runtime session; a listener that throws on removal is gone either way. */
function dropSubscription(state: ThreadState) {
	if (state.unsubscribe === null) {
		return;
	}
	try {
		state.unsubscribe();
	} catch (error) {
		log("unsubscribe threw:", error);
	}
	state.unsubscribe = null;
}

function cleanupThread(state: ThreadState) {
	clearTurnWatchdog(state);
	dropSubscription(state);
	rejectPendingCallbacks(state, "thread closed before the server answered");
}

/**
 * Fails every tool call still waiting on the server (Pi records a thrown error as `isError: true`), so an answer that
 * arrives afterwards finds nothing to settle and is dropped.
 */
function rejectPendingCallbacks(state: ThreadState, reason: string) {
	for (const [cbId, pending] of state.pendingCallbacks) {
		clearTimeout(pending.timer);
		pending.reject(new Error(reason));
		state.pendingCallbacks.delete(cbId);
	}
}

/**
 * The dispatch table. `satisfies Record<MentorMethod, MethodHandler>` is the contract check: a
 * method declared in pi-mentor-protocol.ts with no handler here — or a handler whose signature has
 * drifted — fails the build rather than answering Java with method_not_found at runtime.
 */
const METHODS = {
	hello: handleHello,
	open_thread: handleOpenThread,
	prompt: handlePrompt,
	steer: handleSteer,
	abort: handleAbort,
	close_thread: handleCloseThread,
	shutdown: handleShutdown,
} satisfies Record<MentorMethod, MethodHandler>;

function isMentorMethod(method: string): method is MentorMethod {
	return Object.hasOwn(METHODS, method);
}

async function dispatch(frame: unknown) {
	// Java sends requests and callback responses, but no batches. Reject top-level arrays explicitly.
	if (Array.isArray(frame)) {
		sendError(null, ERR.INVALID_REQUEST, "batch requests are not supported on this transport");
		return;
	}
	if (!isRecord(frame)) {
		log("unrecognised frame:", JSON.stringify(frame).slice(0, 200));
		return;
	}
	const id = asJsonRpcId(frame.id);
	if (typeof frame.method === "string" && frame.method.length > 0) {
		const { method } = frame;
		if (!isMentorMethod(method)) {
			sendError(id, ERR.METHOD_NOT_FOUND, `unknown method: ${method}`);
			return;
		}
		try {
			await METHODS[method](id, isRecord(frame.params) ? frame.params : {});
		} catch (error) {
			log(`handler ${method} threw: ${errorText(error)}`);
			sendError(id, ERR.PI_ERROR, `internal error: ${errorText(error)}`);
		}
		return;
	}
	if (frame.id != null && (frame.result !== undefined || frame.error !== undefined)) {
		handleCallbackResponse(frame);
		return;
	}
	log("unrecognised frame:", JSON.stringify(frame).slice(0, 200));
}

type StubAssistantMessage = Extract<
	Extract<AgentSessionEvent, { type: "message_update" }>["assistantMessageEvent"],
	{ type: "text_delta" }
>["partial"];

function stubAssistantMessage(text: string): StubAssistantMessage {
	return {
		role: "assistant",
		content: text.length === 0 ? [] : [{ type: "text", text }],
		api: "stub",
		provider: "stub",
		model: "stub",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp: Date.now(),
	};
}

function createStubRuntime(): MentorRuntime {
	const subscribers = new Set<(event: AgentSessionEvent) => void>();
	let isStreaming = false;
	let attemptGeneration = 0;
	const emit = (event: AgentSessionEvent) => {
		for (const s of subscribers) {
			try {
				s(event);
			} catch {
				/* ignore stub listener throw */
			}
		}
	};
	const stubSession: MentorAgentSession = {
		subscribe(listener) {
			subscribers.add(listener);
			return () => {
				subscribers.delete(listener);
			};
		},
		async prompt(text) {
			if (isStreaming) {
				throw new Error("stub: already streaming (caller should pass streamingBehavior)");
			}
			isStreaming = true;
			attemptGeneration += 1;
			const attempt = attemptGeneration;
			const stubDelayMs = Number(process.env.MENTOR_RUNNER_STUB_DELAY_MS) || 5;
			emit({ type: "agent_start" });
			await delay(stubDelayMs);
			if (attempt !== attemptGeneration) {
				return;
			}
			const delta = `stub: ${text}`;
			emit({
				type: "message_update",
				message: stubAssistantMessage(delta),
				assistantMessageEvent: {
					type: "text_delta",
					contentIndex: 0,
					delta,
					partial: stubAssistantMessage(delta),
				},
			});
			await delay(stubDelayMs);
			if (attempt !== attemptGeneration) {
				return;
			}
			const retryDelay = Number(process.env.MENTOR_RUNNER_STUB_RETRY_DELAY_MS) || 0;
			if (retryDelay > 0) {
				emit({
					type: "agent_end",
					messages: [stubAssistantMessage("stub: discarded attempt")],
					willRetry: true,
				});
				await delay(retryDelay);
				if (attempt !== attemptGeneration) {
					return;
				}
			}
			emit({ type: "agent_end", messages: [stubAssistantMessage(delta)], willRetry: false });
			emit({ type: "agent_settled" });
			isStreaming = false;
		},
		async steer() {
			// The scripted frames do not depend on steering.
		},
		async abort() {
			if (process.env.MENTOR_RUNNER_STUB_ABORT_REJECTS === "1") {
				throw new Error("stub: abort failed");
			}
			if (isStreaming) {
				attemptGeneration += 1;
				emit({ type: "agent_end", messages: [], willRetry: false });
				emit({ type: "agent_settled" });
				isStreaming = false;
			}
		},
		async compact() {
			throw new Error("stub: nothing to compact");
		},
		abortCompaction() {
			// The stub never compacts.
		},
	};
	return {
		session: stubSession,
		async switchSession() {
			return { cancelled: false };
		},
		async dispose() {
			// Nothing here holds the process open.
		},
		compactionDue: () => false,
	};
}

function announceReady() {
	// Notification (no id) so Java's RPC layer ignores it but the controller observes the event.
	// `threadId: null` keeps the params envelope identical to every other event frame
	// (`sendEvent`), so downstream consumers can match on `params.threadId` uniformly.
	sendEvent(null, {
		type: "runner_ready",
		protocolVersion: PROTOCOL_VERSION,
		turnBudgetMs: TURN_BUDGET_MS,
		turnGraceMs: TURN_GRACE_MS,
	});
}

function start() {
	// All stdin frames + side-effect rebinds funnel through `enqueue` to serialise
	// `runtime.switchSession` against itself.
	const splitter = createLineSplitter((line) => {
		enqueue(async () => {
			let frame: unknown;
			try {
				// The only place untrusted bytes become values. `dispatch` takes `unknown` and
				// narrows structurally from here, so nothing downstream trusts the shape.
				frame = JSON.parse(line);
			} catch (error) {
				log(`parse error: ${errorText(error)} (line len=${line.length})`);
				return;
			}
			try {
				await dispatch(frame);
			} catch (error) {
				log("dispatch failed:", error);
			}
		});
	});

	process.stdin.on("data", (chunk) => splitter(chunk));
	// EOF routes through the dispatch queue so SIGTERM and EOF run the same teardown.
	process.stdin.on("end", () => {
		log("stdin EOF — shutting down");
		enqueue(async () => handleShutdown(undefined));
	});
	process.stdin.on("error", (e) => {
		log("stdin error:", e);
		// Distinct from ENVELOPE_MISMATCH_EXIT (42): a transport failure, not protocol/image drift.
		exitWhenDrained(1);
	});

	process.on("uncaughtException", (e) => {
		log("uncaughtException:", e);
		// A crash still owes the caller whatever is already queued, so drain rather than exit.
		exitWhenDrained(1);
	});
	process.on("unhandledRejection", (e) => {
		log("unhandledRejection:", e);
	});

	// Signal-driven shutdown uses the same path as RPC `shutdown`. Pass `undefined` (NOT `null`)
	// so `sendResult` skips emitting a response frame for this synthetic shutdown.
	for (const signal of ["SIGTERM", "SIGINT"] as const) {
		process.on(signal, () => {
			log(`received ${signal} — initiating clean shutdown`);
			enqueue(async () => handleShutdown(undefined));
		});
	}

	announceReady();
	// Started with the process, not on demand: a sandbox prepared before the first message then has
	// its runtime ready by the time the message arrives. Frames that land while the SDK module
	// evaluates wait for it; open_thread would wait for it anyway.
	void prewarmRuntime();
}

start();
