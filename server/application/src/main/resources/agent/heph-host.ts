// heph-host.ts — the Heph host: one long-lived Node process per worker that runs many Heph turns at once.
// Nothing about a conversation outlives its turn here: each turn rebuilds its Pi session from the ledger.
// docs/contributor/unified-pi-runtime.mdx § The Heph host and § Turn lifecycle have the protocol.

import { readFileSync } from "node:fs";
import { setTimeout as sleep } from "node:timers/promises";

import {
	type AgentSession,
	type AgentSessionEvent,
	type AgentToolResult,
	type ContextEditEntryDraft,
	createAgentSession,
	DEFAULT_COMPACTION_SETTINGS,
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
	HEPH_TOOL_NAMES,
	FETCH_CONTEXT_PATHS,
	isFetchContextKey,
	isRecord,
	MAX_DETAIL_CHARS,
	parseCommand,
	type PartialMessage,
	type PartialSnapshot,
	type StopReason,
	type TurnContext,
	type TurnEnd,
	type TurnModel,
} from "./heph-host-protocol.ts";
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

// The workspace literals below are pinned by SandboxLayoutSyncTest; keep them quoted strings.
const WORKSPACE_ROOT = "/workspace";
// SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH
const MENTOR_SYSTEM_PROMPT_PATH = "agent/mentor/system.md";
// SandboxLayout.PI_AGENT_DIR
const PI_AGENT_DIR = "/workspace/.pi";

/** A conversation is compacted by Pi past this many tokens, when the model's window allows it. */
const MENTOR_WORKING_TOKENS = 49_152;

/**
 * Max characters of one context document a tool result carries. A document past it would crowd the model's window.
 * MentorContextKeys.FETCH_CONTEXT_MAX_CHARS is the same number on the server.
 */
const FETCH_CONTEXT_MAX_CHARS = 200_000;

/** How often the partial answer may reach the worker. */
const PARTIAL_INTERVAL_MS = 250;

const REQUEST_TIMEOUT_MS = 60_000;

/**
 * The codes the worker's LLM proxy starts an error message with when the provider sent nothing for its idle bound, or
 * when the provider's breaker refuses calls (`LlmProxyService`). Retrying cannot help, so the turn fails.
 */
const PROVIDER_UNAVAILABLE = ["upstream_stalled", "upstream_paused"] as const;

/** Waits before each repeat of a context read or an end that the worker did not answer or answered with a 5xx. */
const RETRY_DELAYS_MS = [1000, 2000, 4000];

/** Pi requires a type for a custom message; the cache breakpoint finds the situation by its `<situation>` text. */
const SITUATION_TYPE = "heph-situation";

const ITEM_RESOURCES =
	"For one item, copy the `resource` value exactly as a context file gives it: a pull request's detail (reviewers, " +
	"commits, changed files, threads), an issue's detail (body, comments, linked issues), an observation's evidence, " +
	"and the practice-review runs of a piece of work from its `reviewsResource`. " +
	"Never build one from a pull request number or another id.";

/** What each fixed context path holds, for the `fetch_context` description; every path has one line. */
export const FETCH_CONTEXT_INDEX: Readonly<Record<string, string>> = {
	"inputs/context/user.json": "counts of their work this week and last week",
	"inputs/context/workspace.json": "issues assigned to them and reviews they owe others",
	"inputs/context/recent_authored_work.json":
		"their pull requests and issues, dated, most recently active first",
	"inputs/context/activity.json":
		"what they did in the last 14 days, dated: work opened, merged or closed, reviews and comments they wrote, commits",
	"inputs/context/merge_readiness.json":
		"their open pull requests in detail: checks, reviews, threads, merge state",
	"inputs/context/review_attempts.json":
		"runs of Hephaestus's automated practice review on their work, not reviews by people",
	"inputs/context/observations_history.json":
		"what those practice reviews observed, and reviews people left on their pull requests",
	"inputs/context/delivered_feedback.json": "practice feedback Hephaestus delivered to them",
	"inputs/context/prepared_conversation_feedback.json":
		"practice feedback queued to raise in this conversation",
	"inputs/context/practice_profile.json":
		"their standing in each practice, as their Practice profile shows it",
	"inputs/context/practice_catalog.json": "the workspace's practices and their guidance",
	"inputs/context/slack_conversations.json":
		"Slack threads they took part in, in channels that allow it",
	"inputs/context/outline_docs.json": "team documentation that matches their message, with links",
	"inputs/context/weekly_digest.json": "in a thread on a weekly digest, that digest",
};

