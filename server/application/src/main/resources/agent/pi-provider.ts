import { existsSync, readFileSync } from "node:fs";

import type { ExtensionFactory, ModelRuntime } from "@earendil-works/pi-coding-agent";

import { diagnose, errorKind } from "./pi-diagnostics.ts";
import { hasText } from "./pi-text.ts";
import { isJsonObject as isRecord } from "./work/practice-scripts/lib/core/json.ts";

/** One chat model as {@link ModelRuntime.registerProvider} takes it; the extension-facing type omits sampling. */
export type RegisteredModel = Extract<
	NonNullable<
		Extract<Parameters<ModelRuntime["registerProvider"]>[1], { models?: unknown }>["models"]
	>[number],
	{ reasoning: boolean }
>;

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
	forcedToolChoice?: boolean;
	/** Absent: the provider's own default applies and no effort is sent. */
	reasoningEffort?: ReasoningEffort;
	contextWindow?: number;
	maxOutputTokens?: number;
}

/** Require the recording tool only after exploration has ended or a submission needs correction. */
export function submissionExtension(
	config: ProviderConfig,
	required: () => boolean,
): ExtensionFactory {
	return (pi) => {
		pi.on("before_provider_request", ({ payload }) => {
			if (
				!required() ||
				!isRecord(payload) ||
				config.forcedToolChoice !== true ||
				config.apiProtocol === "anthropic-messages"
			) {
				return;
			}
			const tools: unknown[] = Array.isArray(payload.tools) ? payload.tools : [];
			if (
				config.apiProtocol === "openai-completions" &&
				tools.some(
					(tool) =>
						isRecord(tool) && isRecord(tool.function) && tool.function.name === "submit_result",
				)
			) {
				return {
					...payload,
					tool_choice: { type: "function", function: { name: "submit_result" } },
				};
			}
			if (
				config.apiProtocol === "openai-responses" &&
				tools.some(
					(tool) => isRecord(tool) && tool.type === "function" && tool.name === "submit_result",
				)
			) {
				return { ...payload, tool_choice: { type: "function", name: "submit_result" } };
			}
			if (
				(config.apiProtocol === "google-generative-ai" || config.apiProtocol === "google-vertex") &&
				isRecord(payload.config) &&
				Array.isArray(payload.config.tools) &&
				payload.config.tools.some(
					(tool: unknown) =>
						isRecord(tool) &&
						Array.isArray(tool.functionDeclarations) &&
						tool.functionDeclarations.some(
							(declaration: unknown) =>
								isRecord(declaration) && declaration.name === "submit_result",
						),
				)
			) {
				return {
					...payload,
					config: {
						...payload.config,
						toolConfig: {
							functionCallingConfig: { mode: "ANY", allowedFunctionNames: ["submit_result"] },
						},
					},
				};
			}
			return payload;
		});
	};
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

export function asReasoningEffort(value: unknown): ReasoningEffort | undefined {
	return REASONING_EFFORTS.find((effort) => effort === value);
}

export const PROVIDER_CONFIG_FILENAME = "pi-provider.json";
export const DEFAULT_WORKSPACE_ROOT = "/workspace";
const DEFAULT_CONTEXT_WINDOW = 128_000;
const DEFAULT_MAX_TOKENS = 16_384;

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
		("reasoningEffort" in parsed && asReasoningEffort(parsed.reasoningEffort) === undefined) ||
		("forcedToolChoice" in parsed && typeof parsed.forcedToolChoice !== "boolean")
	) {
		return null;
	}
	return {
		apiProtocol: typeof parsed.apiProtocol === "string" ? parsed.apiProtocol : undefined,
		forcedToolChoice: parsed.forcedToolChoice === true,
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
		diagnose("provider.parse_failed", { error: errorKind(error) }, "provider");
		return null;
	}
}

/** A provider config complete enough to register: it names the protocol and the model. */
export type RegisteredProviderConfig = ProviderConfig & { apiProtocol: string; modelId: string };

/**
 * True once the provider is registered, which needs a complete config and the proxy credentials. A runner that
 * holds one credential for its process names it by its variable; the Heph host passes each turn's credential as
 * `apiKey`, so one turn's calls never carry another's.
 */
export function registerHephaestusProvider(
	modelRuntime: Pick<ModelRuntime, "registerProvider" | "getModel">,
	config: ProviderConfig | null,
	env: Record<string, string | undefined> = process.env,
	apiKey = "$LLM_PROXY_TOKEN",
): config is RegisteredProviderConfig {
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
	const native = config.apiProtocol === "anthropic-messages";
	const candidate = native ? modelRuntime.getModel("anthropic", config.modelId) : undefined;
	const known = candidate?.api === "anthropic-messages" ? candidate : undefined;
	const thinkingLevelMap = native ? known?.thinkingLevelMap : setting.thinkingLevelMap;
	const compat = known?.compat;
	const supportsTemperature =
		compat !== undefined && "supportsTemperature" in compat
			? (compat.supportsTemperature ?? false)
			: false;
	const model: RegisteredModel = {
		id: config.modelId,
		name: config.modelId,
		reasoning: native && known === undefined ? false : setting.reasoning,
		...(thinkingLevelMap ? { thinkingLevelMap } : {}),
		...(native
			? {
					compat: {
						...compat,
						supportsTemperature,
						// The gateway permits client tools and a top-level system prompt. SDK tool discovery
						// and in-band system updates require a different provider surface.
						supportsMidConvoEffort: false,
						supportsMidConvoSystemMessages: false,
						supportsMidConvoToolChanges: false,
					},
				}
			: {}),
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
		apiKey,
		authHeader: true,
		headers: hasText(env.TRACEPARENT) ? { traceparent: env.TRACEPARENT } : undefined,
		api: config.apiProtocol,
		models: [model],
	});
	return true;
}
