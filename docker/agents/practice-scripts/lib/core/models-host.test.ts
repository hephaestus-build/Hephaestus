import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";
import { setImmediate } from "node:timers/promises";
import { z } from "zod";
import { isJsonObject } from "./json.ts";
import { chatOptionsSchema } from "./model-options.ts";

import { InvalidResponseDataError } from "@ai-sdk/provider";

import { MockEmbeddingModelV4, MockLanguageModelV4 } from "ai/test";

import { boundModelsSchema, MODEL_SLOT_CAPS } from "./contract.ts";
import { decisionOutputTokens } from "./decision-logprobs.ts";
import {
	hostModels,
	modelCall,
	ModelBudget,
	reservationOf,
	waitingWhileCallsInFlight,
} from "./models-host.ts";

void test("the models file in the shape the server writes gives every slot a model", async () => {
	const file = new URL("../../test/practice-script-models.json", import.meta.url);
	const bound = boundModelsSchema.parse(JSON.parse(await readFile(file, "utf8")));
	const models = hostModels(bound, "http://proxy.test/internal/llm", "token", "comment-quality");
	assert.deepEqual(Object.keys(models).toSorted(), ["chat", "decision", "embedding", "reranking"]);
});

const never = new AbortController().signal;

const capRefusal = () =>
	new Response("This attempt already has its most model calls in flight.", {
		status: 429,
		headers: { "retry-after": "1", "hephaestus-proxy-refusal": "CALLS_IN_FLIGHT" },
	});

void describe("a direct call that meets its attempt's full cap of calls in flight", () => {
	void test("waits as the proxy asks and still gets its result", async () => {
		const answers = [
			capRefusal(),
			Response.json({ results: [{ index: 1, relevance_score: 0.9 }] }),
		];
		let calls = 0;
		const proxy: typeof fetch = async () => {
			calls += 1;
			const answer = answers.shift();
			assert.ok(answer);
			return answer;
		};
		const file = new URL("../../test/practice-script-models.json", import.meta.url);
		const bound = boundModelsSchema.parse(JSON.parse(await readFile(file, "utf8")));
		const models = hostModels(
			bound,
			"http://proxy.test/internal/llm",
			"token",
			"comment-quality",
			waitingWhileCallsInFlight(proxy),
		);
		assert.ok(models.reranking);

		const ranked = await models.reranking.doRerank({
			documents: { type: "text", values: ["first", "second"] },
			query: "which",
			topN: 1,
			abortSignal: never,
		});

		assert.deepEqual(ranked.ranking, [{ index: 1, relevanceScore: 0.9 }]);
		assert.equal(calls, 2);
	});

	void test("a provider's own rate limit reaches the caller at once", async () => {
		let calls = 0;
		const wait = waitingWhileCallsInFlight(async () => {
			calls += 1;
			return new Response("slow down", { status: 429, headers: { "retry-after": "30" } });
		});

		const answer = await wait("http://proxy.test/internal/llm/practice-script/chat", {});

		assert.equal(answer.status, 429);
		assert.equal(calls, 1);
	});

	void test("an aborted call stops waiting", async () => {
		const stop = new AbortController();
		const wait = waitingWhileCallsInFlight(async () => {
			stop.abort();
			return capRefusal();
		});

		await assert.rejects(
			wait("http://proxy.test/internal/llm/practice-script/chat", { signal: stop.signal }),
			{ name: "AbortError" },
		);
	});
});

