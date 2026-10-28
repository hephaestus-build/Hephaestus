// The host controls credentials, attribution, allowed model options, and budget reservations.
import { createAnthropic } from "@ai-sdk/anthropic";
import { createOpenAI } from "@ai-sdk/openai";
import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { APICallError, InvalidResponseDataError, type RerankingModelV4 } from "@ai-sdk/provider";
import { defaultSettingsMiddleware, wrapLanguageModel } from "ai";
import { z } from "zod";

import { setTimeout as sleep } from "node:timers/promises";

import {
	CALLS_IN_FLIGHT_REFUSAL,
	MODEL_SLOT_CALLS,
	PRACTICE_SCRIPT_PRACTICE_HEADER,
	PROXY_REFUSAL_HEADER,
	type BoundModels,
	type ModelSlot,
	type Reason,
	type SlotAnswer,
	type SlotCall,
	type SlotModels,
} from "./contract.ts";
import { decisionOutputTokens, logprobDecisionModel } from "./decision-logprobs.ts";
import { registerForcedToolChoice } from "./model-capabilities.ts";
import { MAX_OUTPUT_TOKENS } from "./model-options.ts";
import { reasonOf } from "./practice-script.ts";

const rerankResponseSchema = z.object({
	results: z.array(z.object({ index: z.int().nonnegative(), relevance_score: z.number() })),
});

/**
 * A fetch that asks again while the proxy refuses the call because its attempt already has its most
 * model calls in flight. The wait follows `Retry-After`. The loop ends when the call is aborted, or when
 * the proxy answers otherwise: a call past the attempt's work deadline gets its refusal. Any other
 * answer, a provider's 429 included, goes to the caller as it is. A direct call runs once, so without
 * this wait a full cap would leave the call unrated.
 */
export function waitingWhileCallsInFlight(base: typeof fetch = fetch): typeof fetch {
	return async (input, init) => {
		for (;;) {
			const response = await base(input, init);
			if (
				response.status !== 429 ||
				response.headers.get(PROXY_REFUSAL_HEADER) !== CALLS_IN_FLIGHT_REFUSAL
			) {
				return response;
			}
			await response.body?.cancel();
			const seconds = Number(response.headers.get("retry-after"));
			await sleep(Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : 1000, undefined, {
				signal: init?.signal ?? undefined,
			});
		}
	};
}

// Jina and vLLM omit Cohere's required meta field; read their shared results[].index/relevance_score shape.
function cohereRerankModel(
	proxyFetch: typeof fetch,
	baseURL: string,
	headers: Record<string, string>,
	modelId: string,
): RerankingModelV4 {
	return {
		specificationVersion: "v4",
		provider: "hephaestus",
		modelId,
		doRerank: async ({ documents, query, topN, abortSignal }) => {
			const url = `${baseURL}/rerank`;
			const values =
				documents.type === "text"
					? documents.values
					: documents.values.map((d) => JSON.stringify(d));
			const response = await proxyFetch(url, {
				method: "POST",
				headers: { ...headers, "content-type": "application/json" },
				body: JSON.stringify({ model: modelId, query, documents: values, top_n: topN }),
				signal: abortSignal,
			});
			if (!response.ok) {
				throw new APICallError({
					message: `rerank answered ${response.status}`,
					url,
					requestBodyValues: { model: modelId },
					statusCode: response.status,
				});
			}
			const body: unknown = await response.json();
			const parsed = rerankResponseSchema.safeParse(body);
			if (!parsed.success || parsed.data.results.some((r) => r.index >= values.length)) {
				throw new InvalidResponseDataError({
					data: body,
					message: "rerank answered off the format",
				});
			}
			return {
				ranking: parsed.data.results.map((r) => ({
					index: r.index,
					relevanceScore: r.relevance_score,
				})),
			};
		},
	};
}

/**
 * One practice's models for the bound slots, each through the client of its protocol. Every call
 * names the practice in {@link PRACTICE_SCRIPT_PRACTICE_HEADER}. A protocol that cannot serve its
 * slot leaves the slot without a model.
 */
export function hostModels(
	bound: BoundModels,
	proxyUrl: string,
	token: string,
	practice: string,
	proxyFetch: typeof fetch = waitingWhileCallsInFlight(),
): Partial<SlotModels> {
	const headers = { [PRACTICE_SCRIPT_PRACTICE_HEADER]: practice };
	const settings = (slot: ModelSlot) => ({
		baseURL: `${proxyUrl.replace(/\/+$/u, "")}/practice-script/${slot}`,
		apiKey: token,
		headers,
		fetch: proxyFetch,
	});
	const compatible = (slot: ModelSlot) =>
		createOpenAICompatible({
			name: "hephaestus",
			...settings(slot),
			supportsStructuredOutputs: true,
			includeUsage: true,
		});
	const { chat, decision, embedding, reranking } = bound;
	const models: Partial<SlotModels> = {};
	if (chat?.protocol === "openai-responses") {
		models.chat = wrapLanguageModel({
			model: createOpenAI(settings("chat")).responses(chat.modelId),
			// A stored response makes the next step of a tool loop cite it as an `item_reference`, which
			// OpenAI-compatible servers such as vLLM refuse; nor should a provider keep review content.
			middleware: defaultSettingsMiddleware({
				settings: { providerOptions: { openai: { store: false } } },
			}),
		});
	} else if (chat?.protocol === "openai-completions") {
		models.chat = compatible("chat").chatModel(chat.modelId);
	}
	if (chat?.protocol === "anthropic-messages") {
		models.chat = createAnthropic({
			...settings("chat"),
			baseURL: `${settings("chat").baseURL}/v1`,
			headers: { ...headers, authorization: `Bearer ${token}` },
		})(chat.modelId);
	}
	if (models.chat !== undefined) {
		registerForcedToolChoice(
			models.chat,
			chat?.forcedToolChoice === true && chat.protocol !== "anthropic-messages",
		);
	}
	if (decision?.protocol === "openai-decisions") {
		models.decision = createOpenAI(settings("decision")).decisionModel(decision.modelId);
	} else if (decision?.protocol === "openai-completions") {
		models.decision = logprobDecisionModel(
			createOpenAI(settings("decision")).chat(decision.modelId),
		);
	}
	if (embedding?.protocol === "openai-embeddings") {
		models.embedding = compatible("embedding").embeddingModel(embedding.modelId);
	}
	if (reranking?.protocol === "cohere-rerank") {
		models.reranking = cohereRerankModel(
			proxyFetch,
			settings("reranking").baseURL,
			{ ...headers, authorization: `Bearer ${token}` },
			reranking.modelId,
		);
	}
	return models;
}

