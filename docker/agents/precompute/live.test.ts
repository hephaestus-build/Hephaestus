/**
 * Live: the runner and a definition script that uses every slot, against real models behind the
 * stand-in proxy routes. `docs/contributor/testing.mdx` § Live precompute models names the keys; without
 * them the tests skip. They assert what the runner guarantees, never what a model judges.
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { cp, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { after, before, test } from "node:test";

import { PRECOMPUTE_PRACTICE_HEADER, type BoundModels } from "./lib/contract.ts";
import { isJsonObject } from "./lib/json.ts";
import { stagePrecompute } from "./stage.ts";
import { startStandIn } from "./test/proxy-stand-in.ts";

const { env } = process;
const chatBase = env.HEPHAESTUS_LIVE_LLM_BASE_URL ?? "";
const chatKey = env.HEPHAESTUS_LIVE_LLM_API_KEY ?? "";
const chatModel = env.HEPHAESTUS_LIVE_LLM_MODEL ?? "";
const decisionBase = env.HEPHAESTUS_LIVE_DECISION_BASE_URL ?? "";
const decisionKey = env.HEPHAESTUS_LIVE_DECISION_API_KEY ?? "";
const decisionModel = env.HEPHAESTUS_LIVE_DECISION_MODEL ?? "";
const embeddingModel = env.HEPHAESTUS_LIVE_EMBEDDING_MODEL ?? "";
const rerankingModel = env.HEPHAESTUS_LIVE_RERANKING_MODEL ?? "";
const live = chatBase !== "" && chatKey !== "" && chatModel !== "";
const TOKEN = "live-precompute-token";

const roots: string[] = [];
after(async () =>
	Promise.all(roots.map(async (root) => rm(root, { recursive: true, force: true }))),
);

const CHANGE = [
	"diff --git a/src/cart.ts b/src/cart.ts",
	"--- a/src/cart.ts",
	"+++ b/src/cart.ts",
	"@@ -1,1 +1,12 @@",
	" export class Cart {",
	"+  // increment the item count by one",
	"+  count += 1;",
	"+  // retry once: the payment API drops the first call after an idle period",
	"+  await pay(order).catch(() => pay(order));",
	"+  // TODO clean this up before release",
	"+  const legacy = true;",
	"+  // timeout in seconds",
	"+  const timeoutMs = 30_000;",
	"+  // TEMP hardcoded for the demo, remove before merge",
	"+  const discount = 0.5;",
	"+}",
].join("\n");

/** Each slot's real endpoint: the stand-in forwards a call there with the real key. */
function upstream(slot: string, operation: string): { url: string; key: string } {
	if (slot === "decision" && operation === "decisions") {
		return { url: `${decisionBase.replace(/\/+$/u, "")}/decisions`, key: decisionKey };
	}
	return { url: `${chatBase.replace(/\/+$/u, "")}/${operation}`, key: chatKey };
}

/**
 * One small request to each self-hosted model before the runs. A host can take minutes to load a
 * model after a pause, which is longer than a script's deadline: the runner then reports the slot as
 * unrated for its deadline, which is correct, but is not what these tests prove.
 */
async function warmUp(): Promise<void> {
	const calls: [string, object][] = [
		[
			"chat/completions",
			{ model: chatModel, messages: [{ role: "user", content: "Hi" }], max_tokens: 1 },
		],
		...(embeddingModel === ""
			? []
			: [["embeddings", { model: embeddingModel, input: ["Hi"] }] as [string, object]]),
		...(rerankingModel === ""
			? []
			: [
					["rerank", { model: rerankingModel, query: "Hi", documents: ["Hi"] }] as [string, object],
				]),
	];
	await Promise.all(
		calls.map(async ([operation, body]) => {
			const { url, key } = upstream("chat", operation);
			await fetch(url, {
				method: "POST",
				headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
				body: JSON.stringify(body),
				signal: AbortSignal.timeout(170_000),
			});
		}),
	);
}

before(
	async () => {
		if (live) {
			await warmUp();
		}
	},
	{ timeout: 180_000 },
);

