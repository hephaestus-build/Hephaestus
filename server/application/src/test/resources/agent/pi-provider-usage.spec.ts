import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { createServer, type ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { ModelRuntime, SessionManager } from "@earendil-works/pi-coding-agent";

import { registerHephaestusProvider } from "../../../main/resources/agent/pi-provider.ts";
import {
	addAssistantUsage,
	extractUsageFromSession,
	newUsageLedger,
} from "../../../main/resources/agent/pi-runner-usage.ts";

type Protocol = "openai-completions" | "openai-responses";

type Raw = Record<string, unknown>;

function send(response: ServerResponse, events: unknown[]): void {
	response.writeHead(200, { "content-type": "text/event-stream" });
	for (const event of events) {
		response.write(`data: ${JSON.stringify(event)}\n\n`);
	}
	response.end("data: [DONE]\n\n");
}

/** Usage on 100 inclusive prompt tokens and 7 output tokens, in the protocol's own field names. */
function usageOf(protocol: Protocol, details: Raw | null, reasoning?: number): Raw {
	const completions = protocol === "openai-completions";
	return {
		[completions ? "prompt_tokens" : "input_tokens"]: 100,
		[completions ? "completion_tokens" : "output_tokens"]: 7,
		total_tokens: 107,
		...(details === null
			? {}
			: { [completions ? "prompt_tokens_details" : "input_tokens_details"]: details }),
		...(reasoning === undefined
			? {}
			: {
					[completions ? "completion_tokens_details" : "output_tokens_details"]: {
						reasoning_tokens: reasoning,
					},
				}),
	};
}

/** The text "ok", then each usage block in turn; the last stops the reply when it is incomplete. */
function reply(protocol: Protocol, usages: Raw[], incomplete: boolean): unknown[] {
	if (protocol === "openai-completions") {
		const chunk = { id: "c", object: "chat.completion.chunk", created: 0, model: "gpt-5" };
		return [
			{ ...chunk, choices: [{ index: 0, delta: { role: "assistant", content: "ok" } }] },
			{
				...chunk,
				choices: [{ index: 0, delta: {}, finish_reason: incomplete ? "length" : "stop" }],
			},
			...usages.map((usage) => ({ ...chunk, choices: [], usage })),
		];
	}
	const item = { id: "m", type: "message", role: "assistant", status: "completed" };
	const part = { type: "output_text", text: "ok", annotations: [] };
	const done = {
		id: "r",
		status: incomplete ? "incomplete" : "completed",
		...(incomplete ? { incomplete_details: { reason: "max_output_tokens" } } : {}),
		output: [{ ...item, content: [part] }],
		...(usages.length === 0 ? {} : { usage: usages.at(-1) }),
	};
	return [
		{ type: "response.created", response: { id: "r", status: "in_progress", output: [] } },
		{ type: "response.output_item.added", output_index: 0, item: { ...item, content: [] } },
		{
			type: "response.content_part.added",
			item_id: "m",
			output_index: 0,
			content_index: 0,
			part: { ...part, text: "" },
		},
		{
			type: "response.output_text.delta",
			item_id: "m",
			output_index: 0,
			content_index: 0,
			delta: "ok",
		},
		{ type: "response.output_item.done", output_index: 0, item: { ...item, content: [part] } },
		{ type: incomplete ? "response.incomplete" : "response.completed", response: done },
	];
}

async function complete(protocol: Protocol, usages: Raw[], incomplete = false) {
	return completeWith(protocol, (response) => send(response, reply(protocol, usages, incomplete)));
}

/** One completion against a local server that answers with `respond`. */
async function completeWith(
	protocol: Protocol,
	respond: (response: ServerResponse) => void,
	options?: Parameters<ModelRuntime["completeSimple"]>[2],
) {
	const server = createServer((request, response) => {
		request.resume();
		request.on("end", () => respond(response));
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const dir = mkdtempSync(path.join(tmpdir(), "pi-usage-"));
	const token = process.env.LLM_PROXY_TOKEN;
	try {
		const address = server.address();
		if (address === null || typeof address === "string") {
			throw new Error("no port");
		}
		const runtime = await ModelRuntime.create({
			authPath: path.join(dir, "auth.json"),
			modelsPath: path.join(dir, "models.json"),
			allowModelNetwork: false,
		});
		assert.ok(
			registerHephaestusProvider(
				runtime,
				{ apiProtocol: protocol, modelId: "gpt-5" },
				{ LLM_PROXY_URL: `http://127.0.0.1:${String(address.port)}/v1`, LLM_PROXY_TOKEN: "t" },
			),
		);
		const model = runtime.getModel("hephaestus", "gpt-5");
		assert.ok(model);
		process.env.LLM_PROXY_TOKEN = "t";
		return await runtime.completeSimple(
			model,
			{ messages: [{ role: "user", content: "hi", timestamp: Date.now() }] },
			options,
		);
	} finally {
		if (token === undefined) {
			delete process.env.LLM_PROXY_TOKEN;
		} else {
			process.env.LLM_PROXY_TOKEN = token;
		}
		rmSync(dir, { recursive: true, force: true });
		server.closeAllConnections();
		await server[Symbol.asyncDispose]();
	}
}

type Message = Awaited<ReturnType<typeof complete>>;
interface Buckets {
	input: number;
	cacheRead: number;
	cacheWrite: number;
	output: number;
	reasoning?: number;
}

/** The reply text is kept, and the emitted usage, the stream ledger and the message walk agree. */
function assertRecorded(message: Message, expected: Buckets | null): void {
	assert.deepEqual(
		message.content.filter((block) => block.type === "text").map((block) => block.text),
		["ok"],
	);
	const { input, cacheRead, cacheWrite, output, reasoning } = message.usage;
	const none = { input: 0, cacheRead: 0, cacheWrite: 0, output: 0 };
	assert.deepEqual(
		{ input, cacheRead, cacheWrite, output },
		expected === null
			? none
			: {
					input: expected.input,
					cacheRead: expected.cacheRead,
					cacheWrite: expected.cacheWrite,
					output: expected.output,
				},
	);
	assert.equal(message.usage.totalTokens, input + cacheRead + cacheWrite + output);
	if (expected?.reasoning !== undefined) {
		assert.equal(reasoning, expected.reasoning);
	}
	const ledger = newUsageLedger();
	addAssistantUsage(ledger, message);
	for (const stored of [
		extractUsageFromSession({ messages: [message] }, ledger),
		extractUsageFromSession({ messages: [message] }),
	]) {
		assert.deepEqual(
			[
				stored.inputTokens,
				stored.cacheReadTokens,
				stored.cacheWriteTokens,
				stored.outputTokens,
				stored.reasoningTokens,
			],
			[input, cacheRead, cacheWrite, output, reasoning ?? 0],
		);
	}
}

const COUNTED: [string, Raw | null, Buckets][] = [
	[
		"the documented cache-write field",
		{ cached_tokens: 20, cache_write_tokens: 30 },
		{ input: 50, cacheRead: 20, cacheWrite: 30, output: 7 },
	],
	[
		"the proxy alias",
		{ cached_tokens: 20, created_cache_tokens: 30 },
		{ input: 50, cacheRead: 20, cacheWrite: 30, output: 7 },
	],
	[
		"equal cache-write fields",
		{ cached_tokens: 20, cache_write_tokens: 30, created_cache_tokens: 30 },
		{ input: 50, cacheRead: 20, cacheWrite: 30, output: 7 },
	],
	[
		"cache reads alone",
		{ cached_tokens: 20, cache_write_tokens: null },
		{ input: 80, cacheRead: 20, cacheWrite: 0, output: 7 },
	],
	["no details", null, { input: 100, cacheRead: 0, cacheWrite: 0, output: 7 }],
];

const UNUSABLE: [string, Raw | null][] = [
	[
		"conflicting cache-write fields",
		{ cached_tokens: 20, cache_write_tokens: 30, created_cache_tokens: 45 },
	],
	[
		"an invalid documented field beside a valid alias",
		{ cached_tokens: 20, cache_write_tokens: -3, created_cache_tokens: 30 },
	],
	["a fractional alias", { cached_tokens: 20, created_cache_tokens: 1.5 }],
	["a cache read reported as text", { cached_tokens: "20" }],
	["cache details above the inclusive input", { cached_tokens: 80, cache_write_tokens: 30 }],
];

for (const protocol of ["openai-completions", "openai-responses"] as const) {
	for (const [name, details, expected] of COUNTED) {
		void test(`${protocol} counts ${name} once`, async () => {
			assertRecorded(await complete(protocol, [usageOf(protocol, details)]), expected);
		});
	}

	for (const [name, details] of UNUSABLE) {
		void test(`${protocol} keeps the reply but records no usage for ${name}`, async () => {
			const message = await complete(protocol, [usageOf(protocol, details)]);
			assert.equal(message.stopReason, "stop");
			assertRecorded(message, null);
		});
	}

	void test(`${protocol} keeps reasoning as part of output`, async () => {
		assertRecorded(await complete(protocol, [usageOf(protocol, null, 3)]), {
			input: 100,
			cacheRead: 0,
			cacheWrite: 0,
			output: 7,
			reasoning: 3,
		});
	});

	void test(`${protocol} records no usage when the reply reports none`, async () => {
		assertRecorded(await complete(protocol, []), null);
	});

	void test(`${protocol} persists the normalized message through the native session manager`, async () => {
		const message = await complete(protocol, [
			usageOf(protocol, { cached_tokens: 20, created_cache_tokens: 30 }),
		]);
		const dir = mkdtempSync(path.join(tmpdir(), "pi-session-usage-"));
		try {
			const session = SessionManager.create(dir, dir);
			session.appendMessage(message);
			const file = session.getSessionFile();
			assert.ok(file !== undefined && file !== "");
			const reopened = SessionManager.open(file);
			const entry = reopened.getEntries().find((row) => row.type === "message");
			assert.ok(entry?.type === "message" && entry.message.role === "assistant");
			assert.deepEqual(entry.message.usage, message.usage);
			assertRecorded(entry.message, { input: 50, cacheRead: 20, cacheWrite: 30, output: 7 });
		} finally {
			rmSync(dir, { recursive: true, force: true });
		}
	});

	for (const invalid of [-1, 1.5, 2_147_483_648, "100"]) {
		void test(`${protocol} preserves text with an invalid inclusive input ${String(invalid)}`, async () => {
			const usage = usageOf(protocol, null);
			usage[protocol === "openai-completions" ? "prompt_tokens" : "input_tokens"] = invalid;
			assertRecorded(await complete(protocol, [usage]), null);
		});
	}

	void test(`${protocol} counts the usage of an incomplete reply`, async () => {
		const message = await complete(
			protocol,
			[usageOf(protocol, { cached_tokens: 20, created_cache_tokens: 30 })],
			true,
		);
		assert.equal(message.stopReason, "length");
		assertRecorded(message, { input: 50, cacheRead: 20, cacheWrite: 30, output: 7 });
	});
}

void test("openai-completions keeps earlier valid usage when a later usage block is unusable", async () => {
	const protocol = "openai-completions";
	const message = await complete(protocol, [
		usageOf(protocol, { cached_tokens: 20, created_cache_tokens: 30 }),
		usageOf(protocol, { cached_tokens: 20, cache_write_tokens: 30, created_cache_tokens: 45 }),
	]);
	assertRecorded(message, { input: 50, cacheRead: 20, cacheWrite: 30, output: 7 });
});

const COMPLETIONS_READS: [string, Raw, number][] = [
	["cache-hit fallback", { prompt_cache_hit_tokens: 20 }, 20],
	["top-level fallback", { cached_tokens: 20 }, 20],
	[
		"nested precedence",
		{
			prompt_cache_hit_tokens: 40,
			cached_tokens: 50,
			prompt_tokens_details: { cached_tokens: 20, created_cache_tokens: 30 },
		},
		20,
	],
	["cache-hit precedence", { prompt_cache_hit_tokens: 20, cached_tokens: 40 }, 20],
];

for (const [name, fields, read] of COMPLETIONS_READS) {
	void test(`openai-completions retains native ${name} with cache writes`, async () => {
		const usage = { ...usageOf("openai-completions", { created_cache_tokens: 30 }), ...fields };
		assertRecorded(await complete("openai-completions", [usage]), {
			input: 100 - read - 30,
			cacheRead: read,
			cacheWrite: 30,
			output: 7,
		});
	});
}

void test("openai-completions does not replace an invalid selected cache-hit field with a lower-priority field", async () => {
	const usage = {
		...usageOf("openai-completions", null),
		prompt_cache_hit_tokens: 1.5,
		cached_tokens: 20,
	};
	assertRecorded(await complete("openai-completions", [usage]), null);
});

void test("openai-responses ignores completions-only read fields", async () => {
	const usage = {
		...usageOf("openai-responses", { created_cache_tokens: 30 }),
		prompt_cache_hit_tokens: 20,
		cached_tokens: 20,
	};
	assertRecorded(await complete("openai-responses", [usage]), {
		input: 70,
		cacheRead: 0,
		cacheWrite: 30,
		output: 7,
	});
});

for (const total of [undefined, 999]) {
	void test(`openai-responses derives conserved totals when redundant total_tokens is ${String(total)}`, async () => {
		const usage = usageOf("openai-responses", { cached_tokens: 20, created_cache_tokens: 30 });
		usage.total_tokens = total;
		const message = await complete("openai-responses", [usage]);
		assertRecorded(message, { input: 50, cacheRead: 20, cacheWrite: 30, output: 7 });
		assert.equal(message.usage.totalTokens, 107);
	});
}

const SECRET = "sk-diagnostic-secret";

/** The diagnostic name prefix each patched protocol owns. */
const DIAGNOSTIC: Record<Protocol, string> = {
	"openai-completions": "openai_completions",
	"openai-responses": "openai_responses",
};

/** The call's monotonic span: whole milliseconds only, with a response time only when the response arrived. */
function assertCall(
	message: Message,
	responded: boolean,
	protocol: Protocol = "openai-completions",
): { elapsedMs: number; responseMs?: number } {
	const call = message.diagnostics?.find(
		(diagnostic) => diagnostic.type === `${DIAGNOSTIC[protocol]}_call`,
	);
	assert.ok(call);
	assert.equal(call.error, undefined);
	const details: unknown = call.details;
	assert.ok(typeof details === "object" && details !== null);
	const elapsedMs: unknown = Reflect.get(details, "elapsedMs");
	const responseMs: unknown = Reflect.get(details, "responseMs");
	assert.deepEqual(Object.keys(details), responded ? ["elapsedMs", "responseMs"] : ["elapsedMs"]);
	assert.ok(typeof elapsedMs === "number" && Number.isSafeInteger(elapsedMs) && elapsedMs >= 0);
	if (!responded) {
		return { elapsedMs };
	}
	assert.ok(typeof responseMs === "number" && Number.isSafeInteger(responseMs));
	assert.ok(responseMs >= 0 && responseMs <= elapsedMs);
	return { elapsedMs, responseMs };
}

/** The adapter's own failure diagnostic and call span, and that nothing the server sent reached any diagnostic. */
function assertFailure(
	message: Message,
	details: Raw,
	protocol: Protocol = "openai-completions",
): void {
	assert.equal(message.stopReason, details.kind === "ABORTED" ? "aborted" : "error");
	assert.deepEqual(
		message.diagnostics?.map((diagnostic) => diagnostic.type),
		[`${DIAGNOSTIC[protocol]}_failure`, `${DIAGNOSTIC[protocol]}_call`],
	);
	const [diagnostic] = message.diagnostics ?? [];
	assert.ok(diagnostic);
	assert.equal(typeof diagnostic.timestamp, "number");
	assert.equal(diagnostic.error, undefined);
	assert.deepEqual(diagnostic.details, details);
	assertCall(message, details.phase === "response_body", protocol);
	assert.ok(!JSON.stringify(message.diagnostics).includes(SECRET));
}

void test("openai-completions records a server error as its native status, without the body", async () => {
	const message = await completeWith("openai-completions", (response) => {
		response.writeHead(500, { "content-type": "application/json", "x-should-retry": "false" });
		response.end(JSON.stringify({ error: { message: `upstream failed: ${SECRET}` } }));
	});
	assertFailure(message, { kind: "HTTP_ERROR", phase: "request", status: 500 });
});

void test("openai-completions records a stream that ends without a finish reason", async () => {
	const chunk = { id: "c", object: "chat.completion.chunk", created: 0, model: "gpt-5" };
	const message = await completeWith("openai-completions", (response) =>
		send(response, [
			{ ...chunk, choices: [{ index: 0, delta: { role: "assistant", content: SECRET } }] },
		]),
	);
	assertFailure(message, { kind: "STREAM_INCOMPLETE", phase: "response_body" });
});

void test("openai-completions records an aborted call as aborted", async () => {
	const controller = new AbortController();
	const message = await completeWith(
		"openai-completions",
		(response) => {
			controller.abort();
			response.end();
		},
		{ signal: controller.signal, maxRetries: 0 },
	);
	assertFailure(message, { kind: "ABORTED", phase: "request" });
});

void test("openai-completions attaches only its call span to a completed call", async () => {
	const message = await complete("openai-completions", [usageOf("openai-completions", null)]);
	assert.equal(message.stopReason, "stop");
	assert.deepEqual(
		message.diagnostics?.map((diagnostic) => diagnostic.type),
		["openai_completions_call"],
	);
	assertCall(message, true);
});

void test("openai-completions times a call across an adapter request retry and wait", async () => {
	let requests = 0;
	const message = await completeWith(
		"openai-completions",
		(response) => {
			requests += 1;
			if (requests === 1) {
				response.writeHead(503, { "content-type": "application/json", "retry-after-ms": "80" });
				response.end(JSON.stringify({ error: { message: SECRET } }));
				return;
			}
			send(response, reply("openai-completions", [usageOf("openai-completions", null)], false));
		},
		{ maxRetries: 1 },
	);
	assert.equal(requests, 2);
	assert.equal(message.stopReason, "stop");
	const { responseMs } = assertCall(message, true);
	assert.ok(responseMs !== undefined && responseMs >= 80);
	assert.ok(!JSON.stringify(message.diagnostics).includes(SECRET));
});

void test("openai-completions keeps an error finish reason as an adapter condition, not provider text", async () => {
	const chunk = { id: "c", object: "chat.completion.chunk", created: 0, model: "gpt-5" };
	const message = await completeWith("openai-completions", (response) =>
		send(response, [{ ...chunk, choices: [{ index: 0, delta: {}, finish_reason: SECRET }] }]),
	);
	assertFailure(message, { kind: "FINISH_REASON_ERROR", phase: "response_body" });
});

void test("openai-completions distinguishes the native request timeout from an abort", async () => {
	const message = await completeWith("openai-completions", () => undefined, {
		timeoutMs: 150,
		maxRetries: 0,
	});
	assertFailure(message, { kind: "CONNECTION_TIMEOUT", phase: "request" });
});

void test("openai-completions retains a native connection error without an HTTP status", async () => {
	const message = await completeWith(
		"openai-completions",
		(response) => {
			response.destroy();
		},
		{
			maxRetries: 0,
		},
	);
	assertFailure(message, { kind: "CONNECTION_ERROR", phase: "request" });
});

/** A Responses stream that starts a message and then sends `terminal`, if any. */
function responsesUntil(terminal: unknown[]): unknown[] {
	return [
		{ type: "response.created", response: { id: "r", status: "in_progress", output: [] } },
		...terminal,
	];
}

void test("openai-responses attaches only its call span to a completed call", async () => {
	const message = await complete("openai-responses", [usageOf("openai-responses", null)]);
	assert.equal(message.stopReason, "stop");
	assert.deepEqual(
		message.diagnostics?.map((diagnostic) => diagnostic.type),
		["openai_responses_call"],
	);
	assertCall(message, true, "openai-responses");
});

void test("openai-responses records a server error as its native status, without the body", async () => {
	const message = await completeWith("openai-responses", (response) => {
		response.writeHead(500, { "content-type": "application/json", "x-should-retry": "false" });
		response.end(JSON.stringify({ error: { message: `upstream failed: ${SECRET}` } }));
	});
	assertFailure(message, { kind: "HTTP_ERROR", phase: "request", status: 500 }, "openai-responses");
});

const RESPONSES_TERMINALS: [string, unknown[], string][] = [
	[
		"a failed response",
		[
			{
				type: "response.failed",
				response: { id: "r", status: "failed", error: { code: "server_error", message: SECRET } },
			},
		],
		"RESPONSE_FAILED",
	],
	[
		"an incomplete response that is not an output limit",
		[
			{
				type: "response.incomplete",
				response: {
					id: "r",
					status: "incomplete",
					incomplete_details: { reason: "content_filter" },
					output: [],
				},
			},
		],
		"RESPONSE_STATUS_ERROR",
	],
	[
		"an error event",
		[{ type: "error", code: "server_error", message: SECRET }],
		"STREAM_ERROR_EVENT",
	],
	["a stream without a terminal event", [], "STREAM_INCOMPLETE"],
	[
		"a completed response with an unfinished tool call",
		[
			{
				type: "response.output_item.added",
				output_index: 0,
				item: {
					id: "tool",
					type: "function_call",
					call_id: "call",
					name: "read_file",
					arguments: '{"path":',
				},
			},
			{
				type: "response.completed",
				response: { id: "r", status: "completed", output: [] },
			},
		],
		"TOOL_CALL_INCOMPLETE",
	],
];

for (const [name, terminal, kind] of RESPONSES_TERMINALS) {
	void test(`openai-responses names ${name} by its own terminal branch, not provider text`, async () => {
		const message = await completeWith("openai-responses", (response) =>
			send(response, responsesUntil(terminal)),
		);
		assertFailure(message, { kind, phase: "response_body" }, "openai-responses");
	});
}

void test("openai-responses records abort, timeout and connection failures by the SDK's own classes", async () => {
	const controller = new AbortController();
	const aborted = await completeWith(
		"openai-responses",
		(response) => {
			controller.abort();
			response.end();
		},
		{ signal: controller.signal, maxRetries: 0 },
	);
	assertFailure(aborted, { kind: "ABORTED", phase: "request" }, "openai-responses");
	const timedOut = await completeWith("openai-responses", () => undefined, {
		timeoutMs: 150,
		maxRetries: 0,
	});
	assertFailure(timedOut, { kind: "CONNECTION_TIMEOUT", phase: "request" }, "openai-responses");
	const dropped = await completeWith(
		"openai-responses",
		(response) => {
			response.destroy();
		},
		{ maxRetries: 0 },
	);
	assertFailure(dropped, { kind: "CONNECTION_ERROR", phase: "request" }, "openai-responses");
});

void test("openai-responses times a call across an adapter request retry and wait", async () => {
	let requests = 0;
	const message = await completeWith(
		"openai-responses",
		(response) => {
			requests += 1;
			if (requests === 1) {
				response.writeHead(503, { "content-type": "application/json", "retry-after-ms": "80" });
				response.end(JSON.stringify({ error: { message: SECRET } }));
				return;
			}
			send(response, reply("openai-responses", [usageOf("openai-responses", null)], false));
		},
		{ maxRetries: 1 },
	);
	assert.equal(requests, 2);
	assert.equal(message.stopReason, "stop");
	const { responseMs } = assertCall(message, true, "openai-responses");
	assert.ok(responseMs !== undefined && responseMs >= 80);
	assert.ok(!JSON.stringify(message.diagnostics).includes(SECRET));
});