void describe("the attempt's direct model account", () => {
	void test("a call settles at the tokens it reports, and one that reports no usage keeps its whole reservation", async () => {
		const budget = new ModelBudget(1000, 1);
		assert.deepEqual(
			await budget.run(120, never, async () => ({ value: "answered", tokens: 30 })),
			{ value: "answered", tokens: 30 },
		);
		assert.equal(budget.left, 970);
		assert.deepEqual(
			await budget.run(120, never, async () => ({ value: "ranked", tokens: undefined })),
			{ value: "ranked", tokens: 120 },
		);
		assert.equal(budget.left, 850);
	});

	void test("every practice's direct calls spend one account, so a call that it cannot afford does not run", async () => {
		const budget = new ModelBudget(200, 2);
		assert.ok(
			"value" in (await budget.run(150, never, async () => ({ value: "first", tokens: 150 }))),
		);
		let ran = false;
		const outcome = await budget.run(120, never, async () => {
			ran = true;
			return { value: "late", tokens: 1 };
		});
		assert.deepEqual(outcome, { reason: "budget" });
		assert.equal(ran, false);
		assert.equal(budget.left, 50);
	});

	void test("a call that fails keeps its reservation, because the provider may have billed it", async () => {
		const budget = new ModelBudget(1000, 1);
		const outcome = await budget.run(10, never, async () => {
			throw new InvalidResponseDataError({ data: {}, message: "no results" });
		});
		assert.deepEqual(outcome, { reason: "off-format" });
		assert.equal(budget.left, 990);
	});

	void test("a confirmed zero refunds a reservation, while implausible usage keeps it", async () => {
		const budget = new ModelBudget(1000, 1);
		await budget.run(120, never, async () => ({ value: "zero", tokens: 0 }));
		assert.equal(budget.left, 1000);
		for (const tokens of [-1, 0.5, Number.NaN, Number.POSITIVE_INFINITY]) {
			await budget.run(120, never, async () => ({ value: "unverifiable", tokens }));
		}
		assert.equal(budget.left, 520);
	});

	void test("an answer after cancellation keeps its reservation and returns no value", async () => {
		const budget = new ModelBudget(1000, 1);
		const cancelled = new AbortController();
		const outcome = await budget.run(120, cancelled.signal, async () => {
			cancelled.abort();
			return { value: "late", tokens: 0 };
		});
		assert.deepEqual(outcome, { reason: "deadline" });
		assert.equal(budget.left, 880);
	});

	void test("a call that waits for its turn and reaches its deadline does not run and holds nothing", async () => {
		const budget = new ModelBudget(1000, 1);
		const busy = Promise.withResolvers<{ value: string; tokens: number }>();
		const first = budget.run(10, never, async () => busy.promise);
		const deadline = new AbortController();
		let ran = false;
		const waiting = budget.run(10, deadline.signal, async () => {
			ran = true;
			return { value: "late", tokens: 1 };
		});
		deadline.abort();
		assert.deepEqual(await waiting, { reason: "deadline" });
		assert.equal(budget.left, 990);
		busy.resolve({ value: "first", tokens: 10 });
		assert.deepEqual(await first, { value: "first", tokens: 10 });
		assert.equal(ran, false);
		assert.ok(
			"value" in (await budget.run(10, never, async () => ({ value: "next", tokens: 10 }))),
		);
	});

	void test("no more calls than the limit are in flight at once", async () => {
		const budget = new ModelBudget(1000, 2);
		let inFlight = 0;
		let most = 0;
		await Promise.all(
			Array.from({ length: 6 }, async () =>
				budget.run(1, never, async () => {
					inFlight += 1;
					most = Math.max(most, inFlight);
					await setImmediate();
					inFlight -= 1;
					return { value: "done", tokens: 1 };
				}),
			),
		);
		assert.equal(most, 2);
		assert.equal(budget.left, 994);
	});
});

void describe("the request allowlist", () => {
	for (const asked of [-1_000_000, 0.5, "4096"]) {
		void test(`an invalid output limit ${JSON.stringify(asked)} is refused before reserving tokens`, () => {
			assert.throws(() => chatOptionsSchema.parse({ prompt: [], maxOutputTokens: asked }), {
				name: "ZodError",
			});
		});
	}

	void test("a decision reserves an answer line for each question", () => {
		const one = reservationOf("decision", { state: "", questions: { a: {} } });
		const thirty = reservationOf("decision", {
			state: "",
			questions: Object.fromEntries(Array.from({ length: 30 }, (_, i) => [`q${i}`, {}])),
		});
		assert.ok(
			thirty - one >= decisionOutputTokens(30) - decisionOutputTokens(1),
			`${one} → ${thirty}`,
		);
	});
});

