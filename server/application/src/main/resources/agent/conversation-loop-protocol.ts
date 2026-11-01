/** The tools that work in the turn's context folder. Heph has them only in a turn whose context sets `contextFolder`. */
export const CONTEXT_FOLDER_TOOL_NAMES = ["read", "bash", "load_context"] as const;

/** The tools Heph has in every turn. */
export const HEPH_TOOL_NAMES = [
	"link_result",
	"link_problem",
	"show_feedback",
	"search_conversation",
] as const;

/**
 * The tools that show the developer feedback, each with the worker endpoint that admits its id and the field that
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

/** `POST turn/<id>/sandbox/read`: one page of one file in the turn sandbox, paged by the worker. */
export interface SandboxReadRequest {
	path: string;
	offset?: number;
	limit?: number;
}

/** `POST turn/<id>/sandbox/exec`: one command in the turn sandbox, as `bash -c`, with cwd `/workspace`. */
export interface SandboxExecRequest {
	command: string;
	timeoutMs?: number;
}

/** `POST turn/<id>/sandbox/load`: one item staged into the context folder, such as `repos/github.com/acme/api@pull/42`. */
export interface SandboxLoadRequest {
	path: string;
}

/** A refusal the model reads: `detail` is worded for it. */
export interface SandboxRefusal {
	ok: false;
	error: string;
	detail: string;
}

/** The page as Pi's `read` returns a text file, with Pi's continuation notices. */
export type SandboxReadAnswer = { ok: true; text: string } | SandboxRefusal;

/** The output's tail as Pi's `bash` returns it; the worker appends Pi's notice when it cut the output. */
export type SandboxExecAnswer =
	| {
			ok: true;
			/** `null` only when the worker killed the command after its timeout. */
			exitCode: number | null;
			output: string;
			truncated: boolean;
			timedOut: boolean;
			fullOutputPath: string | null;
	  }
	| SandboxRefusal;

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

/** The worker's answer to a read, or `null` when it is malformed. */
export function asReadAnswer(value: unknown): SandboxReadAnswer | null {
	if (!isRecord(value)) {
		return null;
	}
	return value.ok === true && typeof value.text === "string"
		? { ok: true, text: value.text }
		: asRefusal(value);
}

/** The worker's answer to a command, or `null` when it is malformed. */
export function asExecAnswer(value: unknown): SandboxExecAnswer | null {
	if (!isRecord(value)) {
		return null;
	}
	if (value.ok !== true) {
		return asRefusal(value);
	}
	const { exitCode, output, truncated, timedOut, fullOutputPath } = value;
	if (
		(exitCode !== null && !Number.isInteger(exitCode)) ||
		typeof output !== "string" ||
		typeof truncated !== "boolean" ||
		typeof timedOut !== "boolean" ||
		(fullOutputPath !== null && typeof fullOutputPath !== "string")
	) {
		return null;
	}
	return {
		ok: true,
		exitCode: typeof exitCode === "number" ? exitCode : null,
		output,
		truncated,
		timedOut,
		fullOutputPath,
	};
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

/** Why the worker stops a turn: the developer stopped it, its deadline passed, or the attempt lost its lease. */
export type StopReason = "developer" | "deadline" | "lease";

/** Narrowing predicate for values received across a trust boundary. */
export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

export interface Assignment {
	turnId: string;
	attempt: number;
	token: string;
	renewAfterMs: number;
	deadline: string;
}

export function asAssignment(value: unknown): Assignment {
	if (
		!isRecord(value) ||
		typeof value.turnId !== "string" ||
		value.turnId === "" ||
		typeof value.attempt !== "number" ||
		!Number.isSafeInteger(value.attempt) ||
		value.attempt < 1 ||
		typeof value.token !== "string" ||
		value.token === "" ||
		typeof value.renewAfterMs !== "number" ||
		!Number.isSafeInteger(value.renewAfterMs) ||
		value.renewAfterMs < 100 ||
		typeof value.deadline !== "string" ||
		!Number.isFinite(Date.parse(value.deadline))
	) {
		throw new Error("The gateway sent a malformed conversation assignment");
	}
	return {
		turnId: value.turnId,
		attempt: value.attempt,
		token: value.token,
		renewAfterMs: value.renewAfterMs,
		deadline: value.deadline,
	};
}

/** The model a turn runs with, as the worker admitted it. */
export interface TurnModel {
	apiProtocol: string;
	modelId: string;
	contextWindow: number | null;
	maxOutputTokens: number | null;
	reasoningEffort: string | null;
}

/** `GET turn/<id>/context`. */
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
	/** Hephaestus's instructions, which the worker composes for the release it runs. */
	systemPrompt: string;
	/** The worker serves `read`, `bash` and `load_context` for this turn. The host reads a missing field as `false`. */
	contextFolder: boolean;
}

/** What a partial answer keeps of one assistant message: its text, and the calls that show feedback. */
export interface PartialMessage {
	content: (
		| { type: "text"; text: string }
		| { type: "toolCall"; id: string; name: LinkToolName; arguments: unknown }
	)[];
}

/** `PUT turn/<id>/partial`: the whole answer in progress, replaced by every snapshot. */
export interface PartialSnapshot {
	activity: "thinking" | "writing" | "tool";
	/** The tool running, while `activity` is `tool`. */
	tool: string | null;
	/** What Heph is doing, in the developer's words: the one label both surfaces show while the answer is not written. */
	stage: string;
	/** Every assistant message of the turn so far; the last one may still be written. */
	messages: PartialMessage[];
}

/**
 * What kind of failure ended a failed attempt, for the operator. The set is closed, so an end never carries the words
 * of the model provider, a tool or the worker into a log; the worker logs no other detail
 * (`HephTurnController.FAILURE_DETAILS`). The two proxy codes are those of `PROVIDER_UNAVAILABLE`.
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
 * `POST turn/<id>/end`, the only write of the turn's entries. A failed attempt's reason is worded for the developer by
 * the worker.
 */
export interface TurnEnd {
	outcome: "DONE" | "STOPPED" | "FAILED";
	/**
	 * Every Pi entry the turn appended after the stored path, in order. A failed attempt sends only the summaries
	 * written before its question, so the next turn starts from them.
	 */
	entries: unknown[];
	/** `STALLED`: the worker's LLM proxy found the model provider silent or paused (`LlmProxyService`). */
	reason: "ERROR" | "DEADLINE" | "STALLED" | null;
	/** For the operator: the kind of failure of a failed attempt. */
	detail: FailureDetail | null;
}
