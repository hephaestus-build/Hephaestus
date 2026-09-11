// pi-mentor-runner.ts — interactive Pi-mentor runner. Long-lived JSON-RPC 2.0 over stdin/stdout
// (one object per line, terminator strictly `\n`).
//
// Protocol (Java ↔ runner):
//   stdin  (Java→runner): requests `{jsonrpc, id, method, params}`
//   stdout (runner→Java): responses + notifications (`method:"event"`) + runner→Java callbacks
//                         (`fetch_context`).
// Methods: hello, open_thread, prompt, steer, abort, close_thread, shutdown.
// Session restore: Java injects `.sessions/<threadId>.jsonl` into the container at start
//                  time (sourced from `chat_thread.session_jsonl` BYTEA in Postgres). The runner's
//                  `bindThread` → `switchSession` loads byte-identical prior turns transparently
//                  via the Pi SDK SessionManager — no explicit replay RPC is needed.
// Custom tools: fetch_context (callback to Java, whitelisted paths), link_observation (event emit).
// Error codes: -32600 invalid_request, -32601 method_not_found, -32000 thread_not_open,
//              -32001 turn_already_in_flight, -32002 pi_error, -32003 invalid_state.
// Every frame shape named above is declared in pi-mentor-protocol.ts, which is the one place the
// contract with Java lives; this file implements it and pi-mentor-runner.spec.ts drives it.

import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync } from "node:fs";
import path from "node:path";

import type {
	AgentSessionEvent,
	AgentToolResult,
	CreateAgentSessionRuntimeFactory,
} from "@earendil-works/pi-coding-agent";

