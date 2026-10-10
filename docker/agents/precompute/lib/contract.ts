/** The definition contract of a precompute script, as the runner parses it. */
import type {
	EmbeddingModelV4,
	EmbeddingModelV4CallOptions,
	EmbeddingModelV4Result,
	Experimental_DecisionModelV4,
	Experimental_DecisionModelV4CallOptions,
	Experimental_DecisionModelV4Result,
	LanguageModelV4,
	LanguageModelV4CallOptions,
	LanguageModelV4GenerateResult,
	RerankingModelV4,
	RerankingModelV4CallOptions,
	RerankingModelV4Result,
} from "@ai-sdk/provider";
import { z } from "zod";

import { isJsonObject } from "./json.ts";

/** A practice slug as the server writes it: `SandboxLayout.PRACTICE_SLUG`, anchored. */
export const PRACTICE_SLUG = /^[a-z0-9][a-z0-9-]{0,63}$/u;

/** The header that names the practice of every precompute model call, so the proxy can attribute its usage. */
export const PRECOMPUTE_PRACTICE_HEADER = "x-hephaestus-practice";

/**
 * How a practice's script ended, as `<slug>.json` records it. "skipped": a model that the script
 * requires is not available. "timeout": the script was stopped at its deadline, or the stage deadline
 * came before it started.
 */
export const RUN_STATUSES = ["ok", "skipped", "error", "timeout"] as const;
export type RunStatus = (typeof RUN_STATUSES)[number];
/** How a script that returned no result ended: with an error, or at its deadline. */
export type FailedStatus = Extract<RunStatus, "error" | "timeout">;
export const isFailed = (status: RunStatus): status is FailedStatus =>
	status === "error" || status === "timeout";

/** What a precompute script may ask a model to do. One slot, one kind of model. */
export const MODEL_SLOTS = ["chat", "decision", "embedding", "reranking"] as const;
export type ModelSlot = (typeof MODEL_SLOTS)[number];
export const modelSlotSchema = z.enum(MODEL_SLOTS);

/**
 * The most items that one call to a slot's model may hold: decision questions, embedding values and
 * rerank documents. The proxy refuses a larger call on the slot's own API (`LlmProxyService`), so the
 * runner refuses it first, as "too-large", whatever the protocol of the slot's model.
 */
export const MODEL_SLOT_CAPS = {
	decision: 64,
	embedding: 64,
	reranking: 256,
} as const;

/**
 * "required": without a bound model the script does not run, and its section says which model it
 * needs. "optional": the script runs without it and says what it could not rate.
 */
const needSchema = z.enum(["required", "optional"]);
export type Need = z.infer<typeof needSchema>;

/** Why something was not rated. Never "nothing found". */
const REASONS = [
	"unavailable",
	"budget",
	"deadline",
	"too-large",
	"off-format",
	"refused",
	"error",
] as const;
const reasonSchema = z.enum(REASONS);
export type Reason = z.infer<typeof reasonSchema>;

/** One line of text: a newline would let a script write its own headings into the reviewer's turn. */
const line = (max: number) =>
	z
		.string()
		.min(1)
		.max(max)
		.refine((text) => !/[\r\n]/u.test(text), "must be one line");

const LEAD_KIND = /^[a-z][a-z0-9-]{1,47}$/u;

export const metaSchema = z.strictObject({
	models: z.partialRecord(modelSlotSchema, needSchema).optional(),
	/** This practice's token ceiling, inside the stage's own ceiling. */
	tokens: z.int().positive().max(500_000).optional(),
	/** Lead kind → the sentence that the reviewer reads for it. */
	kinds: z.record(z.string().regex(LEAD_KIND), line(300)),
});
export type ParsedMeta = z.infer<typeof metaSchema>;

const scalar = z.union([
	line(200),
	z.number().refine(Number.isFinite, "must be finite"),
	z.boolean(),
]);
const factsSchema = z
	.record(z.string().regex(/^[A-Za-z][A-Za-z0-9]{0,63}$/u), scalar)
	.refine((facts) => Object.keys(facts).length <= 32, "at most 32 facts");
