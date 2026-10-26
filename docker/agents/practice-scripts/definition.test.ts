import assert from "node:assert/strict";
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { after, describe, test } from "node:test";

import { LABELS } from "./fixtures/every-slot.ts";
import { parseDiff } from "./kits/scm/diff-parser.ts";
import { MODEL_CONCURRENCY } from "./lib/core/concurrency.ts";
import {
	MODEL_SLOT_CAPS,
	PRACTICE_SCRIPT_PRACTICE_HEADER,
	type BoundModels,
	type Candidate,
	type EvidenceLocation,
	type ReviewOptions,
} from "./lib/core/contract.ts";
import { EvidenceReader, noReceipts, type EvidenceSources } from "./lib/core/evidence-reader.ts";
import { resolveLeads, type QuotedLead } from "./lib/core/leads.ts";
import { MAX_OUTPUT_TOKENS } from "./lib/core/model-options.ts";
import { hostModels, ModelBudget } from "./lib/core/models-host.ts";
import { UnratedError } from "./lib/core/practice-script.ts";
import { runPracticeProgram, type ProgramOutcome } from "./lib/core/program-host.ts";
import { InputReader } from "./lib/core/script-inputs.ts";
import { stagePracticeScripts } from "./stage.ts";
import {
	chatMessage,
	chatToolCall,
	startStandIn,
	type StandIn,
	type StandInHandler,
} from "./test/proxy-stand-in.ts";

const fixtures = path.join(import.meta.dirname, "fixtures");
const TOKEN = "practice-script-test-token";
const HEAD = "0".repeat(40);
const BASE = "b".repeat(40);

const roots: string[] = [];
after(async () =>
	Promise.all(roots.map(async (root) => rm(root, { recursive: true, force: true }))),
);

const CHANGE = parseDiff(
	[
		"diff --git a/src/cart.ts b/src/cart.ts",
		"--- a/src/cart.ts",
		"+++ b/src/cart.ts",
		"@@ -1,1 +1,8 @@",
		" export class Cart {",
		"+  // increment the item count by one",
		"+  count += 1;",
		"+  // retry once: the payment API drops the first call after an idle period",
		"+  await pay(order).catch(() => pay(order));",
		"+  // TODO clean this up",
		"+  const legacy = true;",
		"+}",
	].join("\n"),
);

const REVIEWED: Candidate = {
	outcome: "COULD_NOT_DETERMINE",
	summary: "The review leaves the practice open",
	explanation: "The fake review answers every review with this result.",
	citations: [
		{
			sourceKind: "scm.pull-request.core",
			sourcePath: "context/metadata.json",
			path: "context/metadata.json",
			startLine: 1,
			endLine: 1,
			quote: "{",
		},
	],
	evidence: {
		openQuestions: [
			{ openQuestion: "What would the review decide?", wouldSettleIt: "A review session." },
		],
	},
	coverage: {
		source: "host",
		reads: 0,
		truncatedReads: 0,
		failedReads: 0,
		receipts: [],
		complete: true,
	},
};

interface Review {
	options: ReviewOptions;
	leads: QuotedLead[];
}

interface Run {
	outcome: ProgramOutcome;
	reviews: Review[];
}

interface RunOptions {
	budget?: ModelBudget;
	deadlineMs?: number;
	slugs?: string[];
}

async function stage(slugs: Record<string, string>) {
	const { root, practices } = await stagePracticeScripts(slugs);
	await copyFile(path.join(fixtures, "comment-kinds.ts"), path.join(practices, "comment-kinds.ts"));
	roots.push(root);
	const repo = path.join(root, "repo");
	await mkdir(path.join(repo, "lib"), { recursive: true });
	await writeFile(
		path.join(repo, "lib", "diff-parser.ts"),
		"// parses unified diffs\nexport function parseDiff(text: string) {\n\treturn text;\n}\n",
	);
	const context = path.join(root, "context");
	await mkdir(context);
	await writeFile(path.join(context, "comments.json"), '[\n  {"body": "Please split this"}\n]\n');
	await copyFile(
		path.join(fixtures, "pull-request-metadata.json"),
		path.join(context, "metadata.json"),
	);
	const sources: EvidenceSources = {
		repo,
		headRevision: HEAD,
		treeSourceFile: "repos/reviewed/.git/HEAD",
		contextDir: context,
		contextReference: "context",
		change: { base: BASE, head: HEAD, files: CHANGE },
		sourceKindOf: () => "scm.pull-request.core",
	};
	return {
		practices,
		sources,
		inputs: new InputReader({
			practicesDir: practices,
		}),
	};
}