import {
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { errorText } from "./pi-error-text.ts";
import {
	MENTOR_ERROR_CODES as ERR,
	JSONRPC_VERSION,
	type JsonRpcId,
	MENTOR_PROTOCOL_VERSION,
	MENTOR_TOOL_NAMES,
	type MentorErrorCode,
	type MentorMethod,
	type MentorOutboundFrame,
	type MentorResult,
	type MentorWireEvent,
} from "./pi-mentor-protocol.ts";
import { loadProviderConfig, registerHephaestusProvider } from "./pi-provider.ts";

/** The Pi SDK module, resolved from `<workspace>/node_modules` by bare specifier at runtime. */
type PiSdk = typeof import("@earendil-works/pi-coding-agent");

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
const MENTOR_SYSTEM_PROMPT_PATH = "agent/mentor/system.md"; // SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH
const PI_AGENT_DIR = "/workspace/.pi"; // SandboxLayout.PI_AGENT_DIR
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

const FETCH_CONTEXT_TIMEOUT_MS = 10_000;
const TURN_BUDGET_MS = (() => {
	const raw = Number(process.env.MENTOR_TURN_BUDGET_MS);
	return Number.isFinite(raw) && raw > 0 ? raw : 120_000;
})();
// 30 s production grace; small overrides are test-only so watchdog rebind scenarios run in ms.
const TURN_GRACE_MS = (() => {
	const raw = Number(process.env.MENTOR_TURN_GRACE_MS);
	return Number.isFinite(raw) && raw > 0 ? raw : 30_000;
})();

// Context-key whitelist for the fetch_context tool. Java remains authoritative and
// re-checks against MentorContextKeys.ALLOWED_OUTPUT_KEYS.
const FETCH_CONTEXT_ALLOWED = new Set([
	"inputs/context/workspace.json",
	"inputs/context/user.json",
	"inputs/context/practice_catalog.json",
	"inputs/context/observations_history.json",
	"inputs/context/delivered_feedback.json",
	"inputs/context/recent_authored_work.json",
	"inputs/context/slack_conversations.json",
	"inputs/context/prepared_conversation_feedback.json",
	"inputs/context/current_thread_history.json",
	"inputs/context/outline_docs.json",
]);

function log(...args: unknown[]) {
	const ts = new Date().toISOString();
	const msg = args
		.map((a) =>
			a instanceof Error
				? `${a.message}\n${a.stack ?? ""}`
				: typeof a === "string"
					? a
					: JSON.stringify(a),
		)
		.join(" ");
	process.stderr.write(`[pi-mentor-runner ${ts}] ${msg}\n`);
}

/** Narrowing predicate for anything that arrived as parsed JSON, used instead of a cast. */
function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/**
 * The text of a field that arrived as parsed JSON, and "" for anything with no text of its own.
 *
 * <p>Every field below is read through this. An object or an array coerces to "[object Object]" or to
 * its elements run together, and each of those is a non-empty string that would then pass a required-
 * field check and reach an allow-list, a path join or the model as if the caller had sent text.
 */
function jsonText(value: unknown): string {
	if (typeof value === "string") return value;
	if (typeof value === "number" || typeof value === "boolean" || typeof value === "bigint") {
		return String(value);
	}
	return "";
}

/**
 * JSON-RPC 2.0 §4 restricts an id to String, Number or Null. Anything else is malformed; treating
 * it as absent means the frame is handled as a notification and draws no response, which is what
 * Java already observes today — `MentorRunnerClient` coerces a response id with `asLong()` and
 * drops any response it cannot correlate.
 */
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
	const MAX_LINE_BYTES = 8 * 1024 * 1024; // 8 MiB hard cap; context JSONs are tiny but be safe
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
			if (lineBuf.length > 0 && lineBuf[lineBuf.length - 1] === 0x0d) {
				lineBuf = lineBuf.subarray(0, lineBuf.length - 1);
			}
			if (lineBuf.length === 0) continue;
			try {
				onLine(lineBuf.toString("utf8"));
			} catch (e) {
				log("line handler threw:", e);
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
	if (id === undefined) return;
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
	if (id === undefined) return;
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

/**
 * The slice of the Pi SDK's `AgentSession` this runner drives.
 *
 * Declaring the subset rather than naming the class is what lets the protocol-only stub implement
 * the same contract without faking a whole SDK. The assignment in `createPiRuntime` makes the
 * compiler prove that the real `AgentSessionRuntime` still satisfies it, so an SDK rename or
 * signature change fails the build rather than the container.
 */
interface MentorAgentSession {
	subscribe: (listener: (event: AgentSessionEvent) => void) => () => void;
	prompt: (text: string) => Promise<void>;
	steer: (text: string) => Promise<void>;
	abort: () => Promise<void>;
}

interface MentorRuntime {
	readonly session: MentorAgentSession;
	switchSession: (sessionPath: string) => Promise<{ cancelled: boolean }>;
	dispose: () => Promise<void>;
}

/** Structured details attached to a `fetch_context` tool result, for logs and UI rendering. */
interface FetchContextDetails {
	ok: true;
	length: number;
	truncated: boolean;
	originalLength: number;
}

type FetchContextToolResult = AgentToolResult<FetchContextDetails>;

/** One in-flight `fetch_context` callback, keyed by its JSON-RPC id in `ThreadState`. */
interface PendingFetchContext {
	resolve: (result: FetchContextToolResult) => void;
	reject: (reason: Error) => void;
	timer: ReturnType<typeof setTimeout>;
}

const threads = new Map<string, ThreadState>();

class ThreadState {
	readonly threadId: string;
	readonly sessionPath: string;
	inFlight = false;
	lastAgentEnd: Extract<AgentSessionEvent, { type: "agent_end" }> | null = null;
	watchdogTimer: ReturnType<typeof setTimeout> | null = null;
	readonly pendingFetchContexts = new Map<string, PendingFetchContext>();
	unsubscribe: (() => void) | null = null;

	constructor(threadId: string, sessionPath: string) {
		this.threadId = threadId;
		this.sessionPath = sessionPath;
	}

	hasTurnInFlight() {
		return this.inFlight;
	}
}

// Currently-bound thread on the AgentSessionRuntime (since runtime is single-session at a time).
let activeThreadId: string | null = null;

let runtime: MentorRuntime | null = null;
let runtimeInitPromise: Promise<MentorRuntime> | null = null;
let systemPrompt: string | null = null; // cached after first read

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
	const previous = dispatchQueue;
	dispatchQueue = (async () => {
		await previous;
		// Pause for stdout drain before running the next task if writes are backing up.
		// Awaiting here naturally pauses the inbound pipe (since stdin frames also queue
		// through enqueue), which is the correct backpressure target: don't accept more
		// Pi events than we can ship to Java.
		if (process.stdout.writableLength > STDOUT_BACKPRESSURE_THRESHOLD_BYTES) {
			await new Promise<void>((resolve) => {
				process.stdout.once("drain", () => resolve());
			});
		}
		await fn();
	})().catch((e: unknown) => log("dispatch queue swallowed:", errorText(e)));
}

function cacheSystemPrompt() {
	if (!existsSync(SYSTEM_PROMPT_PATH)) {
		if (PROTOCOL_ONLY) return;
		throw new Error(`mentor system prompt is missing: ${SYSTEM_PROMPT_PATH}`);
	}
	try {
		systemPrompt = readFileSync(SYSTEM_PROMPT_PATH, "utf8");
		log(`loaded system prompt: ${systemPrompt.length} bytes`);
	} catch (e) {
		throw new Error(`mentor system prompt could not be read: ${errorText(e)}`, { cause: e });
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
	if (!providerConfig?.modelId || !registerHephaestusProvider(sharedModelRuntime, providerConfig)) {
		throw new Error(
			"Hephaestus provider is not configured — pi-provider.json and proxy credentials are required",
		);
	}
	const model = sharedModelRuntime.getModel("hephaestus", providerConfig.modelId);
	if (!model) throw new Error(`Hephaestus model was not registered: ${providerConfig.modelId}`);
	log(
		`registered hephaestus provider: apiProtocol=${providerConfig.apiProtocol} model=${providerConfig.modelId}`,
	);

	const mentorSystemPrompt = systemPrompt;
	if (mentorSystemPrompt === null) throw new Error("mentor system prompt was not loaded");
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
		});
		return { ...result, services, diagnostics: services.diagnostics };
	};

	return createAgentSessionRuntime(createRuntime, {
		cwd: CWD,
		agentDir,
		sessionManager: SessionManager.inMemory(),
	});
}

