// pi-mentor-protocol.ts — the JSON-RPC 2.0 contract spoken between the Java mentor chat service
// and pi-mentor-runner.ts over the sandbox's stdin/stdout (one JSON object per line, terminator
// strictly `\n`).
//
// The authoritative Java counterparts, for anyone reconciling the two:
//   requests / responses  → MentorRunnerClient
//   hello handshake       → MentorChatService#verifyProtocol
//   event → UI chunks     → PiEventToUiChunkTranslator
//   fetch_context replies → MentorChatService#handleFetchContext

import type { AgentSessionEvent } from "@earendil-works/pi-coding-agent";

/** JSON-RPC 2.0 §4: every frame in both directions carries this literal. */
export const JSONRPC_VERSION = "2.0";

/**
 * Handshake version reported by `hello`. Java pins the same number in
 * MentorRunnerClient.PROTOCOL_VERSION and refuses the session on a mismatch, so the two move
 * together or not at all.
 */
export const MENTOR_PROTOCOL_VERSION = 1;

export const MENTOR_TOOL_NAMES = ["fetch_context", "link_observation"] as const;

/**
 * JSON-RPC 2.0 §4 restricts an id to String, Number or Null. Java always sends a Number (an
 * `AtomicLong` counter) and the runner echoes back whatever it received; the runner's own
 * `fetch_context` callbacks use a String id, which Java echoes back unchanged in turn.
 */
export type JsonRpcId = string | number | null;

/**
 * Error codes shared with MentorRunnerException. `-32002` and `-32003` are the two Java treats as
 * sandbox-poisoning: receiving either force-closes the container rather than retrying the turn.
 */
export const MENTOR_ERROR_CODES = Object.freeze({
	INVALID_REQUEST: -32_600,
	METHOD_NOT_FOUND: -32_601,
	THREAD_NOT_OPEN: -32_000,
	TURN_IN_FLIGHT: -32_001,
	PI_ERROR: -32_002,
	INVALID_STATE: -32_003,
});

export type MentorErrorCode = (typeof MENTOR_ERROR_CODES)[keyof typeof MENTOR_ERROR_CODES];

//
// ─── Java → runner: requests ──────────────────────────────────────────────────────────────────
//

/** `hello` and `shutdown` take no arguments; Java still sends an empty object rather than omitting. */
export type EmptyParams = Record<string, never>;

export interface ThreadScopedParams {
	/** Canonical lowercase UUID. The runner re-validates it before it reaches `path.join`. */
	threadId: string;
}

export interface PromptParams extends ThreadScopedParams {
	text: string;
}

/**
 * Every method the runner dispatches, mapped to the params Java sends with it.
 *
 * `steer` has no Java caller today — MentorRunnerClient exposes no steer method — but the runner
 * implements it and the SDK supports it, so it stays part of the declared surface.
 */
export interface MentorRequestParams {
	hello: EmptyParams;
	open_thread: ThreadScopedParams;
	prompt: PromptParams;
	steer: PromptParams;
	abort: ThreadScopedParams;
	close_thread: ThreadScopedParams;
	shutdown: EmptyParams;
}

export type MentorMethod = keyof MentorRequestParams;

interface RequestFrame<M extends MentorMethod> {
	jsonrpc: typeof JSONRPC_VERSION;
	id: JsonRpcId;
	method: M;
	params: MentorRequestParams[M];
}

/** Discriminated union over `method`, so `params` is correlated with the method it accompanies. */
export type MentorRequest = { [M in MentorMethod]: RequestFrame<M> }[MentorMethod];

//
// ─── runner → Java: responses ─────────────────────────────────────────────────────────────────
//

export interface HelloResult {
	protocolVersion: number;
	/**
	 * True when MENTOR_RUNNER_PROTOCOL_ONLY=1 swapped the Pi SDK for the stub. Java fails the
	 * session closed on this rather than serving every user a stubbed answer.
	 */
	protocolOnly: boolean;
}

export interface OpenThreadResult {
	threadId: string;
	sessionPath: string;
}

/** `prompt` and `steer` are accept-and-stream: the turn itself is observed through events. */
export interface AcceptedResult {
	accepted: true;
}

export interface AbortResult {
	aborted: true;
}

export interface CloseThreadResult {
	/** False when the thread was already closed — `close_thread` is idempotent. */
	closed: boolean;
}

export interface ShutdownResult {
	shuttingDown: true;
}

export interface MentorResultPayloads {
	hello: HelloResult;
	open_thread: OpenThreadResult;
	prompt: AcceptedResult;
	steer: AcceptedResult;
	abort: AbortResult;
	close_thread: CloseThreadResult;
	shutdown: ShutdownResult;
}