/** The stage label when no tool runs, and for a tool no label names. */
export const THINKING_STAGE = "Thinking…";

const WORK_STAGE = "Reading your pull requests and issues…";
const PRACTICE_STAGE = "Reading your practice feedback…";

/** The stage of each context document, by the first segment of its path under `inputs/context/`. */
const DOCUMENT_STAGES: Readonly<Record<string, string>> = {
	user: WORK_STAGE,
	workspace: WORK_STAGE,
	recent_authored_work: WORK_STAGE,
	activity: WORK_STAGE,
	merge_readiness: WORK_STAGE,
	issue: WORK_STAGE,
	observations_history: PRACTICE_STAGE,
	delivered_feedback: PRACTICE_STAGE,
	prepared_conversation_feedback: PRACTICE_STAGE,
	practice_profile: PRACTICE_STAGE,
	practice_catalog: PRACTICE_STAGE,
	review_attempts: PRACTICE_STAGE,
	weekly_digest: PRACTICE_STAGE,
	slack_conversations: "Reading your team's Slack…",
	outline_docs: "Reading your team's docs…",
};

/** The stage label of a tool call in flight: what it reads, never its arguments. */
export function turnStage(toolName: string, args: unknown): string {
	if (toolName === "search_conversation") {
		return "Looking back through this conversation…";
	}
	if (toolName !== "fetch_context" || !isRecord(args) || typeof args.path !== "string") {
		return THINKING_STAGE;
	}
	const document = /^inputs\/context\/(?<document>[a-z_]+)/u.exec(args.path)?.groups?.document;
	return (document === undefined ? undefined : DOCUMENT_STAGES[document]) ?? THINKING_STAGE;
}

function log(...parts: unknown[]) {
	const line = parts.map((part) => (typeof part === "string" ? part : errorText(part))).join(" ");
	process.stderr.write(`[heph-host ${new Date().toISOString()}] ${line}\n`);
}

/** The worker answered that this attempt no longer holds its lease: stop working on it, say nothing more. */
export class LeaseLostError extends Error {
	override name = "LeaseLostError";
}

/** The cause of a failure because the worker refused a request body over its size bound (`413`). */
const TOO_LARGE = Symbol("too large");

export function tooLarge(message: string): Error {
	return new Error(message, { cause: TOO_LARGE });
}

export function isTooLarge(error: unknown): boolean {
	return error instanceof Error && error.cause === TOO_LARGE;
}

/** What a turn reads from and writes to its worker, with the turn's credential. */
export interface TurnApi {
	context: () => Promise<TurnContext>;
	fetchContext: (path: string, signal: AbortSignal) => Promise<unknown>;
	/** False when the worker does not let Heph show feedback about this observation now. */
	linkObservation: (observationId: string, signal: AbortSignal) => Promise<boolean>;
	search: (query: Record<string, unknown>, signal: AbortSignal) => Promise<unknown>;
	partial: (snapshot: PartialSnapshot) => Promise<void>;
	end: (end: TurnEnd) => Promise<void>;
}

