import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { APICallError } from "@ai-sdk/provider";

import { Experimental_DecisionMockModelV4, MockLanguageModelV4 } from "ai/test";

import { MODEL_CONCURRENCY } from "./concurrency.ts";
import {
	agent,
	classify,
	isPracticeScriptDefinition,
	readRecordJson,
	reasonOf,
	step,
	UnratedError,
	z,
	type Evidence,
	type QuestionSet,
} from "./practice-script.ts";

const kinds: QuestionSet<string> = {
	name: "comment kind",
	noun: "comment",
	question: "What kind is it?",
	values: { "explains-why": "Gives a reason", "restates-code": "Restates the code" },
	render: (comment) => comment,
};

void describe("the practice script primitives", () => {
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

	void test("a decision model's refusal is unrated as refused", async () => {
		const model = new Experimental_DecisionMockModelV4({
			doDecide: async () => ({ answers: { i1: { type: "refusal" } }, warnings: [] }),
		});
		const { ratings, unrated } = await classify({
			model,
			set: kinds,
			items: ["// increment the count"],
			what: "added comments",
		});
		assert.equal(ratings.size, 0);
		assert.deepEqual(unrated, [{ what: "added comments", count: 1, reason: "refused" }]);
	});

	void test("classify keeps at most MODEL_CONCURRENCY packs in flight and rates every item", async () => {
		let calls = 0;
		let inFlight = 0;
		let most = 0;
		const model = new Experimental_DecisionMockModelV4({
			doDecide: async ({ questions }) => {
				calls += 1;
				inFlight += 1;
				most = Math.max(most, inFlight);
				await delay(5);
				inFlight -= 1;
				return {
					answers: Object.fromEntries(
						Object.keys(questions).map((id) => [
							id,
							{
								type: "choice" as const,
								choice: "explains-why",
								probabilities: { "explains-why": 0.9, "restates-code": 0.1 },
							},
						]),
					),
					warnings: [],
				};
			},
		});
		const items = Array.from({ length: 300 }, (_, i) => `// step ${String(i)}`);
		const { ratings, unrated } = await classify({
			model,
			set: kinds,
			items,
			what: "added comments",
		});
		assert.equal(calls, 10);
		assert.equal(most, MODEL_CONCURRENCY);
		assert.equal(ratings.size, items.length);
		assert.deepEqual(unrated, []);
	});

	void test("items without a model are unrated as unavailable, as one omission", async () => {
		const { ratings, unrated } = await classify({
			model: undefined,
			set: kinds,
			items: ["// a", "// b"],
			what: "added comments",
		});
		assert.equal(ratings.size, 0);
		assert.deepEqual(unrated, [{ what: "added comments", count: 2, reason: "unavailable" }]);
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
	for (const modelId of ["claude-opus-5-5", "anthropic/claude-sonnet-5.5"]) {
		void test(`${modelId} uses auto and can submit after a text-only turn`, async () => {
			let calls = 0;
			const model = new MockLanguageModelV4({
				modelId,
				doGenerate: async () => {
					calls += 1;
					return {
						content:
							calls === 1
								? [{ type: "text", text: "The declaration is in parser.ts." }]
								: [
										{
											type: "tool-call",
											toolCallId: "submit-1",
											toolName: "submit",
											input: '{"file":"parser.ts"}',
										},
									],
						finishReason: { unified: calls === 1 ? "stop" : "tool-calls", raw: "end_turn" },
						usage: {
							inputTokens: { total: 10, noCache: 10, cacheRead: undefined, cacheWrite: undefined },
							outputTokens: { total: 5, text: 5, reasoning: undefined },
						},
						warnings: [],
					};
				},
			});
			const result = await agent({
				model,
				tools: {},
				instructions: "Locate the declaration.",
				prompt: "Where is parseDiff?",
				schema: z.object({ file: z.string() }),
				maxSteps: 3,
			});
			assert.deepEqual(result, { output: { file: "parser.ts" }, steps: 2 });
			assert.ok(model.doGenerateCalls.every((call) => call.toolChoice?.type === "auto"));
			const continuation = model.doGenerateCalls[1];
			assert.ok(continuation);
			assert.ok(continuation.prompt.some((message) => message.role === "assistant"));
			assert.match(JSON.stringify(continuation.prompt), /answer has not been submitted/u);
		});
	}

	void test("auto tool choice has only two reminders and spends one shared step budget", async () => {
		const model = new MockLanguageModelV4({
			modelId: "claude-opus-5-5",
			doGenerate: async () => ({
				content: [{ type: "text", text: "No submission." }],
				finishReason: { unified: "stop", raw: "end_turn" },
				usage: {
					inputTokens: { total: 10, noCache: 10, cacheRead: undefined, cacheWrite: undefined },
					outputTokens: { total: 5, text: 5, reasoning: undefined },
				},
				warnings: [],
			}),
		});
		for (const maxSteps of [2, 8]) {
			const before = model.doGenerateCalls.length;
			await assert.rejects(
				agent({
					model,
					tools: {},
					instructions: "Submit.",
					prompt: "Answer.",
					schema: z.object({ file: z.string() }),
					maxSteps,
				}),
				{ reason: "budget" },
			);
			assert.equal(model.doGenerateCalls.length - before, Math.min(maxSteps, 3));
		}
	});
});

const answered = (statusCode: number) =>
	new APICallError({
		message: "refused",
		url: "http://proxy.test",
		requestBodyValues: {},
		statusCode,
	});

void describe("the context helpers", () => {
	void test("an absent record is null, and malformed JSON or a failed read stops the program", async () => {
		const records: Record<string, string> = {
			"metadata.json": '{"pr_number": 7}',
			"notes.md": "# Notes",
		};
		const evidence: Pick<Evidence, "record"> = {
			record: async (name) => {
				if (name === "broken.json") {
					throw new UnratedError("unavailable", "the read failed");
				}
				const text = records[name];
				return text === undefined ? { status: "ABSENT" } : { status: "FOUND", text };
			},
		};
		assert.deepEqual(await readRecordJson(evidence, "metadata.json"), { pr_number: 7 });
		await assert.rejects(readRecordJson(evidence, "notes.md"), {
			name: "UnratedError",
			reason: "unavailable",
			message: "notes.md is not valid JSON",
		});
		assert.equal(await readRecordJson(evidence, "comments.json"), null);
		await assert.rejects(readRecordJson(evidence, "broken.json"), {
			name: "UnratedError",
			reason: "unavailable",
		});
	});

	void test("a failed call is unrated for its own reason", () => {
		assert.equal(reasonOf(new UnratedError("refused")), "refused");
		assert.equal(reasonOf(answered(402)), "budget");
		assert.equal(reasonOf(answered(413)), "too-large");
		assert.equal(reasonOf(answered(429)), "error");
		assert.equal(reasonOf(new DOMException("late", "TimeoutError")), "deadline");
		assert.equal(reasonOf("thrown text"), "error");
	});

	void test("a definition is a meta object and a run function; anything else is not", () => {
		assert.equal(isPracticeScriptDefinition({ meta: {}, run: async () => ({}) }), true);
		assert.equal(
			isPracticeScriptDefinition(async () => ({})),
			false,
		);
		assert.equal(isPracticeScriptDefinition({ meta: {}, run: "review" }), false);
		assert.equal(isPracticeScriptDefinition({ run: async () => ({}) }), false);
	});
});
