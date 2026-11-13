/**
 * TEST ONLY. What the turn-roll drill sends the server as Slack and as the member's browser: a signed Events API
 * request, and the cookie that binds an install to the browser that started it.
 */
import { createHash, createHmac } from "node:crypto";

import { asArray, asRecord, asString, parseJson } from "./json.ts";

/** One call that reached the fake, with what the drill asserts on. */
export interface ReceivedCall {
	method: string;
	/** Whether the call carried the bot token that the install handed out. */
	authorized: boolean;
	channel: string | null;
	threadTs: string | null;
	/** The `text` argument: what notifications show. */
	text: string | null;
	/** The text of every block that `chat.postMessage` carried: what the reader sees. */
	blocksText: string | null;
	/** The `ts` that `chat.postMessage` answered with. */
	ts: string | null;
	/** The Slack error that the fake answered with; null when it accepted the call. */
	error: string | null;
}

/** Slack's `v0` signature of a request: HMAC-SHA256 over `v0:<timestamp>:<body>`. */
export function slackSignature(secret: string, timestamp: number, body: string): string {
	return `v0=${createHmac("sha256", secret).update(`v0:${timestamp}:${body}`).digest("hex")}`;
}

/** The Events API delivery of a member's direct message to the app. */
export function directMessageEvent(message: {
	team: string;
	user: string;
	channel: string;
	text: string;
	ts: string;
	eventId: string;
}): string {
	return JSON.stringify({
		type: "event_callback",
		team_id: message.team,
		event_id: message.eventId,
		event_time: Math.floor(Number(message.ts)),
		event: {
			type: "message",
			channel_type: "im",
			channel: message.channel,
			user: message.user,
			text: message.text,
			ts: message.ts,
		},
	});
}

/** The OAuth state that the server put in the vendor URL of an install. */
export function installState(vendorUrl: string): string {
	const state = new URL(vendorUrl).searchParams.get("state");
	if (state === null || state === "") {
		throw new Error("the install's vendor URL carries no state");
	}
	return state;
}

/**
 * The cookie that the browser which started an install sends back with its callback: a hash of the state, under a
 * name made from the hash (`OAuthBrowserBinding`).
 */
export function installCookie(state: string): string {
	const hash = createHash("sha256").update(state).digest("hex");
	return `__Host-CONNECT_${hash.slice(0, 16)}=${hash}`;
}

/** What the Slack fake received, from its `GET /received`. */
export function receivedCalls(text: string): ReceivedCall[] {
	return asArray(parseJson(text), "received calls").map((entry) => {
		const call = asRecord(entry, "received call");
		const optional = (key: string) =>
			call[key] === null ? null : asString(call[key], `received call ${key}`);
		return {
			method: asString(call.method, "received call method"),
			authorized: call.authorized === true,
			channel: optional("channel"),
			threadTs: optional("threadTs"),
			text: optional("text"),
			blocksText: optional("blocksText"),
			ts: optional("ts"),
			error: optional("error"),
		};
	});
}

/** What the server records of the drill's one Slack turn and of the outbox post of its reply. */
export interface SlackTurnState {
	status: string;
	answer: string;
	/** The `ts` that Slack gave the posted reply, once the server settled it. */
	replyTs: string | null;
	/** The state of the reply's `agent_action`, with its stop reason: `DONE`, `FAILED:REJECTED`; null before one exists. */
	post: string | null;
}

/** The state of the workspace's one Slack turn; null before the server stored the question. */
export function slackTurnState(text: string): SlackTurnState | null {
	if (text.trim() === "") {
		return null;
	}
	const row = asRecord(parseJson(text), "Slack turn");
	const nullable = (key: string) =>
		row[key] === null ? null : asString(row[key], `Slack turn ${key}`);
	return {
		status: asString(row.status, "Slack turn status"),
		answer: asString(row.answer, "Slack turn answer"),
		replyTs: nullable("replyTs"),
		post: nullable("post"),
	};
}

/**
 * Why the server gave up on the Slack reply: the turn failed or stopped, or the outbox post ended `FAILED`,
 * `CANCELLED` or `UNCERTAIN`, before or after Slack accepted it. Null while neither shows a failure.
 */
export function slackDeliveryFailure(state: SlackTurnState | null): string | null {
	if (state === null) {
		return null;
	}
	if (state.status === "FAILED" || state.status === "STOPPED") {
		return `the Slack turn is ${state.status}`;
	}
	if (
		state.post !== null &&
		["FAILED", "CANCELLED", "UNCERTAIN"].includes(state.post.split(":")[0] ?? "")
	) {
		return `the server's Slack post is ${state.post}`;
	}
	return null;
}

/** How long the drill waits at the fake for a call when no post has arrived. */
export const SLACK_POLL_MS = 5000;
/** How long it waits for the server to settle a post that the fake accepted. */
export const SLACK_SETTLE_MS = 500;

/** What the wait for the Slack reply reads and what it uses for time. */
export interface SlackWatch {
	now: () => number;
	/** The calls from the `after`th on; resolves as soon as there is one, or after `timeoutMs`. */
	receive: (after: number, timeoutMs: number) => Promise<ReceivedCall[]>;
	snapshot: () => Promise<SlackTurnState | null>;
}

function summary(calls: readonly ReceivedCall[]): string {
	return JSON.stringify(
		calls.map(({ method, authorized, channel, error }) => ({ method, authorized, channel, error })),
	);
}

/**
 * Waits until the fake accepted the reply's `chat.postMessage` and the server committed the answer's `ts`. The fake
 * answers the moment a call arrives. After each wait the server's record is read, so a failed turn, a failed,
 * cancelled or uncertain post, and a post that the fake refused, each end the wait at once, before and after the fake
 * accepted a post. No wait lasts longer than the time left before `deadline`.
 */
export async function awaitSlackReply(
	deadline: number,
	watch: SlackWatch,
): Promise<{ reply: ReceivedCall; turn: SlackTurnState }> {
	const calls: ReceivedCall[] = [];
	let state: SlackTurnState | null = null;
	for (;;) {
		const left = deadline - watch.now();
		if (left <= 0) {
			throw new Error(
				`turn roll: the Slack reply did not settle by the deadline; the fake received ${summary(calls)}; the server has ${JSON.stringify(state)}`,
			);
		}
		const accepted = calls.some((call) => call.method === "chat.postMessage");
		calls.push(
			...(await watch.receive(
				calls.length,
				Math.min(accepted ? SLACK_SETTLE_MS : SLACK_POLL_MS, left),
			)),
		);
		const post = calls.find((call) => call.method === "chat.postMessage");
		if (post?.error !== undefined && post.error !== null) {
			throw new Error(`turn roll: the fake Slack refused the reply with ${post.error}`);
		}
		state = await watch.snapshot();
		const failure = slackDeliveryFailure(state);
		if (failure !== null) {
			throw new Error(`turn roll: the Slack reply failed: ${failure}`);
		}
		if (post !== undefined && state?.status === "DONE" && state.replyTs !== null) {
			return { reply: post, turn: state };
		}
	}
}