async function ensureRuntime(): Promise<MentorRuntime> {
	if (runtime) return runtime;
	if (runtimeInitPromise) return runtimeInitPromise;
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
	} catch (err) {
		runtimeInitFailure = { err, at: Date.now() };
		throw err;
	} finally {
		runtimeInitPromise = null;
	}
}

function defineFetchContextTool(sdk: PiSdk) {
	const { defineTool } = sdk;
	return defineTool({
		name: "fetch_context",
		label: "Fetch Context",
		description:
			"Fetch a Hephaestus mentor context JSON resource from the server. Use the exact canonical path, " +
			`for example inputs/context/recent_authored_work.json. Allowed paths: ${[...FETCH_CONTEXT_ALLOWED].join(", ")}.`,
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["path"],
			properties: {
				path: { type: "string", minLength: 1 },
			},
		},
		execute: async (_toolCallId, params): Promise<FetchContextToolResult> => {
			const contextKey = jsonText(params.path).trim();
			// Pi treats THROWN errors as the tool's failure signal — a returned `isError:true`
			// is ignored by the runtime, so throw to flag the call as failed.
			if (!FETCH_CONTEXT_ALLOWED.has(contextKey)) {
				throw new Error(`fetch_context: path "${contextKey}" is not in the allow-list`);
			}
			if (!activeThreadId) {
				throw new Error("fetch_context: no active thread bound to the runtime");
			}
			const state = threads.get(activeThreadId);
			if (!state) {
				throw new Error(`fetch_context: thread state lost for ${activeThreadId}`);
			}
			const callbackId = `fc-${randomUUID()}`;
			const { promise, resolve, reject } = Promise.withResolvers<FetchContextToolResult>();
			const timer = setTimeout(() => {
				if (state.pendingFetchContexts.delete(callbackId)) {
					log(
						`fetch_context timed out: thread=${activeThreadId} path=${contextKey} id=${callbackId}`,
					);
					reject(
						new Error(`fetch_context(${contextKey}) timed out after ${FETCH_CONTEXT_TIMEOUT_MS}ms`),
					);
				}
			}, FETCH_CONTEXT_TIMEOUT_MS);
			state.pendingFetchContexts.set(callbackId, { resolve, reject, timer });

			writeFrame({
				jsonrpc: JSONRPC_VERSION,
				id: callbackId,
				method: "fetch_context",
				params: { threadId: activeThreadId, path: contextKey },
			});
			return promise;
		},
	});
}

