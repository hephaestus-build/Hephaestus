import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { after, describe, test } from "node:test";

import { MODEL_SLOT_CAPS, PRECOMPUTE_PRACTICE_HEADER, type BoundModels } from "./lib/contract.ts";
import { isJsonObject } from "./lib/json.ts";
import { MAX_OUTPUT_TOKENS } from "./lib/models-host.ts";
import { stagePrecompute } from "./stage.ts";
import {
	chatMessage,
	chatToolCall,
	startStandIn,
	type StandIn,
	type StandInHandler,
} from "./test/proxy-stand-in.ts";

const runner = path.join(import.meta.dirname, "runner.ts");
const fixtures = path.join(import.meta.dirname, "fixtures");
const TOKEN = "precompute-test-token";

const roots: string[] = [];
after(async () =>
	Promise.all(roots.map(async (root) => rm(root, { recursive: true, force: true }))),
);

const CHANGE = [
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
].join("\n");

interface Staged {
	output: string;
	json: (slug: string) => Promise<Record<string, unknown>>;
	section: (slug: string) => Promise<string>;
	stderr: string;
}

/**
 * Stages one fixture as a practice, under each of `slugs`, next to a `lib` link, a change, a repository
 * and captured context, then runs the runner.
 */
async function run(
	fixture: string,
	bound: BoundModels,
	standIn?: StandIn,
	args: string[] = [],
	slugs: string[] = [fixture],
): Promise<Staged> {
	const source = await readFile(path.join(fixtures, `${fixture}.ts`), "utf8");
	const { root, practices } = await stagePrecompute(
		Object.fromEntries(slugs.map((slug) => [slug, source])),
	);
	roots.push(root);
	const output = path.join(root, "out");
	await writeFile(path.join(root, "change.diff"), CHANGE);
	await mkdir(path.join(root, "repo", "lib"), { recursive: true });
	await writeFile(
		path.join(root, "repo", "lib", "diff-parser.ts"),
		"// parses unified diffs\nexport function parseDiff(text: string) {\n\treturn text;\n}\n",
	);
	await mkdir(path.join(root, "context"), { recursive: true });
	await writeFile(
		path.join(root, "context", "comments.json"),
		'[\n  {"body": "Please split this"}\n]\n',
	);
	await writeFile(path.join(root, "models.json"), JSON.stringify(bound));
	// Asynchronous: the stand-in answers from this process while the runner waits on it.
	const child = spawn(
		process.execPath,
		[
			runner,
			"--repo",
			path.join(root, "repo"),
			"--diff",
			path.join(root, "change.diff"),
			"--context",
			path.join(root, "context"),
			"--context-reference",
			"context",
			"--practices",
			practices,
			"--output",
			output,
			"--models",
			path.join(root, "models.json"),
			...args,
		],
		{
			env:
				standIn === undefined ? {} : { LLM_PROXY_URL: standIn.url, PRECOMPUTE_PROXY_TOKEN: TOKEN },
			stdio: ["ignore", "ignore", "pipe"],
		},
	);
	const chunks: string[] = [];
	child.stderr.setEncoding("utf8");
	child.stderr.on("data", (chunk: string) => {
		chunks.push(chunk);
	});
	const closed: readonly unknown[] = await once(child, "close");
	const stderr = chunks.join("");
	assert.equal(closed[0], 0, stderr);
	return {
		output,
		stderr,
		json: async (slug) => {
			const parsed: unknown = JSON.parse(await readFile(path.join(output, `${slug}.json`), "utf8"));
			assert.ok(isJsonObject(parsed), `${slug}.json holds an object`);
			return parsed;
		},
		section: async (slug) => readFile(path.join(output, `${slug}.md`), "utf8"),
	};
}

/**
 * Answers a packed classify call: a comment that starts "// increment" restates the code, and one that
 * starts "// TODO" is untracked work.
 */
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