async function runSource(
	source: string,
	bound: BoundModels,
	standIn: StandIn | undefined,
	options: RunOptions & { slugs: string[] },
): Promise<Map<string, Run>> {
	const staged = await stage(Object.fromEntries(options.slugs.map((slug) => [slug, source])));
	const budget = options.budget ?? new ModelBudget(200_000, MODEL_CONCURRENCY);
	const runs = new Map<string, Run>();
	for (const slug of options.slugs) {
		const read = await staged.inputs.read(slug);
		assert.ok("inputs" in read, "error" in read ? read.error : "");
		const reviews: Review[] = [];
		const receipts = noReceipts();
		const outcome = await runPracticeProgram(
			{ script: { path: path.join(staged.practices, `${slug}.ts`), source: Buffer.from(source) } },
			{
				slug,
				inputs: read.inputs,
				files: {
					head: { commit: HEAD },
					change: { base: BASE, head: HEAD, files: new Map(CHANGE) },
				},
				now: "2026-01-01T00:00:00Z",
				deadline: Date.now() + (options.deadlineMs ?? 30_000),
			},
			{
				reader: new EvidenceReader(staged.sources, receipts),
				// Nothing listens there: a test without the stand-in binds no model.
				models: hostModels(bound, standIn?.url ?? "http://127.0.0.1:9", TOKEN, slug),
				budget,
				review: async (reviewOptions) => {
					const leads = await resolveLeads(reviewOptions.leads ?? [], staged.sources);
					reviews.push({ options: reviewOptions, leads });
					return REVIEWED;
				},
				signal: new AbortController().signal,
				log: () => undefined,
				receipts,
				requiredSources: [],
			},
		);
		runs.set(slug, { outcome, reviews });
	}
	return runs;
}

async function run(
	fixture: string,
	bound: BoundModels,
	standIn?: StandIn,
	options: RunOptions = {},
): Promise<Run> {
	const source = await readFile(path.join(fixtures, `${fixture}.ts`), "utf8");
	const runs = await runSource(source, bound, standIn, {
		...options,
		slugs: options.slugs ?? [fixture],
	});
	const first = runs.values().next();
	assert.ok(first.done !== true);
	return first.value;
}

function reviewed({ outcome, reviews }: Run): Review {
	assert.equal(outcome.status, "ok", outcome.error);
	const { coverage: _coverage, ...result } = REVIEWED;
	assert.partialDeepStrictEqual(outcome.candidate, result);
	const [review, ...more] = reviews;
	assert.ok(review !== undefined && more.length === 0, `${reviews.length} reviews`);
	return review;
}

function placeOf(at: EvidenceLocation): string {
	if ("change" in at) {
		return `${at.change}:${at.line}`;
	}
	return "file" in at ? `${at.file}:${at.line}` : at.record;
}

function leadsLabelled(review: Review, label: string): string[] {
	return review.leads
		.filter((lead) => lead.label === label)
		.map((lead) => `${placeOf(lead.at)} ${lead.quote}`);
}

const leadAt = (at: EvidenceLocation) => ({ at, label: "A lead." });

