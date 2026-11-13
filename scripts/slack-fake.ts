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

/** How one field of a block or element must look. */
export interface Spec {
	is: "string" | "boolean" | "object" | "array";
	required?: boolean;
	/** Characters of a string, or items of an array. */
	min?: number;
	max?: number;
	/** The values that a string may have. */
	values?: readonly string[];
	/** The schema of an object, or of each item of an array. */
	of?: string;
	/** The field is allowed only while a sibling field has this value: `[field, value]`. */
	onlyWhen?: readonly [string, string];
}

/** The fields that Slack defines for a block, an element or a composition object. A field that is not here is refused. */
export interface Schema {
	fields: Readonly<Record<string, Spec>>;
	/** Groups of fields, one of which must be present in full: a section needs `text` or `fields`. */
	anyOf?: readonly (readonly string[])[];
}

const PLAIN = ["plain_text"] as const;
const ANY = ["plain_text", "mrkdwn"] as const;
const STYLES = ["primary", "danger"] as const;

/** A text object: https://docs.slack.dev/reference/block-kit/composition-objects/text-object/ */
function textSchema(types: readonly string[], max: number): Schema {
	return {
		fields: {
			type: { is: "string", required: true, values: types },
			text: { is: "string", required: true, min: 1, max },
			emoji: { is: "boolean", onlyWhen: ["type", "plain_text"] },
			verbatim: { is: "boolean", onlyWhen: ["type", "mrkdwn"] },
		},
	};
}

/** `type` of a block or element: always the one value. */
function kind(value: string): Spec {
	return { is: "string", required: true, values: [value] };
}

const BLOCK_ID: Spec = { is: "string", max: 255 };

/**
 * The block types that the server sends in a message, with the fields that Slack documents for each
 * (https://docs.slack.dev/reference/block-kit/blocks/ and https://docs.slack.dev/reference/block-kit/block-elements/).
 * The `text-*` schemas are text objects with the limit of the place that uses them.
 */
export const SCHEMAS: Readonly<Record<string, Schema>> = {
	"text-3000": textSchema(ANY, 3000),
	"text-2000": textSchema(ANY, 2000),
	"text-300": textSchema(ANY, 300),
	"plain-100": textSchema(PLAIN, 100),
	"plain-75": textSchema(PLAIN, 75),
	"plain-30": textSchema(PLAIN, 30),
	markdown: {
		fields: {
			type: kind("markdown"),
			text: { is: "string", required: true, min: 1 },
			block_id: { is: "string" },
		},
	},
	section: {
		fields: {
			type: kind("section"),
			text: { is: "object", of: "text-3000" },
			fields: { is: "array", of: "text-2000", min: 1, max: 10 },
			block_id: BLOCK_ID,
			accessory: { is: "object", of: "button" },
			expand: { is: "boolean" },
		},
		anyOf: [["text"], ["fields"]],
	},
	context: {
		fields: {
			type: kind("context"),
			elements: { is: "array", of: "text-3000", required: true, min: 1, max: 10 },
			block_id: BLOCK_ID,
		},
	},
	context_actions: {
		fields: {
			type: kind("context_actions"),
			elements: { is: "array", of: "feedback_buttons", required: true, min: 1, max: 5 },
			block_id: BLOCK_ID,
		},
	},
	actions: {
		fields: {
			type: kind("actions"),
			elements: { is: "array", of: "button", required: true, min: 1, max: 25 },
			block_id: BLOCK_ID,
		},
	},
	button: {
		fields: {
			type: kind("button"),
			text: { is: "object", of: "plain-75", required: true },
			action_id: { is: "string", max: 255 },
			url: { is: "string", max: 3000 },
			value: { is: "string", max: 2000 },
			style: { is: "string", values: STYLES },
			confirm: { is: "object", of: "confirm" },
			accessibility_label: { is: "string", max: 75 },
		},
	},
	feedback_buttons: {
		fields: {
			type: kind("feedback_buttons"),
			positive_button: { is: "object", of: "feedback_button", required: true },
			negative_button: { is: "object", of: "feedback_button", required: true },
			action_id: { is: "string", max: 255 },
		},
	},
	feedback_button: {
		fields: {
			text: { is: "object", of: "plain-75", required: true },
			value: { is: "string", required: true, min: 1, max: 2000 },
			accessibility_label: { is: "string", max: 75 },
		},
	},
	confirm: {
		fields: {
			title: { is: "object", of: "plain-100", required: true },
			text: { is: "object", of: "text-300", required: true },
			confirm: { is: "object", of: "plain-30", required: true },
			deny: { is: "object", of: "plain-30", required: true },
			style: { is: "string", values: STYLES },
		},
	},
};

/** The schemas that a message's `blocks` may start from: the block types, by their `type`. */
export const BLOCK_TYPES = [
	"markdown",
	"section",
	"context",
	"context_actions",
	"actions",
] as const;

function conformsTo(value: unknown, spec: Spec): boolean {
	switch (spec.is) {
		case "string": {
			return (
				typeof value === "string" &&
				value.length >= (spec.min ?? 0) &&
				value.length <= (spec.max ?? Number.POSITIVE_INFINITY) &&
				(spec.values === undefined || spec.values.includes(value))
			);
		}
		case "boolean": {
			return typeof value === "boolean";
		}
		case "object": {
			return spec.of !== undefined && conforms(value, spec.of);
		}
		case "array": {
			const { of } = spec;
			return (
				of !== undefined &&
				Array.isArray(value) &&
				value.length >= (spec.min ?? 0) &&
				value.length <= (spec.max ?? Number.POSITIVE_INFINITY) &&
				value.every((item) => conforms(item, of))
			);
		}
		default: {
			return false;
		}
	}
}

/**
 * Whether `value` has what the schema `name` defines: every field it carries is one of the schema's, of its type, enum
 * and limits; every required field is there. A JSON `null` counts as absent, as the Java SDK's JSON leaves unset fields.
 */
export function conforms(value: unknown, name: string): boolean {
	const schema = Object.hasOwn(SCHEMAS, name) ? SCHEMAS[name] : undefined;
	if (schema === undefined || !isObject(value)) {
		return false;
	}
	const given = (key: string) => value[key] !== undefined && value[key] !== null;
	const known = Object.keys(value).every((key) => {
		const spec = Object.hasOwn(schema.fields, key) ? schema.fields[key] : undefined;
		return (
			!given(key) ||
			(spec !== undefined &&
				conformsTo(value[key], spec) &&
				(spec.onlyWhen === undefined || value[spec.onlyWhen[0]] === spec.onlyWhen[1]))
		);
	});
	return (
		known &&
		Object.entries(schema.fields).every(([key, spec]) => spec.required !== true || given(key)) &&
		(schema.anyOf === undefined || schema.anyOf.some((group) => group.every(given)))
	);
}

/** Whether a block is one of the types the server sends, and has what Slack defines for that type. */
function validBlock(block: Record<string, unknown>): boolean {
	return (
		typeof block.type === "string" &&
		(BLOCK_TYPES as readonly string[]).includes(block.type) &&
		conforms(block, block.type)
	);
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