void describe("chat usage receipts from the bound provider", () => {
	for (const usage of [
		undefined,
		{},
		{ prompt_tokens: 10 },
		{ completion_tokens: 5 },
		{ prompt_tokens: -1, completion_tokens: 5 },
		{ prompt_tokens: 0.5, completion_tokens: 5 },
		{ prompt_tokens: 10, completion_tokens: 5, prompt_tokens_details: { cached_tokens: 11 } },
		{ prompt_tokens: 10, completion_tokens: 5, completion_tokens_details: { reasoning_tokens: 6 } },
	]) {
		void test(`an incomplete or implausible receipt ${JSON.stringify(usage)} retains the reservation`, async (t) => {
			t.mock.method(globalThis, "fetch", async () =>
				Response.json({
					choices: [{ message: { role: "assistant", content: "yes" }, finish_reason: "stop" }],
					usage,
				}),
			);
			const { chat } = hostModels(
				{ chat: { protocol: "openai-completions", modelId: "chat" } },
				"https://proxy.example/internal/llm",
				"test-token",
				"practice",
			);
			assert.ok(chat !== undefined);
			const call = modelCall(
				"chat",
				chat,
				{
					prompt: [{ role: "user", content: [{ type: "text", text: "Say yes." }] }],
					maxOutputTokens: 1000,
				},
				never,
			);
			assert.ok("run" in call);
			const budget = new ModelBudget(5000, 1);
			assert.ok("value" in (await budget.run(call.reserve, never, call.run)));
			assert.equal(budget.left, 5000 - call.reserve);
		});
	}

	void test("a complete receipt preserves a confirmed zero", async (t) => {
		t.mock.method(globalThis, "fetch", async () =>
			Response.json({
				choices: [{ message: { role: "assistant", content: "yes" }, finish_reason: "stop" }],
				usage: { prompt_tokens: 0, completion_tokens: 0 },
			}),
		);
		const { chat } = hostModels(
			{ chat: { protocol: "openai-completions", modelId: "chat" } },
			"https://proxy.example/internal/llm",
			"test-token",
			"practice",
		);
		assert.ok(chat !== undefined);
		const call = modelCall("chat", chat, { prompt: [], maxOutputTokens: 1000 }, never);
		assert.ok("run" in call);
		const budget = new ModelBudget(5000, 1);
		const answer = await budget.run(call.reserve, never, call.run);
		assert.ok("value" in answer);
		assert.equal(answer.tokens, 0);
		assert.equal(budget.left, 5000);
	});
});