/**
 * The tokens reserved for a call before it is made: four characters per token of input, plus the most
 * output the call can write. It is an estimate; the call settles at the tokens that it reports.
 */
export function reservationOf(slot: ModelSlot, options: Record<string, unknown>): number {
	const input = Math.ceil(JSON.stringify(options).length / 4);
	switch (slot) {
		case "chat": {
			return input + Number(options.maxOutputTokens ?? MAX_OUTPUT_TOKENS);
		}
		case "decision": {
			return (
				input +
				decisionOutputTokens(
					Object.keys(z.record(z.string(), z.unknown()).parse(options.questions)).length,
				)
			);
		}
		case "embedding":
		case "reranking": {
			return input + 64;
		}
	}
}

/**
 * The call that a script asked a slot's model for, with only the options a script may set: the tokens
 * to reserve for it and the call itself, or "too-large" when it holds more items than one call to the
 * model may. Options that are not a call to the slot's model throw.
 */
export function modelCall<S extends ModelSlot>(
	slot: S,
	model: SlotModels[S],
	options: unknown,
	signal: AbortSignal,
):
	| { reason: "too-large" }
	| { reserve: number; run: () => Promise<{ value: SlotAnswer; tokens: number | undefined }> } {
	const calls: { [K in ModelSlot]: SlotCall<K> } = MODEL_SLOT_CALLS;
	const entry = calls[slot];
	const kept = entry.options.parse(options);
	if (entry.items !== undefined && entry.items.count(kept) > entry.items.max) {
		return { reason: "too-large" };
	}
	return {
		reserve: reservationOf(slot, kept),
		run: async () => entry.call(model, { ...kept, abortSignal: signal }),
	};
}

function failureOf(signal: AbortSignal, error: unknown): Reason {
	if (signal.aborted) {
		return "deadline";
	}
	const reason = reasonOf(error);
	return reason === "cancelled" ? "deadline" : reason;
}

/**
 * The attempt's account for direct model calls: one token ceiling for every practice's direct calls,
 * and a limit on calls in flight. Tokens are reserved before a call starts and settled when it ends.
 * A call that fails, is cancelled, or reports no usage keeps its reservation, because the provider may
 * have billed it. A review session's own calls are not direct calls: the proxy bounds them.
 */
export class ModelBudget {
	#left: number;
	#free: number;
	#waiting: ((turn: undefined) => void)[] = [];

	constructor(tokens: number, concurrency: number) {
		this.#left = tokens;
		this.#free = concurrency;
	}

	/** The tokens that direct calls of this attempt may still spend. */
	get left(): number {
		return this.#left;
	}

	/**
	 * Run `call` when fewer calls than the limit are in flight and the account holds `reserve` tokens.
	 * A call waiting for its turn holds nothing. The result is the call's value and the tokens charged,
	 * or the reason it did not run or failed.
	 */
	async run<T>(
		reserve: number,
		signal: AbortSignal,
		call: () => Promise<{ value: T; tokens: number | undefined }>,
	): Promise<{ value: T; tokens: number } | { reason: Reason }> {
		try {
			signal.throwIfAborted();
		} catch {
			return { reason: "deadline" };
		}
		if (this.#free > 0) {
			this.#free -= 1;
		} else {
			const turn = Promise.withResolvers<undefined>();
			this.#waiting.push(turn.resolve);
			const abandon = () => {
				this.#waiting = this.#waiting.filter((waiter) => waiter !== turn.resolve);
				turn.reject(signal.reason);
			};
			signal.addEventListener("abort", abandon, { once: true });
			try {
				await turn.promise;
			} catch {
				return { reason: "deadline" };
			} finally {
				signal.removeEventListener("abort", abandon);
			}
		}
		try {
			if (signal.aborted) {
				return { reason: "deadline" };
			}
			if (reserve > this.#left) {
				return { reason: "budget" };
			}
			this.#left -= reserve;
			try {
				const answered = await call();
				const tokens =
					answered.tokens !== undefined &&
					Number.isSafeInteger(answered.tokens) &&
					answered.tokens >= 0
						? answered.tokens
						: reserve;
				signal.throwIfAborted();
				this.#left += reserve - tokens;
				return { value: answered.value, tokens };
			} catch (error) {
				return { reason: failureOf(signal, error) };
			}
		} finally {
			const next = this.#waiting.shift();
			if (next === undefined) {
				this.#free += 1;
			} else {
				next(undefined);
			}
		}
	}
}
