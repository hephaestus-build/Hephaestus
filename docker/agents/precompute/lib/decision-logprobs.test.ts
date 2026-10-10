import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { InvalidResponseDataError, type LanguageModelV4GenerateResult } from "@ai-sdk/provider";
import { experimental_decide } from "ai";
import { MockLanguageModelV4 } from "ai/test";

import { logprobDecisionModel } from "./decision-logprobs.ts";

/** A completion that writes `q1: <sampled>` with the given alternatives at the answer position. */
function answering(
	sampled: string,
	top: { token: string; logprob: number }[],
): LanguageModelV4GenerateResult {
	return {
		content: [{ type: "text", text: `q1:${sampled}` }],
		finishReason: { unified: "stop", raw: "stop" },
		usage: {
			inputTokens: { total: 40, noCache: 40, cacheRead: undefined, cacheWrite: undefined },
			outputTokens: { total: 3, text: 3, reasoning: undefined },
		},
		warnings: [],
		providerMetadata: {
			openai: {
				logprobs: [
					{ token: "q1", logprob: 0, top_logprobs: [] },
					{ token: ":", logprob: 0, top_logprobs: [] },
					{ token: sampled, logprob: top[0]?.logprob ?? 0, top_logprobs: top },
				],
			},
		},
	};
}

async function decideKind(result: LanguageModelV4GenerateResult) {
	const model = logprobDecisionModel(new MockLanguageModelV4({ doGenerate: result }));
	return experimental_decide({
		model,
		state: "// increment the count\ncount += 1;",
		questions: {
			kind: {
				type: "choice",
				instructions: "What kind of comment is it?",
				criteria: { "explains-why": "Gives a reason", "restates-code": "Restates the code" },
			},
		},
	});
}

void describe("a decision model on a chat model's logprobs", () => {
	void test("the distribution over the option letters becomes the answer, normalized over those letters", async () => {
		const { answers, usage } = await decideKind(
			answering(" B", [
				{ token: " B", logprob: Math.log(0.6) },
				{ token: " A", logprob: Math.log(0.2) },
				{ token: " C", logprob: Math.log(0.2) },
			]),
		);
		assert.equal(answers.kind.choice, "restates-code");
		assert.ok(Math.abs((answers.kind.probabilities?.["restates-code"] ?? 0) - 0.75) < 1e-9);
		assert.ok(Math.abs((answers.kind.probabilities?.["explains-why"] ?? 0) - 0.25) < 1e-9);
		assert.equal(usage.inputTokens, 40);
	});

	void test("a score is the mean level of the distribution over the level letters", async () => {
		const { answers } = await experimental_decide({
			model: logprobDecisionModel(
				new MockLanguageModelV4({
					doGenerate: answering(" B", [
						{ token: " B", logprob: Math.log(0.5) },
						{ token: " C", logprob: Math.log(0.5) },
					]),
				}),
			),
			state: "// increment the count\ncount += 1;",
			questions: {
				q: {
					type: "score",
					instructions: "How much does it restate?",
					criteria: [null, null, null],
				},
			},
		});
		assert.equal(answers.q.score, 1.5);
	});

	void test("a boolean is the probability of the letter for true", async () => {
		const { answers } = await experimental_decide({
			model: logprobDecisionModel(
				new MockLanguageModelV4({
					doGenerate: answering(" A", [
						{ token: " A", logprob: Math.log(0.8) },
						{ token: " B", logprob: Math.log(0.2) },
					]),
				}),
			),
			state: "// increment the count\ncount += 1;",
			questions: { q: { type: "boolean", instructions: "Does it only restate the code?" } },
		});
		assert.ok(Math.abs(answers.q.probability - 0.8) < 1e-9);
	});

	void test("a sampled token outside the option letters is off the format, whatever the alternatives hold", async () => {
		await assert.rejects(
			decideKind(
				answering(" Z", [
					{ token: " Z", logprob: Math.log(0.99) },
					{ token: " A", logprob: Math.log(0.01) },
				]),
			),
			(error) => InvalidResponseDataError.isInstance(error),
		);
	});

	void test("a completion without logprobs is off the format", async () => {
		const result = answering(" A", []);
		await assert.rejects(decideKind({ ...result, providerMetadata: undefined }), (error) =>
			InvalidResponseDataError.isInstance(error),
		);
	});
});
