import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, test } from "node:test";

import { InvalidResponseDataError } from "@ai-sdk/provider";

import { MockEmbeddingModelV4 } from "ai/test";

import { boundModelsSchema, MODEL_SLOT_CAPS } from "./contract.ts";
import { answerTokens } from "./decision-logprobs.ts";
import {
	allowedOptions,
	hostModels,
	MAX_OUTPUT_TOKENS,
	modelCall,
	ModelBudget,
	reservationOf,
} from "./models-host.ts";

void test("the models file in the shape the server writes gives every slot a model", async () => {
	// PrecomputeContractSyncTest proves that the server writes this file for the same four models.
	const file = new URL("../test/precompute-models.json", import.meta.url);
	const bound = boundModelsSchema.parse(JSON.parse(await readFile(file, "utf8")));
	const models = hostModels(bound, "http://proxy.test/internal/llm", "token", "comment-quality");
	assert.deepEqual(Object.keys(models).toSorted(), ["chat", "decision", "embedding", "reranking"]);
});

const never = new AbortController().signal;

void describe("the precompute budget", () => {
	void test("a call that reports no usage keeps its whole reservation", async () => {
		const budget = new ModelBudget(1000, 1);
		const practice = { left: 500 };
		const outcome = await budget.run(practice, 120, never, async () => ({
			value: "ranked",
			tokens: undefined,
		}));
		assert.deepEqual(outcome, { value: "ranked", tokens: 120 });
		assert.equal(practice.left, 380);
		assert.equal(budget.stageLeft, 880);
	});

	void test("a call that its practice can afford but the stage cannot does not run", async () => {
		let ran = false;
		const outcome = await new ModelBudget(100, 1).run({ left: 500 }, 120, never, async () => {
			ran = true;
			return { value: "late", tokens: 1 };
		});
		assert.deepEqual(outcome, { reason: "budget" });
		assert.equal(ran, false);
	});

	void test("a call that waits for its turn and reaches its deadline does not run", async () => {
		const budget = new ModelBudget(1000, 1);
		const busy = Promise.withResolvers<{ value: string; tokens: number }>();
		const first = budget.run({ left: 500 }, 10, never, async () => busy.promise);
		const deadline = new AbortController();
		let ran = false;
		const waiting = budget.run({ left: 500 }, 10, deadline.signal, async () => {
			ran = true;
			return { value: "late", tokens: 1 };
		});
		deadline.abort();
		assert.deepEqual(await waiting, { reason: "deadline" });
		busy.resolve({ value: "first", tokens: 10 });
		assert.deepEqual(await first, { value: "first", tokens: 10 });
		assert.equal(ran, false);
	});

	void test("an answer off the format is unrated for that reason", async () => {
		const outcome = await new ModelBudget(1000, 1).run({ left: 500 }, 10, never, async () => {
			throw new InvalidResponseDataError({ data: {}, message: "no results" });
		});
		assert.deepEqual(outcome, { reason: "off-format" });
	});
});

void describe("the request allowlist", () => {
	for (const asked of [-1_000_000, 0.5, "4096"]) {
		void test(`an output limit of ${JSON.stringify(asked)} is the cap, so the reservation cannot shrink`, () => {
			const kept = allowedOptions("chat", { prompt: [], maxOutputTokens: asked });
			assert.equal(kept.maxOutputTokens, MAX_OUTPUT_TOKENS);
			assert.ok(reservationOf("chat", kept) > MAX_OUTPUT_TOKENS);
		});
	}

	void test("a decision reserves an answer line for each question", () => {
		const one = reservationOf("decision", { state: "", questions: { a: {} } });
		const thirty = reservationOf("decision", {
			state: "",
			questions: Object.fromEntries(Array.from({ length: 30 }, (_, i) => [`q${i}`, {}])),
		});
		assert.ok(thirty - one >= answerTokens(30) - answerTokens(1), `${one} → ${thirty}`);
	});
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
				message: `its options are not a ${slot} call`,
			});
		});
	}
});