async function runEverySlot(bound: BoundModels) {
	const standIn = await startStandIn(TOKEN, async (request) => {
		const { url, key } = upstream(request.slot, request.operation);
		const response = await fetch(url, {
			method: "POST",
			headers: { authorization: `Bearer ${key}`, "content-type": "application/json" },
			body: JSON.stringify(request.body),
		});
		const body: unknown = await response.json();
		return response.ok ? body : { status: response.status, body };
	});
	try {
		const { root, practices } = await stagePrecompute({
			"every-slot": await readFile(
				path.join(import.meta.dirname, "fixtures", "every-slot.ts"),
				"utf8",
			),
		});
		roots.push(root);
		await writeFile(path.join(root, "change.diff"), CHANGE);
		// The repository the agent searches: this library, which declares parseDiff.
		await cp(path.join(import.meta.dirname, "lib"), path.join(root, "repo", "lib"), {
			recursive: true,
		});
		await writeFile(path.join(root, "models.json"), JSON.stringify(bound));
		const child = spawn(
			process.execPath,
			[
				path.join(import.meta.dirname, "runner.ts"),
				"--repo",
				path.join(root, "repo"),
				"--diff",
				path.join(root, "change.diff"),
				"--practices",
				practices,
				"--output",
				path.join(root, "out"),
				"--models",
				path.join(root, "models.json"),
				"--stage-ms",
				"170000",
			],
			{
				env: { LLM_PROXY_URL: standIn.url, PRECOMPUTE_PROXY_TOKEN: TOKEN },
				stdio: ["ignore", "ignore", "pipe"],
				timeout: 180_000,
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
		const result: unknown = JSON.parse(
			await readFile(path.join(root, "out", "every-slot.json"), "utf8"),
		);
		assert.ok(isJsonObject(result), "every-slot.json holds an object");
		const section = await readFile(path.join(root, "out", "every-slot.md"), "utf8");
		return { result, section, stderr, requests: standIn.requests };
	} finally {
		await standIn.close();
	}
}

void test(
	"live: every slot through the runner against real models",
	{ skip: !live && "set HEPHAESTUS_LIVE_LLM_* to run", timeout: 200_000 },
	async () => {
		const bound: BoundModels = {
			chat: { protocol: "openai-completions", modelId: chatModel },
			// Without a decision endpoint, the chat model serves the decision slot through its logprobs.
			decision:
				decisionBase === ""
					? { protocol: "openai-completions", modelId: chatModel }
					: { protocol: "openai-decisions", modelId: decisionModel },
			...(embeddingModel === ""
				? {}
				: {
						embedding: { protocol: "openai-embeddings" as const, modelId: embeddingModel },
					}),
			...(rerankingModel === ""
				? {}
				: {
						reranking: { protocol: "cohere-rerank" as const, modelId: rerankingModel },
					}),
		};
		const { result, section, stderr, requests } = await runEverySlot(bound);
		assert.equal(result.status, "ok", `${section}\n${stderr}`);
		for (const name of Object.keys(bound)) {
			assert.ok(
				requests.some((r) => r.slot === name),
				`${name} was called\n${section}`,
			);
			assert.partialDeepStrictEqual(
				result,
				{ models: { [name]: { bound: true, notRated: {} } } },
				`${name} answered every call\n${section}`,
			);
		}
		assert.equal(result.dropped, 0, section);
		assert.ok(requests.every((r) => r.headers.authorization === `Bearer ${TOKEN}`));
		assert.ok(requests.every((r) => r.headers[PRECOMPUTE_PRACTICE_HEADER] === "every-slot"));
	},
);

void test(
	"live: a chat model serves the decision slot through its logprobs",
	{ skip: !live && "set HEPHAESTUS_LIVE_LLM_* to run", timeout: 200_000 },
	async () => {
		const { result, section, requests } = await runEverySlot({
			decision: { protocol: "openai-completions", modelId: chatModel },
		});
		assert.equal(requests.filter((r) => r.slot === "decision").length, 1, section);
		assert.partialDeepStrictEqual(result, { models: { decision: { notRated: {} } } }, section);
	},
);
