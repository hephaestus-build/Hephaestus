import { existsSync, readFileSync } from "node:fs";

import type { ModelRuntime, ProviderModelConfig } from "@earendil-works/pi-coding-agent";

import { errorText } from "./pi-error-text.ts";
import { hasText } from "./pi-text.ts";

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

function positiveInteger(value: unknown): boolean {
	return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function asProviderConfig(parsed: unknown): ProviderConfig | null {
	if (!isRecord(parsed)) {
		return null;
	}
	if (
		("contextWindow" in parsed && !positiveInteger(parsed.contextWindow)) ||
		("maxOutputTokens" in parsed && !positiveInteger(parsed.maxOutputTokens))
	) {
		return null;
	}
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
	modelRuntime: ModelRuntime,
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

	const model: ProviderModelConfig = {
		id: config.modelId,
		name: config.modelId,
		reasoning: Boolean(config.supportsReasoning),
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: config.contextWindow ?? DEFAULT_CONTEXT_WINDOW,
		maxTokens: config.maxOutputTokens ?? DEFAULT_MAX_TOKENS,
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
