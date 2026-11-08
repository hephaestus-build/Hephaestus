import assert from "node:assert/strict";
import { test } from "node:test";

import { normalizeContext, type Model } from "@earendil-works/pi-ai";
import { anthropicMessagesApi } from "@earendil-works/pi-ai/api/anthropic-messages.lazy";
import { openAICompletionsApi } from "@earendil-works/pi-ai/api/openai-completions.lazy";
import { openAIResponsesApi } from "@earendil-works/pi-ai/api/openai-responses.lazy";
import { modelFailure } from "../../../main/resources/agent/pi-model-failure.ts";

import { waitForGateway } from "../../../main/resources/agent/pi-gateway-wait.ts";

const model = {
	id: "test-model",
	name: "Test model",
	api: "openai-completions",
	provider: "test",
	baseUrl: "http://gateway.invalid/v1",
	reasoning: false,
	input: ["text"],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 4096,
	maxTokens: 64,
} satisfies Model<"openai-completions">;
const context = normalizeContext({
	messages: [{ role: "user", content: "Return a test answer", timestamp: 0 }],
});
const provider = openAICompletionsApi();
const stream = (...args: Parameters<typeof provider.streamSimple>) =>
	provider.streamSimple(...args);
const options = { apiKey: "test-key", maxRetries: 0 };

async function eventsFrom<T>(source: AsyncIterable<T>): Promise<T[]> {
	const events: T[] = [];
	for await (const event of source) {
		events.push(event);
	}
	return events;
}

function chunk(text: string, finish: string | null = null): string {
	return `data: ${JSON.stringify({
		id: "test-completion",
		object: "chat.completion.chunk",
		created: 0,
		model: model.id,
		choices: [{ index: 0, delta: { content: text }, finish_reason: finish }],
	})}\n\n`;
}

function completed(): Response {
	return new Response(`${chunk("complete answer", "stop")}data: [DONE]\n\n`, {
		headers: { "content-type": "text/event-stream" },
	});
}

await test("gateway connection failures stay inside one model stream beyond the model retry limit", async () => {
	const requests: unknown[] = [];
	const output = await waitForGateway(stream, Date.now() + 10_000)(model, context, {
		...options,
		fetch: async (_input, init) => {
			requests.push(init?.body);
			if (requests.length <= 3) {
				throw new TypeError("fetch failed", { cause: { code: "ECONNREFUSED" } });
			}
			return completed();
		},
	});
	const events = await eventsFrom(output);
	assert.equal(requests.length, 4);
	assert.ok(requests.every((request) => request === requests[0]));
	assert.equal(events.filter((event) => event.type === "error").length, 0);
	assert.equal(events.filter((event) => event.type === "start").length, 1);
	assert.equal(events.filter((event) => event.type === "done").length, 1);
	const result = await output.result();
	assert.deepEqual(result.content, [{ type: "text", text: "complete answer" }]);
});

for (const code of ["ETIMEDOUT", "EHOSTUNREACH", "ENETUNREACH"]) {
	await test(`an ambiguous ${code} bounds possible billed response replays`, async () => {
		let calls = 0;
		let interrupted = false;
		const output = await waitForGateway(stream, Date.now() + 10_000, {
			unavailable: () => {
				interrupted = true;
			},
		})(model, context, {
			...options,
			fetch: async () => {
				calls += 1;
				throw new TypeError("fetch failed", { cause: { code } });
			},
		});
		await eventsFrom(output);
		assert.equal(calls, 3);
		assert.equal(interrupted, true);
		const result = await output.result();
		assert.equal(result.stopReason, "aborted");
	});
}

await test("a proven connect timeout waits within the same model stream", async () => {
	let calls = 0;
	const output = await waitForGateway(stream, Date.now() + 10_000)(model, context, {
		...options,
		fetch: async () => {
			calls += 1;
			if (calls <= 3) {
				throw new TypeError("fetch failed", { cause: { code: "ETIMEDOUT", syscall: "connect" } });
			}
			return completed();
		},
	});
	const events = await eventsFrom(output);
	assert.equal(calls, 4);
	assert.equal(events.filter((event) => event.type === "error").length, 0);
	const result = await output.result();
	assert.equal(result.stopReason, "stop");
});

