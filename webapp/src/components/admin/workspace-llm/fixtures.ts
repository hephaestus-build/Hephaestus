import type { ModelAssignment, AvailableLlmModel } from "@/api/types.gen";

export const SERVED_ALONE = {
	IN_HOUSE: ["IN_HOUSE", "CLOUD"],
	CLOUD: ["CLOUD"],
	UNDECLARED: ["UNDECLARED"],
} as const satisfies Record<ModelAssignment["dataHandlingTier"], ModelAssignment["servedTiers"]>;

export const mockAvailableModels: AvailableLlmModel[] = [
	{
		dataHandlingTier: "CLOUD",
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "OpenAI production",
		brand: "OPENAI",
		purposes: ["PRACTICE_REVIEW", "CONVERSATION"],
		pricingMode: "PRICED",
		per1mInputUsd: 3,
		per1mOutputUsd: 15,
		reasoningEffort: "MEDIUM",
	},
	{
		dataHandlingTier: "IN_HOUSE",
		id: 2,
		scope: "SHARED",
		displayName: "Local Llama (self-hosted)",
		connectionDisplayName: "On-prem GPU",
		brand: "META",
		purposes: ["PRACTICE_REVIEW", "CONVERSATION"],
		pricingMode: "NO_CHARGE",
	},
	{
		dataHandlingTier: "UNDECLARED",
		id: 10,
		scope: "WORKSPACE",
		displayName: "My OpenAI key",
		connectionDisplayName: "My provider",
		brand: "OPENAI",
		purposes: ["PRACTICE_REVIEW", "CONVERSATION"],
		pricingMode: "UNPRICED",
		reasoningEffort: "MEDIUM",
	},
];