/** Every request that reached the stand-in names the practice whose script made it. */
function assertAttributed(standIn: StandIn, practice: string) {
	assert.ok(standIn.requests.length > 0, "a model was called");
	for (const request of standIn.requests) {
		assert.equal(
			request.headers[PRECOMPUTE_PRACTICE_HEADER],
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

void describe("definition scripts", () => {
	void test("run without any model: rule leads, and each unrated item named with its reason", async () => {
		const { json, section } = await run("every-slot", {});
		const result = await json("every-slot");
		assert.equal(result.contract, "definition");
		assert.equal(result.status, "ok");
		assert.deepEqual(result.models, {
			chat: { need: "optional", bound: false, notRated: {} },
			decision: { need: "optional", bound: false, notRated: {} },
			embedding: { need: "optional", bound: false, notRated: {} },
			reranking: { need: "optional", bound: false, notRated: {} },
		});
		assert.ok(typeof result.durationMs === "number" && result.durationMs >= 0);
		const text = await section("every-slot");
		assert.match(text, /- untracked-todo at `src\/cart\.ts` \[L6\]: `\/\/ TODO clean this up`/u);
		assert.match(
			text,
			/- Not rated: 2 added comments without a TODO marker \(unavailable\)\. Read those yourself\./u,
		);
	});

	void test("a script whose required model is not bound does not run, and its section says which model it needs", async () => {
		const { json, section } = await run("requires-decision", {});
		const result = await json("requires-decision");
		assert.equal(result.status, "skipped");
		assert.deepEqual(result.models, { decision: { need: "required", bound: false, notRated: {} } });
		assert.match(
			await section("requires-decision"),
			/No model is available for a slot that this practice's precompute requires: decision\./u,
		);
	});

	void test("rate items through a decision model in one packed call, the quote written by the runner", async () => {
		await withStandIn(
			async (request) => decisions(request),
			async (standIn) => {
				const { json, section } = await run(
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
				assert.partialDeepStrictEqual(await json("every-slot"), {
					models: { decision: { need: "optional", bound: true, notRated: {} } },
				});
				const text = await section("every-slot");
				assert.match(
					text,
					/- comment-kind \(model-rated\) at `src\/cart\.ts` \[L2\]: `\/\/ increment the item count by one`/u,
				);
				// Code found the TODO, so the model never rates it, and it has one lead.
				assert.deepEqual(text.match(/^- .* at `src\/cart\.ts` \[L6\].*$/gmu), [
					"- untracked-todo at `src/cart.ts` [L6]: `// TODO clean this up`",
				]);
			},
		);
	});

	void test("rate items through a chat model's logprobs when the decision slot holds a chat model", async () => {
		await withStandIn(
			async (request) => {
				assert.equal(request.body.logprobs, true);
				const prompt = JSON.stringify(request.body.messages);
				// One answer line per question, each letter with its distribution.
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
				const { section } = await run(
					"every-slot",
					{ decision: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				assert.equal(standIn.requests[0]?.operation, "chat/completions");
				assert.equal(standIn.requests[0].slot, "decision");
				assertAttributed(standIn, "every-slot");
				// Letter E is the fifth kind, restates-code, for both comments without a TODO marker.
				const text = await section("every-slot");
				assert.equal(text.match(/comment-kind \(model-rated\)/gu)?.length, 2);
			},
		);
	});

	void test("an agent reads the repository with tools that run inside the sandbox, and answers through submit", async () => {
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
				// The tool result came back from the child: the second turn sees it.
				assert.match(
					JSON.stringify(request.body.messages),
					/lib\/diff-parser\.ts:2: export function parseDiff/u,
				);
				return chatToolCall("submit", { file: "lib/diff-parser.ts", line: 2 }, "call-2");
			},
			async (standIn) => {
				const { json, section } = await run(
					"every-slot",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				assert.ok(standIn.requests.every((r) => r.slot === "chat"));
				assertAttributed(standIn, "every-slot");
				assert.match(
					await section("every-slot"),
					/- declaration at `lib\/diff-parser\.ts:2`: `export function parseDiff\(text: string\) \{` \[agentSteps=2\]/u,
				);
				assert.partialDeepStrictEqual(await json("every-slot"), { facts: { changeKind: "code" } });
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
				const { section } = await run(
					"every-slot",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				const text = await section("every-slot");
				assert.match(text, /- untracked-todo at `src\/cart\.ts` \[L6\]/u);
				assert.match(text, /- Not rated: 1 kind of the change \(off-format\)\./u);
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
				const { json, section } = await run(
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
				assert.partialDeepStrictEqual(await json("every-slot"), {
					facts: { embeddingDimensions: 2 },
				});
				assert.match(
					await section("every-slot"),
					/- most-unfinished at `src\/cart\.ts` \[L6\]: `\/\/ TODO clean this up`/u,
				);
			},
		);
	});

	void test("the budget holds when calls run at once: reservations are taken before the calls start", async () => {
		await withStandIn(
			async () => chatMessage("ok"),
			async (standIn) => {
				const { json } = await run(
					"parallel-budget",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				const result = await json("parallel-budget");
				assert.deepEqual(result.facts, { answered: 2, refused: 2 });
				assert.equal(standIn.requests.length, 2);
			},
		);
	});

	void test("a call above the stage's token ceiling is unrated for its budget and never reaches the proxy", async () => {
		await withStandIn(
			async () => chatMessage("ok"),
			async (standIn) => {
				const { json } = await run(
					"unrated-call",
					{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
					standIn,
					["--tokens", "1"],
				);
				const result = await json("unrated-call");
				assert.deepEqual(result.facts, { reason: "budget", fast: true });
				assert.equal(standIn.requests.length, 0);
			},
		);
	});

	void test("a call the script cancels stops at once and is unrated for its deadline", async () => {
		await withStandIn(
			async () => Promise.withResolvers<never>().promise,
			async (standIn) => {
				const { json } = await run(
					"unrated-call",
					{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
					standIn,
				);
				const result = await json("unrated-call");
				assert.deepEqual(result.facts, { reason: "deadline", fast: true });
			},
		);
	});

	void test("a model that never answers leaves its step unrated, and the script still returns its leads", async () => {
		await withStandIn(
			async () => Promise.withResolvers<never>().promise,
			async (standIn) => {
				const { json } = await run(
					"slow-model",
					{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
					standIn,
					["--stage-ms", "8000"],
				);
				const result = await json("slow-model");
				assert.partialDeepStrictEqual(result, {
					status: "ok",
					facts: { reason: "deadline" },
					leads: [{ kind: "found" }],
					models: { embedding: { need: "required", bound: true, notRated: { deadline: 1 } } },
				});
			},
		);
	});

	for (const [status, refusal, reason] of [
		[402, "a spending limit", "budget"],
		[413, "too many items", "too-large"],
		[429, "a rate limit", "error"],
	] as const) {
		void test(`a call the proxy refuses with ${status} for ${refusal} is unrated as ${reason}`, async () => {
			await withStandIn(
				async () => ({ status, error: refusal }),
				async (standIn) => {
					const { json } = await run(
						"unrated-call",
						{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
						standIn,
					);
					const result = await json("unrated-call");
					assert.deepEqual(result.facts, { reason, fast: true });
					assert.partialDeepStrictEqual(result, {
						models: { embedding: { notRated: { [reason]: 1 } } },
					});
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
				const { json } = await run(
					"oversize",
					{
						decision: { protocol: "openai-decisions", modelId: "d" },
						embedding: { protocol: "openai-embeddings", modelId: "e" },
						reranking: { protocol: "cohere-rerank", modelId: "r" },
					},
					standIn,
				);
				const result = await json("oversize");
				assert.deepEqual(result.facts, {
					rerankOver: "too-large",
					rerankAt: "rated",
					decideOver: "too-large",
					embedOver: "too-large",
					embedMany: "rated",
				});
				assert.partialDeepStrictEqual(result, {
					models: {
						decision: { notRated: { "too-large": 1 } },
						embedding: { notRated: { "too-large": 1 } },
						reranking: { notRated: { "too-large": 1 } },
					},
				});
				// Only the rerank at the cap and the two calls that `embedMany` split its values into.
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
					[],
					["first-practice", "second-practice"],
				);
				assert.deepEqual(
					new Set(standIn.requests.map((r) => r.headers[PRECOMPUTE_PRACTICE_HEADER])),
					new Set(["first-practice", "second-practice"]),
				);
				assert.equal(standIn.requests.length, 2);
			},
		);
	});

	void test("a script with more requests in flight than the runner answers is stopped", async () => {
		await withStandIn(
			async () => Promise.withResolvers<never>().promise,
			async (standIn) => {
				const { section } = await run(
					"flood",
					{ embedding: { protocol: "openai-embeddings", modelId: "e" } },
					standIn,
				);
				assert.match(await section("flood"), /- Script failed: more than 32 requests at once/u);
			},
		);
	});

	void test("a model call the runner cannot read stops that script, not the runner", async () => {
		await withStandIn(
			async () => chatMessage("never asked"),
			async (standIn) => {
				const { section } = await run(
					"hostile-call",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
				);
				assert.match(
					await section("hostile-call"),
					/- Script failed: the runner could not answer a model request/u,
				);
				assert.equal(standIn.requests.length, 0);
			},
		);
	});

	void test("provider options, headers and oversized outputs never leave the runner", async () => {
		await withStandIn(
			async () => chatMessage("hi"),
			async (standIn) => {
				await run(
					"allowlist",
					{ chat: { protocol: "openai-completions", modelId: "qwen" } },
					standIn,
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
				await run("allowlist", { chat: { protocol: "openai-responses", modelId: "m" } }, standIn);
				assert.equal(standIn.requests[0]?.body.store, false);
				assertAttributed(standIn, "allowlist");
			},
		);
	});

	void test("leads with an undeclared kind or a citation the source does not hold are dropped and counted", async () => {
		const { json, section } = await run("leads", {});
		const result = await json("leads");
		assert.equal(result.dropped, 3);
		const text = await section("leads");
		assert.match(
			text,
			/- restates-code at `src\/cart\.ts` \[L2\]: `\/\/ increment the item count by one`/u,
		);
		assert.match(
			text,
			/- record-row at `context\/comments\.json:2`: `\{"body": "Please split this"\}`/u,
		);
		assert.match(
			text,
			/- Not rated: 3 lead\(s\) with a kind that the script did not declare or cited lines that the source does not hold \(off-format\)\./u,
		);
	});

	void test("a script whose meta is invalid is a failure whose models are unknown, never one that uses no model", async () => {
		const { json } = await run("invalid-meta", {});
		assert.partialDeepStrictEqual(await json("invalid-meta"), {
			contract: "unknown",
			status: "error",
		});
	});

	void test("a result that breaks the contract is a script failure, never leads", async () => {
		const { json, section } = await run("invalid-result", {});
		assert.match(
			await section("invalid-result"),
			/\*\*Script failed\.\*\*.*\n\n- Script failed: result: leads/su,
		);
		const result = await json("invalid-result");
		assert.partialDeepStrictEqual(result, { contract: "definition", status: "error", leads: [] });
		assert.match(String(result.error), /^result: leads /u);
	});
});
