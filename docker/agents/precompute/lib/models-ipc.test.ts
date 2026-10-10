import assert from "node:assert/strict";
import { test } from "node:test";

import type { ModelSlot, SlotAnswer } from "./contract.ts";
import { ipcModels } from "./models-ipc.ts";

const embedded: SlotAnswer = { slot: "embedding", result: { embeddings: [[1, 0]], warnings: [] } };

void test("a model call crosses to the runner without its signal, and returns the runner's answer", async () => {
	const asked: { slot: ModelSlot; options: unknown; signal: AbortSignal | undefined }[] = [];
	const { embedding } = ipcModels({ embedding: "e" }, async (slot, options, signal) => {
		asked.push({ slot, options, signal });
		return embedded;
	});
	assert.ok(embedding !== undefined);
	const { signal } = new AbortController();
	assert.deepEqual(
		await embedding.doEmbed({ values: ["a"], abortSignal: signal }),
		embedded.result,
	);
	assert.deepEqual(asked, [{ slot: "embedding", options: { values: ["a"] }, signal }]);
});

void test("an answer for another slot is refused", async () => {
	const { decision } = ipcModels({ decision: "d" }, async () => embedded);
	assert.ok(decision !== undefined);
	await assert.rejects(async () => decision.doDecide({ state: "s", questions: {} }), {
		message: "the runner answered a decision call with another slot's result",
	});
});