export type MentorResult = MentorResultPayloads[MentorMethod];

export interface JsonRpcError {
	/** One of {@link MENTOR_ERROR_CODES}: a code Java has no mapping for is a code it cannot act on. */
	code: MentorErrorCode;
	message: string;
	/** Neither side populates this today; JSON-RPC 2.0 §5.1 allows it and Java carries it through. */
	data?: unknown;
}

export interface JsonRpcSuccessResponse {
	jsonrpc: typeof JSONRPC_VERSION;
	id: JsonRpcId;
	result: MentorResult;
}

export interface JsonRpcErrorResponse {
	jsonrpc: typeof JSONRPC_VERSION;
	id: JsonRpcId;
	error: JsonRpcError;
}

//
// ─── runner → Java: event notifications ───────────────────────────────────────────────────────
//

/** Announced once at startup, before any request is served. Carries `threadId: null`. */
export interface RunnerReadyEvent {
	type: "runner_ready";
	protocolVersion: number;
	turnBudgetMs: number;
	turnGraceMs: number;
}

export interface SessionPersistedEvent {
	type: "session_persisted";
	jsonl: string;
}

/**
 * A runner-side failure surfaced to the user as a terminal error chunk.
 *
 * Two spellings are on the wire: the session-persistence paths send `message` and the
 * prompt-rejection path sends `error`. PiEventToUiChunkTranslator#handleError reads `message`
 * first and falls back to `error`, so both render identically — but a consumer written against
 * only one of them would miss half the failures.
 */
export type PiErrorEvent =
	| { type: "pi_error"; message: string }
	| { type: "pi_error"; error: string };

/** Emitted before the watchdog rebuilds a stalled turn's session. Java ignores the body. */
export interface TurnWatchdogFiredEvent {
	type: "turn_watchdog_fired";
	threadId: string;
}

/** Emitted by the `link_observation` tool; Java requires `observationId` to parse as a UUID. */
export interface LinkObservationEvent {
	type: "link_observation";
	observationId: string;
}

/** The events the runner constructs itself, as opposed to forwarding from the Pi SDK. */
export type MentorRunnerEvent =
	| RunnerReadyEvent
	| SessionPersistedEvent
	| PiErrorEvent
	| TurnWatchdogFiredEvent
	| LinkObservationEvent;

/**
 * Everything that can appear as `params.event`. The runner forwards every Pi SDK session event
 * verbatim and adds its own on top, so the union spans both.
 */
export type MentorWireEvent = AgentSessionEvent | MentorRunnerEvent;

export interface MentorEventNotification {
	jsonrpc: typeof JSONRPC_VERSION;
	method: "event";
	params: {
		/** Null for runner-scoped events (`runner_ready`); Java fans those out to every client. */
		threadId: string | null;
		event: MentorWireEvent;
	};
}

//
// ─── runner → Java: the fetch_context callback ────────────────────────────────────────────────
//

export interface FetchContextParams {
	threadId: string;
	/** A canonical context key, e.g. `inputs/context/recent_authored_work.json`. */
	path: string;
}

/**
 * A request in the reverse direction: the runner asks Java for a context document while a Pi tool
 * call is in flight. The id is a string (`fc-<uuid>`), which Java echoes back untouched.
 */
export interface FetchContextRequest {
	jsonrpc: typeof JSONRPC_VERSION;
	id: string;
	method: "fetch_context";
	params: FetchContextParams;
}

export interface FetchContextResult {
	/**
	 * The context document as parsed JSON — an object or array in practice, and JSON null when the
	 * key is absent from the thread's context inputs. It is not pre-stringified, so the runner
	 * serialises it once on the way to the model.
	 */
	content: unknown;
}

export interface FetchContextSuccessResponse {
	jsonrpc: typeof JSONRPC_VERSION;
	id: JsonRpcId;
	result: FetchContextResult;
}

export interface FetchContextErrorResponse {
	jsonrpc: typeof JSONRPC_VERSION;
	id: JsonRpcId;
	error: JsonRpcError;
}

//
// ─── frame unions ─────────────────────────────────────────────────────────────────────────────
//

/** Everything the runner writes to stdout. */
export type MentorOutboundFrame =
	| JsonRpcSuccessResponse
	| JsonRpcErrorResponse
	| MentorEventNotification
	| FetchContextRequest;

/**
 * Everything the runner reads from stdin. Java never sends batches; the runner rejects top-level
 * arrays explicitly rather than dropping them silently.
 */
export type MentorInboundFrame =
	| MentorRequest
	| FetchContextSuccessResponse
	| FetchContextErrorResponse;
