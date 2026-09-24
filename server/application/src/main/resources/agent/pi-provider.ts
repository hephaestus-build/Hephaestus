import { existsSync, readFileSync } from "node:fs";

import type { ModelRuntime } from "@earendil-works/pi-coding-agent";

import { errorText } from "./pi-error-text.ts";
import { hasText } from "./pi-text.ts";

/** One model as {@link ModelRuntime.registerProvider} takes it; the extension-facing type omits sampling. */
export type RegisteredModel = NonNullable<
	Extract<Parameters<ModelRuntime["registerProvider"]>[1], { models?: unknown }>["models"]
>[number];

/** The server's `ReasoningEffort`: OpenAI's effort scale, which the providers that take an effort share. */
export const REASONING_EFFORTS = [
	"NONE",
	"MINIMAL",
	"LOW",
	"MEDIUM",
	"HIGH",
	"XHIGH",
	"MAX",
] as const;
export type ReasoningEffort = (typeof REASONING_EFFORTS)[number];

/** Pi's session thinking level; "off" sends no effort at all. */
export type SessionThinkingLevel = "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";

export interface ProviderConfig {
	apiProtocol?: string;
	modelId?: string;
	/** Absent: the provider's own default applies and no effort is sent. */
	reasoningEffort?: ReasoningEffort;
	contextWindow?: number;
	maxOutputTokens?: number;
}

/**
 * How one configured effort reaches the wire. Pi takes a thinking level for the session and sends the
 * model's `thinkingLevelMap` entry for it, or the level itself: `reasoning_effort` on chat completions,
 * `reasoning.effort` on the responses API. OpenAI's `none` is not a Pi level, so it rides on `minimal`
 * mapped to "none"; `xhigh` and `max` exist for a model only when its map names them. No effort is
 * `off` on a model that does not reason, which sends no reasoning parameter on either protocol — on the
 * responses API a reasoning model at `off` would send `effort: "none"` and switch reasoning off.
 */
export interface ReasoningSetting {
	thinkingLevel: SessionThinkingLevel;
	reasoning: boolean;
	thinkingLevelMap?: Partial<Record<SessionThinkingLevel, string>>;
}

const REASONING_SETTINGS: Record<ReasoningEffort, ReasoningSetting> = {
	NONE: { thinkingLevel: "minimal", reasoning: true, thinkingLevelMap: { minimal: "none" } },
	MINIMAL: { thinkingLevel: "minimal", reasoning: true },
	LOW: { thinkingLevel: "low", reasoning: true },
	MEDIUM: { thinkingLevel: "medium", reasoning: true },
	HIGH: { thinkingLevel: "high", reasoning: true },
	XHIGH: { thinkingLevel: "xhigh", reasoning: true, thinkingLevelMap: { xhigh: "xhigh" } },
	MAX: { thinkingLevel: "max", reasoning: true, thinkingLevelMap: { max: "max" } },
};

export function reasoningSetting(effort: ReasoningEffort | undefined): ReasoningSetting {
	return effort === undefined
		? { thinkingLevel: "off", reasoning: false }
		: REASONING_SETTINGS[effort];
}

function asReasoningEffort(value: unknown): ReasoningEffort | undefined {
	return REASONING_EFFORTS.find((effort) => effort === value);
}

export const PROVIDER_CONFIG_FILENAME = "pi-provider.json";
export const DEFAULT_WORKSPACE_ROOT = "/workspace";
const DEFAULT_CONTEXT_WINDOW = 128_000;
const DEFAULT_MAX_TOKENS = 16_384;

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

function positiveInteger(value: unknown): boolean {
	return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function asProviderConfig(parsed: unknown): ProviderConfig | null {
	if (!isRecord(parsed)) {
		return null;
	}
	if (
		("contextWindow" in parsed && !positiveInteger(parsed.contextWindow)) ||
		("maxOutputTokens" in parsed && !positiveInteger(parsed.maxOutputTokens)) ||
		("reasoningEffort" in parsed && asReasoningEffort(parsed.reasoningEffort) === undefined)
	) {
		return null;
	}
	return {
		apiProtocol: typeof parsed.apiProtocol === "string" ? parsed.apiProtocol : undefined,
		modelId: typeof parsed.modelId === "string" ? parsed.modelId : undefined,
		reasoningEffort: asReasoningEffort(parsed.reasoningEffort),
		contextWindow: typeof parsed.contextWindow === "number" ? parsed.contextWindow : undefined,
		maxOutputTokens:
			typeof parsed.maxOutputTokens === "number" ? parsed.maxOutputTokens : undefined,
	};
}

export function loadProviderConfig(cwd = DEFAULT_WORKSPACE_ROOT): ProviderConfig | null {
	const path = `${cwd}/${PROVIDER_CONFIG_FILENAME}`;
	if (!existsSync(path)) {
		return null;
	}
	try {
		const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
		return asProviderConfig(parsed);
	} catch (error) {
		console.error(`[pi-provider] failed to parse ${path}: ${errorText(error)}`);
		return null;
	}
}

export function registerHephaestusProvider(
	modelRuntime: Pick<ModelRuntime, "registerProvider">,
	config: ProviderConfig | null,
	env: Record<string, string | undefined> = process.env,
): boolean {
	const baseUrl = env.LLM_PROXY_URL;
	if (
		config === null ||
		!hasText(config.apiProtocol) ||
		!hasText(config.modelId) ||
		!hasText(baseUrl) ||
		!hasText(env.LLM_PROXY_TOKEN)
	) {
		return false;
	}

	// The operator's review temperature, when set: a review classifies work against fixed criteria,
	// and the same work should land in the same cell on every run.
	const temperature = Number(env.LLM_SAMPLING_TEMPERATURE);
	const samplingParams =
		env.LLM_SAMPLING_TEMPERATURE !== undefined && Number.isFinite(temperature)
			? { temperature }
			: undefined;
	const setting = reasoningSetting(config.reasoningEffort);
	const model: RegisteredModel = {
		id: config.modelId,
		name: config.modelId,
		reasoning: setting.reasoning,
		...(setting.thinkingLevelMap ? { thinkingLevelMap: setting.thinkingLevelMap } : {}),
		// Pi decides from the base URL whether an OpenAI-compatible endpoint takes reasoning_effort, and
		// ours is the LLM proxy's; the effort was configured for this model, so it is sent.
		...(setting.reasoning && config.apiProtocol === "openai-completions"
			? { compat: { supportsReasoningEffort: true } }
			: {}),
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: config.contextWindow ?? DEFAULT_CONTEXT_WINDOW,
		maxTokens: config.maxOutputTokens ?? DEFAULT_MAX_TOKENS,
		...(samplingParams ? { samplingParams } : {}),
	};
	modelRuntime.registerProvider("hephaestus", {
		name: "Hephaestus Gateway",
		baseUrl,
		apiKey: "$LLM_PROXY_TOKEN",
		authHeader: true,
		headers: hasText(env.TRACEPARENT) ? { traceparent: env.TRACEPARENT } : undefined,
		api: config.apiProtocol,
		models: [model],
	});
	return true;
}
