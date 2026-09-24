import type { WorkspaceAiModel } from "@/api/types.gen";

export type AiConnectionPlatform = NonNullable<WorkspaceAiModel["connectionPlatform"]>;

export const AI_CONNECTION_PLATFORMS = [
	"AZURE",
	"AWS_BEDROCK",
	"GOOGLE_VERTEX",
	"OPENAI",
	"VERCEL_AI_GATEWAY",
] as const satisfies readonly AiConnectionPlatform[];

export const AI_CONNECTION_PLATFORM_LABELS: Record<AiConnectionPlatform, string> = {
	AZURE: "Azure",
	AWS_BEDROCK: "Amazon Bedrock",
	GOOGLE_VERTEX: "Google Vertex AI",
	OPENAI: "OpenAI API",
	VERCEL_AI_GATEWAY: "Vercel AI Gateway",
};

export const AI_CONNECTION_PLATFORM_LOGOS: Record<AiConnectionPlatform, string> = {
	AZURE: "/brand/ai/azure.svg",
	AWS_BEDROCK: "/brand/ai/bedrock.svg",
	GOOGLE_VERTEX: "/brand/ai/vertex-ai.svg",
	OPENAI: "/brand/ai/openai.svg",
	VERCEL_AI_GATEWAY: "/brand/ai/vercel.svg",
};