function defineLinkObservationTool(sdk: PiSdk) {
	const { defineTool } = sdk;
	return defineTool({
		name: "link_observation",
		label: "Link Observation",
		description:
			"Surface a Hephaestus practice observation inline in the chat by linking it to its UUID. " +
			"Use this when referring to a specific observation from a prior review.",
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["observationId"],
			properties: {
				observationId: { type: "string", minLength: 1 },
			},
		},
		execute: (_toolCallId, params): Promise<AgentToolResult<{ observationId: string }>> => {
			const observationId = jsonText(params.observationId).trim();
			if (!observationId) {
				return Promise.reject(new Error("link_observation: observationId is required"));
			}
			if (activeThreadId) {
				sendEvent(activeThreadId, { type: "link_observation", observationId });
			}
			return Promise.resolve({
				content: [{ type: "text", text: `Linked observation ${observationId}` }],
				details: { observationId },
			});
		},
	});
}

function handleHello(id: JsonRpcId | undefined /*, params */) {
	// Java validates protocolOnly so a stub runtime cannot answer production traffic.
	sendResult(id, { protocolVersion: PROTOCOL_VERSION, protocolOnly: PROTOCOL_ONLY });
	// Reply before synchronously evaluating the SDK during background prewarm.
	if (!PROTOCOL_ONLY) {
		setImmediate(() => {
			ensureRuntime().catch((e) => log("prewarm ensureRuntime failed (will retry on demand):", e));
		});
	}
}

/** Untrusted JSON-RPC parameters. */
type MentorParams = Record<string, unknown>;

/** An undefined id identifies a JSON-RPC notification and receives no response. */
type MethodHandler = (id: JsonRpcId | undefined, params: MentorParams) => void | Promise<void>;

// Prevent thread IDs from escaping SESSIONS_DIR.
const THREAD_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function normalizeThreadId(params: MentorParams) {
	return jsonText(params.threadId).trim().toLowerCase();
}

async function handleOpenThread(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	if (!threadId) {
		return sendError(id, ERR.INVALID_REQUEST, "threadId is required");
	}
	if (!THREAD_ID_PATTERN.test(threadId)) {
		return sendError(id, ERR.INVALID_REQUEST, "threadId must be a canonical UUID");
	}
	try {
		await ensureRuntime();
	} catch (e) {
		log("runtime init failed:", e);
		return sendError(id, ERR.PI_ERROR, `runtime init failed: ${errorText(e)}`);
	}

	let state = threads.get(threadId);
	if (!state) {
		const sessionPath = path.join(SESSIONS_DIR, `${threadId}.jsonl`);
		// Defence-in-depth: even with the regex above, assert the resolved path stays inside
		// SESSIONS_DIR. `path.resolve` collapses any residual `..` or symlink hop.
		const resolvedSessions = path.resolve(SESSIONS_DIR) + path.sep;
		if (!path.resolve(sessionPath).startsWith(resolvedSessions)) {
			return sendError(id, ERR.INVALID_REQUEST, "threadId resolves outside sessions dir");
		}
		state = new ThreadState(threadId, sessionPath);
		threads.set(threadId, state);
	}

	try {
		await bindThread(state);
		sendResult(id, { threadId, sessionPath: state.sessionPath });
	} catch (e) {
		log(`open_thread failed for ${threadId}:`, e);
		sendError(id, ERR.PI_ERROR, `open_thread failed: ${errorText(e)}`);
	}
}

