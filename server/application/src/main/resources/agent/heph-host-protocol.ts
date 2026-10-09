// heph-host-protocol.ts — what the Heph host and its worker exchange.
//
// Commands arrive over one WebSocket the host opens to its gateway session
// (`${SANDBOX_RUNTIME_URL}/commands`). Everything a turn reads or writes goes over HTTP to
// `${LLM_PROXY_URL}/turn/<turnId>/...` with the turn's own credential. The Java counterparts:
//   commands                → HephTurnDispatcher, HephHostChannelConfiguration
//   turn endpoints          → HephTurnController
//   fetch_context whitelist → MentorContextKeys (MentorContextKeysRunnerMirrorTest keeps them equal)

export const HEPH_TOOL_NAMES = [
	"fetch_context",
	"link_observation",
	"search_conversation",
] as const;

// Context-key whitelist for the fetch_context tool. Java remains authoritative and
// re-checks against MentorContextKeys.ALLOWED_OUTPUT_KEYS.
const FETCH_CONTEXT_ALLOWED = new Set([
	"inputs/context/workspace.json",
	"inputs/context/user.json",
	"inputs/context/practice_catalog.json",
	"inputs/context/observations_history.json",
	"inputs/context/delivered_feedback.json",
	"inputs/context/recent_authored_work.json",
	"inputs/context/activity.json",
	"inputs/context/practice_profile.json",
	"inputs/context/merge_readiness.json",
	"inputs/context/review_attempts.json",
	"inputs/context/slack_conversations.json",
	"inputs/context/prepared_conversation_feedback.json",
	"inputs/context/weekly_digest.json",
	"inputs/context/outline_docs.json",
]);
// One authored pull request by artifactId, in any stored state, read on demand; mirrors MergeReadinessContentSource.ITEM_KEY.
const MERGE_READINESS_ITEM = /^inputs\/context\/merge_readiness\/\d{1,18}\.json$/u;
// One listed observation's evidence by its canonical id; mirrors ObservationHistoryContentSource.DETAIL_KEY.
const OBSERVATION_DETAIL =
	/^inputs\/context\/observations_history\/[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}\.json$/u;
// One issue the developer authored or is assigned to, by artifactId; mirrors IssueDetailSource.ITEM_KEY.
const ISSUE_ITEM = /^inputs\/context\/issue\/\d{1,18}\.json$/u;
// One authored pull request's or issue's retained reviews by artifactId; mirrors ReviewAttemptsContentSource.WORK_KEY.
const REVIEW_ATTEMPTS_ITEM =
	/^inputs\/context\/review_attempts\/(?:pull_request|issue)\/\d{1,18}\.json$/u;

export const FETCH_CONTEXT_PATHS: readonly string[] = [...FETCH_CONTEXT_ALLOWED];

export function isFetchContextKey(key: string): boolean {
	return (
		FETCH_CONTEXT_ALLOWED.has(key) ||
		MERGE_READINESS_ITEM.test(key) ||
		ISSUE_ITEM.test(key) ||
		OBSERVATION_DETAIL.test(key) ||
		REVIEW_ATTEMPTS_ITEM.test(key)
	);
}

/** Why the worker stops a turn: the developer stopped it, its deadline passed, or the attempt lost its lease. */
export type StopReason = "developer" | "deadline" | "lease";

/**
 * A command the worker sends the host. A command names the attempt it is for: a turn whose lease expired runs again as
 * a later attempt, and a command for one attempt never acts on another.
 */
export type HostCommand =
	| { type: "start"; turnId: string; attempt: number; token: string }
	| { type: "stop"; turnId: string; attempt: number; reason: StopReason };

/** Narrowing predicate for parsed JSON, used instead of a cast. */
export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/** The only place command bytes become values; anything else is ignored. */
export function parseCommand(text: string): HostCommand | null {
	let value: unknown;
	try {
		value = JSON.parse(text);
	} catch {
		return null;
	}
	if (
		!isRecord(value) ||
		typeof value.turnId !== "string" ||
		value.turnId === "" ||
		typeof value.attempt !== "number" ||
		!Number.isInteger(value.attempt) ||
		value.attempt < 1
	) {
		return null;
	}
	const { turnId, attempt } = value;
	if (value.type === "start" && typeof value.token === "string" && value.token !== "") {
		return { type: "start", turnId, attempt, token: value.token };
	}
	if (
		value.type === "stop" &&
		(value.reason === "developer" || value.reason === "deadline" || value.reason === "lease")
	) {
		return { type: "stop", turnId, attempt, reason: value.reason };
	}
	return null;
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
}

/** What a partial answer keeps of one assistant message: its text, and the feedback it showed. */
export interface PartialMessage {
	content: (
		| { type: "text"; text: string }
		| { type: "toolCall"; id: string; name: "link_observation"; arguments: unknown }
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

/** The most characters of a failure's detail an end carries. */
export const MAX_DETAIL_CHARS = 500;

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
	/** For the operator: why a failed attempt failed, as the host's or the model provider's error text. */
	detail: string | null;
}
