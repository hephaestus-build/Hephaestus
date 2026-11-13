/**
 * TEST ONLY. The Slack Web API of the turn-roll drill: it installs the app, takes the messages that the server
 * posts, and lists them at `GET /received`. It refuses a post that `chat.postMessage` refuses, with Slack's error
 * (https://docs.slack.dev/reference/methods/chat.postMessage/#errors), and lists the refusal. It holds no secret: the
 * bot token is new on each start.
 */
import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";

import type { ReceivedCall } from "./lib/slack-turn.ts";

/** What the fake tells the install: the team that the drill's Slack identity belongs to. */
export const FAKE_TEAM = { id: "T0TURNROLL", name: "Turn roll" } as const;
/** The code that the drill hands to the server's OAuth callback, as Slack would. */
export const FAKE_INSTALL_CODE = "turn-roll-install-code";

const MAX_BODY_BYTES = 1024 * 1024;
/** The longest that `GET /received` waits for a call. */
const MAX_WAIT_MS = 30_000;
/** A Slack `ts`: ten digits of seconds, a dot, six digits. */
const SLACK_TS = /^\d{10}\.\d{6}$/u;

async function readBody(request: IncomingMessage): Promise<string> {
	const chunks: Buffer[] = [];
	let size = 0;
	for await (const part of request) {
		const chunk = Buffer.isBuffer(part) ? part : Buffer.from(String(part));
		size += chunk.length;
		if (size > MAX_BODY_BYTES) {
			throw new RangeError("the request body is too large");
		}
		chunks.push(chunk);
	}
	return Buffer.concat(chunks).toString("utf8");
}

/** The fields of a request body: Slack's SDK posts forms, and a JSON object is as good. */
function fieldsOf(contentType: string | undefined, raw: string): Map<string, string> {
	const fields = new Map<string, string>();
	if (contentType?.startsWith("application/json") === true) {
		const parsed: unknown = JSON.parse(raw);
		if (typeof parsed === "object" && parsed !== null) {
			for (const [key, value] of Object.entries(parsed)) {
				fields.set(key, typeof value === "string" ? value : JSON.stringify(value));
			}
		}
		return fields;
	}
	for (const [key, value] of new URLSearchParams(raw)) {
		fields.set(key, value);
	}
	return fields;
}