// Detach previous thread (unsubscribe), switch the runtime session file, re-subscribe.
// SDK docs §163-171 are explicit that listeners bind to a specific AgentSession; missing the
// rebind would silently drop events after the first switch.
async function bindThread(state: ThreadState): Promise<MentorRuntime> {
	// Asking for the runtime rather than reading the module-level handle keeps the "a bound thread
	// implies an initialised runtime" invariant inside one function instead of spread across every
	// caller. `ensureRuntime` is a no-op once initialised.
	const rt = await ensureRuntime();
	if (activeThreadId === state.threadId && state.unsubscribe) {
		return rt; // already bound
	}
	// Switch FIRST so a switchSession failure doesn't leave the previous session unsubscribed
	// with no path back: if we tore down `prev.unsubscribe` first and switch threw, the
	// previous thread would lose its event stream permanently. SDK guarantees the prior
	// session's listeners are invalidated as a side effect of a successful switchSession.
	const prevState = activeThreadId ? threads.get(activeThreadId) : null;
	const { cancelled } = await rt.switchSession(state.sessionPath);
	if (cancelled) {
		throw new Error(`switchSession cancelled by extension hook for thread ${state.threadId}`);
	}
	if (prevState?.unsubscribe) {
		try {
			prevState.unsubscribe();
		} catch (e) {
			log("prev unsubscribe threw:", e);
		}
		prevState.unsubscribe = null;
	}
	activeThreadId = state.threadId;
	state.unsubscribe = rt.session.subscribe((event) => forwardEvent(state, event));
	log(`bound thread ${state.threadId} → ${state.sessionPath}`);
	return rt;
}