function decisions(request: { body: Record<string, unknown> }) {
	const { questions, input } = request.body;
	assert.ok(typeof input === "string" && Array.isArray(questions), "an OpenAI Decisions request");
	// Each item of a pack is `[i<n>]` on its own line, then the item's first line.
	const items = new Map(
		[...input.matchAll(/\[(?<id>i\d+)\]\n(?<text>[^\n]*)/gu)].map((m) => [
			m.groups?.id,
			m.groups?.text,
		]),
	);
	return {
		model: "stand-in-decisions",
		answers: questions.map((question: { name: string; choices: { value: string }[] }) => {
			const text = items.get(question.name);
			if (text === undefined) {
				throw new Error(`stand-in: no item ${question.name}`);
			}
			let kind = "explains-why";
			if (text.startsWith("Comment: // increment")) {
				kind = "restates-code";
			} else if (text.startsWith("Comment: // TODO")) {
				kind = "untracked-todo";
			}
			return {
				type: "choice",
				name: question.name,
				choice: kind,
				confidence: 0.9,
				probabilities: question.choices.map((c) => ({
					value: c.value,
					probability: c.value === kind ? 0.91 : 0.01,
				})),
			};
		}),
		usage: { input_tokens: 120, output_tokens: 0, total_tokens: 120 },
	};
}

function assertAttributed(standIn: StandIn, practice: string) {
	assert.ok(standIn.requests.length > 0, "a model was called");
	for (const request of standIn.requests) {
		assert.equal(
			request.headers[PRACTICE_SCRIPT_PRACTICE_HEADER],
			practice,
			`${request.slot}/${request.operation}`,
		);
	}
}

async function withStandIn(handler: StandInHandler, body: (standIn: StandIn) => Promise<void>) {
	const standIn = await startStandIn(TOKEN, handler);
	try {
		await body(standIn);
	} finally {
		await standIn.close();
	}
}

const never = async () => Promise.withResolvers<never>().promise;

const TODO_LEAD = "src/cart.ts:6   // TODO clean this up";