await test("a disconnected partial response is replaced by one complete model response", async () => {
	let calls = 0;
	const output = await waitForGateway(stream, Date.now() + 5000)(model, context, {
		...options,
		fetch: async () => {
			calls += 1;
			if (calls > 1) {
				return completed();
			}
			let sent = false;
			return new Response(
				new ReadableStream<Uint8Array>({
					pull(controller) {
						if (!sent) {
							sent = true;
							controller.enqueue(new TextEncoder().encode(chunk("partial answer")));
							return;
						}
						controller.error(new TypeError("terminated", { cause: { code: "UND_ERR_SOCKET" } }));
					},
				}),
				{ headers: { "content-type": "text/event-stream" } },
			);
		},
	});
	const events = await eventsFrom(output);
	assert.equal(calls, 2);
	assert.ok(
		events.some((event) => event.type === "text_delta" && event.delta === "partial answer"),
	);
	assert.equal(events.filter((event) => event.type === "start").length, 1);
	assert.equal(events.filter((event) => event.type === "error").length, 0);
	assert.equal(events.filter((event) => event.type === "done").length, 1);
	const result = await output.result();
	assert.deepEqual(result.content, [{ type: "text", text: "complete answer" }]);
});

for (const status of [429, 503]) {
	await test(`a provider HTTP ${status} remains a model failure`, async () => {
		let calls = 0;
		const output = await waitForGateway(stream, Date.now() + 5000)(model, context, {
			...options,
			fetch: async () => {
				calls += 1;
				return Response.json({ error: { message: "provider unavailable" } }, { status });
			},
		});
		const events = await eventsFrom(output);
		assert.equal(calls, 1);
		assert.equal(events.filter((event) => event.type === "error").length, 1);
		const result = await output.result();
		assert.equal(result.stopReason, "error");
	});
}

await test("cancellation ends a gateway wait without another model request", async () => {
	const abort = new AbortController();
	const called = Promise.withResolvers<undefined>();
	let calls = 0;
	const output = await waitForGateway(stream, Date.now() + 5000)(model, context, {
		...options,
		signal: abort.signal,
		fetch: async () => {
			calls += 1;
			called.resolve(undefined);
			throw new TypeError("fetch failed", { cause: { code: "ECONNREFUSED" } });
		},
	});
	await called.promise;
	abort.abort();
	const events = await eventsFrom(output);
	assert.equal(calls, 1);
	assert.equal(events.filter((event) => event.type === "error").length, 1);
	const result = await output.result();
	assert.equal(result.stopReason, "aborted");
});

await test("the work deadline bounds a gateway wait", async () => {
	let calls = 0;
	const output = await waitForGateway(stream, Date.now() + 100)(model, context, {
		...options,
		fetch: async () => {
			calls += 1;
			throw new TypeError("fetch failed", { cause: { code: "ECONNREFUSED" } });
		},
	});
	await eventsFrom(output);
	assert.ok(calls <= 1);
	const result = await output.result();
	assert.equal(result.stopReason, "aborted");
	assert.equal(modelFailure(result).source, "ADAPTER");
});

await test("repeated lost responses stop as an infrastructure interruption without a model retry", async () => {
	let calls = 0;
	let interrupted = false;
	const output = await waitForGateway(stream, Date.now() + 10_000, {
		unavailable: () => {
			interrupted = true;
		},
	})(model, context, {
		...options,
		fetch: async () => {
			calls += 1;
			return new Response(
				new ReadableStream({
					start(controller) {
						controller.error(new TypeError("terminated", { cause: { code: "UND_ERR_SOCKET" } }));
					},
				}),
				{ headers: { "content-type": "text/event-stream" } },
			);
		},
	});
	const events = await eventsFrom(output);
	const result = await output.result();
	assert.equal(calls, 3);
	assert.equal(interrupted, true);
	assert.equal(events.filter((event) => event.type === "error").length, 1);
	assert.equal(result.stopReason, "aborted");
	assert.equal(modelFailure(result).source, "ADAPTER");
});

for (const providerCase of [
	{ api: "openai-responses", provider: openAIResponsesApi() },
	{ api: "anthropic-messages", provider: anthropicMessagesApi() },
] as const) {
	await test(`${providerCase.api} waits for a connection, then preserves a provider failure`, async () => {
		let calls = 0;
		const native = (...args: Parameters<typeof providerCase.provider.streamSimple>) =>
			providerCase.provider.streamSimple(...args);
		const output = await waitForGateway(native, Date.now() + 5000)(
			{ ...model, api: providerCase.api },
			context,
			{
				...options,
				fetch: async () => {
					calls += 1;
					if (calls === 1) {
						throw new TypeError("fetch failed", { cause: { code: "ECONNREFUSED" } });
					}
					return Response.json({ error: { message: "provider unavailable" } }, { status: 503 });
				},
			},
		);
		const events = await eventsFrom(output);
		const result = await output.result();
		assert.equal(calls, 2);
		assert.equal(events.filter((event) => event.type === "error").length, 1);
		assert.equal(result.stopReason, "error");
	});
}
