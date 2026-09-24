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
] as const satisfies readonly AiModelBrand[];

export const AI_MODEL_BRAND_LABELS: Record<AiModelBrand, string> = {
	OPENAI: "OpenAI",
	ANTHROPIC: "Anthropic",
	GEMINI: "Google Gemini",
	GEMMA: "Google Gemma",
	META: "Meta",
	MISTRAL: "Mistral AI",
	QWEN: "Qwen",
	DEEPSEEK: "DeepSeek",
};

export const AI_MODEL_BRAND_LOGOS: Record<AiModelBrand, string> = {
	OPENAI: "/brand/ai/openai.svg",
	ANTHROPIC: "/brand/ai/anthropic.svg",
	GEMINI: "/brand/ai/gemini.svg",
	GEMMA: "/brand/ai/gemma.svg",
	META: "/brand/ai/meta.svg",
	MISTRAL: "/brand/ai/mistral.svg",
	QWEN: "/brand/ai/qwen.svg",
	DEEPSEEK: "/brand/ai/deepseek.svg",
};