function isObject(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Every string under a key `text`, at any depth, in order: the words that a reader sees in blocks. */
function textsOf(value: unknown): string[] {
	if (Array.isArray(value)) {
		return value.flatMap(textsOf);
	}
	if (!isObject(value)) {
		return [];
	}
	return Object.entries(value).flatMap(([key, child]) =>
		key === "text" && typeof child === "string" ? [child] : textsOf(child),
	);
}

/**
 * What `chat.postMessage` refuses: `blocks` must be a JSON array of block objects, the message needs a channel and
 * `text` or `blocks`, `thread_ts` must be a `ts`, and `metadata` must be an object with an `event_type` and an
 * `event_payload`. `blocksText` is the words of the blocks, whether the post is refused or not.
 */
export function postRefusal(fields: ReadonlyMap<string, string>): {
	error: string | null;
	blocksText: string | null;
} {
	const rawBlocks = fields.get("blocks");
	let blocks: unknown[] = [];
	if (rawBlocks !== undefined) {
		let parsed: unknown;
		try {
			parsed = JSON.parse(rawBlocks);
		} catch {
			return { error: "invalid_blocks_format", blocksText: null };
		}
		if (!Array.isArray(parsed)) {
			return { error: "invalid_blocks_format", blocksText: null };
		}
		if (!parsed.every((block) => isObject(block) && typeof block.type === "string")) {
			return { error: "invalid_blocks", blocksText: null };
		}
		blocks = parsed;
	}
	const blocksText = blocks.length === 0 ? null : textsOf(blocks).join("\n");
	const refuse = (error: string) => ({ error, blocksText });
	if ((fields.get("channel") ?? "") === "") {
		return refuse("channel_not_found");
	}
	if ((fields.get("text") ?? "") === "" && blocks.length === 0) {
		return refuse("no_text");
	}
	const threadTs = fields.get("thread_ts");
	if (threadTs !== undefined && !SLACK_TS.test(threadTs)) {
		return refuse("invalid_arguments");
	}
	const rawMetadata = fields.get("metadata");
	if (rawMetadata !== undefined) {
		let metadata: unknown;
		try {
			metadata = JSON.parse(rawMetadata);
		} catch {
			return refuse("invalid_metadata_format");
		}
		if (
			!isObject(metadata) ||
			typeof metadata.event_type !== "string" ||
			!isObject(metadata.event_payload)
		) {
			return refuse("invalid_metadata_schema");
		}
	}
	return { error: null, blocksText };
}

function answer(response: ServerResponse, body: unknown): void {
	response.writeHead(200, { "content-type": "application/json" }).end(JSON.stringify(body));
}

/** What the fake answers a call with, and the call as the drill reads it back. `ordinal` is the call's place in the list. */
function handle(
	method: string,
	fields: ReadonlyMap<string, string>,
	authorized: boolean,
	token: string,
	ordinal: number,
): { call: ReceivedCall; body: unknown } {
	const call: ReceivedCall = {
		method,
		authorized,
		channel: fields.get("channel") ?? null,
		threadTs: fields.get("thread_ts") ?? null,
		text: fields.get("text") ?? null,
		blocksText: null,
		ts: null,
		error: null,
	};
	if (method === "oauth.v2.access") {
		call.error = fields.get("code") === FAKE_INSTALL_CODE ? null : "invalid_code";
		return {
			call,
			body:
				call.error === null
					? {
							ok: true,
							access_token: token,
							token_type: "bot",
							team: FAKE_TEAM,
							bot_user_id: "U0TURNROLLBOT",
						}
					: { ok: false, error: call.error },
		};
	}
	if (method === "chat.postMessage") {
		const refusal = authorized ? postRefusal(fields) : { error: "invalid_auth", blocksText: null };
		call.blocksText = refusal.blocksText;
		call.error = refusal.error;
		if (refusal.error === null) {
			call.ts = `${1_760_000_000 + ordinal}.000100`;
		}
		return {
			call,
			body:
				refusal.error === null
					? { ok: true, channel: call.channel, ts: call.ts }
					: { ok: false, error: refusal.error },
		};
	}
	// Any other call is on the list for the drill's failure message, and Slack refuses it.
	call.error = authorized ? "unknown_method" : "invalid_auth";
	return { call, body: { ok: false, error: call.error } };
}

/**
 * A Slack Web API that lists what it received. `GET /received?after=<n>&timeoutMs=<ms>` lists the calls from the
 * `n`th on, and holds the request until there is one, for at most `timeoutMs`. Close the returned server to stop it.
 */
export function slackFake(): { server: Server; token: string } {
	const token = `xoxb-${randomUUID()}`;
	const received: ReceivedCall[] = [];
	const arrivals = new EventTarget();
	const listFrom = async (response: ServerResponse, after: number, waitMs: number) => {
		if (received.length <= after && waitMs > 0) {
			const gone = new AbortController();
			response.once("close", () => {
				gone.abort();
			});
			try {
				await once(arrivals, "call", {
					signal: AbortSignal.any([AbortSignal.timeout(waitMs), gone.signal]),
				});
			} catch {
				// The deadline passed or the caller left: the answer is what there is.
			}
		}
		answer(response, received.slice(after));
	};
	const server = createServer((request, response) => {
		void (async () => {
			try {
				const url = new URL(request.url ?? "/", "http://fake");
				const path = url.pathname;
				if (request.method === "GET" && path === "/health") {
					response.end("ok");
				} else if (request.method === "GET" && path === "/received") {
					await listFrom(
						response,
						Math.max(0, Number(url.searchParams.get("after") ?? 0) || 0),
						Math.min(MAX_WAIT_MS, Math.max(0, Number(url.searchParams.get("timeoutMs") ?? 0) || 0)),
					);
				} else if (request.method === "POST" && path.startsWith("/api/")) {
					const fields = fieldsOf(request.headers["content-type"], await readBody(request));
					const { call, body } = handle(
						path.slice("/api/".length),
						fields,
						request.headers.authorization === `Bearer ${token}`,
						token,
						received.length,
					);
					// A call is listed once it is final, and wakes the requests that wait for one.
					received.push(call);
					arrivals.dispatchEvent(new Event("call"));
					answer(response, body);
				} else {
					response.writeHead(404).end();
				}
			} catch {
				if (!response.headersSent) {
					response.writeHead(400);
				}
				response.end();
			}
		})();
	});
	return { server, token };
}

if (import.meta.main) {
	const { server } = slackFake();
	server.listen(8080, "0.0.0.0");
	for (const signal of ["SIGTERM", "SIGINT"] as const) {
		process.once(signal, () => {
			server.close();
		});
	}
}