void describe("practice programs and their direct models at the host", () => {
	void test("run without any model: code's leads, and each unrated item named with its reason", async () => {
		const result = await run("every-slot", {});
		assert.deepEqual(result.outcome.models, [
			{ slot: "chat", need: "optional", bound: false, failures: {} },
			{ slot: "decision", need: "optional", bound: false, failures: {} },
			{ slot: "embedding", need: "optional", bound: false, failures: {} },
			{ slot: "reranking", need: "optional", bound: false, failures: {} },
		]);
		assert.ok(typeof result.outcome.durationMs === "number" && result.outcome.durationMs >= 0);
		const review = reviewed(result);
		assert.deepEqual(leadsLabelled(review, LABELS.untrackedTodo), [TODO_LEAD]);
		assert.equal(review.leads.length, 1);
		assert.deepEqual(review.options.limitations, [
			{ what: "added comments without a TODO marker", count: 2, reason: "unavailable" },
		]);
		assert.deepEqual(review.options.facts, { comments: 3 });
	});

	void test("a program whose required model is not bound does not run, and its report names the model", async () => {
		const { outcome, reviews } = await run("requires-decision", {});
		assert.equal(outcome.status, "withheld");
		assert.equal(outcome.stopReason, "unavailable");
		assert.deepEqual(outcome.models, [
			{ slot: "decision", need: "required", bound: false, failures: {} },
		]);
		assert.deepEqual(reviews, []);
	});

	void test("rate items through a decision model in one packed call, each lead quoted by the host", async () => {
		await withStandIn(
			async (request) => decisions(request),
			async (standIn) => {
				const result = await run(
					"every-slot",
					{ decision: { protocol: "openai-decisions", modelId: "jev" } },
					standIn,
				);
				assert.equal(standIn.requests.length, 1);
				const [call] = standIn.requests;
				assert.equal(call?.slot, "decision");
				assert.equal(call.operation, "decisions");
				assert.equal(call.body.model, "jev");
				assertAttributed(standIn, "every-slot");
				assert.deepEqual(
					result.outcome.models.find((model) => model.slot === "decision"),
					{ slot: "decision", need: "optional", bound: true, failures: {} },
				);
				assert.equal(result.outcome.calls.decision, 1);
				const review = reviewed(result);
				assert.deepEqual(leadsLabelled(review, LABELS.commentKind), [
					"src/cart.ts:2   // increment the item count by one",
				]);
				assert.deepEqual(
					review.leads
						.filter((lead) => "change" in lead.at && lead.at.line === 6)
						.map((lead) => lead.label),
					[LABELS.untrackedTodo],
				);
			},
		);
	});

	void test("rate items through a chat model's logprobs when the decision slot holds a chat model", async () => {
		await withStandIn(
			async (request) => {
				assert.equal(request.body.logprobs, true);
				const prompt = JSON.stringify(request.body.messages);
				const ids = [...prompt.matchAll(/\[(?<id>q\d+)\] Item \[i\d+\]/gu)].map(
					(m) => m.groups?.id ?? "",
				);
				const content = ids.flatMap((id, i) => [
					{ token: `${i === 0 ? "" : "\n"}${id}`, logprob: 0, top_logprobs: [] },
					{ token: ":", logprob: 0, top_logprobs: [] },
					{
						token: " E",
						logprob: -0.1,
						top_logprobs: [
							{ token: " E", logprob: -0.1 },
							{ token: " A", logprob: -2.4 },
						],
					},
				]);
				return chatMessage(ids.map((id) => `${id}: E`).join("\n"), { logprobs: { content } });
			},
			async (standIn) => {
				const result = await run(
					"every-slot",
					{ decision: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				assert.equal(standIn.requests[0]?.operation, "chat/completions");
				assert.equal(standIn.requests[0].slot, "decision");
				assertAttributed(standIn, "every-slot");
				assert.equal(leadsLabelled(reviewed(result), LABELS.commentKind).length, 2);
			},
		);
	});

	void test("an agent reads the repository with evidence tools that the host answers, and answers through submit", async () => {
		let turn = 0;
		await withStandIn(
			async (request) => {
				if (request.body.response_format !== undefined) {
					return chatMessage('{"result":"code"}');
				}
				turn += 1;
				if (turn === 1) {
					return chatToolCall("grep", { pattern: "export function parseDiff" });
				}
				assert.match(
					JSON.stringify(request.body.messages),
					/lib\/diff-parser\.ts:2: export function parseDiff/u,
				);
				return chatToolCall("submit", { file: "lib/diff-parser.ts", line: 2 }, "call-2");
			},
			async (standIn) => {
				const result = await run(
					"every-slot",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				assert.ok(standIn.requests.every((r) => r.slot === "chat"));
				assertAttributed(standIn, "every-slot");
				assert.equal(result.outcome.coverageReached.reads, 1);
				const review = reviewed(result);
				assert.deepEqual(leadsLabelled(review, LABELS.declaration), [
					"lib/diff-parser.ts:2 export function parseDiff(text: string) {",
				]);
				assert.partialDeepStrictEqual(review.options, {
					leads: [{}, { facts: { agentSteps: 2 } }],
					facts: { changeKind: "code" },
				});
			},
		);
	});

	void test("a chat answer without an output leaves that step unrated and keeps the leads that code found", async () => {
		await withStandIn(
			async (request) =>
				request.body.response_format === undefined
					? chatToolCall("submit", { file: "lib/diff-parser.ts", line: 2 })
					: {
							...chatMessage(""),
							choices: [
								{ index: 0, finish_reason: "length", message: { role: "assistant", content: "" } },
							],
						},
			async (standIn) => {
				const review = reviewed(
					await run(
						"every-slot",
						{ chat: { protocol: "openai-completions", modelId: "qwen" } },
						standIn,
					),
				);
				assert.deepEqual(leadsLabelled(review, LABELS.untrackedTodo), [TODO_LEAD]);
				assert.deepEqual(
					review.options.limitations?.filter((item) => item.what === "kind of the change"),
					[{ what: "kind of the change", count: 1, reason: "off-format" }],
				);
			},
		);
	});

	void test("embed and rerank through their own slots", async () => {
		await withStandIn(
			async (request) => {
				if (request.operation === "embeddings") {
					const { input } = request.body;
					assert.ok(Array.isArray(input));
					return {
						object: "list",
						model: "e",
						data: input.map((_, index) => ({
							object: "embedding",
							index,
							embedding: [1, index === 2 ? 1 : 0],
						})),
						usage: { prompt_tokens: 12, total_tokens: 12 },
					};
				}
				return { results: [{ index: 2, relevance_score: 0.87 }] };
			},
			async (standIn) => {
				const result = await run(
					"every-slot",
					{
						embedding: { protocol: "openai-embeddings", modelId: "e" },
						reranking: { protocol: "cohere-rerank", modelId: "r" },
					},
					standIn,
				);
				assert.deepEqual(standIn.requests.map((r) => `${r.slot}/${r.operation}`).toSorted(), [
					"embedding/embeddings",
					"reranking/rerank",
				]);
				assertAttributed(standIn, "every-slot");
				const review = reviewed(result);
				assert.partialDeepStrictEqual(review.options, { facts: { embeddingDimensions: 2 } });
				assert.deepEqual(leadsLabelled(review, LABELS.mostUnfinished), [TODO_LEAD]);
			},
		);
	});
});

void describe("the attempt's direct model budget", () => {
	void test("the budget holds when calls run at once: reservations are taken before the calls start", async (t) => {
		const attempted = Promise.withResolvers<undefined>();
		let attempts = 0;
		try {
			await withStandIn(
				async () => {
					// Keep refunds behind all four real reservations, regardless of RPC scheduling.
					await attempted.promise;
					return chatMessage("ok");
				},
				async (standIn) => {
					const budget = new ModelBudget(2300, MODEL_CONCURRENCY);
					const runCall = budget.run.bind(budget);
					t.mock.method(
						budget,
						"run",
						async <T>(
							reserve: number,
							signal: AbortSignal,
							call: () => Promise<{ value: T; tokens: number | undefined }>,
						) => {
							const outcome = runCall(reserve, signal, call);
							attempts += 1;
							if (attempts === 4) {
								attempted.resolve(undefined);
							}
							return outcome;
						},
					);
					const review = reviewed(
						await run(
							"parallel-budget",
							{ chat: { protocol: "openai-completions", modelId: "qwen" } },
							standIn,
							{ budget },
						),
					);
					assert.equal(attempts, 4);
					assert.deepEqual(review.options.facts, { answered: 2, refused: 2 });
					assert.equal(standIn.requests.length, 2);
					assert.equal(budget.left, 2300 - 2 * 15);
				},
			);
		} finally {
			attempted.resolve(undefined);
		}
	});

	void test("a call above what the attempt has left is unrated for its budget and never reaches the proxy", async () => {
		await withStandIn(
			async () => chatMessage("ok"),
			async (standIn) => {
				const result = await run(
					"unrated-call",
					{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
					standIn,
					{ budget: new ModelBudget(1, MODEL_CONCURRENCY) },
				);
				assert.deepEqual(reviewed(result).options.facts, { reason: "budget", fast: true });
				assert.deepEqual(result.outcome.models, [
					{ slot: "embedding", need: "required", bound: true, failures: { budget: 1 } },
				]);
				assert.equal(standIn.requests.length, 0);
			},
		);
	});

	void test("a call the program cancels stops at once and is unrated for its deadline", async () => {
		await withStandIn(never, async (standIn) => {
			const result = await run(
				"unrated-call",
				{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
				standIn,
			);
			assert.deepEqual(reviewed(result).options.facts, { reason: "deadline", fast: true });
		});
	});

	void test("a program that returns a candidate after its deadline fails without a candidate", async () => {
		await withStandIn(never, async (standIn) => {
			const { outcome } = await run(
				"slow-model",
				{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
				standIn,
				{ deadlineMs: 1500 },
			);
			assert.equal(outcome.status, "failed", outcome.error);
			assert.equal(outcome.stopReason, "deadline");
			assert.equal(outcome.candidate, undefined);
		});
	});

	for (const usage of [
		{},
		{ prompt_tokens: 10 },
		{ completion_tokens: 5 },
		{ prompt_tokens: -1, completion_tokens: 5 },
	]) {
		void test(`a chat receipt ${JSON.stringify(usage)} keeps its reservation`, async () => {
			await withStandIn(
				async () => ({ ...chatMessage("ok"), usage }),
				async (standIn) => {
					const budget = new ModelBudget(5000, MODEL_CONCURRENCY);
					const source = `import {definePracticeScript,generateText} from "../lib/core/practice-script.ts";
export default definePracticeScript({ meta: { models: { chat: "required" } }, async run(ctx) {
await generateText({ model: ctx.models.chat, prompt: "Say yes.", maxOutputTokens: 1000 });
return ctx.review();
} });`;
					const result = await runSource(
						source,
						{ chat: { protocol: "openai-completions", modelId: "chat" } },
						standIn,
						{ slugs: ["receipt"], budget },
					);
					reviewed(result.get("receipt") ?? assert.fail("the program ran"));
					assert.ok(budget.left < 4000, `reservation retained: ${budget.left}`);
					assert.equal(standIn.requests.length, 1);
				},
			);
		});
	}

	for (const [status, refusal, reason] of [
		[402, "a spending limit", "budget"],
		[413, "too many items", "too-large"],
		[429, "a rate limit", "error"],
	] as const) {
		void test(`a call the proxy refuses with ${status} for ${refusal} is unrated as ${reason}`, async () => {
			await withStandIn(
				async () => ({ status, error: refusal }),
				async (standIn) => {
					const result = await run(
						"unrated-call",
						{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
						standIn,
					);
					assert.deepEqual(reviewed(result).options.facts, { reason, fast: true });
					assert.deepEqual(result.outcome.models, [
						{ slot: "embedding", need: "required", bound: true, failures: { [reason]: 1 } },
					]);
				},
			);
		});
	}

	void test("a call with more items than one call to its model may hold is unrated as too large and never reaches the proxy", async () => {
		await withStandIn(
			async (request) => {
				if (request.operation === "embeddings") {
					const { input } = request.body;
					assert.ok(Array.isArray(input));
					return {
						object: "list",
						model: "e",
						data: input.map((_, index) => ({ object: "embedding", index, embedding: [1, 0] })),
						usage: { prompt_tokens: 1, total_tokens: 1 },
					};
				}
				return { results: [{ index: 0, relevance_score: 0.5 }] };
			},
			async (standIn) => {
				const result = await run(
					"oversize",
					{
						decision: { protocol: "openai-decisions", modelId: "d" },
						embedding: { protocol: "openai-embeddings", modelId: "e" },
						reranking: { protocol: "cohere-rerank", modelId: "r" },
					},
					standIn,
				);
				assert.deepEqual(reviewed(result).options.facts, {
					rerankOver: "too-large",
					rerankAt: "rated",
					decideOver: "too-large",
					embedOver: "too-large",
					embedMany: "rated",
				});
				assert.deepEqual(
					result.outcome.models.map(({ slot, failures }) => ({ slot, failures })),
					[
						{ slot: "decision", failures: { "too-large": 1 } },
						{ slot: "embedding", failures: { "too-large": 1 } },
						{ slot: "reranking", failures: { "too-large": 1 } },
					],
				);
				assert.deepEqual(
					standIn.requests
						.map((r) => {
							const items = r.operation === "rerank" ? r.body.documents : r.body.input;
							return `${r.operation} ${Array.isArray(items) ? items.length : "?"}`;
						})
						.toSorted(),
					[
						`embeddings ${MODEL_SLOT_CAPS.embedding}`,
						"embeddings 1",
						`rerank ${MODEL_SLOT_CAPS.reranking}`,
					].toSorted(),
				);
			},
		);
	});
});

void describe("what the host lets a program send", () => {
	void test("each practice's calls name that practice and no other", async () => {
		await withStandIn(
			async () => ({
				object: "list",
				model: "e",
				data: [{ object: "embedding", index: 0, embedding: [1, 0] }],
				usage: { prompt_tokens: 1, total_tokens: 1 },
			}),
			async (standIn) => {
				await run(
					"unrated-call",
					{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
					standIn,
					{
						slugs: ["first-practice", "second-practice"],
					},
				);
				assert.deepEqual(
					new Set(standIn.requests.map((r) => r.headers[PRACTICE_SCRIPT_PRACTICE_HEADER])),
					new Set(["first-practice", "second-practice"]),
				);
				assert.equal(standIn.requests.length, 2);
			},
		);
	});

	void test("a program with more requests in flight than the host answers is stopped", async () => {
		await withStandIn(never, async (standIn) => {
			const { outcome } = await run(
				"flood",
				{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
				standIn,
			);
			assert.equal(outcome.status, "failed");
			assert.equal(outcome.stopReason, "error");
			assert.equal(outcome.error, "more than 32 requests at once");
		});
	});

	void test("a model call the host cannot read stops that program, not the host", async () => {
		await withStandIn(
			async () => chatMessage("never asked"),
			async (standIn) => {
				const { outcome } = await run(
					"hostile-call",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				assert.equal(outcome.status, "failed");
				assert.equal(outcome.stopReason, "error");
				assert.equal(standIn.requests.length, 0);
			},
		);
	});

	void test("provider options, headers and oversized outputs never leave the host", async () => {
		await withStandIn(
			async () => chatMessage("hi"),
			async (standIn) => {
				reviewed(
					await run(
						"allowlist",
						{ chat: { protocol: "openai-completions", modelId: "qwen" } },
						standIn,
					),
				);
				const [call] = standIn.requests;
				assert.equal(call?.headers["x-sneaky"], undefined);
				assertAttributed(standIn, "allowlist");
				assert.equal(call?.body.n, undefined);
				assert.equal(call?.body.logit_bias, undefined);
				assert.equal(call?.body.max_tokens, MAX_OUTPUT_TOKENS);
				assert.equal(call.body.model, "qwen");
			},
		);
	});

	void test("a Responses chat call asks the provider not to store the response", async () => {
		await withStandIn(
			async () => ({
				id: "resp-1",
				created_at: 0,
				model: "m",
				status: "completed",
				output: [
					{
						id: "msg-1",
						type: "message",
						role: "assistant",
						status: "completed",
						content: [{ type: "output_text", text: "hi", annotations: [] }],
					},
				],
				usage: { input_tokens: 1, output_tokens: 1 },
			}),
			async (standIn) => {
				reviewed(
					await run("allowlist", { chat: { protocol: "openai-responses", modelId: "m" } }, standIn),
				);
				assert.equal(standIn.requests[0]?.body.store, false);
				assertAttributed(standIn, "allowlist");
			},
		);
	});
});

void describe("a program the host refuses", () => {
	void test("a lead its source does not hold is an error of the program, never dropped", async () => {
		const { sources } = await stage({});
		const quoted = await resolveLeads(
			[
				leadAt({ change: "src/cart.ts", line: 2, side: "NEW" }),
				leadAt({ record: "comments.json", line: 2 }),
			],
			sources,
		);
		assert.deepEqual(
			quoted.map((each) => each.quote),
			["  // increment the item count by one", '  {"body": "Please split this"}'],
		);
		for (const at of [
			{ change: "src/cart.ts", line: 400, side: "NEW" as const },
			{ record: "../../../../../../etc/hostname", line: 1 },
			{ file: "lib/missing.ts", line: 1 },
		]) {
			await assert.rejects(resolveLeads([leadAt(at)], sources), (error: unknown) => {
				assert.ok(error instanceof UnratedError);
				assert.equal(error.reason, "error");
				assert.match(
					error.message,
					/^lead 1 \(A lead\.\) points at .*, which its source does not hold$/u,
				);
				return true;
			});
		}
		const runs = await runSource(
			`import {definePracticeScript,generateText} from "../lib/core/practice-script.ts";
export default definePracticeScript({
	meta: {},
	run: async (ctx) => ctx.review({ leads: [{ at: { change: "src/cart.ts", line: 400 }, label: "A lead." }] }),
});
`,
			{},
			undefined,
			{ slugs: ["unheld-lead"] },
		);
		const outcome = runs.get("unheld-lead")?.outcome;
		assert.equal(outcome?.status, "failed");
		assert.equal(outcome.stopReason, "error");
		assert.match(outcome.error ?? "", /which its source does not hold$/u);
	});

	void test("a program whose meta is invalid fails before it says which models it uses", async () => {
		const { outcome } = await run("invalid-meta", {});
		assert.equal(outcome.status, "failed");
		assert.equal(outcome.stopReason, "invalid-program");
		assert.match(outcome.error ?? "", /^meta: models\.decision /u);
		assert.deepEqual(outcome.models, []);
	});
});
