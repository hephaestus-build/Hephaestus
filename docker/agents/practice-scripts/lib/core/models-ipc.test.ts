import assert from "node:assert/strict";
import { test } from "node:test";

import type { ModelSlot, SlotAnswer } from "./contract.ts";
import { ipcModels } from "./models-ipc.ts";
import { agent, z } from "./practice-script.ts";

const embedded: SlotAnswer = { slot: "embedding", result: { embeddings: [[1, 0]], warnings: [] } };

void test("only the slots that the host made available have a model", () => {
	const models = ipcModels({ embedding: "e", reranking: "r" }, async () => embedded);
	assert.deepEqual(Object.keys(models).toSorted(), ["embedding", "reranking"]);
	assert.equal(models.embedding?.modelId, "e");
});

void test("a model call crosses to the host without its signal or a function, and returns the host's answer", async () => {
	const asked: { slot: ModelSlot; options: unknown; signal: AbortSignal | undefined }[] = [];
	const { embedding } = ipcModels({ embedding: "e" }, async (slot, options, signal) => {
		asked.push({ slot, options, signal });
		return embedded;
	});
	assert.ok(embedding !== undefined);
	const { signal } = new AbortController();
	const options = { values: ["a"], abortSignal: signal, headers: { "x-a": "1" } };
	Reflect.set(options, "fetch", () => undefined);
	assert.deepEqual(await embedding.doEmbed(options), embedded.result);
	assert.deepEqual(asked, [
		{ slot: "embedding", options: { values: ["a"], headers: { "x-a": "1" } }, signal },
	]);
});

void test("an answer for another slot is refused", async () => {
	const { decision } = ipcModels({ decision: "d" }, async () => embedded);
	assert.ok(decision !== undefined);
	await assert.rejects(async () => decision.doDecide({ state: "s", questions: {} }), {
		message: "the host answered a decision call with another slot's result",
	});
});

for (const [supported, expected] of [
	[true, "required"],
	[false, "auto"],
] as const) {
	void test(`a custom script uses ${expected} tool choice from the host capability for an opaque alias`, async () => {
		const asked: unknown[] = [];
		const { chat } = ipcModels(
			{ chat: "review-alias" },
			async (_slot, options) => {
				asked.push(options);
				return {
					slot: "chat",
					result: {
						content: [
							{ type: "tool-call", toolCallId: "call-1", toolName: "submit", input: '{"ok":true}' },
						],
						finishReason: { unified: "tool-calls", raw: "tool_use" },
						usage: {
							inputTokens: { total: 1, noCache: 1, cacheRead: undefined, cacheWrite: undefined },
							outputTokens: { total: 1, text: 1, reasoning: undefined },
						},
						warnings: [],
					},
				};
			},
			supported,
		);
		const answer = await agent({
			model: chat,
			tools: {},
			instructions: "Check the work.",
			prompt: "Check.",
			schema: z.object({ ok: z.boolean() }),
		});
		assert.deepEqual(answer.output, { ok: true });
		assert.equal(
			z.object({ toolChoice: z.object({ type: z.string() }) }).parse(asked[0]).toolChoice.type,
			expected,
		);
	});
}