void describe("logprob decision usage receipts from the bound provider", () => {
	for (const { usage, tokens } of [
		{ usage: undefined },
		{ usage: {} },
		{ usage: { prompt_tokens: 10 } },
		{ usage: { completion_tokens: 5 } },
		{ usage: { prompt_tokens: 0, completion_tokens: 0 }, tokens: 0 },
		{ usage: { prompt_tokens: 10, completion_tokens: 5 }, tokens: 15 },
	] satisfies { usage: unknown; tokens?: number }[]) {
		void test(`a decision adapter preserves the receipt ${JSON.stringify(usage)}`, async (t) => {
			let outputLimit = 0;
			t.mock.method(
				globalThis,
				"fetch",
				async (_input: Parameters<typeof fetch>[0], init?: RequestInit) => {
					const body = z
						.object({
							max_completion_tokens: z.number().optional(),
							max_tokens: z.number().optional(),
						})
						.parse(JSON.parse(z.string().parse(init?.body)));
					outputLimit = Number(body.max_completion_tokens ?? body.max_tokens);
					return Response.json({
						id: "completion",
						model: "decision",
						created: 0,
						choices: [
							{
								index: 0,
								message: { role: "assistant", content: "q1: A" },
								finish_reason: "stop",
								logprobs: {
									content: [
										{ token: "q1", logprob: 0, top_logprobs: [] },
										{ token: ":", logprob: 0, top_logprobs: [] },
										{ token: " A", logprob: 0, top_logprobs: [{ token: " A", logprob: 0 }] },
									],
								},
							},
						],
						usage,
					});
				},
			);
			const { decision } = hostModels(
				{ decision: { protocol: "openai-completions", modelId: "decision" } },
				"https://proxy.example/internal/llm",
				"test-token",
				"practice",
			);
			assert.ok(decision !== undefined);
			const call = modelCall(
				"decision",
				decision,
				{ state: "work", questions: { q: { type: "boolean", instructions: "Is it met?" } } },
				never,
			);
			assert.ok("run" in call);
			const budget = new ModelBudget(5000, 1);
			const outcome = await budget.run(call.reserve, never, call.run);
			assert.ok("value" in outcome, JSON.stringify(outcome));
			assert.ok(outputLimit >= 4096);
			assert.ok(call.reserve >= outputLimit);
			assert.equal(budget.left, 5000 - (tokens ?? call.reserve));
			assert.ok(outcome.value.slot === "decision");
			assert.equal(outcome.value.result.usage === undefined, tokens === undefined);
		});
	}
});

const values = (count: number) => Array.from({ length: count }, (_, i) => `v${i}`);

void describe("a call to a slot's model", () => {
	const embedder = new MockEmbeddingModelV4({
		doEmbed: async ({ values: asked }) => ({
			embeddings: asked.map(() => [1, 0]),
			usage: { tokens: 7 },
			warnings: [],
		}),
	});

	void test("at the cap it runs, and reports the tokens that the model reports", async () => {
		const call = modelCall(
			"embedding",
			embedder,
			{ values: values(MODEL_SLOT_CAPS.embedding), providerOptions: { x: 1 } },
			never,
		);
		assert.ok("run" in call);
		const { value, tokens } = await call.run();
		assert.equal(tokens, 7);
		assert.equal(value.slot, "embedding");
		assert.equal(embedder.doEmbedCalls.at(-1)?.providerOptions, undefined);
	});

	for (const [slot, options] of [
		["embedding", { values: values(MODEL_SLOT_CAPS.embedding + 1) }],
		[
			"decision",
			{
				state: "s",
				questions: Object.fromEntries(
					values(MODEL_SLOT_CAPS.decision + 1).map((q) => [
						q,
						{ type: "boolean", instructions: q },
					]),
				),
			},
		],
		[
			"reranking",
			{ query: "q", documents: { type: "text", values: values(MODEL_SLOT_CAPS.reranking + 1) } },
		],
	] as const) {
		void test(`above the cap, the ${slot} call is too large and never starts`, () => {
			const model = hostModels(
				{
					decision: { protocol: "openai-decisions", modelId: "d" },
					embedding: { protocol: "openai-embeddings", modelId: "e" },
					reranking: { protocol: "cohere-rerank", modelId: "r" },
				},
				"http://proxy.test",
				"token",
				"comment-quality",
			)[slot];
			assert.ok(model !== undefined);
			assert.deepEqual(modelCall(slot, model, options, never), { reason: "too-large" });
		});
	}

	for (const [slot, options] of [
		["chat", { prompt: "Say hi." }],
		["decision", { state: "s" }],
		["decision", { state: 1, questions: { q: { type: "boolean", instructions: "q" } } }],
		["decision", { state: "s", questions: { q: { type: "free", instructions: "q" } } }],
		["decision", { state: "s", questions: { q: { type: "boolean" } } }],
		["embedding", { values: [1, 2] }],
		["reranking", { documents: { type: "text", values: ["a"] } }],
		["reranking", { query: "q", documents: { type: "text", values: [{ text: "a" }] } }],
		["reranking", { query: "q", documents: { type: "object", values: ["a"] } }],
	] as const) {
		void test(`options that are not a call to the ${slot} model never reach it`, () => {
			const model = hostModels(
				{
					chat: { protocol: "openai-completions", modelId: "c" },
					decision: { protocol: "openai-decisions", modelId: "d" },
					embedding: { protocol: "openai-embeddings", modelId: "e" },
					reranking: { protocol: "cohere-rerank", modelId: "r" },
				},
				"http://proxy.test",
				"token",
				"comment-quality",
			)[slot];
			assert.ok(model !== undefined);
			assert.throws(() => modelCall(slot, model, options, never), {
				name: "ZodError",
			});
		});
	}
});

