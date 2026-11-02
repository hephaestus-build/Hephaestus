import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
	Experimental_DecisionUnsupportedQuestionTypeError,
	InvalidResponseDataError,
	UnsupportedFunctionalityError,
	type LanguageModelV4GenerateResult,
} from "@ai-sdk/provider";
import { experimental_decide } from "ai";
import { MockLanguageModelV4 } from "ai/test";

import { logprobDecisionModel } from "./decision-logprobs.ts";

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
	void test("a box question is refused by its type, because a bounding box needs an image", async () => {
		const model = logprobDecisionModel(
			new MockLanguageModelV4({ doGenerate: answering(" A", []) }),
		);
		await assert.rejects(
			async () =>
				model.doDecide({
					state: [{ type: "text", text: "a screenshot" }],
					questions: { where: { type: "box", instructions: "Where is the submit button?" } },
				}),
			(error: unknown) =>
				Experimental_DecisionUnsupportedQuestionTypeError.isInstance(error) &&
				error.questionId === "where" &&
				error.questionType === "box",
		);
	});

	void test("a file part in the state is refused rather than dropped from what the model judges", async () => {
		const model = logprobDecisionModel(
			new MockLanguageModelV4({ doGenerate: answering(" A", []) }),
		);
		await assert.rejects(
			async () =>
				model.doDecide({
					state: [
						{ type: "text", text: "the change" },
						{
							type: "file",
							mediaType: "image/png",
							data: { type: "data", data: new Uint8Array([1]) },
						},
					],
					questions: { met: { type: "boolean", instructions: "Is it met?" } },
				}),
			(error: unknown) => UnsupportedFunctionalityError.isInstance(error),
		);
	});

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

void test("a bounded reasoning completion uses only its visible answer's letter probabilities", async () => {
	const reasoning = [
		{ token: "q1", top_logprobs: [] },
		{ token: ":", top_logprobs: [] },
		{
			token: " B",
			top_logprobs: [
				{ token: " A", logprob: Math.log(0.1) },
				{ token: " B", logprob: Math.log(0.9) },
			],
		},
		{ token: " (analysis)", top_logprobs: [] },
	];
	const answer = answering(" A", [
		{ token: " A", logprob: Math.log(0.8) },
		{ token: " B", logprob: Math.log(0.2) },
	]);
	const model = new MockLanguageModelV4({
		doGenerate: async (options) => {
			assert.ok((options.maxOutputTokens ?? 0) >= 515);
			assert.equal(options.providerOptions?.openai?.reasoningEffort, undefined);
			return {
				...answer,
				content: [
					{ type: "reasoning", text: reasoning.map((entry) => entry.token).join("") },
					...answer.content,
				],
				providerMetadata: {
					openai: {
						logprobs: [
							...reasoning,
							{ token: "q1", top_logprobs: [] },
							{ token: ":", top_logprobs: [] },
							{
								token: " A",
								top_logprobs: [
									{ token: " A", logprob: Math.log(0.8) },
									{ token: " B", logprob: Math.log(0.2) },
								],
							},
						],
					},
				},
				usage: { ...answer.usage, outputTokens: { total: 515, text: 3, reasoning: 512 } },
			};
		},
	});
	const result = await experimental_decide({
		model: logprobDecisionModel(model),
		state: "current work",
		questions: { answer: { type: "boolean", instructions: "Does the work meet the criterion?" } },
	});
	assert.ok(Math.abs(result.answers.answer.probability - 0.8) < 1e-9);
	assert.equal(result.usage.outputTokens, 515);
});

void test("reasoning alone and logprobs that do not match visible text cannot supply a decision", async () => {
	const result = answering(" A", [{ token: " A", logprob: 0 }]);
	for (const content of [
		[{ type: "reasoning" as const, text: "q1: A" }],
		[{ type: "text" as const, text: "q1: B" }],
	]) {
		await assert.rejects(decideKind({ ...result, content }), (error) =>
			InvalidResponseDataError.isInstance(error),
		);
	}
});

