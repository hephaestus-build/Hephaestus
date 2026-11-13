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

const PLAIN_TEXT = ["plain_text"];
const ANY_TEXT = ["plain_text", "mrkdwn"];

/** A string of at most `max` characters when present. */
function optionalString(object: Record<string, unknown>, key: string, max: number): boolean {
	const value = object[key];
	return value === undefined || (typeof value === "string" && value.length <= max);
}

/** A text object (https://docs.slack.dev/reference/block-kit/composition-objects/text-object/): 1 to `max` characters. */
function textObject(value: unknown, kinds: readonly string[], max: number): boolean {
	return (
		isObject(value) &&
		typeof value.type === "string" &&
		kinds.includes(value.type) &&
		typeof value.text === "string" &&
		value.text.length > 0 &&
		value.text.length <= max
	);
}

/** An array of `min` to `max` items that all pass `valid`. */
function items(
	value: unknown,
	min: number,
	max: number,
	valid: (item: unknown) => boolean,
): boolean {
	return Array.isArray(value) && value.length >= min && value.length <= max && value.every(valid);
}

/** A confirmation dialog object: a title, a text and both labels. */
function confirmation(value: unknown): boolean {
	return (
		isObject(value) &&
		textObject(value.title, PLAIN_TEXT, 100) &&
		textObject(value.text, ANY_TEXT, 300) &&
		textObject(value.confirm, PLAIN_TEXT, 30) &&
		textObject(value.deny, PLAIN_TEXT, 30)
	);
}

/** A button element: https://docs.slack.dev/reference/block-kit/block-elements/button-element/ */
function button(value: unknown): boolean {
	return (
		isObject(value) &&
		value.type === "button" &&
		textObject(value.text, PLAIN_TEXT, 75) &&
		optionalString(value, "action_id", 255) &&
		optionalString(value, "url", 3000) &&
		optionalString(value, "value", 2000) &&
		optionalString(value, "accessibility_label", 75) &&
		(value.style === undefined || value.style === "primary" || value.style === "danger") &&
		(value.confirm === undefined || confirmation(value.confirm))
	);
}

/** A feedback button of `feedback_buttons`: a plain text of at most 75 characters and a value. */
function feedbackButton(value: unknown): boolean {
	return (
		isObject(value) &&
		textObject(value.text, PLAIN_TEXT, 75) &&
		typeof value.value === "string" &&
		value.value.length > 0 &&
		value.value.length <= 2000 &&
		optionalString(value, "accessibility_label", 75)
	);
}

/** The element of `context_actions` that the server sends: https://docs.slack.dev/reference/block-kit/block-elements/feedback-buttons-element/ */
function feedbackButtons(value: unknown): boolean {
	return (
		isObject(value) &&
		value.type === "feedback_buttons" &&
		feedbackButton(value.positive_button) &&
		feedbackButton(value.negative_button) &&
		optionalString(value, "action_id", 255)
	);
}

/** What a section block needs: a text object, or fields, and an optional button as its accessory. */
function section(block: Record<string, unknown>): boolean {
	return (
		(block.text !== undefined || block.fields !== undefined) &&
		(block.text === undefined || textObject(block.text, ANY_TEXT, 3000)) &&
		(block.fields === undefined ||
			items(block.fields, 1, 10, (field) => textObject(field, ANY_TEXT, 2000))) &&
		(block.accessory === undefined || button(block.accessory))
	);
}

/**
 * Whether a block has what Slack requires of its type (https://docs.slack.dev/reference/block-kit/blocks/). Only the
 * types that the server sends in a message are known: `markdown`, `section`, `context`, `context_actions` and
 * `actions`. Another type is refused.
 */
function validBlock(block: Record<string, unknown>): boolean {
	if (!optionalString(block, "block_id", 255)) {
		return false;
	}
	switch (block.type) {
		case "markdown": {
			return typeof block.text === "string" && block.text.length > 0;
		}
		case "section": {
			return section(block);
		}
		case "context": {
			return items(block.elements, 1, 10, (element) => textObject(element, ANY_TEXT, 3000));
		}
		case "context_actions": {
			return items(block.elements, 1, 5, feedbackButtons);
		}
		case "actions": {
			return items(block.elements, 1, 25, button);
		}
		default: {
			return false;
		}
	}
}

/** All `markdown` blocks of one payload share 12,000 characters. */
const MARKDOWN_LIMIT = 12_000;

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
		if (!parsed.every((block) => isObject(block) && validBlock(block))) {
			return { error: "invalid_blocks", blocksText: null };
		}
		blocks = parsed;
		const markdown = parsed.reduce<number>(
			(sum, block) =>
				sum + (isObject(block) && block.type === "markdown" ? String(block.text).length : 0),
			0,
		);
		if (markdown > MARKDOWN_LIMIT) {
			return { error: "msg_blocks_too_long", blocksText: null };
		}
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