void test("chat credentials and provider features cannot be supplied through messages or tools", async () => {
	const chat = new MockLanguageModelV4({
		doGenerate: {
			content: [],
			finishReason: { unified: "stop", raw: "stop" },
			usage: {
				inputTokens: { total: 0, noCache: 0, cacheRead: 0, cacheWrite: 0 },
				outputTokens: { total: 0, text: 0, reasoning: 0 },
			},
			warnings: [],
		},
	});
	const providerOptions = { upstream: { privileged: true } };
	const call = modelCall(
		"chat",
		chat,
		{
			prompt: [
				{
					role: "user",
					providerOptions,
					content: [{ type: "text", text: "Review this.", providerOptions }],
				},
			],
			headers: { authorization: "script-supplied" },
			providerOptions,
			tools: [
				{ type: "function", name: "read", inputSchema: { type: "object" }, providerOptions },
				{ type: "provider", id: "upstream.browser", name: "browse", args: {} },
			],
		},
		never,
	);
	assert.ok("run" in call);
	await call.run();
	const [received] = chat.doGenerateCalls;
	assert.ok(received !== undefined);
	assert.equal(received.headers, undefined);
	assert.equal(received.providerOptions, undefined);
	assert.deepEqual(received.prompt, [
		{ role: "user", content: [{ type: "text", text: "Review this." }] },
	]);
	assert.deepEqual(received.tools, [
		{ type: "function", name: "read", inputSchema: { type: "object" } },
	]);
});

void test("native Anthropic chat carries gateway authorization and exclusive cache usage", async () => {
	let sent: Record<string, unknown> = {};
	const { chat } = hostModels(
		{ chat: { protocol: "anthropic-messages", modelId: "alias", forcedToolChoice: true } },
		"http://proxy.test/internal/llm",
		"job-token",
		"practice",
		async (url, init) => {
			assert.equal(url, "http://proxy.test/internal/llm/practice-script/chat/v1/messages");
			assert.equal(new Headers(init?.headers).get("authorization"), "Bearer job-token");
			assert.equal(typeof init?.body, "string");
			assert.ok(typeof init?.body === "string");
			const parsed: unknown = JSON.parse(init.body);
			assert.ok(isJsonObject(parsed));
			sent = parsed;
			return Response.json({
				id: "msg-1",
				type: "message",
				role: "assistant",
				model: "alias",
				content: [{ type: "text", text: "ok" }],
				stop_reason: "end_turn",
				stop_sequence: null,
				usage: {
					input_tokens: 13,
					output_tokens: 7,
					cache_read_input_tokens: 1200,
					cache_creation_input_tokens: 300,
				},
			});
		},
	);
	assert.ok(chat);
	const reply = await chat.doGenerate({
		prompt: [{ role: "user", content: [{ type: "text", text: "Check" }] }],
		maxOutputTokens: 64,
	});
	assert.equal(sent.max_tokens, 64);
	assert.equal(sent.model, "alias");
	assert.equal(reply.usage.inputTokens.noCache, 13);
	assert.equal(reply.usage.inputTokens.cacheRead, 1200);
	assert.equal(reply.usage.inputTokens.cacheWrite, 300);
	assert.equal(reply.usage.inputTokens.total, 1513);
});