export type Facts = z.infer<typeof factsSchema>;

/**
 * Where a lead points, in the coordinates an observation cites: a line the change adds or removes,
 * a repository file, or a captured record under the context folder. The runner writes the quote.
 */
const citationSchema = z.union([
	z.strictObject({
		change: z.string().min(1),
		line: z.int().positive(),
		endLine: z.int().positive().optional(),
		side: z.enum(["OLD", "NEW"]).optional(),
	}),
	z.strictObject({
		file: z.string().min(1),
		line: z.int().positive(),
		endLine: z.int().positive().optional(),
	}),
	z.strictObject({
		record: z.string().min(1),
		line: z.int().nonnegative().optional(),
	}),
]);
export type Citation = z.infer<typeof citationSchema>;

const ratingSchema = z.strictObject({
	value: z.string().regex(LEAD_KIND),
	score: z.number().min(0).max(1),
});

const leadSchema = z.strictObject({
	at: citationSchema,
	kind: z.string().regex(LEAD_KIND),
	facts: factsSchema.optional(),
	/** A model's answer about the cited place. The reviewer sees that it was rated, never the score. */
	rating: ratingSchema.optional(),
});
export type Lead = z.infer<typeof leadSchema>;

const unratedSchema = z.strictObject({
	what: line(160),
	count: z.int().nonnegative(),
	reason: reasonSchema,
});
export type Unrated = z.infer<typeof unratedSchema>;

export const precomputedSchema = z.strictObject({
	leads: z.array(leadSchema).max(200),
	facts: factsSchema.optional(),
	unrated: z.array(unratedSchema).max(20).optional(),
	/** Directions to the reviewer. The script's author writes them; model text does not belong here. */
	directions: z.array(line(300)).max(10).optional(),
});
export type Precomputed = z.infer<typeof precomputedSchema>;

const PROTOCOLS = [
	"openai-completions",
	"openai-responses",
	"openai-decisions",
	"openai-embeddings",
	"cohere-rerank",
] as const;
const boundModelSchema = z.strictObject({
	protocol: z.enum(PROTOCOLS),
	modelId: z.string().min(1),
	reasoningEffort: z.string().optional(),
});
/**
 * The models the server bound for this job, as `precompute-models.json` holds them: one entry per
 * slot that has a model. A slot without an entry has no model.
 */
export const boundModelsSchema = z.partialRecord(modelSlotSchema, boundModelSchema);
export type BoundModels = z.infer<typeof boundModelsSchema>;

/** The provider model of each slot, its call options, and what a call returns. */
interface SlotTypes {
	chat: {
		model: LanguageModelV4;
		options: LanguageModelV4CallOptions;
		result: LanguageModelV4GenerateResult;
	};
	decision: {
		model: Experimental_DecisionModelV4;
		options: Experimental_DecisionModelV4CallOptions;
		result: Experimental_DecisionModelV4Result;
	};
	embedding: {
		model: EmbeddingModelV4;
		options: EmbeddingModelV4CallOptions;
		result: EmbeddingModelV4Result;
	};
	reranking: {
		model: RerankingModelV4;
		options: RerankingModelV4CallOptions;
		result: RerankingModelV4Result;
	};
}

/** The provider model of each slot: in the runner, in a script's process, and in `ctx.models`. */
export type SlotModels = { [S in ModelSlot]: SlotTypes[S]["model"] };

/** What one slot's model returned, named by its slot. */
export type SlotAnswer<S extends ModelSlot = ModelSlot> = {
	[K in S]: { slot: K; result: SlotTypes[K]["result"] };
}[S];

type CallOptions<S extends ModelSlot> = SlotTypes[S]["options"] & Record<string, unknown>;

