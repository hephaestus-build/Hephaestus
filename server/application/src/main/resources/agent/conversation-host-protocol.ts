// conversation-host-protocol.ts — what a conversation sandbox's turn and the server exchange.
//
// A turn runs as its conversation sandbox's `TURN` run (turn.ts). Everything it reads or writes goes over HTTP to the
// run's routes, with the run's claim. The Java counterparts:
//   /internal/agent/runs/<runId>/context        → AgentRunController, TurnSandboxRunKind.context
//   /internal/agent/runs/<runId>/turn/*         → ConversationTurnController
//   /internal/agent/runs/<runId>/turn/sandbox/* → ConversationTurnSandboxController

/** The tools that work in the turn's context folder. Hephaestus has them only in a turn whose context sets `contextFolder`. */
export const CONTEXT_FOLDER_TOOL_NAMES = ["read", "bash", "load_context"] as const;

/** The tools Hephaestus has in every turn. */
export const CONVERSATION_TOOL_NAMES = [
	"link_result",
	"link_problem",
	"show_feedback",
	"search_conversation",
] as const;

/**
 * The tools that show the developer feedback, each with the turn endpoint that admits its id and the field that
 * carries the id both ways. A tool shows its `text` only after a `200` that echoes the id.
 */
export const LINK_TOOLS = {
	link_result: { path: "link-result", idKey: "resultId" },
	link_problem: { path: "link-problem", idKey: "problemId" },
	show_feedback: { path: "show-feedback", idKey: "feedbackId" },
} as const;

export type LinkToolName = keyof typeof LINK_TOOLS;

export function isLinkTool(name: string): name is LinkToolName {
	return Object.hasOwn(LINK_TOOLS, name);
}

/** A refusal the model reads: `detail` is worded for it. */
export interface SandboxRefusal {
	ok: false;
	error: string;
	detail: string;
}

export type SandboxLoadAnswer =
	| {
			ok: true;
			/** The staged directory, without `@ref`. */
			path: string;
			resolved: { ref: string; sha: string; cite: string };
			/** What was staged and how to cite it, worded for the model. */
			note: string;
	  }
	| SandboxRefusal;

function asRefusal(value: Record<string, unknown>): SandboxRefusal | null {
	return value.ok === false && typeof value.error === "string" && typeof value.detail === "string"
		? { ok: false, error: value.error, detail: value.detail }
		: null;
}

/** The worker's answer to a load, or `null` when it is malformed. */
export function asLoadAnswer(value: unknown): SandboxLoadAnswer | null {
	if (!isRecord(value)) {
		return null;
	}
	if (value.ok !== true) {
		return asRefusal(value);
	}
	const { path, resolved, note } = value;
	if (
		typeof path !== "string" ||
		typeof note !== "string" ||
		!isRecord(resolved) ||
		typeof resolved.ref !== "string" ||
		typeof resolved.sha !== "string" ||
		typeof resolved.cite !== "string"
	) {
		return null;
	}
	return {
		ok: true,
		path,
		resolved: { ref: resolved.ref, sha: resolved.sha, cite: resolved.cite },
		note,
	};
}

/** Why a turn attempt stops: the developer stopped it, its deadline passed, or the attempt lost its lease. */
export type StopReason = "developer" | "deadline" | "lease";

/** Narrowing predicate for parsed JSON, used instead of a cast. */
export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/** The model a turn runs with, as the worker admitted it. */
export interface TurnModel {
	apiProtocol: string;
	modelId: string;
	contextWindow: number | null;
	maxOutputTokens: number | null;
	reasoningEffort: string | null;
}

/** `GET context`. */
export interface TurnContext {
	/** The Pi session id: the conversation id. */
	sessionId: string;
	/** The Pi session entries before the turn, as the server rendered them. */
	entries: unknown[];
	/** The turn's question, passed to `prompt()` verbatim. */
	prompt: string;
	model: TurnModel;
	/** The transient message appended after the path on every model request; never stored. */
	situation: string;
	/** The worker serves `read`, `bash` and `load_context` for this turn. The turn reads a missing field as `false`. */
	contextFolder: boolean;
	/** Hephaestus's system prompt for the turn's session. */
	systemPrompt: string;
	/** The documents archive from the same build as the situation receipt, encoded as Base64. */
	documents: string;
}

