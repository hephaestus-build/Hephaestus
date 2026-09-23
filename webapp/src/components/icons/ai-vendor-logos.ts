import type { WorkspaceAiModel } from "@/api/types.gen";

export type AiVendor = NonNullable<WorkspaceAiModel["maker"]>;

/**
 * The mark a developer recognises for each vendor, served from `public/brand/ai/` (lobe-icons, MIT,
 * see the NOTICE there). A mark only ever names the company behind a model the workspace set up.
 */
export const AI_VENDOR_LOGOS: Record<AiVendor, { label: string; src: string }> = {
	OPENAI: { label: "OpenAI", src: "/brand/ai/openai.svg" },
	AZURE: { label: "Microsoft Azure", src: "/brand/ai/azure.svg" },
	ANTHROPIC: { label: "Anthropic", src: "/brand/ai/anthropic.svg" },
	GEMINI: { label: "Google Gemini", src: "/brand/ai/gemini.svg" },
	GEMMA: { label: "Google Gemma", src: "/brand/ai/gemma.svg" },
	META: { label: "Meta Llama", src: "/brand/ai/meta.svg" },
	MISTRAL: { label: "Mistral AI", src: "/brand/ai/mistral.svg" },
	QWEN: { label: "Qwen", src: "/brand/ai/qwen.svg" },
	DEEPSEEK: { label: "DeepSeek", src: "/brand/ai/deepseek.svg" },
	OLLAMA: { label: "Ollama", src: "/brand/ai/ollama.svg" },
};

/**
 * At most two marks for an answer, the way Artemis pairs a platform with a model maker (Azure and
 * OpenAI, Ollama and Gemma): the first model's platform, then its maker, then the next model's.
 */
export function vendorMarks(models: readonly WorkspaceAiModel[]): AiVendor[] {
	const marks: AiVendor[] = [];
	for (const { platform, maker } of models) {
		for (const vendor of [platform, maker]) {
			if (vendor !== undefined && !marks.includes(vendor)) {
				marks.push(vendor);
			}
		}
	}
	return marks.slice(0, 2);
}