export interface SlotCall<S extends ModelSlot> {
	/** The options a script may set. Provider options and headers stay with the runner. */
	options: readonly string[];
	/**
	 * Whether the options hold each field that a call to this slot's model requires, as a list, a record
	 * or text. It also checks the entries of embedding values and rerank documents, and the type and
	 * instructions of each decision question. It does not check a chat prompt's messages, what a
	 * decision state holds, or the optional fields: the provider client checks them, and a call that it
	 * refuses is not rated, with "error".
	 */
	accepts: (options: Record<string, unknown>) => options is CallOptions<S>;
	/** The items a call holds, and the most that one call may hold. */
	items?: { count: (options: Record<string, unknown>) => number; max: number };
	/** Call the model. The tokens are input and output together, or undefined when it reports none. */
	call: (
		model: SlotTypes[S]["model"],
		options: SlotTypes[S]["options"],
	) => Promise<{ value: SlotAnswer; tokens: number | undefined }>;
}

/** The entries of a list, or the keys of a record. */
function size(value: unknown): number {
	if (Array.isArray(value)) {
		return value.length;
	}
	return isJsonObject(value) ? Object.keys(value).length : 0;
}

const sum = (...counts: (number | undefined)[]): number | undefined =>
	counts.every((n) => n === undefined)
		? undefined
		: counts.reduce<number>((s, n) => s + (n ?? 0), 0);

function isChatCall(options: Record<string, unknown>): options is CallOptions<"chat"> {
	return Array.isArray(options.prompt);
}

const QUESTION_TYPES = new Set<unknown>(["boolean", "choice", "score"]);

function isDecisionCall(options: Record<string, unknown>): options is CallOptions<"decision"> {
	const { state, questions } = options;
	return (
		(typeof state === "string" || (typeof state === "object" && state !== null)) &&
		isJsonObject(questions) &&
		Object.values(questions).every(
			(question) =>
				isJsonObject(question) &&
				QUESTION_TYPES.has(question.type) &&
				question.instructions !== undefined,
		)
	);
}

function isEmbeddingCall(options: Record<string, unknown>): options is CallOptions<"embedding"> {
	return Array.isArray(options.values) && options.values.every((v) => typeof v === "string");
}

function isRerankingCall(options: Record<string, unknown>): options is CallOptions<"reranking"> {
	const { query, documents } = options;
	if (typeof query !== "string" || !isJsonObject(documents) || !Array.isArray(documents.values)) {
		return false;
	}
	return documents.type === "text"
		? documents.values.every((v) => typeof v === "string")
		: documents.type === "object" && documents.values.every(isJsonObject);
}

/** How the runner calls each slot's model for a script. */
export const MODEL_SLOT_CALLS = {
	chat: {
		options: [
			"prompt",
			"tools",
			"toolChoice",
			"responseFormat",
			"temperature",
			"topP",
			"seed",
			"stopSequences",
			"maxOutputTokens",
		],
		accepts: isChatCall,
		call: async (model, options) => {
			const result = await model.doGenerate(options);
			const { inputTokens, outputTokens } = result.usage;
			return {
				value: { slot: "chat", result },
				tokens: sum(inputTokens.total, outputTokens.total),
			};
		},
	},
	decision: {
		options: ["state", "questions"],
		accepts: isDecisionCall,
		items: { count: (options) => size(options.questions), max: MODEL_SLOT_CAPS.decision },
		call: async (model, options) => {
			const result = await model.doDecide(options);
			return {
				value: { slot: "decision", result },
				tokens: sum(result.usage?.inputTokens, result.usage?.outputTokens),
			};
		},
	},
	embedding: {
		options: ["values"],
		accepts: isEmbeddingCall,
		items: { count: (options) => size(options.values), max: MODEL_SLOT_CAPS.embedding },
		call: async (model, options) => {
			const result = await model.doEmbed(options);
			return { value: { slot: "embedding", result }, tokens: result.usage?.tokens };
		},
	},
	reranking: {
		options: ["documents", "query", "topN"],
		accepts: isRerankingCall,
		items: {
			count: (options) =>
				size(isJsonObject(options.documents) ? options.documents.values : undefined),
			max: MODEL_SLOT_CAPS.reranking,
		},
		call: async (model, options) => {
			const result = await model.doRerank(options);
			return { value: { slot: "reranking", result }, tokens: undefined };
		},
	},
} satisfies { [S in ModelSlot]: SlotCall<S> };
