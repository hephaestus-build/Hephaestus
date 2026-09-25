import type { WorkspaceAiModel } from "@/api/types.gen";

export type AiModelBrand = NonNullable<WorkspaceAiModel["brand"]>;

export const AI_MODEL_BRANDS = [
	"OPENAI",
	"ANTHROPIC",
	"GEMINI",
	"GEMMA",
	"META",
	"MISTRAL",
	"QWEN",
	"DEEPSEEK",
	"XAI",
	"COHERE",
	"Z_AI",
	"MOONSHOT",
] as const satisfies readonly AiModelBrand[];

export const AI_MODEL_BRAND_META: Record<AiModelBrand, { label: string; src: string }> = {
	OPENAI: { label: "OpenAI", src: "/brand/ai/openai.svg" },
	ANTHROPIC: { label: "Anthropic", src: "/brand/ai/anthropic.svg" },
	GEMINI: { label: "Google Gemini", src: "/brand/ai/gemini.svg" },
	GEMMA: { label: "Google Gemma", src: "/brand/ai/gemma.svg" },
	META: { label: "Meta", src: "/brand/ai/meta.svg" },
	MISTRAL: { label: "Mistral AI", src: "/brand/ai/mistral.svg" },
	QWEN: { label: "Qwen", src: "/brand/ai/qwen.svg" },
	DEEPSEEK: { label: "DeepSeek", src: "/brand/ai/deepseek.svg" },
	XAI: { label: "xAI", src: "/brand/ai/xai.svg" },
	COHERE: { label: "Cohere", src: "/brand/ai/cohere.svg" },
	Z_AI: { label: "Z.ai", src: "/brand/ai/z-ai.svg" },
	MOONSHOT: { label: "Moonshot AI", src: "/brand/ai/moonshot.svg" },
};
