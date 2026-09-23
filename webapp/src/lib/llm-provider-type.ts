/**
 * Create-time presets, presentation only: a persisted connection keeps its endpoint, protocol and
 * credential shape but not the preset it was seeded from.
 */
export type ProviderPreset = "OPENAI" | "AZURE_OPENAI_V1" | "OTHER";
export type LlmAuthMode = "BEARER" | "API_KEY";

export const PROVIDER_PRESET_ORDER: readonly ProviderPreset[] = [
	"OPENAI",
	"AZURE_OPENAI_V1",
	"OTHER",
];

export const PROVIDER_PRESET_LABELS: Record<ProviderPreset, string> = {
	OPENAI: "OpenAI",
	AZURE_OPENAI_V1: "Azure OpenAI v1",
	OTHER: "Other OpenAI-compatible endpoint",
};

export const PROVIDER_PRESET_SELECT_ITEMS = PROVIDER_PRESET_ORDER.map((preset) => ({
	value: preset,
	label: PROVIDER_PRESET_LABELS[preset],
}));

export const API_PROTOCOLS = {
	OPENAI_COMPLETIONS: "openai-completions",
	OPENAI_RESPONSES: "openai-responses",
} as const;

export function defaultProtocolFor(useResponsesApi = true): string {
	return useResponsesApi ? API_PROTOCOLS.OPENAI_RESPONSES : API_PROTOCOLS.OPENAI_COMPLETIONS;
}

export function usesResponsesApi(apiProtocol: string): boolean {
	return apiProtocol === API_PROTOCOLS.OPENAI_RESPONSES;
}

export function authModeDefaultFor(preset: ProviderPreset): LlmAuthMode {
	return preset === "AZURE_OPENAI_V1" ? "API_KEY" : "BEARER";
}

export function baseUrlDefaultFor(preset: ProviderPreset): string {
	switch (preset) {
		case "OPENAI": {
			return "https://api.openai.com/v1";
		}
		case "AZURE_OPENAI_V1": {
			return "https://RESOURCE.openai.azure.com/openai/v1";
		}
		case "OTHER": {
			return "";
		}
	}
}

export interface OpenAiConnectionIdentity {
	apiProtocol: string;
	baseUrl: string;
}

/** Azure is not inferred back from a stored connection; the preset only seeds the create form. */
export function presetForConnection(connection: OpenAiConnectionIdentity): ProviderPreset {
	try {
		if (new URL(connection.baseUrl).hostname.toLowerCase() === "api.openai.com") {
			return "OPENAI";
		}
	} catch {
		// An unparseable stored URL stays editable as a generic endpoint.
	}
	return "OTHER";
}
