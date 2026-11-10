// heph-host.ts — the conversation loop: one Node process in each replica of its Deployment runs many Heph turns at once.
// Nothing about a conversation outlives its turn here: each turn rebuilds its Pi session from the ledger.
// docs/contributor/unified-pi-runtime.mdx § The conversation loop and § Turn lifecycle have the protocol.

import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

import {
	type AgentSession,
	type AgentSessionEvent,
	type AgentToolResult,
	type ContextEditEntryDraft,
	createAgentSession,
	createBashToolDefinition,
	createReadToolDefinition,
	DEFAULT_COMPACTION_SETTINGS,
	DEFAULT_MAX_BYTES,
	DEFAULT_MAX_LINES,
	DefaultResourceLoader,
	defineTool,
	estimateTokens,
	type ExtensionFactory,
	type FileEntry,
	ModelRuntime,
	type SessionEntry,
	SessionManager,
	type SessionMessageEntry,
	SettingsManager,
	shouldCompact,
} from "@earendil-works/pi-coding-agent";

import {
	asBindAnswer,
	asLoadAbout,
	asLoadAnswer,
	asReadAnswer,
	CONTEXT_FOLDER_TOOL_NAMES,
	type FailureDetail,
	HEPH_TOOL_NAMES,
	isLinkTool,
	isRecord,
	LINK_TOOLS,
	type LinkToolName,
	asAssignment,
	type PartialMessage,
	type PartialSnapshot,
	type PartialUpdate,
	MAX_PARTIAL_CHARS,
	type SandboxExecAnswer,
	type SandboxExecRequest,
	type SandboxLoadAnswer,
	type SandboxLoadRequest,
	type SandboxReadAnswer,
	type SandboxReadRequest,
	type StopReason,
	type TurnContext,
	type TurnEnd,
	type TurnModel,
} from "./heph-host-protocol.ts";
import { FOLDER, type LoadedArchive, TurnTools } from "./heph-tools.ts";
import {
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "./pi-agent-sandbox.ts";
import { errorText } from "./pi-error-text.ts";
import { asReasoningEffort, reasoningSetting, registerHephaestusProvider } from "./pi-provider.ts";
import { hasText } from "./pi-text.ts";

type PiMessage = SessionMessageEntry["message"];
type AssistantMessage = Extract<PiMessage, { role: "assistant" }>;
type ToolResultMessage = Extract<PiMessage, { role: "toolResult" }>;

/** A conversation is compacted by Pi past this many tokens, when the model's window allows it. */
const MENTOR_WORKING_TOKENS = 49_152;

/** How often the partial answer may reach the server. */
const PARTIAL_INTERVAL_MS = 250;

const REQUEST_TIMEOUT_MS = 60_000;
/** The longest a command runs, in seconds: the server's bound, which also never passes the turn's deadline. */
const EXEC_TIMEOUT_SECONDS = 60;

/**
 * The codes the server's LLM proxy starts an error message with when the provider sent nothing for its idle bound, or
 * when the provider's breaker refuses calls (`LlmProxyService`). Retrying cannot help, so the turn fails.
 */
const PROVIDER_UNAVAILABLE = ["upstream_stalled", "upstream_paused"] as const;

/**
 * The most model calls one turn makes, retries included. An answer takes a handful. A turn that keeps calling loops on
 * a tool or a failing call, and it would keep one of the server's few turn slots, and every question queued behind it,
 * until its deadline.
 */
const MAX_TURN_MODEL_CALLS = 40;

/** Waits before each repeat of a context read or an end that the server did not answer or answered with a 5xx. */
const RETRY_DELAYS_MS = [1000, 2000, 4000];

/** Pi requires a type for a custom message; the cache breakpoint finds the situation by its `<situation>` text. */
const SITUATION_TYPE = "heph-situation";

/** The stage label when no tool runs, and for a tool no label names. */
export const THINKING_STAGE = "Thinking…";

const WORK_STAGE = "Reading your pull requests and issues…";
const PRACTICE_STAGE = "Reading your practice feedback…";
const REPOSITORY_STAGE = "Reading the repository…";

/** The stage of each context document, by the first segment of its path under `inputs/context/`. */
const DOCUMENT_STAGES: Readonly<Record<string, string>> = {
	user: WORK_STAGE,
	workspace: WORK_STAGE,
	recent_authored_work: WORK_STAGE,
	activity: WORK_STAGE,
	merge_readiness: WORK_STAGE,
	issue: WORK_STAGE,
	results_history: PRACTICE_STAGE,
	delivered_feedback: PRACTICE_STAGE,
	prepared_conversation_feedback: PRACTICE_STAGE,
	practice_profile: PRACTICE_STAGE,
	practice_catalog: PRACTICE_STAGE,
	review_attempts: PRACTICE_STAGE,
	weekly_digest: PRACTICE_STAGE,
	slack_conversations: "Reading your team's Slack…",
	outline_docs: "Reading your team's docs…",
};

/** The stage of the first context document a path or a command names, if any. */
function documentStage(text: string): string | undefined {
	const document = /(?:^|[\s'"/])inputs\/context\/(?<document>[a-z_]+)/u.exec(text)?.groups
		?.document;
	return document === undefined ? undefined : DOCUMENT_STAGES[document];
}

/** Whether a path or a command works on a loaded repository. */
function namesRepository(text: string): boolean {
	return /(?:^|[\s'"/])repos\/|(?:^|[\s;&|(])git\s/u.test(text);
}

/** The argument of a context folder tool that says what it reads. */
const STAGED_ARGUMENT: Readonly<Record<string, string>> = { read: "path", bash: "command" };

/** The stage label of a tool call in flight: what it reads, never its arguments. */
export function turnStage(toolName: string, args: unknown): string {
	if (toolName === "search_conversation") {
		return "Looking back through this conversation…";
	}
	if (toolName === "load_context") {
		return "Loading the repository…";
	}
	const key = STAGED_ARGUMENT[toolName];
	const text = key === undefined || !isRecord(args) ? undefined : args[key];
	if (typeof text !== "string") {
		return THINKING_STAGE;
	}
	return documentStage(text) ?? (namesRepository(text) ? REPOSITORY_STAGE : THINKING_STAGE);
}

/** Writes one line to stderr; a failure among the parts is written as its `failureSummary`. */
function log(...parts: unknown[]) {
	const line = parts
		.map((part) => (typeof part === "string" ? part : failureSummary(part)))
		.join(" ");
	process.stderr.write(`[heph-host ${new Date().toISOString()}] ${line}\n`);
}

/** The cause of a failure because the server answered that this attempt no longer holds its lease (`409`). */
const LEASE_LOST = Symbol("lease lost");

/** The attempt lost its lease: stop working on it, say nothing more. */
export function leaseLost(message: string): Error {
	return new Error(message, { cause: LEASE_LOST });
}

export function isLeaseLost(error: unknown): boolean {
	return error instanceof Error && error.cause === LEASE_LOST;
}

/** The cause of a failure because the server refused a request body over its size bound (`413`). */
const TOO_LARGE = Symbol("too large");

export function tooLarge(message: string): Error {
	return new Error(message, { cause: TOO_LARGE });
}

export function isTooLarge(error: unknown): boolean {
	return error instanceof Error && error.cause === TOO_LARGE;
}

/** The server's answer to a link: admitted, or refused with the server's reason when it gave one. */
export type LinkAnswer = { admitted: true } | { admitted: false; reason: string | null };

/** What a turn reads from and writes to its server, with the turn's credential. */
export interface TurnApi {
	context: () => Promise<TurnContext>;
	/** Reads one page of a file in the tools Pod; a refusal is an answer, not a failure. */
	read: (request: SandboxReadRequest, signal: AbortSignal) => Promise<SandboxReadAnswer>;
	/** Runs one command in the tools Pod. */
	exec: (request: SandboxExecRequest, signal: AbortSignal) => Promise<SandboxExecAnswer>;
	/** Stages one item, such as a repository, into the turn's context folder. */
	load: (request: SandboxLoadRequest, signal: AbortSignal) => Promise<SandboxLoadAnswer>;
	/** Asks the server to admit one id for a tool that shows feedback (`LINK_TOOLS`). */
	link: (tool: LinkToolName, id: string, signal: AbortSignal) => Promise<LinkAnswer>;
	search: (query: Record<string, unknown>, signal: AbortSignal) => Promise<unknown>;
	partial: (update: PartialUpdate) => Promise<void>;
	/** Renews the attempt's lease; a stop that it learns of goes to the api's `onStop`. */
	lease: () => Promise<void>;
	end: (end: TurnEnd) => Promise<void>;
}

/** The server answered a turn request with this status. The message stays what a tool tells the model. */
export class WorkerStatusError extends Error {
	override name = "WorkerStatusError";
	readonly status: number;

	constructor(message: string, status: number, options?: ErrorOptions) {
		super(message, options);
		this.status = status;
	}
}

async function ok(response: Response, what: string): Promise<void> {
	if (!response.ok) {
		const detail = await response.text();
		throw new WorkerStatusError(
			`${what} failed with ${response.status}: ${detail.slice(0, 300)}`,
			response.status,
		);
	}
	await response.body?.cancel();
}

async function json(response: Response, what: string): Promise<unknown> {
	if (!response.ok) {
		await ok(response, what);
	}
	const value: unknown = await response.json();
	return value;
}

/** The cause of a failure that says nothing about the request itself: no answer, or a server error. */
const TRANSIENT = Symbol("transient");

function transient(message: string): Error {
	return new Error(message, { cause: TRANSIENT });
}

/** The cause of a failure because the conversation does not fit the model's window, even after it was shortened. */
const CONTEXT_OVERFLOW = Symbol("context overflow");

function contextOverflow(message: string): Error {
	return new Error(message, { cause: CONTEXT_OVERFLOW });
}

/** The closed kind of a failure (`FAILURE_DETAILS`), never its words. */
function failureKind(failure: unknown): FailureDetail {
	if (failure instanceof Error && failure.cause === CONTEXT_OVERFLOW) {
		return "context_overflow";
	}
	if (failure instanceof WorkerStatusError) {
		return failure.status >= 500 ? "worker_unavailable" : "worker_refused";
	}
	if (failure instanceof Error && failure.cause === TRANSIENT) {
		return "worker_unavailable";
	}
	return isTooLarge(failure) ? "worker_refused" : "host_error";
}

/**
 * What the log says of a failure: its closed kind, and the server's HTTP status when the server answered. Its message,
 * name and stack are left out: each is text that a multi-line message, such as the server's answer, can write into.
 */
export function failureSummary(failure: unknown): string {
	const status =
		failure instanceof WorkerStatusError && Number.isInteger(failure.status)
			? ` status=${failure.status}`
			: "";
	return `${failureKind(failure)}${status}`;
}

/** The answer in a load's `Hephaestus-Load` header, or `null` when it is missing or malformed. */
function loadAbout(header: string | null) {
	try {
		return asLoadAbout(JSON.parse(Buffer.from(header ?? "", "base64url").toString("utf8")));
	} catch {
		return null;
	}
}

/**
 * The size that a response declares, or `null` when it declares none: `Number` reads a missing header as `0`, and a
 * chunked response has none, so a bound on it would bound nothing.
 */
export function declaredSize(headers: Headers): number | null {
	const length = headers.get("content-length");
	if (length === null || !/^\d+$/u.test(length)) {
		return null;
	}
	const size = Number(length);
	return Number.isSafeInteger(size) ? size : null;
}

/** A load's archive as the server sends it: what the model reads about it in a header, then the archive's bytes. */
async function loadedArchive(response: Response, what: string): Promise<LoadedArchive> {
	const { headers, body } = response;
	const about = loadAbout(headers.get("hephaestus-load"));
	const size = declaredSize(headers);
	const sha256 = headers.get("hephaestus-load-sha256") ?? "";
	if (about === null || size === null || !/^[0-9a-f]{64}$/u.test(sha256) || body === null) {
		await body?.cancel();
		throw new Error(`the server sent a malformed ${what} answer`);
	}
	const stream = body;
	async function* bytes() {
		for await (const chunk of stream) {
			yield Buffer.from(chunk);
		}
	}
	return {
		ok: true,
		...about,
		archive: { bytes: bytes(), size, sha256 },
		cancel: async () => {
			await stream.cancel().catch(() => undefined);
		},
	};
}

/** The most bytes of a turn's documents that the host holds: they are JSON documents of one turn, never a checkout. */
const MAX_DOCUMENTS_BYTES = 32 * 1024 * 1024;

/** Why a `409` of a turn endpoint stops the attempt: the server's word, or a lost lease. */
async function stopOf(response: Response): Promise<StopReason> {
	const problem: unknown = await response.json().catch(() => null);
	const stop = isRecord(problem) ? problem.stop : undefined;
	return stop === "developer" || stop === "deadline" ? stop : "lease";
}

/** How long a call waits before it repeats a `401`: a server that just started may not know the issuer's key yet. */
const UNKNOWN_KEY_DELAY_MS = 5000;

/**
 * @param onStop called when a call learns that the attempt must stop, before the call fails
 */
export function httpTurnApi(
	proxyUrl: string,
	turnId: string,
	token: string,
	onStop: (reason: StopReason) => void = () => undefined,
	retryDelaysMs: readonly number[] = RETRY_DELAYS_MS,
	unknownKeyDelayMs = UNKNOWN_KEY_DELAY_MS,
): TurnApi {
	let sentPartial = false;
	async function call(
		method: string,
		path: string,
		body: unknown,
		signal?: AbortSignal,
		timeoutMs: number | null = REQUEST_TIMEOUT_MS,
	): Promise<Response> {
		const timeout = timeoutMs === null ? undefined : AbortSignal.timeout(timeoutMs);
		let response: Response | undefined;
		for (const delayMs of [unknownKeyDelayMs, null]) {
			try {
				response = await fetch(`${proxyUrl}/turn/${turnId}/${path}`, {
					method,
					headers: {
						Authorization: `Bearer ${token}`,
						...(body === undefined ? {} : { "content-type": "application/json" }),
					},
					body: body === undefined ? undefined : JSON.stringify(body),
					signal: AbortSignal.any([signal, timeout].filter((part) => part !== undefined)),
				});
			} catch (error) {
				throw signal?.aborted === true ? error : transient(errorText(error));
			}
			if (response.status !== 401 || delayMs === null) {
				break;
			}
			await response.body?.cancel();
			await sleep(delayMs);
		}
		if (response === undefined) {
			throw transient(`${path} of turn ${turnId} got no answer`);
		}
		if (response.status === 409) {
			const reason = await stopOf(response);
			onStop(reason);
			throw leaseLost(
				reason === "lease"
					? `turn ${turnId} lost its lease`
					: `turn ${turnId} must stop: ${reason}`,
			);
		}
		if (response.status === 413) {
			await response.body?.cancel();
			throw tooLarge(`${path} of turn ${turnId} is larger than the server accepts`);
		}
		// A limit of the gateway passes as a server's 5xx does: the end of a turn must not lose its answer to one.
		if (response.status === 429 || response.status >= 500) {
			const detail = await response.text();
			throw new WorkerStatusError(
				`${path} failed with ${response.status}: ${detail.slice(0, 300)}`,
				response.status,
				{ cause: TRANSIENT },
			);
		}
		return response;
	}

	/** Tools wait within the turn's cancellation and server deadline; a shorter request timeout would abandon a live tool call. */
	async function sandbox<T>(
		endpoint: string,
		request: object,
		signal: AbortSignal,
		parse: (value: unknown) => T | null,
	): Promise<T> {
		const what = `sandbox/${endpoint}`;
		const answer = parse(await json(await call("POST", what, request, signal, null), what));
		if (answer === null) {
			throw new Error(`the server sent a malformed ${what} answer`);
		}
		return answer;
	}

	/**
	 * A call that is safe to repeat, repeated once for each retry delay while the server does not answer it or answers
	 * with a 429 or a 5xx.
	 */
	async function repeated<T>(what: string, send: () => Promise<T>): Promise<T> {
		for (let attempt = 0; ; attempt += 1) {
			try {
				return await send();
			} catch (error) {
				const delay = retryDelaysMs[attempt];
				if (!(error instanceof Error) || error.cause !== TRANSIENT || delay === undefined) {
					throw error;
				}
				log(`${what} for turn ${turnId} is repeated:`, error);
				await sleep(delay);
			}
		}
	}
	const tools = new TurnTools({
		bind: async (signal) => {
			const what = "sandbox/bind";
			const answer = asBindAnswer(await json(await call("POST", what, {}, signal, null), what));
			if (answer === null) {
				throw new Error(`the server sent a malformed ${what} answer`);
			}
			return answer;
		},
		discard: async (id) => ok(await call("POST", "sandbox/discard", { id }), "sandbox/discard"),
		documents: async (signal) => {
			const what = "sandbox/documents";
			const response = await call("GET", what, undefined, signal, null);
			// A failure carries its text. A success keeps its body for the archive: a cancelled clone of it would wait for the
			// archive's bytes to be read, and they never would be.
			if (!response.ok) {
				await ok(response, what);
			}
			const size = declaredSize(response.headers);
			if (size === null || size > MAX_DOCUMENTS_BYTES) {
				await response.body?.cancel();
				throw new Error(`the server sent a ${what} archive without a size within its bound`);
			}
			return Buffer.from(await response.arrayBuffer());
		},
		document: async (request, signal) => sandbox("document", request, signal, asReadAnswer),
		load: async (path, signal) => {
			const what = "sandbox/load";
			const response = await call("POST", what, { path }, signal, null);
			if (response.headers.get("content-type")?.startsWith("application/json") === true) {
				const answer = asLoadAnswer(await json(response, what));
				if (answer === null || answer.ok) {
					throw new Error(`the server sent a malformed ${what} answer`);
				}
				return answer;
			}
			// A failure carries its text. A success keeps its body for the archive: a cancelled clone of it would wait for the
			// archive's bytes to be read, and they never would be.
			if (!response.ok) {
				await ok(response, what);
			}
			return loadedArchive(response, what);
		},
	});
	return {
		async context() {
			const what = "reading the turn context";
			return repeated(what, async () =>
				asTurnContext(await json(await call("GET", "context", undefined), what)),
			);
		},
		async read(request, signal) {
			return tools.read(request, signal);
		},
		async exec(request, signal) {
			return tools.exec(request, signal);
		},
		async load(request, signal) {
			return tools.load(request, signal);
		},
		async link(tool, id, signal) {
			const { path, idKey } = LINK_TOOLS[tool];
			const response = await call("POST", path, { [idKey]: id }, signal);
			if (response.status === 422) {
				const problem: unknown = await response.json().catch(() => null);
				const reason =
					isRecord(problem) && typeof problem.detail === "string" ? problem.detail : null;
				return { admitted: false, reason };
			}
			const value = await json(response, `${tool}(${id})`);
			return isRecord(value) && value[idKey] === id
				? { admitted: true }
				: { admitted: false, reason: null };
		},
		async search(query, signal) {
			return json(await call("POST", "search", query, signal), "search_conversation");
		},
		async partial(update) {
			// The first partial answer times the attempt's first token on the server.
			const path = sentPartial ? "partial" : "partial?first=true";
			await ok(await call("PUT", path, update), "reporting the partial answer");
			sentPartial = true;
		},
		async lease() {
			try {
				await ok(await call("POST", "lease", {}), "renewing the lease");
			} catch (error) {
				// A stop went to `onStop`; the next renewal tries again after any other failure.
				if (!isLeaseLost(error)) {
					log(`renewing the lease of turn ${turnId} failed:`, error);
				}
			}
		},
		async end(end) {
			const what = "ending the turn";
			try {
				await repeated(what, async () => ok(await call("POST", "end", end), what));
			} finally {
				await tools.close();
			}
		},
	};
}

/** The server's context, checked where it crosses into the host. */
function asTurnContext(value: unknown): TurnContext {
	if (
		!isRecord(value) ||
		typeof value.sessionId !== "string" ||
		!Array.isArray(value.entries) ||
		typeof value.prompt !== "string" ||
		typeof value.situation !== "string" ||
		typeof value.systemPrompt !== "string" ||
		!isRecord(value.model) ||
		typeof value.model.apiProtocol !== "string" ||
		typeof value.model.modelId !== "string"
	) {
		throw new Error("the server sent a malformed turn context");
	}
	const { model } = value;
	const entries: unknown[] = value.entries;
	return {
		sessionId: value.sessionId,
		entries,
		prompt: value.prompt,
		situation: value.situation,
		contextFolder: value.contextFolder === true,
		systemPrompt: value.systemPrompt,
		model: {
			apiProtocol: value.model.apiProtocol,
			modelId: value.model.modelId,
			contextWindow: typeof model.contextWindow === "number" ? model.contextWindow : null,
			maxOutputTokens: typeof model.maxOutputTokens === "number" ? model.maxOutputTokens : null,
			reasoningEffort: typeof model.reasoningEffort === "string" ? model.reasoningEffort : null,
		},
	};
}

/**
 * A stored entry is a Pi session entry: an object with a type, an id and a parent. The server's path builder renders
 * them from entries Pi itself wrote, so Pi reads them back as it wrote them.
 */
function isFileEntry(value: unknown): value is FileEntry {
	return (
		isRecord(value) &&
		typeof value.type === "string" &&
		typeof value.id === "string" &&
		(value.parentId === null || typeof value.parentId === "string")
	);
}

function asFileEntries(entries: readonly unknown[]): FileEntry[] {
	const valid: FileEntry[] = [];
	for (const entry of entries) {
		if (!isFileEntry(entry)) {
			throw new Error("the server sent a malformed session entry");
		}
		valid.push(entry);
	}
	return valid;
}

type HostModel = NonNullable<ReturnType<ModelRuntime["getModel"]>>;

/** Creates the model runtime of one turn, with the turn's credential. */
export type ModelFactory = (
	model: TurnModel,
	token: string,
) => Promise<{ modelRuntime: ModelRuntime; model: HostModel }>;

/**
 * The `hephaestus` provider on the server's LLM proxy, registered per turn: each turn's runtime carries that turn's
 * credential and no other. The proxy bills the attempt the credential names.
 */
export function proxyModels(proxyUrl: string, agentDir: string): ModelFactory {
	return async (turnModel, token) => {
		const modelRuntime = await ModelRuntime.create({
			authPath: `${agentDir}/auth.json`,
			modelsPath: null,
			allowModelNetwork: false,
		});
		const config = {
			apiProtocol: turnModel.apiProtocol,
			modelId: turnModel.modelId,
			reasoningEffort: asReasoningEffort(turnModel.reasoningEffort),
			contextWindow: turnModel.contextWindow ?? undefined,
			maxOutputTokens: turnModel.maxOutputTokens ?? undefined,
		};
		if (
			!registerHephaestusProvider(
				modelRuntime,
				config,
				{ LLM_PROXY_URL: proxyUrl, LLM_PROXY_TOKEN: token },
				token,
			)
		) {
			throw new Error("the turn's model is not configured");
		}
		const model = modelRuntime.getModel("hephaestus", turnModel.modelId);
		if (!model) {
			throw new Error(`the turn's model was not registered: ${turnModel.modelId}`);
		}
		return { modelRuntime, model };
	};
}

/**
 * The mentor's working policy in Pi's own terms, for a model whose real window is `window`. Pi compacts once the
 * context passes `window - reserveTokens`, so the reserve sets the trigger; it also caps Pi's summary output, which
 * the model's own output limit caps in turn. The window itself is never changed.
 *
 * `keepRecentTokens` is Pi's native target for recent messages kept unsummarised, not a bound: Pi cuts at a message
 * boundary, keeps a tool call with its results, and may split a long active turn. Every share scales with the window
 * down to the smallest one Heph takes (32,768 tokens): a quarter for the reserve and at most a sixth for the recent
 * messages, so the fixed prompt, a summary and the recent messages leave room to answer.
 */
function mentorCompaction(
	window: number,
	nativeReserve = DEFAULT_COMPACTION_SETTINGS.reserveTokens,
	nativeKeepRecent = DEFAULT_COMPACTION_SETTINGS.keepRecentTokens,
): { reserveTokens: number; keepRecentTokens: number } {
	if (!Number.isInteger(window) || window <= 1) {
		throw new Error(`the model's context window is not a usable size: ${window}`);
	}
	const minimumReserve = Math.min(nativeReserve, Math.floor(window / 4));
	const trigger = Math.min(MENTOR_WORKING_TOKENS, window - minimumReserve);
	return {
		reserveTokens: window - trigger,
		keepRecentTokens: Math.min(nativeKeepRecent, Math.floor(trigger / 2), Math.floor(window / 6)),
	};
}

/**
 * Whether a conversation of `tokens` leaves the model room to answer in its real window: the window less an eighth of
 * it for the answer (4,096 tokens at the smallest window), or less when the model writes less. Pi fits each request's
 * output limit to what is left.
 */
function leavesRoomToAnswer(
	tokens: number,
	model: { contextWindow: number; maxTokens: number },
): boolean {
	return (
		tokens + Math.min(model.maxTokens, Math.floor(model.contextWindow / 8)) < model.contextWindow
	);
}

/** What Pi knows the session holds, or the messages' own estimate when Pi has no usage since a compaction. */
function contextTokens(session: AgentSession): number {
	return (
		session.getContextUsage()?.tokens ??
		session.messages.reduce((sum, message) => sum + estimateTokens(message), 0)
	);
}

/**
 * Writes every summary with thinking off: a reasoning model would spend Pi's summary output cap on thinking and leave
 * the summary unfinished. Pi reads the session's level when it asks for a summary, so the level is `off` for the span
 * of a compaction and restored after it. Pi maps `off` to each API's own control and sends no provider-specific field;
 * a model server that thinks by default keeps thinking (`docs/admin/ai-providers.mdx`). The level goes on the agent's
 * state, not through setThinkingLevel, which would store the change in the conversation.
 */
function summaryThinkingOff(session: AgentSession) {
	let restore: (() => void) | null = null;
	return {
		observe(event: AgentSessionEvent) {
			if (event.type === "compaction_start") {
				const { state } = session.agent;
				const level = state.thinkingLevel;
				state.thinkingLevel = "off";
				restore = () => {
					state.thinkingLevel = level;
				};
			} else if (event.type === "compaction_end") {
				restore?.();
				restore = null;
			}
		},
	};
}

/** Pi's answers to a compaction that has nothing older than its recent messages to summarise. */
const UNSHORTENABLE = /^(?:Nothing to compact|Already compacted)/u;

/**
 * A stored conversation past the working trigger is compacted before its prompt, inside the turn, so the summary is
 * billed to that turn and no later question reads a conversation while it is shortened. Pi's own check before a prompt
 * reads only its last reply's usage; the session's estimate also counts what came after it. A summary that cannot be
 * written leaves the history whole: the prompt goes out while it fits the model's window, and the next turn compacts
 * again. A summary that leaves the conversation past the trigger — Pi keeps a recent tool batch whole — ends the turn
 * rather than sending the history the trigger exists to keep out.
 */
async function compactBeforePrompt(
	session: AgentSession,
	settings: SettingsManager,
	halted: () => boolean,
): Promise<void> {
	const { model } = session;
	if (
		model === undefined ||
		!shouldCompact(contextTokens(session), model.contextWindow, settings.getCompactionSettings())
	) {
		return;
	}
	const result = await session.compact().catch((error: unknown) => {
		// Pi keeps the recent messages whole and finds nothing older to summarise, or its summary call failed. Within
		// the model's window the prompt goes out and the next turn compacts again; failing it would fail every later
		// question of the conversation the same way. Past the window, Pi would read the answer as an overflow and
		// drop it.
		log(UNSHORTENABLE.test(errorText(error)) ? "compaction skipped:" : "compaction failed:", error);
		return null;
	});
	// A stop cancels the summary; the caller ends the turn stopped.
	if (halted()) {
		return;
	}
	if (result === null) {
		const tokens = contextTokens(session);
		if (!leavesRoomToAnswer(tokens, model)) {
			throw contextOverflow(
				`This conversation does not fit the model's context window (${tokens} of ${model.contextWindow} tokens) and cannot get shorter.`,
			);
		}
		return;
	}
	// Past the trigger after a summary is still an answer when the model has room for one: the next turn compacts
	// again. The summary is kept either way (`summariesOf`).
	// Pi estimates the messages alone; the request also carries the fixed prompt.
	const estimated =
		result.estimatedTokensAfter === undefined
			? undefined
			: result.estimatedTokensAfter + Math.ceil(session.systemPrompt.length / 4);
	if (estimated !== undefined && !leavesRoomToAnswer(estimated, model)) {
		throw contextOverflow(
			`This conversation is still too long after shortening it (${estimated} estimated tokens).`,
		);
	}
}

/**
 * The summaries a failed turn still stores: those written before its question, which only shorten the conversation it
 * read. A later one covers the turn's own unstored entries, so it goes with them.
 */
function summariesOf(entries: readonly SessionEntry[]): SessionEntry[] {
	const question = entries.findIndex((entry) => entry.type === "message");
	return (question === -1 ? entries : entries.slice(0, question)).filter(
		(entry) => entry.type === "compaction",
	);
}

/** What the model reads of each tool that shows feedback. */
const LINK_TOOL_WORDS = [
	{
		name: "link_result",
		label: "Link Result",
		noun: "result",
		purpose:
			"one observation: what a practice review recorded about one piece of their work. Use it when you " +
			"discuss that observation.",
	},
	{
		name: "link_problem",
		label: "Link Problem",
		noun: "problem",
		purpose:
			"one problem: one located problem of a Not met observation. Use it when you discuss that one problem.",
	},
	{
		name: "show_feedback",
		label: "Show Feedback",
		noun: "prepared feedback",
		purpose:
			"one prepared piece of feedback listed for this conversation. Use its feedbackId when you raise the " +
			"prepared point.",
	},
] as const satisfies readonly {
	name: LinkToolName;
	label: string;
	noun: string;
	purpose: string;
}[];

/** Text a tool argument holds; objects and arrays are not text. */
function argumentText(value: unknown): string {
	return typeof value === "string" ? value.trim() : "";
}

/** Pi's own `read` and `bash`, for their parameter schemas: Heph's tools keep the contracts models know. */
const PI_READ = createReadToolDefinition(FOLDER);
const PI_BASH = createBashToolDefinition(FOLDER);

const LIMITS = `${DEFAULT_MAX_LINES} lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first)`;

const READ_DESCRIPTION =
	`Read the contents of a file. Supports text files only. Output is truncated to ${LIMITS}. Use offset/limit for ` +
	"large files. When you need the full file, continue with offset until complete. Paths are relative to the " +
	`context folder, ${FOLDER}: start with INDEX.md. read also opens the full output file that bash names.`;

const BASH_DESCRIPTION =
	`Execute a bash command in the current working directory, ${FOLDER}, the context folder. Returns stdout and ` +
	`stderr. Output is truncated to last ${LIMITS}. If truncated, full output is saved to a file that read opens. ` +
	`Optionally provide a timeout in seconds; ${EXEC_TIMEOUT_SECONDS} is the default and the most. The sandbox has ` +
	"git, rg, grep, find, jq, sed, awk and node, and no network. It sees the documents under inputs/ and the loaded " +
	"repositories under repos/, which are read-only; INDEX.md and FIELDS.md open with read only. Write scratch " +
	"files elsewhere under /tmp. Nothing is kept after this answer. Prefer small, targeted commands: rg with a path, " +
	"git log -n 20, git diff --stat before a full diff.";

const LOAD_DESCRIPTION =
	"Load a repository into the context folder, so read and bash can open its files and its Git history. `path` is a " +
	"repository path that INDEX.md lists, such as `repos/github.com/acme/api`, with an optional ref: `@<branch>`, " +
	"`@<sha>`, `@pull/<number>` (GitHub) or `@merge-request/<number>` (GitLab). Without a ref, the default branch is " +
	"loaded. A second load of the same repository replaces the first. The answer gives the directory, the resolved " +
	"ref and sha, and how to cite: `path:line@<short sha>`. At most 5 loads per answer. NOT_VISIBLE means you cannot " +
	"read that repository; do not guess why. UNAVAILABLE means repositories cannot be loaded now: do not retry, and " +
	"say that you cannot read the code.";

/** Pi's `bash` puts a command's status after its output. */
function withStatus(output: string, status: string): string {
	return output === "" ? status : `${output}\n\n${status}`;
}

/** `read`, `bash` and `load_context`: Pi's contracts, run by the server in the tools Pod. */
function contextFolderTools(
	api: TurnApi,
	signalOf: (signal: AbortSignal | undefined) => AbortSignal,
) {
	const read = defineTool({
		name: "read",
		label: PI_READ.label,
		description: READ_DESCRIPTION,
		parameters: PI_READ.parameters,
		constrainedSampling: PI_READ.constrainedSampling,
		execute: async (_toolCallId, params, signal) => {
			const { path, offset, limit } = params;
			const answer = await api.read(
				{
					path,
					...(offset === undefined ? {} : { offset }),
					...(limit === undefined ? {} : { limit }),
				},
				signalOf(signal),
			);
			// Pi treats a thrown error as the tool's failure.
			if (!answer.ok) {
				throw new Error(answer.detail);
			}
			return { content: [{ type: "text", text: answer.text }], details: {} };
		},
	});
	const bash = defineTool({
		name: "bash",
		label: PI_BASH.label,
		description: BASH_DESCRIPTION,
		parameters: PI_BASH.parameters,
		constrainedSampling: PI_BASH.constrainedSampling,
		execute: async (_toolCallId, params, signal) => {
			const { command, timeout } = params;
			if (timeout !== undefined && (!Number.isFinite(timeout) || timeout <= 0)) {
				throw new Error("Invalid timeout: must be a finite number of seconds");
			}
			const seconds = Math.min(timeout ?? EXEC_TIMEOUT_SECONDS, EXEC_TIMEOUT_SECONDS);
			const answer = await api.exec(
				{ command, ...(timeout === undefined ? {} : { timeoutMs: Math.ceil(seconds * 1000) }) },
				signalOf(signal),
			);
			if (!answer.ok) {
				throw new Error(answer.detail);
			}
			if (answer.timedOut || answer.exitCode === null) {
				throw new Error(withStatus(answer.output, `Command timed out after ${seconds} seconds`));
			}
			const { exitCode, truncated, fullOutputPath } = answer;
			const text = answer.output === "" ? "(no output)" : answer.output;
			const details = { exitCode, truncated, fullOutputPath };
			return exitCode === 0
				? { content: [{ type: "text", text }], details }
				: {
						content: [
							{ type: "text", text: withStatus(text, `Command exited with code ${exitCode}`) },
						],
						details,
						isError: true,
					};
		},
	});
	const loadContext = defineTool({
		name: "load_context",
		label: "Load Context",
		description: LOAD_DESCRIPTION,
		parameters: {
			type: "object",
			additionalProperties: false,
			required: ["path"],
			properties: {
				path: {
					type: "string",
					minLength: 1,
					description: "A repository path from INDEX.md, with an optional `@ref`.",
				},
			},
		},
		execute: async (_toolCallId, params, signal) => {
			const answer = await api.load({ path: argumentText(params.path) }, signalOf(signal));
			if (!answer.ok) {
				throw new Error(`${answer.error}: ${answer.detail}`);
			}
			const { path, resolved, note } = answer;
			return {
				content: [
					{
						type: "text",
						text:
							`Loaded ${path} at ${resolved.ref} (${resolved.sha}). Cite its code as ` +
							`\`<file>:<line>${resolved.cite}\`.\n${note}`,
					},
				],
				details: { path, ...resolved },
			};
		},
	});
	return [read, bash, loadContext];
}

function turnTools(api: TurnApi, turnSignal: AbortSignal, contextFolder: boolean) {
	const signalOf = (signal: AbortSignal | undefined) =>
		signal === undefined ? turnSignal : AbortSignal.any([signal, turnSignal]);
	const linkTools = LINK_TOOL_WORDS.map((link) => {
		const { name, noun } = link;
		const { idKey } = LINK_TOOLS[name];
		return defineTool({
			name,
			label: link.label,
			description:
				`Give the developer your feedback about ${link.purpose} \`text\` is shown to them as part of your reply, ` +
				"exactly where you call this, so write it to them in your own words and do not repeat it in your answer.",
			parameters: {
				type: "object",
				additionalProperties: false,
				required: [idKey, "text"],
				properties: {
					[idKey]: { type: "string", minLength: 1 },
					text: { type: "string", minLength: 1 },
				},
			},
			execute: async (
				_toolCallId,
				params,
				signal,
			): Promise<AgentToolResult<Record<string, string>>> => {
				const linkedId = argumentText(params[idKey]);
				const text = argumentText(params.text);
				if (linkedId === "" || text === "") {
					throw new Error(`${name}: ${idKey} and text are required`);
				}
				// The server decides what a reply may show: nothing is shown or reported as shown until it admits this id,
				// and a refusal fails the call.
				const answer = await api.link(name, linkedId, signalOf(signal));
				if (turnSignal.aborted) {
					throw new Error(`${name}: the turn was stopped, so nothing was shown`);
				}
				if (!answer.admitted) {
					const refusal = `${name}: nothing was shown, the server did not admit ${noun} ${linkedId}`;
					throw new Error(answer.reason === null ? refusal : `${refusal}: ${answer.reason}`);
				}
				return {
					content: [
						{ type: "text", text: `Shown to the developer: feedback on ${noun} ${linkedId}` },
					],
					details: { [idKey]: linkedId },
				};
			},
		});
	});
	const searchConversation = defineTool({
		name: "search_conversation",
		label: "Search Conversation",
		description:
			"Search the messages of this conversation and its threads: the developer's, yours and weekly digests. " +
			"`branch` is `main` (the main conversation), `this` (where you are) or `all` (the default). Without a " +
			"query the newest messages come first. Dates are ISO-8601. Cite what you use with its date.",
		parameters: {
			type: "object",
			additionalProperties: false,
			properties: {
				query: {
					type: "string",
					description: "Words to find; quotes and OR work as in a web search.",
				},
				branch: { type: "string", enum: ["main", "this", "all"] },
				before: { type: "string", description: "Only messages written before this instant." },
				after: { type: "string", description: "Only messages written after this instant." },
				limit: { type: "integer", minimum: 1, maximum: 20 },
			},
		},
		execute: async (_toolCallId, params, signal) => {
			const query: Record<string, unknown> = {};
			for (const key of ["query", "branch", "before", "after"] as const) {
				const value = argumentText(params[key]);
				if (value !== "") {
					query[key] = value;
				}
			}
			if (typeof params.limit === "number") {
				query.limit = params.limit;
			}
			const result = await api.search(query, signalOf(signal));
			return { content: [{ type: "text", text: JSON.stringify(result) }], details: {} };
		},
	});
	return [
		...(contextFolder ? contextFolderTools(api, signalOf) : []),
		...linkTools,
		searchConversation,
	];
}

/** The situation (MentorSituation) as a Pi custom message. */
function situationMessage(situation: string) {
	return {
		role: "custom" as const,
		customType: SITUATION_TYPE,
		content: situation,
		display: false,
		timestamp: 0,
	};
}

/** The text a provider request message starts with, whether its content is a string or parts. */
function firstText(message: unknown): string {
	if (!isRecord(message)) {
		return "";
	}
	const { content } = message;
	if (typeof content === "string") {
		return content;
	}
	if (Array.isArray(content)) {
		const first: unknown = content[0];
		return isRecord(first) && typeof first.text === "string" ? first.text : "";
	}
	return "";
}

/**
 * Anthropic marks the last message as a cache breakpoint, which is the situation: it changes every turn, so nothing
 * after the previous breakpoint would be cached. The breakpoint moves to the message before it, the end of the
 * stored conversation. A request without a breakpoint on the situation is left as it is.
 */
export function breakpointBeforeSituation(payload: unknown): unknown {
	if (!isRecord(payload) || !Array.isArray(payload.messages)) {
		return undefined;
	}
	const messages: unknown[] = payload.messages;
	const index = messages.findLastIndex((message) => firstText(message).startsWith("<situation>"));
	const situation = messages[index];
	if (index < 1 || !isRecord(situation) || !Array.isArray(situation.content)) {
		return undefined;
	}
	const parts: unknown[] = situation.content;
	if (!parts.some((part) => isRecord(part) && "cache_control" in part)) {
		return undefined;
	}
	for (const part of parts) {
		if (isRecord(part)) {
			delete part.cache_control;
		}
	}
	const previous = messages[index - 1];
	if (isRecord(previous)) {
		if (typeof previous.content === "string") {
			previous.content = [{ type: "text", text: previous.content }];
		}
		if (Array.isArray(previous.content)) {
			const last: unknown = previous.content.at(-1);
			if (isRecord(last)) {
				last.cache_control = { type: "ephemeral" };
			}
		}
	}
	return payload;
}

/**
 * Only the latest completed turn's `read`, `bash` and `load_context` results stay verbatim. Every result among the
 * first `stored` entries, the ones stored before this turn, that no edit replaced yet becomes a note of what was read
 * and when, so Heph reads current data instead of trusting an old read. Each turn has a new context folder, so a
 * repository loaded in an earlier turn is not there any more. `link_result`, `link_problem`, `show_feedback` and
 * `search_conversation` results stay: they record what was shown and found.
 */
function staleContextResults(
	entries: readonly SessionEntry[],
	stored: number,
): ContextEditEntryDraft[] {
	const calls = toolCalls(entries);
	const edited = new Set<string>();
	for (const entry of entries) {
		if (entry.type === "context_edit") {
			edited.add(entry.targetId);
		}
	}
	const edits: ContextEditEntryDraft[] = [];
	for (const entry of entries.slice(0, stored)) {
		if (!isContextResult(entry) || edited.has(entry.id)) {
			continue;
		}
		const note = contextNote(entry.message, calls.get(entry.message.toolCallId));
		// An end that was too large stored its results as notes already.
		if (JSON.stringify(entry.message.content) !== JSON.stringify(note)) {
			edits.push({ type: "context_edit", targetId: entry.id, replacement: { content: note } });
		}
	}
	return edits;
}

/**
 * The appended entries without what Pi left out of the model's view for good: a model call that failed and that Pi
 * then retried or recovered, and the `context_edit` without a replacement that left it out. Nobody reads them, so they
 * are not stored. An edit that leaves out an entry stored before this turn stays.
 */
function withoutOmitted(entries: readonly SessionEntry[]): SessionEntry[] {
	const appended = new Set(entries.map((entry) => entry.id));
	const omitted = new Set<string>();
	for (const entry of entries) {
		if (
			entry.type === "context_edit" &&
			entry.replacement === null &&
			appended.has(entry.targetId)
		) {
			omitted.add(entry.targetId);
			omitted.add(entry.id);
		}
	}
	return entries.filter((entry) => !omitted.has(entry.id));
}

/** The arguments of each tool call among the entries, by tool call id. */
function toolCalls(entries: readonly SessionEntry[]): Map<string, Record<string, unknown>> {
	const calls = new Map<string, Record<string, unknown>>();
	for (const entry of entries) {
		if (entry.type === "message" && entry.message.role === "assistant") {
			for (const block of entry.message.content) {
				if (block.type === "toolCall") {
					calls.set(block.id, block.arguments);
				}
			}
		}
	}
	return calls;
}

/** The tools whose results copy what the context folder holds. */
const CONTEXT_TOOLS: ReadonlySet<string> = new Set(CONTEXT_FOLDER_TOOL_NAMES);

type ContextResultEntry = SessionMessageEntry & { message: ToolResultMessage };

function isContextResult(entry: SessionEntry): entry is ContextResultEntry {
	return (
		entry.type === "message" &&
		entry.message.role === "toolResult" &&
		CONTEXT_TOOLS.has(entry.message.toolName)
	);
}

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] as const;

/**
 * A time in UTC with its weekday, for example `Mon 2026-10-12 12:41 UTC`. The host has no time zone; the weekday is
 * written so the model never works it out.
 */
function utcTime(timestamp: number): string {
	const time = new Date(timestamp);
	const iso = time.toISOString();
	return `${WEEKDAYS[time.getUTCDay()] ?? ""} ${iso.slice(0, 10)} ${iso.slice(11, 16)} UTC`;
}

/** The most characters of a command that its note repeats. */
const NOTE_COMMAND_CHARS = 200;

/**
 * What stands for a `read`, `bash` or `load_context` result that a later turn reads: what was read, and when. The
 * note names the path or the command, never the output.
 */
function contextNote(
	result: ToolResultMessage,
	args: Record<string, unknown> | undefined,
): { type: "text"; text: string }[] {
	const at = utcTime(result.timestamp);
	const path = argumentText(args?.path) || "an earlier path";
	let text: string;
	if (result.toolName === "bash") {
		const command = argumentText(args?.command);
		const shown =
			command.length > NOTE_COMMAND_CHARS ? `${command.slice(0, NOTE_COMMAND_CHARS)}…` : command;
		text =
			`[bash \`${shown}\`, run at ${at}. This result is from an earlier turn and may be out of date; run it ` +
			"again for current data.]";
	} else if (result.toolName === "load_context") {
		text =
			`[load_context ${path}, loaded at ${at}. Each turn has a new context folder: load it again before you ` +
			"read the repository.]";
	} else {
		text =
			`[read ${path}, read at ${at}. This result is from an earlier turn and may be out of date; read it again ` +
			"for current data.]";
	}
	return [{ type: "text", text }];
}

/**
 * The entries with every `read`, `bash` and `load_context` result replaced by its note: an end that the server
 * refuses for its size is sent again with them. The next turn then reads the notes, which the turn after it would
 * write anyway.
 */
function withContextNotes(entries: readonly SessionEntry[]): SessionEntry[] {
	const calls = toolCalls(entries);
	return entries.map((entry) =>
		isContextResult(entry)
			? {
					...entry,
					message: {
						...entry.message,
						content: contextNote(entry.message, calls.get(entry.message.toolCallId)),
					},
				}
			: entry,
	);
}

/**
 * What the server reads of an assistant message in progress: its text and the feedback it showed. Reasoning, usage and
 * the other tool calls stay on the host.
 */
function partialMessage(message: AssistantMessage): PartialMessage {
	const content: PartialMessage["content"] = [];
	for (const block of message.content) {
		if (block.type === "text") {
			content.push({ type: "text", text: block.text });
		} else if (block.type === "toolCall" && isLinkTool(block.name)) {
			content.push({
				type: "toolCall",
				id: block.id,
				name: block.name,
				arguments: block.arguments,
			});
		}
	}
	return { content };
}

function writesText(message: PartialMessage): boolean {
	return message.content.some((block) => block.type === "text" && block.text !== "");
}

interface PartialStream {
	observe: (event: AgentSessionEvent) => void;
	/** The turn is over: no later event changes the partial answer, and the snapshot not yet sent is dropped. */
	stop: () => void;
	/** Waits for the snapshot in flight, so none reaches the server after the end. */
	settled: () => Promise<void>;
}

/**
 * Sends the whole answer in progress at most every interval, the newest snapshot only. One write is in flight at a
 * time. A snapshot taken during that write waits for it, and a newer snapshot replaces it.
 */
/**
 * How long an unchanged partial answer waits before it is sent again: a reader process that started, reconnected or
 * missed a frame since sees it then, during a long tool call too.
 */
const PARTIAL_RESEND_MS = 10_000;

/** How long a reader that missed a frame waits at most for the next whole snapshot. */
const PARTIAL_WHOLE_EVERY_MS = 2000;

/**
 * The changes of one attempt's partial answers: each changes only what differs from the one before, and a whole
 * snapshot follows at least every `PARTIAL_WHOLE_EVERY_MS`, so a reader that missed a frame catches up. A change whose
 * write failed still advances the base, so readers skip changes until the next whole snapshot.
 */
export function partialUpdates(wholeEveryMs = PARTIAL_WHOLE_EVERY_MS) {
	let sequence = 0;
	let text: string | null = null;
	let wholeAt: number | null = null;
	return (next: string, now: number): PartialUpdate => {
		// Sequences grow with time, so an attempt's later loop never names the base of an earlier one by chance.
		const nextSequence = Math.max(sequence + 1, now);
		let update: PartialUpdate;
		if (text === null || wholeAt === null || now >= wholeAt + wholeEveryMs) {
			update = { sequence: nextSequence, baseSequence: 0, prefix: 0, suffix: 0, text: next };
			wholeAt = now;
		} else {
			const prefix = commonPrefix(text, next);
			const suffix = commonSuffix(text, next, prefix);
			update = {
				sequence: nextSequence,
				baseSequence: sequence,
				prefix,
				suffix,
				text: next.slice(prefix, next.length - suffix),
			};
		}
		sequence = nextSequence;
		text = next;
		return update;
	};
}

// The indices are UTF-16 code units, as Java's `String` counts them in `MentorTurnProgress`.
/* oxlint-disable unicorn/prefer-code-point -- a code unit, not a code point, is what both sides index */
function commonPrefix(a: string, b: string): number {
	const max = Math.min(a.length, b.length);
	let prefix = 0;
	while (prefix < max && a.charCodeAt(prefix) === b.charCodeAt(prefix)) {
		prefix += 1;
	}
	// Never between the two halves of a surrogate pair: the server sends the changed text as UTF-8.
	if (prefix > 0 && isHighSurrogate(a.charCodeAt(prefix - 1))) {
		prefix -= 1;
	}
	return prefix;
}

/** The common suffix that does not overlap the common `prefix` in either text, nor split a surrogate pair. */
function commonSuffix(a: string, b: string, prefix: number): number {
	const max = Math.min(a.length, b.length) - prefix;
	let suffix = 0;
	while (
		suffix < max &&
		a.charCodeAt(a.length - 1 - suffix) === b.charCodeAt(b.length - 1 - suffix)
	) {
		suffix += 1;
	}
	if (suffix > 0 && isLowSurrogate(b.charCodeAt(b.length - suffix))) {
		suffix -= 1;
	}
	return suffix;
}
/* oxlint-enable unicorn/prefer-code-point */

function isHighSurrogate(unit: number): boolean {
	return unit >= 0xd8_00 && unit <= 0xdb_ff;
}

function isLowSurrogate(unit: number): boolean {
	return unit >= 0xdc_00 && unit <= 0xdf_ff;
}

export function partialStream(
	api: TurnApi,
	intervalMs: number,
	resendMs = PARTIAL_RESEND_MS,
): PartialStream {
	/** The turn's finished assistant messages; the one Pi writes now follows them in a snapshot. */
	const written: PartialMessage[] = [];
	let latest: PartialSnapshot | null = null;
	/** The stage of a tool that started in the window not yet sent. */
	let heldStage: string | null = null;
	/** `latest` was not sent yet. */
	let dirty = false;
	let timer: NodeJS.Timeout | undefined;
	/** Sends `latest` again when nothing changed for `resendMs`. */
	let resend: NodeJS.Timeout | undefined;
	let inFlight: Promise<void> | null = null;
	/** When the last write started: the next one starts at least `intervalMs` later. */
	let lastWrite = Number.NEGATIVE_INFINITY;
	let closed = false;

	const encode = partialUpdates();

	async function report(snapshot: PartialSnapshot) {
		const text = JSON.stringify(snapshot);
		if (text.length > MAX_PARTIAL_CHARS) {
			// No reader keeps it, so it is not sent; the answer itself is stored at the end.
			return;
		}
		try {
			await api.partial(encode(text, Date.now()));
		} catch (error) {
			// A lost partial answer is replaced by the next one; a lost lease surfaces at the end.
			log("partial answer not reported:", error);
		}
	}

	/** The snapshot to send next, or null if the server has the newest one or the stream stopped. */
	function take(): PartialSnapshot | null {
		if (!dirty || closed) {
			return null;
		}
		dirty = false;
		return latest;
	}

	/** Starts the next window when a snapshot waits: one interval after the last write started, at the earliest. */
	function schedule(delayMs: number) {
		if (dirty && !closed && timer === undefined && inFlight === null) {
			timer = setTimeout(send, Math.max(0, delayMs));
		}
	}

	async function write(snapshot: PartialSnapshot) {
		lastWrite = Date.now();
		await report(snapshot);
		inFlight = null;
		clearTimeout(resend);
		resend = setTimeout(() => {
			if (!closed && !dirty && latest !== null) {
				dirty = true;
				schedule(0);
			}
		}, resendMs);
		resend.unref();
		// A snapshot that came during the write waits for its window, so a slow write never speeds the cadence up.
		schedule(lastWrite + intervalMs - Date.now());
	}

	function send() {
		clearTimeout(timer);
		timer = undefined;
		heldStage = null;
		const snapshot = take();
		if (snapshot !== null) {
			inFlight = write(snapshot);
		}
	}

	function set(snapshot: PartialSnapshot) {
		// A tool's stage wins the window it started in: a fast read is followed at once by "Thinking…", and the
		// developer would never see what Heph read.
		if (snapshot.activity === "tool") {
			heldStage = snapshot.stage;
		}
		latest = heldStage === null ? snapshot : { ...snapshot, stage: heldStage };
		dirty = true;
		schedule(intervalMs);
	}

	function stop() {
		closed = true;
		clearTimeout(timer);
		clearTimeout(resend);
		timer = undefined;
	}

	return {
		observe(event) {
			if (event.type === "message_update" && event.message.role === "assistant") {
				const writing = partialMessage(event.message);
				set({
					activity: writesText(writing) ? "writing" : "thinking",
					tool: null,
					stage: THINKING_STAGE,
					messages: [...written, writing],
				});
			} else if (
				event.type === "message_end" &&
				event.message.role === "assistant" &&
				event.message.stopReason !== "error"
			) {
				// A failed model call is retried or fails the turn: either way it is not part of the answer.
				written.push(partialMessage(event.message));
			} else if (event.type === "tool_execution_start") {
				set({
					activity: "tool",
					tool: event.toolName,
					stage: turnStage(event.toolName, event.args),
					messages: [...written],
				});
			} else if (event.type === "turn_start") {
				set({ activity: "thinking", tool: null, stage: THINKING_STAGE, messages: [...written] });
			}
		},
		stop,
		async settled() {
			await inFlight;
		},
	};
}

/**
 * Why a turn stops: the server's stop command, a provider the server's proxy found unavailable, or a turn past its
 * model calls.
 */
type HaltReason = StopReason | "stalled" | "calls";

interface TurnState {
	readonly attempt: number;
	readonly controller: AbortController;
	stopReason: HaltReason | null;
	/** The proxy's code for an unavailable provider, for the server log. */
	stallDetail: (typeof PROVIDER_UNAVAILABLE)[number] | null;
	session: AgentSession | null;
}

/** The proxy's code when a failed model call failed because its provider was unavailable, else `null`. */
function providerUnavailable(
	event: AgentSessionEvent,
): (typeof PROVIDER_UNAVAILABLE)[number] | null {
	if (event.type !== "message_end" || event.message.role !== "assistant") {
		return null;
	}
	const { stopReason, errorMessage } = event.message;
	if (stopReason !== "error" || errorMessage === undefined) {
		return null;
	}
	return PROVIDER_UNAVAILABLE.find((code) => errorMessage.includes(code)) ?? null;
}

export interface HostOptions {
	cwd: string;
	agentDir: string;
	/** A stop that one of the turn's calls learns of goes to `onStop`. */
	turnApi: (turnId: string, token: string, onStop: (reason: StopReason) => void) => TurnApi;
	models: ModelFactory;
	partialIntervalMs?: number;
	/** How often a running turn renews its lease. */
	leaseIntervalMs?: number;
}

/** A third of the server's lease, so two renewals may fail before the lease ends. */
const LEASE_INTERVAL_MS = 5000;

export interface HephHost {
	start: (turnId: string, attempt: number, token: string) => void;
	stop: (turnId: string, attempt: number, reason: StopReason) => void;
	/** Resolves once no turn runs. */
	idle: () => Promise<void>;
	/** Resolves once a turn that runs now ends; at once when none runs. */
	settled: () => Promise<void>;
	readonly running: number;
}

/**
 * The loop's turns. Turns of different conversations run at once; the servers never hand out two of one. A later
 * attempt of a turn replaces the attempt that runs here: that one lost its lease, so it is stopped and says nothing
 * more, and the later one starts when it settled.
 */
export function createHephHost(options: HostOptions): HephHost {
	const turns = new Map<string, TurnState>();
	const finished = new Map<string, Promise<void>>();

	function start(turnId: string, attempt: number, token: string) {
		const current = turns.get(turnId);
		if (current !== undefined && current.attempt >= attempt) {
			return;
		}
		if (current !== undefined) {
			stop(turnId, current.attempt, "lease");
		}
		const earlier = finished.get(turnId);
		const state: TurnState = {
			attempt,
			controller: new AbortController(),
			stopReason: null,
			stallDetail: null,
			session: null,
		};
		turns.set(turnId, state);
		const api = options.turnApi(turnId, token, (reason) => stop(turnId, attempt, reason));
		// The lease is the only claim on the turn: a loop that stops renewing it lets the server take the turn back.
		const renewals = setInterval(() => {
			void api.lease();
		}, options.leaseIntervalMs ?? LEASE_INTERVAL_MS);
		const run = (async () => {
			try {
				await earlier;
				await runTurn(turnId, api, token, state);
			} catch (error) {
				log(`turn ${turnId} attempt ${attempt} ended without an answer:`, error);
			} finally {
				clearInterval(renewals);
				// A later attempt that replaced this one keeps its own place.
				if (turns.get(turnId) === state) {
					turns.delete(turnId);
					finished.delete(turnId);
				}
			}
		})();
		finished.set(turnId, run);
	}

	function stop(turnId: string, attempt: number, reason: StopReason) {
		const state = turns.get(turnId);
		if (state === undefined || state.attempt !== attempt) {
			return;
		}
		halt(turnId, state, reason);
	}

	function halt(turnId: string, state: TurnState, reason: HaltReason) {
		state.stopReason ??= reason;
		state.controller.abort();
		const { session } = state;
		if (session !== null) {
			void abortQuietly(turnId, session);
		}
	}

	async function runTurn(turnId: string, api: TurnApi, token: string, state: TurnState) {
		// Once the session exists: its entries, the first `stored` of which the ledger already holds.
		let appended: { manager: SessionManager; stored: number } | null = null;
		// The only write of the turn's entries (MentorTurnService.end).
		const end: Ending = async (outcome, reason, detail, failure) => {
			const turnEntries =
				appended === null
					? []
					: withoutOmitted(appended.manager.getEntries().slice(appended.stored));
			const entries = outcome === "FAILED" ? summariesOf(turnEntries) : turnEntries;
			if (outcome === "FAILED") {
				// A thrown failure's summary is its kind, which is `detail`, with the server's status when it answered.
				const said = failure === undefined ? (detail ?? "host_error") : failureSummary(failure);
				log(`turn ${turnId} attempt ${state.attempt} failed (${reason ?? "ERROR"}): ${said}`);
			}
			// An end without a detail sends `null`, as the server reads it.
			const ending: TurnEnd = { outcome, entries, reason, detail: detail ?? null };
			try {
				await api.end(ending);
			} catch (error) {
				if (!isTooLarge(error)) {
					throw error;
				}
				log(
					`the end of turn ${turnId} is too large; it is sent again with its context folder results as notes`,
				);
				await api.end({ ...ending, entries: withContextNotes(entries) });
			}
		};
		try {
			const context = await api.context();
			if (stopped(state)) {
				await endStopped(state, end);
				return;
			}
			const { modelRuntime, model } = await options.models(context.model, token);
			const entries = asFileEntries(context.entries);
			const stored = entries.length;
			const sessionManager = SessionManager.inMemory(
				options.cwd,
				{ id: context.sessionId },
				entries,
			);
			const settingsManager = SettingsManager.inMemory(
				{
					compaction: { enabled: true, ...mentorCompaction(model.contextWindow) },
					// As a practice review repeats (PiRuntimeFactory); the turn's deadline still bounds them.
					retry: { enabled: true, maxRetries: 5, baseDelayMs: 4000 },
				},
				SANDBOX_SETTINGS_MANAGER_OPTIONS,
			);
			const turnExtension: ExtensionFactory = (pi) => {
				pi.on("context", (event) => ({
					messages: [...event.messages, situationMessage(context.situation)],
				}));
				pi.on("before_provider_request", (event) => breakpointBeforeSituation(event.payload));
				// Pi checks its threshold before each model request inside the turn. A summary there turns what the
				// turn just read, or an older turn it splits, into a memory beside the answer in progress. The host
				// compacts before each prompt instead (compactBeforePrompt, Pi's `manual`); overflow still compacts.
				pi.on("session_before_compact", (event) =>
					event.reason === "threshold" ? { cancel: true } : undefined,
				);
				pi.on("agent_before_settle", (event) =>
					event.outcome === "completed"
						? { entries: staleContextResults(sessionManager.getEntries(), stored) }
						: undefined,
				);
			};
			const resourceLoader = new DefaultResourceLoader({
				cwd: options.cwd,
				agentDir: options.agentDir,
				settingsManager,
				...SANDBOX_RESOURCE_LOADER_OPTIONS,
				noSkills: true,
				noPromptTemplates: true,
				noThemes: true,
				extensionFactories: [turnExtension],
				systemPromptOverride: () => context.systemPrompt,
			});
			await resourceLoader.reload();
			const { session: agent } = await createAgentSession({
				cwd: options.cwd,
				agentDir: options.agentDir,
				modelRuntime,
				model,
				thinkingLevel: reasoningSetting(asReasoningEffort(context.model.reasoningEffort))
					.thinkingLevel,
				resourceLoader,
				sessionManager,
				settingsManager,
				customTools: turnTools(api, state.controller.signal, context.contextFolder),
				tools: [...(context.contextFolder ? CONTEXT_FOLDER_TOOL_NAMES : []), ...HEPH_TOOL_NAMES],
			});
			state.session = agent;
			appended = { manager: sessionManager, stored };
			const partials = partialStream(api, options.partialIntervalMs ?? PARTIAL_INTERVAL_MS);
			const summaries = summaryThinkingOff(agent);
			let calls = 0;
			const unsubscribe = agent.subscribe((event) => {
				summaries.observe(event);
				partials.observe(event);
				if (event.type === "message_end" && event.message.role === "assistant") {
					calls += 1;
					// Only a turn that goes on to a tool stops here. An answer, a cut answer or a failed call at the limit
					// ends the turn as it would anyway, with its own reason.
					if (calls >= MAX_TURN_MODEL_CALLS && event.message.stopReason === "toolUse") {
						halt(turnId, state, "calls");
					}
				}
				const unavailable = providerUnavailable(event);
				if (unavailable !== null) {
					state.stallDetail ??= unavailable;
					halt(turnId, state, "stalled");
				}
			});
			try {
				if (!stopped(state)) {
					await compactBeforePrompt(agent, settingsManager, () => stopped(state));
				}
				if (!stopped(state)) {
					await agent.prompt(context.prompt);
				}
				// A tool call that a stop left without its result needs nothing here: Pi's transformMessages
				// answers it in this attempt, and later turns read the answer without it
				// (MentorContextPathBuilder.keepCutOffAnswer).
				await agent.waitForIdle();
				partials.stop();
				await partials.settled();
				if (stopped(state)) {
					await endStopped(state, end);
					return;
				}
				const failure = unanswered(withoutOmitted(sessionManager.getEntries().slice(stored)));
				if (failure !== null) {
					await end("FAILED", "ERROR", failure);
					return;
				}
				await end("DONE", null);
			} finally {
				partials.stop();
				unsubscribe();
				agent.dispose();
			}
		} catch (error) {
			// A stop that a call learned of failed that call too: the turn still ends, as stopped. A lost lease is no stop.
			if (state.stopReason === "lease" || (isLeaseLost(error) && !stopped(state))) {
				// Also the answer to a repeated end whose earlier try the server stored.
				log(`turn ${turnId} is no longer this attempt's to end; abandoning it`);
				return;
			}
			await endQuietly(turnId, async () =>
				stopped(state) ? endStopped(state, end) : end("FAILED", "ERROR", failureKind(error), error),
			);
		}
	}

	return {
		start,
		stop,
		async idle() {
			await Promise.all(finished.values());
		},
		async settled() {
			await Promise.race([
				...finished.values(),
				...(finished.size === 0 ? [Promise.resolve()] : []),
			]);
		},
		get running() {
			return turns.size;
		},
	};
}

/**
 * Read through a call: a test of `state.stopReason` narrows it for the rest of the function, which would make the same
 * check after an await dead to the type system while a stop can arrive during it.
 */
function stopped(state: TurnState): boolean {
	return state.stopReason !== null;
}

async function abortQuietly(turnId: string, session: AgentSession): Promise<void> {
	try {
		await session.abort();
	} catch (error) {
		log(`stopping turn ${turnId} failed:`, error);
	}
}

async function endQuietly(turnId: string, ending: () => Promise<void>): Promise<void> {
	try {
		await ending();
	} catch (error) {
		log(`turn ${turnId} could not be ended:`, error);
	}
}

/**
 * `failure`, when a thrown failure ended the turn, goes to the host's log as its `failureSummary` (kind and server
 * status), not to the server.
 */
type Ending = (
	outcome: TurnEnd["outcome"],
	reason: TurnEnd["reason"],
	detail?: FailureDetail | null,
	failure?: unknown,
) => Promise<void>;

/**
 * A stop by the developer keeps what Heph wrote; a deadline, a silent model or too many model calls fail the turn; a
 * lost lease says nothing. The call limit is not a time limit: the turn fails as an error, so the developer does not
 * read that Hephaestus ran out of time.
 */
async function endStopped(state: TurnState, end: Ending): Promise<void> {
	if (state.stopReason === "developer") {
		await end("STOPPED", null);
	} else if (state.stopReason === "deadline") {
		await end("FAILED", "DEADLINE", "deadline");
	} else if (state.stopReason === "stalled") {
		await end("FAILED", "STALLED", state.stallDetail ?? "provider_unavailable");
	} else if (state.stopReason === "calls") {
		await end("FAILED", "ERROR", "call_limit");
	}
}

/**
 * Why the turn ends without an answer, or `null` when the last assistant message the turn stores completed with text.
 * An answer Pi left out of the model's view, as it does with one it reads as a context overflow, is not stored and
 * so does not count.
 */
const CONTROL_TOKENS = /<\|[^|<>]*\|>/gu;

function unanswered(entries: readonly SessionEntry[]): FailureDetail | null {
	const last = entries.findLast(
		(entry) => entry.type === "message" && entry.message.role === "assistant",
	);
	if (last?.type !== "message" || last.message.role !== "assistant") {
		return "no_answer";
	}
	// The provider's error message stays out: it can quote the request. The server's proxy logs the call's status.
	const { stopReason } = last.message;
	if (stopReason === "error") {
		return "model_error";
	}
	if (stopReason === "aborted") {
		return "model_aborted";
	}
	// An answer with no words would end the turn done and show the developer nothing. Chat-template control tokens
	// (`<|channel|>`) a model leaks are not words either.
	if (!hasWords(last.message)) {
		if (stopReason === "length") {
			return "output_limit";
		}
		// The prompt asks for nothing more after an answer whose last call showed what it cites.
		return answeredBeforeLinks(entries, last) ? null : "empty_answer";
	}
	return null;
}

function hasWords(message: AssistantMessage): boolean {
	return message.content.some(
		(part) => part.type === "text" && part.text.replaceAll(CONTROL_TOKENS, "").trim() !== "",
	);
}

/**
 * Whether this turn's messages before `last` showed the developer an answer and then only feedback that the server
 * admitted: words, or the `text` of an admitted link call, with no other tool and no refused link after them.
 */
function answeredBeforeLinks(entries: readonly SessionEntry[], last: SessionEntry): boolean {
	const before = entries.slice(0, entries.indexOf(last));
	const refused = new Set(
		before.flatMap((entry) =>
			entry.type === "message" && entry.message.role === "toolResult" && entry.message.isError
				? [entry.message.toolCallId]
				: [],
		),
	);
	for (const entry of before.toReversed()) {
		if (entry.type !== "message" || entry.message.role !== "assistant") {
			continue;
		}
		const calls = entry.message.content.filter((part) => part.type === "toolCall");
		if (calls.some((call) => !isLinkTool(call.name) || refused.has(call.id))) {
			return false;
		}
		if (hasWords(entry.message) || calls.some((call) => argumentText(call.arguments.text) !== "")) {
			return true;
		}
	}
	return false;
}

/** How long the loop waits after each failed pull in a row: the gateway Service can have no ready server for a while. */
const PULL_DELAYS_MS = [500, 1000, 2000, 4000];

/** A pull waits on the server for up to 20 seconds; this bounds a pull that never ends. */
const PULL_TIMEOUT_MS = 60_000;

/**
 * Pulls turn attempts from the servers' gateway and runs each on `host`, while it has room for `turns`, until `signal`
 * aborts. A pull that started ends first: the server claimed its turn already, and an answer the loop drops costs the
 * turn its lease. `beat` runs at each pull, so the liveness probe sees the loop work.
 */
export async function pullTurns(
	proxyUrl: string,
	secret: string,
	host: Pick<HephHost, "start" | "settled" | "running">,
	turns: number,
	signal: AbortSignal,
	beat: () => void = () => undefined,
	delaysMs: readonly number[] = PULL_DELAYS_MS,
): Promise<void> {
	let failures = 0;
	const failed = async (why: unknown) => {
		log("pulling a turn failed:", why);
		const delayMs = delaysMs[Math.min(failures, delaysMs.length - 1)] ?? 0;
		failures += 1;
		await sleep(delayMs);
	};
	while (!signal.aborted) {
		beat();
		if (host.running >= turns) {
			// The bound's timer outlives a wait that a turn ended, so it must not hold the process open.
			await Promise.race([host.settled(), sleep(PULL_TIMEOUT_MS, undefined, { ref: false })]);
			continue;
		}
		let response: Response;
		try {
			response = await fetch(`${proxyUrl}/turn-assignments`, {
				method: "POST",
				headers: { Authorization: `Bearer ${secret}` },
				signal: AbortSignal.timeout(PULL_TIMEOUT_MS),
			});
		} catch (error) {
			await failed(withCause(error));
			continue;
		}
		if (response.status === 200) {
			failures = 0;
			const assignment = asAssignment(await response.json().catch(() => null));
			if (assignment === null) {
				log("ignoring a malformed assignment");
			} else {
				host.start(assignment.turnId, assignment.attempt, assignment.token);
			}
			continue;
		}
		await response.body?.cancel();
		if (response.status === 204) {
			failures = 0;
		} else {
			// 503: that server stops; 401: that server does not know the loop's credential yet, or it is wrong.
			await failed(response.status);
		}
	}
}

/**
 * A failed fetch's text with its cause's code, such as `ECONNREFUSED` or `ENOTFOUND`: undici's own message is only
 * "fetch failed". The cause names an address, never a credential.
 */
function withCause(error: unknown): string {
	const cause = error instanceof Error ? error.cause : undefined;
	const code = isRecord(cause) && typeof cause.code === "string" ? cause.code : undefined;
	return code === undefined ? errorText(error) : `${errorText(error)} (${code})`;
}

/** The loop's own files: Pi's agent directory, and the files its probes read. */
const LOOP_DIR = "/tmp/heph-loop";

async function main() {
	const proxyUrl = process.env.LLM_PROXY_URL;
	const secret = process.env.HEPHAESTUS_CONVERSATION_LOOP_SECRET;
	const turns = Number(process.env.HEPHAESTUS_MENTOR_TURN_CAPACITY);
	if (!hasText(proxyUrl) || !hasText(secret) || !Number.isSafeInteger(turns) || turns < 1) {
		// A plain line: the log's error summary names only a kind of failure.
		log(
			"LLM_PROXY_URL, HEPHAESTUS_CONVERSATION_LOOP_SECRET and HEPHAESTUS_MENTOR_TURN_CAPACITY are required",
		);
		process.exit(1);
	}
	const agentDir = process.env.PI_CODING_AGENT_DIR ?? `${LOOP_DIR}/.pi`;
	mkdirSync(agentDir, { recursive: true });
	const host = createHephHost({
		cwd: LOOP_DIR,
		agentDir,
		turnApi: (turnId, turnToken, onStop) => httpTurnApi(proxyUrl, turnId, turnToken, onStop),
		models: proxyModels(proxyUrl, agentDir),
	});
	const stopping = new AbortController();
	// On SIGTERM the loop pulls no more and ends its turns; the grace period is the turn deadline and the stop time.
	process.once("SIGTERM", () => {
		log("stopping: no more pulls; waiting for the running turns");
		rmSync(`${LOOP_DIR}/ready`, { force: true });
		stopping.abort();
	});
	writeFileSync(`${LOOP_DIR}/ready`, "");
	await pullTurns(proxyUrl, secret, host, turns, stopping.signal, () =>
		writeFileSync(`${LOOP_DIR}/alive`, ""),
	);
	await host.idle();
	log("stopped");
	process.exit(0);
}

if (import.meta.main) {
	process.on("unhandledRejection", (error) => log("unhandled rejection:", error));
	// Node's own report would print the message; log it in the host's own format instead.
	process.on("uncaughtException", (error) => {
		log("uncaught exception:", error);
		process.exit(1);
	});
	process.once("SIGINT", () => process.exit(0));
	try {
		await main();
	} catch (error) {
		log(error);
		process.exit(1);
	}
}