/** What a partial answer keeps of one assistant message: its text, and the calls that show feedback. */
export interface PartialMessage {
	content: (
		| { type: "text"; text: string }
		| { type: "toolCall"; id: string; name: LinkToolName; arguments: unknown }
	)[];
}

/** The whole answer in progress, which every partial answer replaces. */
export interface PartialSnapshot {
	activity: "thinking" | "writing" | "tool";
	/** The tool running, while `activity` is `tool`. */
	tool: string | null;
	/** What Hephaestus is doing, in the developer's words: the one label both surfaces show while the answer is not written. */
	stage: string;
	/** Every assistant message of the turn so far; the last one may still be written. */
	messages: PartialMessage[];
}

/**
 * `PUT turn/partial`: one change of the partial answer's JSON text, as `ConversationTurnProgress.Update` reads it. A
 * change puts `text` between the first `prefix` and the last `suffix` UTF-16 code units of the snapshot whose sequence
 * is `baseSequence`; a `baseSequence` of 0 makes `text` the whole snapshot. The loop is the attempt's one writer, so it
 * makes each change, and any server forwards it.
 */
export interface PartialUpdate {
	sequence: number;
	baseSequence: number;
	prefix: number;
	suffix: number;
	text: string;
}

/** The longest partial answer, in UTF-16 code units of its JSON text, that readers keep (`ConversationTurnProgress`). */
export const MAX_PARTIAL_CHARS = 256 * 1024;

/**
 * What kind of failure ended a failed attempt, for the operator. The set is closed, so an end never carries the words
 * of the model provider, a tool or the server into a log; the server logs no other detail
 * (`ConversationTurnController.FAILURE_DETAILS`). The two proxy codes are those of `PROVIDER_UNAVAILABLE`.
 */
export const FAILURE_DETAILS = [
	"deadline",
	"call_limit",
	"upstream_stalled",
	"upstream_paused",
	"provider_unavailable",
	"model_error",
	"model_aborted",
	"no_answer",
	"empty_answer",
	"output_limit",
	"context_overflow",
	"worker_refused",
	"worker_unavailable",
	"host_error",
] as const;

export type FailureDetail = (typeof FAILURE_DETAILS)[number];

/**
 * `POST turn/end`, the only write of the turn's entries. A failed attempt's reason is worded for the developer by
 * the worker.
 */
export interface TurnEnd {
	outcome: "DONE" | "STOPPED" | "FAILED";
	/**
	 * Every Pi entry the turn appended after the stored path, in order. A failed attempt sends only the summaries
	 * written before its question, so the next turn starts from them.
	 */
	entries: unknown[];
	/** `STALLED`: the server's LLM proxy found the model provider silent or paused (`LlmProxyService`). */
	reason: "ERROR" | "DEADLINE" | "STALLED" | null;
	/** For the operator: the kind of failure of a failed attempt. */
	detail: FailureDetail | null;
	/** The writer's last published snapshot, which may have reached readers on another replica. */
	lastPartial: PartialSnapshot | null;
}

/** What the worker says of a load's archive, in its `Hephaestus-Load` header: the answer and the summary command. */
interface SandboxLoadAbout {
	path: string;
	resolved: { ref: string; sha: string; cite: string };
	note: string;
	summary: string[];
}

/** The `Hephaestus-Load` header's answer, or `null` when it is malformed. */
export function asLoadAbout(value: unknown): SandboxLoadAbout | null {
	if (!isRecord(value) || value.ok !== true) {
		return null;
	}
	const { path, resolved, note, summary } = value;
	if (
		typeof path !== "string" ||
		typeof note !== "string" ||
		!isRecord(resolved) ||
		typeof resolved.ref !== "string" ||
		typeof resolved.sha !== "string" ||
		typeof resolved.cite !== "string" ||
		!Array.isArray(summary) ||
		!summary.every((part): part is string => typeof part === "string")
	) {
		return null;
	}
	return {
		path,
		resolved: { ref: resolved.ref, sha: resolved.sha, cite: resolved.cite },
		note,
		summary,
	};
}
