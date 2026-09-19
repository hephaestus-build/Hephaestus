import { existsSync, readFileSync } from "node:fs";

import type { ModelRuntime } from "@earendil-works/pi-coding-agent";

/** One model as {@link ModelRuntime.registerProvider} takes it; the extension-facing type omits sampling. */
export type RegisteredModel = NonNullable<
	Extract<Parameters<ModelRuntime["registerProvider"]>[1], { models?: unknown }>["models"]
>[number];

import { errorText } from "./pi-error-text.ts";

export interface ProviderConfig {
	apiProtocol?: string;
	modelId?: string;
	supportsReasoning?: boolean;
	contextWindow?: number;
	maxOutputTokens?: number;
}

export const PROVIDER_CONFIG_FILENAME = "pi-provider.json";
export const DEFAULT_WORKSPACE_ROOT = "/workspace";
const DEFAULT_CONTEXT_WINDOW = 128_000;
const DEFAULT_MAX_TOKENS = 16_384;

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

function asProviderConfig(parsed: unknown): ProviderConfig | null {
	if (!isRecord(parsed)) return null;
	const positiveInteger = (value: unknown) =>
		typeof value === "number" && Number.isInteger(value) && value > 0;
	if (
		("contextWindow" in parsed && !positiveInteger(parsed.contextWindow)) ||
		("maxOutputTokens" in parsed && !positiveInteger(parsed.maxOutputTokens))
	)
		return null;
	return {
		apiProtocol: typeof parsed.apiProtocol === "string" ? parsed.apiProtocol : undefined,
		modelId: typeof parsed.modelId === "string" ? parsed.modelId : undefined,
		supportsReasoning: parsed.supportsReasoning === true,
		contextWindow: typeof parsed.contextWindow === "number" ? parsed.contextWindow : undefined,
		maxOutputTokens:
			typeof parsed.maxOutputTokens === "number" ? parsed.maxOutputTokens : undefined,
	};
}

export function loadProviderConfig(cwd = DEFAULT_WORKSPACE_ROOT): ProviderConfig | null {
	const path = `${cwd}/${PROVIDER_CONFIG_FILENAME}`;
	if (!existsSync(path)) return null;
	try {
		const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
		return asProviderConfig(parsed);
	} catch (e) {
		console.error(`[pi-provider] failed to parse ${path}: ${errorText(e)}`);
		return null;
	}
}

export function registerHephaestusProvider(
	modelRuntime: Pick<ModelRuntime, "registerProvider">,
	config: ProviderConfig | null,
	env: Record<string, string | undefined> = process.env,
): boolean {
	const baseUrl = env.LLM_PROXY_URL;
	const hasToken = Boolean(env.LLM_PROXY_TOKEN);
	if (!config?.apiProtocol || !config.modelId || !baseUrl || !hasToken) {
		return false;
	}

	// The operator's review temperature, when set: a review classifies work against fixed criteria,
	// and the same work should land in the same cell on every run.
	const temperature = Number(env.LLM_SAMPLING_TEMPERATURE);
	const samplingParams =
		env.LLM_SAMPLING_TEMPERATURE !== undefined && Number.isFinite(temperature)
			? { temperature }
			: undefined;
	const model: RegisteredModel = {
		id: config.modelId,
		name: config.modelId,
		reasoning: Boolean(config.supportsReasoning),
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
		headers: env.TRACEPARENT ? { traceparent: env.TRACEPARENT } : undefined,
		api: config.apiProtocol,
		models: [model],
	});
	return true;
}
