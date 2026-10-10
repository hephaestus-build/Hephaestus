/**
 * AI SDK model objects inside a precompute child. Only the `do*` call crosses to the runner, which
 * holds the credential: the AI SDK's loop, output parsing and tools run in the child.
 */
import { customProvider } from "ai";

import { MODEL_SLOT_CAPS, type ModelSlot, type SlotAnswer, type SlotModels } from "./contract.ts";

/** Sends one model call to the runner and settles with what the runner's model answered. */
type ModelCall = (
	slot: ModelSlot,
	options: unknown,
	signal: AbortSignal | undefined,
) => Promise<SlotAnswer>;

/** What crosses a process boundary: no abort signal, no functions. */
function portable(options: object): unknown {
	return JSON.parse(
		JSON.stringify(options, (key, value: unknown) =>
			key === "abortSignal" || typeof value === "function" ? undefined : value,
		),
	);
}

function isAnswerOf<S extends ModelSlot>(
	answer: SlotAnswer,
	slot: S,
): answer is Extract<SlotAnswer, { slot: S }> {
	return answer.slot === slot;
}

/**
 * Build the child's models for the slots the runner made available. A string model id would reach
 * the AI SDK's default provider, which talks to the internet, so the default provider refuses all.
 */
export function ipcModels(
	available: Partial<Record<ModelSlot, string>>,
	call: ModelCall,
): Partial<SlotModels> {
	globalThis.AI_SDK_DEFAULT_PROVIDER = customProvider({});
	// The reply crosses the process boundary untyped, so its slot is what proves its result's type.
	const result = async <S extends ModelSlot>(
		slot: S,
		options: { abortSignal?: AbortSignal },
	): Promise<SlotAnswer<S>["result"]> => {
		const answer = await call(slot, portable(options), options.abortSignal);
		if (!isAnswerOf(answer, slot)) {
			throw new Error(`the runner answered a ${slot} call with another slot's result`);
		}
		return answer.result;
	};
	const provider = "hephaestus";
	const models: Partial<SlotModels> = {};
	if (available.chat !== undefined) {
		models.chat = {
			specificationVersion: "v4",
			provider,
			modelId: available.chat,
			supportedUrls: {},
			doGenerate: async (options) => result("chat", options),
			doStream: () => {
				throw new Error("precompute calls models with generateText, never streamText");
			},
		};
	}
	if (available.decision !== undefined) {
		models.decision = {
			specificationVersion: "v4",
			provider,
			modelId: available.decision,
			supportedQuestionTypes: ["boolean", "choice", "score"],
			doDecide: async (options) => result("decision", options),
		};
	}
	if (available.embedding !== undefined) {
		models.embedding = {
			specificationVersion: "v4",
			provider,
			modelId: available.embedding,
			// `embedMany` splits its values into calls of this size, so no call is too large.
			maxEmbeddingsPerCall: MODEL_SLOT_CAPS.embedding,
			supportsParallelCalls: true,
			doEmbed: async (options) => result("embedding", options),
		};
	}
	if (available.reranking !== undefined) {
		models.reranking = {
			specificationVersion: "v4",
			provider,
			modelId: available.reranking,
			doRerank: async (options) => result("reranking", options),
		};
	}
	return models;
}