async function ok(response: Response, what: string): Promise<void> {
	if (!response.ok) {
		const detail = await response.text();
		throw new Error(`${what} failed with ${response.status}: ${detail.slice(0, 300)}`);
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

export function httpTurnApi(
	proxyUrl: string,
	turnId: string,
	token: string,
	retryDelaysMs: readonly number[] = RETRY_DELAYS_MS,
): TurnApi {
	async function call(
		method: string,
		path: string,
		body: unknown,
		signal?: AbortSignal,
	): Promise<Response> {
		const timeout = AbortSignal.timeout(REQUEST_TIMEOUT_MS);
		let response: Response;
		try {
			response = await fetch(`${proxyUrl}/turn/${turnId}/${path}`, {
				method,
				headers: {
					Authorization: `Bearer ${token}`,
					...(body === undefined ? {} : { "content-type": "application/json" }),
				},
				body: body === undefined ? undefined : JSON.stringify(body),
				signal: signal === undefined ? timeout : AbortSignal.any([signal, timeout]),
			});
		} catch (error) {
			throw signal?.aborted === true ? error : transient(errorText(error));
		}
		if (response.status === 409) {
			await response.body?.cancel();
			throw new LeaseLostError(`turn ${turnId} lost its lease`);
		}
		if (response.status === 413) {
			await response.body?.cancel();
			throw tooLarge(`${path} of turn ${turnId} is larger than the worker accepts`);
		}
		if (response.status >= 500) {
			const detail = await response.text();
			throw transient(`${path} failed with ${response.status}: ${detail.slice(0, 300)}`);
		}
		return response;
	}

	/**
	 * A call that is safe to repeat, repeated once for each retry delay while the worker does not answer it or answers
	 * with a 5xx.
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
	return {
		async context() {
			const what = "reading the turn context";
			return repeated(what, async () =>
				asTurnContext(await json(await call("GET", "context", undefined), what)),
			);
		},
		async fetchContext(path, signal) {
			const response = await call("POST", "fetch-context", { path }, signal);
			if (response.status === 422) {
				const problem: unknown = await response.json().catch(() => null);
				throw new Error(
					isRecord(problem) && typeof problem.detail === "string"
						? problem.detail
						: `fetch_context: "${path}" was refused`,
				);
			}
			const value = await json(response, `fetch_context(${path})`);
			return isRecord(value) ? value.content : null;
		},
		async linkObservation(observationId, signal) {
			const response = await call("POST", "link-observation", { observationId }, signal);
			if (response.status === 422) {
				await response.body?.cancel();
				return false;
			}
			const value = await json(response, `link_observation(${observationId})`);
			return isRecord(value) && value.observationId === observationId;
		},
		async search(query, signal) {
			return json(await call("POST", "search", query, signal), "search_conversation");
		},
		async partial(snapshot) {
			await ok(await call("PUT", "partial", snapshot), "reporting the partial answer");
		},
		async end(end) {
			const what = "ending the turn";
			await repeated(what, async () => ok(await call("POST", "end", end), what));
		},
	};
}

/** The worker's context, checked where it crosses into the host. */
function asTurnContext(value: unknown): TurnContext {
	if (
		!isRecord(value) ||
		typeof value.sessionId !== "string" ||
		!Array.isArray(value.entries) ||
		typeof value.prompt !== "string" ||
		typeof value.situation !== "string" ||
		!isRecord(value.model) ||
		typeof value.model.apiProtocol !== "string" ||
		typeof value.model.modelId !== "string"
	) {
		throw new Error("the worker sent a malformed turn context");
	}
	const { model } = value;
	const entries: unknown[] = value.entries;
	return {
		sessionId: value.sessionId,
		entries,
		prompt: value.prompt,
		situation: value.situation,
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
			throw new Error("the worker sent a malformed session entry");
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
 * The `hephaestus` provider on the worker's LLM proxy, registered per turn: each turn's runtime carries that turn's
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
		log(
			UNSHORTENABLE.test(errorText(error)) ? "compaction skipped:" : "compaction failed:",
			errorText(error),
		);
		return null;
	});
	// A stop cancels the summary; the caller ends the turn stopped.
	if (halted()) {
		return;
	}
	if (result === null) {
		const tokens = contextTokens(session);
		if (!leavesRoomToAnswer(tokens, model)) {
			throw new Error(
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
		throw new Error(
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

/** The document a context resource holds, as the model reads it: a string as sent, anything else serialised once. */
function contextText(content: unknown): string {
	if (content === null || content === undefined) {
		return "null";
	}
	return typeof content === "string" ? content : JSON.stringify(content);
}

/** A `fetch_context` result, cut at the cap with the cut named in a part of its own. */
function fetchContextResult(content: unknown): AgentToolResult<{
	length: number;
	truncated: boolean;
	originalLength: number;
}> {
	let text = contextText(content);
	const originalLength = text.length;
	const truncated = text.length > FETCH_CONTEXT_MAX_CHARS;
	if (truncated) {
		text = text.slice(0, FETCH_CONTEXT_MAX_CHARS);
	}
	const parts: { type: "text"; text: string }[] = [{ type: "text", text }];
	if (truncated) {
		parts.push({
			type: "text",
			text: `[truncated ${originalLength - FETCH_CONTEXT_MAX_CHARS} chars from response]`,
		});
	}
	return { content: parts, details: { length: text.length, truncated, originalLength } };
}

/** Text a tool argument holds; objects and arrays are not text. */
function argumentText(value: unknown): string {
	return typeof value === "string" ? value.trim() : "";
}

function turnTools(api: TurnApi, turnSignal: AbortSignal) {
	const signalOf = (signal: AbortSignal | undefined) =>
		signal === undefined ? turnSignal : AbortSignal.any([signal, turnSignal]);
	const fetchContext = defineTool({
		name: "fetch_context",
		label: "Fetch Context",
		description: `Read Hephaestus's stored context about the developer's work by its exact path. Read the relevant one before you answer about their work, reviews, issues, Slack or documentation. Fixed paths:\n${FETCH_CONTEXT_PATHS.map(
			(path) => `- ${path}: ${FETCH_CONTEXT_INDEX[path] ?? ""}`,
		).join("\n")}\n${ITEM_RESOURCES}`,
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
		execute: async (_toolCallId, params, signal) => {
			const path = argumentText(params.path);
			// Pi treats a thrown error as the tool's failure; a returned object never fails the call.
			if (!isFetchContextKey(path)) {
				throw new Error(`fetch_context: "${path}" is not a context resource. ${ITEM_RESOURCES}`);
			}
			return fetchContextResult(await api.fetchContext(path, signalOf(signal)));
		},
	});
	const linkObservation = defineTool({
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
		execute: async (_toolCallId, params, signal) => {
			const observationId = argumentText(params.observationId);
			const text = argumentText(params.text);
			if (observationId === "" || text === "") {
				throw new Error("link_observation: observationId and text are required");
			}
			// The worker decides which observation a reply may show feedback about: nothing is shown or reported as
			// shown until it admits this one, and a refusal fails the call.
			const admitted = await api.linkObservation(observationId, signalOf(signal));
			if (turnSignal.aborted) {
				throw new Error("link_observation: the turn was stopped, so nothing was shown");
			}
			if (!admitted) {
				throw new Error(
					`link_observation: nothing was shown, observation ${observationId} was not admitted`,
				);
			}
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
	return [fetchContext, linkObservation, searchConversation];
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
 * Only the latest completed turn's `fetch_context` results stay verbatim. Every result among the first `stored`
 * entries, the ones stored before this turn, that no edit replaced yet becomes a note of what was read and when, so
 * Heph fetches current data instead of trusting an old read. `link_observation` and `search_conversation` results
 * stay: they record what was shown and found.
 */
function staleFetchResults(
	entries: readonly SessionEntry[],
	stored: number,
): ContextEditEntryDraft[] {
	const paths = fetchPaths(entries);
	const edited = new Set<string>();
	for (const entry of entries) {
		if (entry.type === "context_edit") {
			edited.add(entry.targetId);
		}
	}
	const edits: ContextEditEntryDraft[] = [];
	for (const entry of entries.slice(0, stored)) {
		if (!isFetchResult(entry) || edited.has(entry.id)) {
			continue;
		}
		const note = readNote(paths.get(entry.message.toolCallId), entry.message.timestamp);
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

/** The path each `fetch_context` call among the entries read, by tool call id. */
function fetchPaths(entries: readonly SessionEntry[]): Map<string, string> {
	const paths = new Map<string, string>();
	for (const entry of entries) {
		if (entry.type === "message" && entry.message.role === "assistant") {
			for (const block of entry.message.content) {
				if (block.type === "toolCall" && block.name === "fetch_context") {
					paths.set(block.id, argumentText(block.arguments.path));
				}
			}
		}
	}
	return paths;
}

type FetchResultEntry = SessionMessageEntry & { message: ToolResultMessage };

function isFetchResult(entry: SessionEntry): entry is FetchResultEntry {
	return (
		entry.type === "message" &&
		entry.message.role === "toolResult" &&
		entry.message.toolName === "fetch_context" &&
		!entry.message.isError
	);
}

/** What stands for a `fetch_context` result that a later turn reads: which resource was read, and when. */
function readNote(path: string | undefined, timestamp: number): { type: "text"; text: string }[] {
	const readAt = new Date(timestamp).toISOString();
	return [
		{
			type: "text",
			text:
				`[fetch_context ${path ?? "an earlier resource"}, read at ${readAt}. This result is from an earlier ` +
				"turn and may be out of date; fetch it again for current data.]",
		},
	];
}

/**
 * The entries with every `fetch_context` result replaced by its read note: an end that the worker refuses for its size
 * is sent again with them. The next turn then reads the notes, which the turn after it would write anyway.
 */
function withReadNotes(entries: readonly SessionEntry[]): SessionEntry[] {
	const paths = fetchPaths(entries);
	return entries.map((entry) =>
		isFetchResult(entry)
			? {
					...entry,
					message: {
						...entry.message,
						content: readNote(paths.get(entry.message.toolCallId), entry.message.timestamp),
					},
				}
			: entry,
	);
}

/** Runs `task` once everything queued before it ran, so a queue keeps its order. */
async function after(previous: Promise<void>, task: () => Promise<void>): Promise<void> {
	await previous;
	await task();
}

/**
 * What the worker reads of an assistant message in progress: its text and the feedback it showed. Reasoning, usage and
 * the other tool calls stay on the host.
 */
function partialMessage(message: AssistantMessage): PartialMessage {
	const content: PartialMessage["content"] = [];
	for (const block of message.content) {
		if (block.type === "text") {
			content.push({ type: "text", text: block.text });
		} else if (block.type === "toolCall" && block.name === "link_observation") {
			content.push({
				type: "toolCall",
				id: block.id,
				name: "link_observation",
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
	/** The turn is over: no later event changes the partial answer. */
	stop: () => void;
	/** Waits for the snapshot in flight, so none reaches the worker after the end. */
	settled: () => Promise<void>;
}

/** Sends the whole answer in progress at most every interval, the newest snapshot only. */
function partialStream(api: TurnApi, intervalMs: number): PartialStream {
	/** The turn's finished assistant messages; the one Pi writes now follows them in a snapshot. */
	const written: PartialMessage[] = [];
	let latest: PartialSnapshot | null = null;
	/** The stage of a tool that started in the window not yet sent. */
	let heldStage: string | null = null;
	let dirty = false;
	let timer: NodeJS.Timeout | undefined;
	let queue: Promise<void> = Promise.resolve();
	let closed = false;

	async function report(snapshot: PartialSnapshot) {
		try {
			await api.partial(snapshot);
		} catch (error) {
			// A lost partial answer is replaced by the next one; a lost lease surfaces at the end.
			log("partial answer not reported:", error);
		}
	}

	function send() {
		clearTimeout(timer);
		timer = undefined;
		heldStage = null;
		const snapshot = latest;
		if (!dirty || closed || snapshot === null) {
			return;
		}
		dirty = false;
		queue = after(queue, async () => report(snapshot));
	}

	function set(snapshot: PartialSnapshot) {
		// A tool's stage wins the window it started in: a fast read is followed at once by "Thinking…", and the
		// developer would never see what Heph read.
		if (snapshot.activity === "tool") {
			heldStage = snapshot.stage;
		}
		latest = heldStage === null ? snapshot : { ...snapshot, stage: heldStage };
		dirty = true;
		if (timer === undefined && !closed) {
			timer = setTimeout(send, intervalMs);
		}
	}

	function stop() {
		closed = true;
		clearTimeout(timer);
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
			await queue;
		},
	};
}

/** Why a turn stops: the worker's stop command, or a provider the worker's proxy found unavailable. */
type HaltReason = StopReason | "stalled";

interface TurnState {
	readonly attempt: number;
	readonly controller: AbortController;
	stopReason: HaltReason | null;
	/** What the proxy said about an unavailable provider, for the worker log. */
	stallDetail: string | null;
	session: AgentSession | null;
}

/** The proxy's message when a failed model call failed because its provider was unavailable, else `null`. */
function providerUnavailable(event: AgentSessionEvent): string | null {
	if (event.type !== "message_end" || event.message.role !== "assistant") {
		return null;
	}
	const { stopReason, errorMessage } = event.message;
	return stopReason === "error" &&
		errorMessage !== undefined &&
		PROVIDER_UNAVAILABLE.some((code) => errorMessage.includes(code))
		? errorMessage
		: null;
}

export interface HostOptions {
	cwd: string;
	agentDir: string;
	systemPrompt: string;
	turnApi: (turnId: string, token: string) => TurnApi;
	models: ModelFactory;
	partialIntervalMs?: number;
}

export interface HephHost {
	start: (turnId: string, attempt: number, token: string) => void;
	stop: (turnId: string, attempt: number, reason: StopReason) => void;
	/** Resolves once no turn runs. */
	idle: () => Promise<void>;
	readonly running: number;
}

/**
 * The host's turns. Turns of different conversations run at once; the worker never starts two of one. A later attempt
 * of a turn replaces the attempt that runs here: that one lost its lease, so it is stopped and says nothing more, and
 * the later one starts when it settled.
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
		const run = (async () => {
			try {
				await earlier;
				await runTurn(turnId, options.turnApi(turnId, token), token, state);
			} catch (error) {
				log(`turn ${turnId} attempt ${attempt} ended without an answer:`, error);
			} finally {
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
		const end: Ending = async (outcome, reason, detail = null) => {
			const turnEntries =
				appended === null
					? []
					: withoutOmitted(appended.manager.getEntries().slice(appended.stored));
			const entries = outcome === "FAILED" ? summariesOf(turnEntries) : turnEntries;
			if (outcome === "FAILED") {
				log(`turn ${turnId} attempt ${state.attempt} failed (${reason ?? "ERROR"}):`, detail ?? "");
			}
			const ending: TurnEnd = {
				outcome,
				entries,
				reason,
				detail: detail === null ? null : detail.slice(0, MAX_DETAIL_CHARS),
			};
			try {
				await api.end(ending);
			} catch (error) {
				if (!isTooLarge(error)) {
					throw error;
				}
				log(
					`the end of turn ${turnId} is too large; it is sent again with its documents as read notes`,
				);
				await api.end({ ...ending, entries: withReadNotes(entries) });
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
				pi.on("agent_before_settle", (event) =>
					event.outcome === "completed"
						? { entries: staleFetchResults(sessionManager.getEntries(), stored) }
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
				systemPromptOverride: () => options.systemPrompt,
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
				customTools: turnTools(api, state.controller.signal),
				tools: [...HEPH_TOOL_NAMES],
			});
			state.session = agent;
			appended = { manager: sessionManager, stored };
			const partials = partialStream(api, options.partialIntervalMs ?? PARTIAL_INTERVAL_MS);
			const summaries = summaryThinkingOff(agent);
			const unsubscribe = agent.subscribe((event) => {
				summaries.observe(event);
				partials.observe(event);
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
			if (error instanceof LeaseLostError || state.stopReason === "lease") {
				// Also the answer to a repeated end whose earlier try the worker stored.
				log(`turn ${turnId} is no longer this attempt's to end; abandoning it`);
				return;
			}
			await endQuietly(turnId, async () =>
				stopped(state) ? endStopped(state, end) : end("FAILED", "ERROR", errorText(error)),
			);
		}
	}

	return {
		start,
		stop,
		async idle() {
			await Promise.all(finished.values());
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

type Ending = (
	outcome: TurnEnd["outcome"],
	reason: TurnEnd["reason"],
	detail?: string | null,
) => Promise<void>;

/**
 * A stop by the developer keeps what Heph wrote; a deadline or a silent model fails the turn; a lost lease says
 * nothing.
 */
async function endStopped(state: TurnState, end: Ending): Promise<void> {
	if (state.stopReason === "developer") {
		await end("STOPPED", null);
	} else if (state.stopReason === "deadline") {
		await end("FAILED", "DEADLINE", "the worker stopped the turn at its deadline");
	} else if (state.stopReason === "stalled") {
		await end(
			"FAILED",
			"STALLED",
			state.stallDetail ?? "the worker's LLM proxy found the model provider unavailable",
		);
	}
}

/**
 * Why the turn ends without an answer, or `null` when the last assistant message the turn stores completed with text.
 * An answer Pi left out of the model's view, as it does with one it reads as a context overflow, is not stored and
 * so does not count.
 */
const CONTROL_TOKENS = /<\|[^|<>]*\|>/gu;

function unanswered(entries: readonly SessionEntry[]): string | null {
	const last = entries.findLast(
		(entry) => entry.type === "message" && entry.message.role === "assistant",
	);
	if (last?.type !== "message" || last.message.role !== "assistant") {
		return "the model wrote no answer";
	}
	const { stopReason, errorMessage, content } = last.message;
	if (stopReason === "error" || stopReason === "aborted") {
		return errorMessage ?? `the model's answer ended with ${stopReason}`;
	}
	// An answer with no words would end the turn done and show the developer nothing. Chat-template control tokens
	// (`<|channel|>`) a model leaks are not words either.
	if (
		!content.some(
			(part) => part.type === "text" && part.text.replace(CONTROL_TOKENS, "").trim() !== "",
		)
	) {
		return stopReason === "length"
			? "the model reached its output limit before it wrote an answer"
			: "the model wrote an empty answer";
	}
	return null;
}

/**
 * Node's WebSocket client (undici) takes request headers. The DOM library this tree is type-checked against types the
 * second argument as subprotocols only, so the constructor is restated with the init Node accepts.
 */
// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- the lib types the browser's constructor, not Node's.
const NodeWebSocket = WebSocket as unknown as new (
	url: URL,
	init: { headers: Record<string, string> },
) => WebSocket;

/**
 * Opens the command channel to the worker and runs its commands on `host` until the channel closes. The worker pings
 * the channel; Node's WebSocket answers each ping itself.
 */
export async function runCommands(
	runtimeUrl: string,
	token: string,
	host: Pick<HephHost, "start" | "stop">,
): Promise<void> {
	const url = new URL(`${runtimeUrl}/commands`);
	url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
	const socket = new NodeWebSocket(url, { headers: { Authorization: `Bearer ${token}` } });
	socket.addEventListener("open", () => log("command channel open"));
	socket.addEventListener("message", (event) => {
		const command = typeof event.data === "string" ? parseCommand(event.data) : null;
		if (command === null) {
			log("ignoring a malformed command");
		} else if (command.type === "start") {
			host.start(command.turnId, command.attempt, command.token);
		} else {
			host.stop(command.turnId, command.attempt, command.reason);
		}
	});
	const closed = Promise.withResolvers<undefined>();
	socket.addEventListener("close", () => closed.resolve(undefined));
	socket.addEventListener("error", () => closed.resolve(undefined));
	await closed.promise;
}

async function main() {
	const runtimeUrl = process.env.SANDBOX_RUNTIME_URL;
	const proxyUrl = process.env.LLM_PROXY_URL;
	const token = process.env.LLM_PROXY_TOKEN;
	if (!hasText(runtimeUrl) || !hasText(proxyUrl) || !hasText(token)) {
		throw new Error("SANDBOX_RUNTIME_URL, LLM_PROXY_URL and LLM_PROXY_TOKEN are required");
	}
	const agentDir = process.env.PI_CODING_AGENT_DIR ?? PI_AGENT_DIR;
	const host = createHephHost({
		cwd: WORKSPACE_ROOT,
		agentDir,
		systemPrompt: readFileSync(`${WORKSPACE_ROOT}/${MENTOR_SYSTEM_PROMPT_PATH}`, "utf8"),
		turnApi: (turnId, turnToken) => httpTurnApi(proxyUrl, turnId, turnToken),
		models: proxyModels(proxyUrl, agentDir),
	});
	await runCommands(runtimeUrl, token, host);
	// The worker removes a host whose channel closed and starts a new one; this one has nothing left to do.
	log("command channel closed; exiting");
	process.exit(1);
}

if (import.meta.main) {
	process.on("unhandledRejection", (error) => log("unhandled rejection:", error));
	for (const signal of ["SIGTERM", "SIGINT"] as const) {
		process.on(signal, () => process.exit(0));
	}
	try {
		await main();
	} catch (error) {
		log(error);
		process.exit(1);
	}
}