function forwardEvent(state: ThreadState, event: AgentSessionEvent) {
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

	if (event.type === "agent_settled") {
		emitSessionPersisted(state);
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

function emitSessionPersisted(state: ThreadState) {
	if (!existsSync(state.sessionPath)) {
		// Legitimate case: PROTOCOL_ONLY stub never persists. In production this is anomalous —
		// surface as pi_error so Java logs a warning rather than silently caching stale bytes.
		if (!PROTOCOL_ONLY) {
			sendEvent(state.threadId, {
				type: "pi_error",
				message: "session file missing at settlement",
			});
		}
		return;
	}
	try {
		const bytes = readFileSync(state.sessionPath, "utf8");
		if (bytes.length === 0) return;
		sendEvent(state.threadId, { type: "session_persisted", jsonl: bytes });
	} catch (e) {
		log(`emitSessionPersisted failed for thread=${state.threadId}: ${errorText(e)}`);
		sendEvent(state.threadId, {
			type: "pi_error",
			message: `session_persist_read_failed: ${errorText(e)}`,
		});
	}
}

/** Post-turn major GC fires only above this heap watermark (requires `--expose-gc`). */
const POST_TURN_GC_HEAP_THRESHOLD_BYTES = 64 * 1024 * 1024;

function maybePostTurnGc() {
	// Captured rather than re-read inside the callback: `global.gc` only exists when the runtime
	// was started with --expose-gc (MentorRunnerProfile passes it), and holding the reference
	// means the guard and the call can never disagree.
	const gc = global.gc;
	if (typeof gc !== "function") return;
	if (process.memoryUsage().heapUsed < POST_TURN_GC_HEAP_THRESHOLD_BYTES) return;
	setImmediate(() => {
		try {
			gc();
		} catch (e) {
			log("post-turn gc threw:", e);
		}
	});
}

async function handlePrompt(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	const text = jsonText(params.text);
	if (!threadId || !text) {
		return sendError(id, ERR.INVALID_REQUEST, "threadId and text are required");
	}
	const state = threads.get(threadId);
	if (!state) {
		return sendError(id, ERR.THREAD_NOT_OPEN, `thread ${threadId} is not open`);
	}
	if (state.inFlight) {
		return sendError(id, ERR.TURN_IN_FLIGHT, `thread ${threadId} already has a turn in flight`);
	}

	let rt: MentorRuntime;
	try {
		rt = await bindThread(state);
	} catch (e) {
		return sendError(id, ERR.PI_ERROR, `bind failed: ${errorText(e)}`);
	}

	state.inFlight = true;
	state.lastAgentEnd = null;
	startTurnWatchdog(state);

	// Accept-and-stream: respond to the prompt RPC immediately; the actual turn is observed
	// via subscribed events. This mirrors the SDK's own RPC mode semantics (rpc.md §44-77).
	sendResult(id, { accepted: true });

	rt.session
		.prompt(text)
		.then(() => {
			log(`prompt resolved: thread=${threadId}`);
		})
		.catch((e: unknown) => {
			log(`prompt rejected for thread ${threadId}: ${errorText(e)}`);
			if (!state.inFlight) return;
			sendEvent(threadId, { type: "pi_error", error: errorText(e) });
			sendEvent(threadId, { type: "agent_end", messages: [], willRetry: false });
			clearTurnWatchdog(state);
			state.inFlight = false;
		});
}

async function handleSteer(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	const text = jsonText(params.text);
	if (!threadId || !text) {
		return sendError(id, ERR.INVALID_REQUEST, "threadId and text are required");
	}
	const state = threads.get(threadId);
	if (!state) {
		return sendError(id, ERR.THREAD_NOT_OPEN, `thread ${threadId} is not open`);
	}
	try {
		const rt = await bindThread(state);
		await rt.session.steer(text);
		sendResult(id, { accepted: true });
	} catch (e) {
		sendError(id, ERR.PI_ERROR, `steer failed: ${errorText(e)}`);
	}
}

async function handleAbort(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	if (!threadId) {
		return sendError(id, ERR.INVALID_REQUEST, "threadId is required");
	}
	const state = threads.get(threadId);
	if (!state) {
		return sendError(id, ERR.THREAD_NOT_OPEN, `thread ${threadId} is not open`);
	}
	if (!state.hasTurnInFlight()) {
		return sendError(id, ERR.INVALID_STATE, "no turn in flight for this thread");
	}
	try {
		const rt = await bindThread(state);
		await rt.session.abort();
		sendResult(id, { aborted: true });
	} catch (e) {
		sendError(id, ERR.PI_ERROR, `abort failed: ${errorText(e)}`);
	}
}

function handleCloseThread(id: JsonRpcId | undefined, params: MentorParams) {
	const threadId = normalizeThreadId(params);
	if (!threadId) {
		return sendError(id, ERR.INVALID_REQUEST, "threadId is required");
	}
	const state = threads.get(threadId);
	if (!state) {
		// Idempotent close.
		return sendResult(id, { closed: false });
	}
	cleanupThread(state);
	threads.delete(threadId);
	if (activeThreadId === threadId) {
		activeThreadId = null;
	}
	sendResult(id, { closed: true });
}

/** A shutdown that has not drained by here is wedged; losing the frame beats never exiting. */
const DRAIN_DEADLINE_MS = 5_000;

/**
 * Stops the runner once stdout has drained. `process.exit` discards whatever is still queued for a
 * pipe, and the frame most likely to be queued is the last one — the result the caller is waiting
 * for. Setting `exitCode` and releasing stdin lets the loop empty on its own, which drains the pipe;
 * the unref'd timer is the backstop for a runtime that never settles.
 */
function exitWhenDrained(code: number): void {
	// First failure wins: a later clean shutdown must not mask a crash's code.
	if (code !== 0 || !process.exitCode) process.exitCode = code;
	process.stdin.pause();
	setTimeout(() => {
		// Say what was lost. Exiting quietly on a wedged pipe is the defect this function exists for.
		const unsent = process.stdout.writableLength;
		if (unsent > 0) log(`drain deadline exceeded — exiting with ${unsent} bytes unsent`);
		process.exit(code);
	}, DRAIN_DEADLINE_MS).unref();
}

async function handleShutdown(id: JsonRpcId | undefined) {
	sendResult(id, { shuttingDown: true });
	// Reject pending fetch_context callbacks (Pi flushes a clean is-error tool result) and
	// tear down sessions. cleanupThread is sync, so a plain loop is enough.
	for (const state of threads.values()) cleanupThread(state);
	threads.clear();
	activeThreadId = null;
	try {
		await runtime?.dispose();
	} catch (e) {
		log(`runtime.dispose during shutdown failed: ${errorText(e)}`);
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
 * A `fetch_context` failure reported by Java, carrying the JSON-RPC code alongside the message.
 * Pi surfaces the thrown message to the model; the code stays attached for server-side
 * diagnostics that survive the rethrow → LLM tool-error round-trip.
 */
class FetchContextServerError extends Error {
	readonly code: number | string;

	constructor(code: number | string, detail: string) {
		super(`fetch_context server error [${code}]: ${detail}`);
		this.name = "FetchContextServerError";
		this.code = code;
	}

	/** Java always sends `{code: int, message: string}`; anything else is reported as unknown. */
	static from(error: unknown): FetchContextServerError {
		const body = isRecord(error) ? error : {};
		const code =
			typeof body.code === "number" || typeof body.code === "string" ? body.code : "unknown";
		const message =
			typeof body.message === "string" && body.message.length > 0 ? body.message : "unknown error";
		return new FetchContextServerError(code, message);
	}
}

// fetch_context responses (Java → runner)
function handleFetchContextResponse(frame: Record<string, unknown>) {
	const callbackId = jsonText(frame.id);
	if (!callbackId) {
		log("fetch_context response missing id; dropping");
		return;
	}
	// Search every thread for the matching pending callback (small N).
	for (const state of threads.values()) {
		const pending = state.pendingFetchContexts.get(callbackId);
		if (!pending) continue;
		state.pendingFetchContexts.delete(callbackId);
		clearTimeout(pending.timer);
		if (frame.error != null) {
			// Reject so Pi records this tool call as failed (agent-loop.ts §632-638). Echo the
			// JSON-RPC error code in the rejection so server-side diagnostics survive the
			// rethrow → LLM tool-error round-trip.
			pending.reject(FetchContextServerError.from(frame.error));
		} else {
			// Pi tool results accept `content: [{type:"text", text: string}]` (verified against
			// pi-mono SDK tool-result type). Java sends the context document as parsed JSON, so we
			// stringify ONCE; a plain string passes through untouched. Double-stringifying a
			// string ("\"foo\"" → "\\\"foo\\\"") would leak an extra layer of JSON escaping into
			// the LLM prompt.
			const content = isRecord(frame.result) ? frame.result.content : undefined;
			let text =
				content == null ? "{}" : typeof content === "string" ? content : JSON.stringify(content);
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
			pending.resolve({
				content: parts,
				details: { ok: true, length: text.length, truncated, originalLength },
			});
		}
		return;
	}
	log(`fetch_context response had no matching pending callback: id=${callbackId}`);
}

function startTurnWatchdog(state: ThreadState) {
	clearTurnWatchdog(state);
	// Serialize rebinding with RPC-driven session switches.
	state.watchdogTimer = setTimeout(() => {
		enqueue(() => runWatchdogRebind(state));
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
	sendEvent(state.threadId, { type: "turn_watchdog_fired", threadId: state.threadId });
	// Read once: the abort below yields, and every step after it must act on the same runtime.
	const rt = runtime;
	try {
		// Reject callbacks before the rebound session can reuse their ids.
		for (const [cbId, pending] of state.pendingFetchContexts) {
			clearTimeout(pending.timer);
			pending.reject(new Error("fetch_context: turn aborted by watchdog"));
			state.pendingFetchContexts.delete(cbId);
		}
		await rt?.session
			.abort()
			.catch((e: unknown) => log(`abort during watchdog failed: ${errorText(e)}`));
		if (state.unsubscribe) {
			try {
				state.unsubscribe();
			} catch {
				/* ignore */
			}
			state.unsubscribe = null;
		}
		// A runtime has one active session; remove its prior thread subscription before rebinding.
		if (rt) {
			try {
				if (activeThreadId && activeThreadId !== state.threadId) {
					const prev = threads.get(activeThreadId);
					if (prev?.unsubscribe) {
						try {
							prev.unsubscribe();
						} catch {
							/* ignore */
						}
						prev.unsubscribe = null;
					}
				}
				await rt.switchSession(state.sessionPath);
				activeThreadId = state.threadId;
				state.unsubscribe = rt.session.subscribe((event) => forwardEvent(state, event));
			} catch (e) {
				log(`watchdog rebind failed for thread=${state.threadId}: ${errorText(e)}`);
				if (activeThreadId === state.threadId) activeThreadId = null;
			}
		}
	} finally {
		if (state.hasTurnInFlight()) {
			state.lastAgentEnd = null;
			sendEvent(state.threadId, { type: "agent_end", messages: [], willRetry: false });
			state.inFlight = false;
		}
	}
}

function clearTurnWatchdog(state: ThreadState) {
	if (state.watchdogTimer) clearTimeout(state.watchdogTimer);
	state.watchdogTimer = null;
}

function cleanupThread(state: ThreadState) {
	clearTurnWatchdog(state);
	if (state.unsubscribe) {
		try {
			state.unsubscribe();
		} catch {
			/* ignore */
		}
		state.unsubscribe = null;
	}
	for (const [cbId, pending] of state.pendingFetchContexts) {
		clearTimeout(pending.timer);
		// Reject so Pi sees a failed tool call (thrown error → isError: true).
		pending.reject(new Error("fetch_context: thread closed before context arrived"));
		state.pendingFetchContexts.delete(cbId);
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
	// Two shapes arrive on stdin:
	//   1. JSON-RPC requests from Java: {jsonrpc, id, method, params}
	//   2. JSON-RPC responses to our fetch_context callbacks: {jsonrpc, id, result|error}
	// JSON-RPC 2.0 §6 allows batch (top-level array) but neither end emits batches today.
	// Reject loudly rather than silently dropping — a future Java caller that bundles
	// open_thread + prompt would otherwise vanish into the log.
	if (Array.isArray(frame)) {
		return sendError(
			null,
			ERR.INVALID_REQUEST,
			"batch requests are not supported on this transport",
		);
	}
	if (!isRecord(frame)) {
		log("unrecognised frame:", JSON.stringify(frame).slice(0, 200));
		return;
	}
	const id = asJsonRpcId(frame.id);
	if (typeof frame.method === "string" && frame.method.length > 0) {
		const method = frame.method;
		if (!isMentorMethod(method)) {
			return sendError(id, ERR.METHOD_NOT_FOUND, `unknown method: ${method}`);
		}
		try {
			await METHODS[method](id, isRecord(frame.params) ? frame.params : {});
		} catch (e) {
			log(`handler ${method} threw: ${errorText(e)}`);
			sendError(id, ERR.PI_ERROR, `internal error: ${errorText(e)}`);
		}
		return;
	}
	if (frame.id != null && (frame.result !== undefined || frame.error !== undefined)) {
		return handleFetchContextResponse(frame);
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
			const attempt = ++attemptGeneration;
			const delay = Number(process.env.MENTOR_RUNNER_STUB_DELAY_MS) || 5;
			emit({ type: "agent_start" });
			await new Promise((resolve) => {
				setTimeout(resolve, delay);
			});
			if (attempt !== attemptGeneration) return;
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
			await new Promise((resolve) => {
				setTimeout(resolve, delay);
			});
			if (attempt !== attemptGeneration) return;
			const retryDelay = Number(process.env.MENTOR_RUNNER_STUB_RETRY_DELAY_MS) || 0;
			if (retryDelay > 0) {
				emit({
					type: "agent_end",
					messages: [stubAssistantMessage("stub: discarded attempt")],
					willRetry: true,
				});
				await new Promise((resolve) => {
					setTimeout(resolve, retryDelay);
				});
				if (attempt !== attemptGeneration) return;
			}
			emit({ type: "agent_end", messages: [stubAssistantMessage(delta)], willRetry: false });
			emit({ type: "agent_settled" });
			isStreaming = false;
		},
		steer() {
			return Promise.resolve();
		},
		abort() {
			if (isStreaming) {
				attemptGeneration++;
				emit({ type: "agent_end", messages: [], willRetry: false });
				emit({ type: "agent_settled" });
				isStreaming = false;
			}
			return Promise.resolve();
		},
	};
	return {
		session: stubSession,
		switchSession() {
			return Promise.resolve({ cancelled: false });
		},
		dispose() {
			return Promise.resolve();
		},
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
			} catch (e) {
				log(`parse error: ${errorText(e)} (line len=${line.length})`);
				return;
			}
			try {
				await dispatch(frame);
			} catch (e) {
				log("dispatch failed:", e);
			}
		});
	});

	process.stdin.on("data", (chunk) => splitter(chunk));
	// EOF routes through the dispatch queue so SIGTERM and EOF run the same teardown.
	process.stdin.on("end", () => {
		log("stdin EOF — shutting down");
		enqueue(() => handleShutdown(undefined));
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
			enqueue(() => handleShutdown(undefined));
		});
	}

	announceReady();
	// SDK prewarm is intentionally NOT triggered here — it fires inside handleHello after the
	// reply is written. Pi SDK module evaluation is synchronous (~300-400 ms) and would block
	// hello until it completes. Firing it post-hello lets the reply land instantly and the load
	// runs while Java orchestrates open_thread.
}

start();
