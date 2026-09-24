import type { WorkspaceAiModel } from "@/api/types.gen";

export type AiConnectionPlatform = NonNullable<WorkspaceAiModel["connectionPlatform"]>;

export const AI_CONNECTION_PLATFORMS = [
	"LOGOS",
	"VLLM",
	"OLLAMA",
	"AZURE",
	"AWS_BEDROCK",
	"GOOGLE_VERTEX",
	"OPENAI",
	"GOOGLE_AI_STUDIO",
	"ALIBABA_CLOUD",
	"GROQ",
	"FIREWORKS",
	"TOGETHER_AI",
	"DEEPINFRA",
	"NEBIUS",
	"OPENROUTER",
	"CLOUDFLARE_AI_GATEWAY",
	"VERCEL_AI_GATEWAY",
] as const satisfies readonly AiConnectionPlatform[];

export const AI_CONNECTION_PLATFORM_META: Record<
	AiConnectionPlatform,
	{ label: string; src: string }
> = {
	LOGOS: { label: "Logos", src: "/brand/ai/logos.svg" },
	VLLM: { label: "vLLM", src: "/brand/ai/vllm.svg" },
	OLLAMA: { label: "Ollama", src: "/brand/ai/ollama.svg" },
	AZURE: { label: "Microsoft Azure", src: "/brand/ai/azure.svg" },
	AWS_BEDROCK: { label: "Amazon Bedrock", src: "/brand/ai/bedrock.svg" },
	GOOGLE_VERTEX: { label: "Google Vertex AI", src: "/brand/ai/vertex-ai.svg" },
	OPENAI: { label: "OpenAI API", src: "/brand/ai/openai.svg" },
	GOOGLE_AI_STUDIO: { label: "Google AI Studio", src: "/brand/ai/google.svg" },
	ALIBABA_CLOUD: { label: "Alibaba Cloud", src: "/brand/ai/alibaba-cloud.svg" },
	GROQ: { label: "Groq", src: "/brand/ai/groq.svg" },
	FIREWORKS: { label: "Fireworks AI", src: "/brand/ai/fireworks.svg" },
	TOGETHER_AI: { label: "Together AI", src: "/brand/ai/together.svg" },
	DEEPINFRA: { label: "DeepInfra", src: "/brand/ai/deepinfra.svg" },
	NEBIUS: { label: "Nebius", src: "/brand/ai/nebius.svg" },
	OPENROUTER: { label: "OpenRouter", src: "/brand/ai/openrouter.svg" },
	CLOUDFLARE_AI_GATEWAY: { label: "Cloudflare AI Gateway", src: "/brand/ai/cloudflare.svg" },
	VERCEL_AI_GATEWAY: { label: "Vercel AI Gateway", src: "/brand/ai/vercel.svg" },
};