void test("a question answered only in reasoning remains unanswered", async () => {
	const result = answering(" A", [{ token: " A", logprob: 0 }]);
	const model = logprobDecisionModel(
		new MockLanguageModelV4({
			doGenerate: {
				...result,
				content: [{ type: "reasoning", text: "q2: A\n" }, ...result.content],
				providerMetadata: {
					openai: {
						logprobs: [
							{ token: "q2", top_logprobs: [] },
							{ token: ":", top_logprobs: [] },
							{ token: " A", top_logprobs: [{ token: " A", logprob: 0 }] },
							{ token: "\n", top_logprobs: [] },
							{ token: "q1", top_logprobs: [] },
							{ token: ":", top_logprobs: [] },
							{ token: " A", top_logprobs: [{ token: " A", logprob: 0 }] },
						],
					},
				},
			},
		}),
	);
	await assert.rejects(
		experimental_decide({
			model,
			state: "current work",
			questions: {
				first: { type: "boolean", instructions: "Is the first criterion met?" },
				second: { type: "boolean", instructions: "Is the second criterion met?" },
			},
		}),
		(error) => InvalidResponseDataError.isInstance(error),
	);
});

for (const [count, stopToken, reasoning] of [
	[4, "<|return|>", true],
	[5, "<|return|>", true],
	[4, "<|im_end|>", false],
	[5, "<|im_end|>", false],
] as const) {
	void test(`${count} visible decision answers retain their probabilities before a ${reasoning ? "reasoning" : "non-reasoning"} provider stop token`, async () => {
		const questions = Object.fromEntries(
			Array.from({ length: count }, (_, index) => [
				`item${index}`,
				{ type: "boolean" as const, instructions: "Is this criterion met?" },
			]),
		);
		const lines = Array.from(
			{ length: count },
			(_, index) => `q${index + 1}: ${index % 2 === 0 ? "A" : "B"}`,
		);
		const tokens = lines.flatMap((_, index) => [
			{ token: "q", top_logprobs: [] },
			{ token: String(index + 1), top_logprobs: [] },
			{ token: ":", top_logprobs: [] },
			{
				token: index % 2 === 0 ? " A" : " B",
				top_logprobs: [
					{ token: " A", logprob: Math.log(index % 2 === 0 ? 0.8 : 0.2) },
					{ token: " B", logprob: Math.log(index % 2 === 0 ? 0.2 : 0.8) },
				],
			},
			...(index < count - 1 ? [{ token: "\n", top_logprobs: [] }] : []),
		]);
		const base = answering(" A", []);
		const { answers } = await experimental_decide({
			model: logprobDecisionModel(
				new MockLanguageModelV4({
					doGenerate: {
						...base,
						content: [
							...(reasoning ? [{ type: "reasoning" as const, text: "q1: B (preview)" }] : []),
							{ type: "text", text: lines.join("\n") },
						],
						providerMetadata: {
							openai: {
								logprobs: [
									...(reasoning ? [{ token: "q1: B (preview)", top_logprobs: [] }] : []),
									...tokens,
									{ token: stopToken, top_logprobs: [] },
								],
							},
						},
					},
				}),
			),
			state: "current work",
			questions,
		});
		for (const [index, answer] of Object.values(answers).entries()) {
			assert.ok(Math.abs(answer.probability - (index % 2 === 0 ? 0.8 : 0.2)) < 1e-9);
		}
	});
}

void test("ordinary trailing text cannot be mistaken for protocol termination", async () => {
	const result = answering(" A", [{ token: " A", logprob: 0 }]);
	await assert.rejects(
		decideKind({
			...result,
			providerMetadata: {
				openai: {
					logprobs: [
						{ token: "q1", top_logprobs: [] },
						{ token: ":", top_logprobs: [] },
						{ token: " A", top_logprobs: [{ token: " A", logprob: 0 }] },
						{ token: " unrelated answer", top_logprobs: [] },
						{ token: "<|im_end|>", top_logprobs: [] },
					],
				},
			},
		}),
		(error) => InvalidResponseDataError.isInstance(error),
	);
});
