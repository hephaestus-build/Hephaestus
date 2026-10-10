import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { Experimental_DecisionMockModelV4, MockLanguageModelV4 } from "ai/test";

import { agent, classify, step, z, type QuestionSet } from "./precompute.ts";

const kinds: QuestionSet<string> = {
	name: "comment kind",
	noun: "comment",
	question: "What kind is it?",
	values: { "explains-why": "Gives a reason", "restates-code": "Restates the code" },
	render: (comment) => comment,
};

void describe("the precompute primitives", () => {
	void test("a choice without a probability is unrated, never a certain rating", async () => {
		const model = new Experimental_DecisionMockModelV4({
			doDecide: async () => ({
				answers: { i1: { type: "choice", choice: "restates-code" } },
				warnings: [],
			}),
		});
		const { ratings, unrated } = await classify({
			model,
			set: kinds,
			items: ["// increment the count"],
			what: "added comments",
		});
		assert.equal(ratings.size, 0);
		assert.deepEqual(unrated, [{ what: "added comments", count: 1, reason: "off-format" }]);
	});

	void test("no items and no model leave nothing unrated, so the reviewer reads no empty line", async () => {
		const { ratings, unrated } = await classify({
			model: undefined,
			set: kinds,
			items: [],
			what: "added comments",
		});
		assert.equal(ratings.size, 0);
		assert.deepEqual(unrated, []);
	});

	void test("an agent that does not submit within its steps is unrated for its budget", async () => {
		const model = new MockLanguageModelV4({
			doGenerate: async () => ({
				content: [{ type: "tool-call", toolCallId: "look-1", toolName: "look", input: "{}" }],
				finishReason: { unified: "tool-calls", raw: "tool_calls" },
				usage: {
					inputTokens: { total: 10, noCache: 10, cacheRead: undefined, cacheWrite: undefined },
					outputTokens: { total: 5, text: 5, reasoning: undefined },
				},
				warnings: [],
			}),
		});
		const outcome = await step("locate", async () =>
			agent({
				model,
				tools: {
					look: { inputSchema: z.object({}), execute: async () => "nothing here" },
				},
				instructions: "Locate the declaration.",
				prompt: "Where is parseDiff declared?",
				schema: z.object({ file: z.string() }),
				maxSteps: 2,
			}),
		);
		assert.deepEqual(outcome, { unrated: "budget" });
		assert.equal(model.doGenerateCalls.length, 2);
	});
});
