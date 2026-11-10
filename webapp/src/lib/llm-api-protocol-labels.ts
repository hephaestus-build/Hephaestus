import type { LlmApiProtocol } from "@/lib/llm-provider-type";

export interface LlmApiProtocolDef {
	label: string;
	/** Shown under the API select while this option is chosen. */
	description?: string;
}

export const LLM_API_PROTOCOL_LABELS = {
	"openai-responses": { label: "Responses API" },
	"openai-completions": {
		label: "Chat Completions API",
		description: "Use only if the endpoint does not serve the Responses API.",
	},
	"anthropic-messages": {
		label: "Messages API (Anthropic)",
		description: "Anthropic’s native API. Reviews always let the model decide when to call tools.",
	},
} satisfies Record<LlmApiProtocol, LlmApiProtocolDef>;

const isProtocol = (key: string): key is LlmApiProtocol =>
	Object.hasOwn(LLM_API_PROTOCOL_LABELS, key);

export const LLM_API_PROTOCOL_SELECT_ITEMS = Object.keys(LLM_API_PROTOCOL_LABELS)
	.filter(isProtocol)
	.map((protocol) => ({
		value: protocol,
		label: LLM_API_PROTOCOL_LABELS[protocol].label,
	}));

export function llmApiProtocolDescription(protocol: LlmApiProtocol): string | undefined {
	const def: LlmApiProtocolDef = LLM_API_PROTOCOL_LABELS[protocol];
	return def.description;
}
