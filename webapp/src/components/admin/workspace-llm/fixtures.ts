import type { AgentBinding, AvailableLlmModel, PracticeScriptSummary } from "@/api/types.gen";
import { inStoryYear } from "@/stories/story-clock";

export const SERVED_ALONE = {
	IN_HOUSE: ["IN_HOUSE", "CLOUD"],
	CLOUD: ["CLOUD"],
	UNDECLARED: ["UNDECLARED"],
} as const satisfies Record<AgentBinding["dataHandlingTier"], AgentBinding["servedTiers"]>;

export const mockAvailableModels: AvailableLlmModel[] = [
	{
		dataHandlingTier: "CLOUD",
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "OpenAI production",
		brand: "OPENAI",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
		pricingMode: "PRICED",
		per1mInputUsd: 3,
		per1mOutputUsd: 15,
	},
	{
		dataHandlingTier: "IN_HOUSE",
		id: 2,
		scope: "SHARED",
		displayName: "Local Llama (self-hosted)",
		connectionDisplayName: "On-prem GPU",
		brand: "META",
		purposes: ["PRACTICE_REVIEW", "MENTOR", "PRACTICE_DECISION"],
		pricingMode: "NO_CHARGE",
	},
	{
		dataHandlingTier: "UNDECLARED",
		id: 10,
		scope: "WORKSPACE",
		displayName: "My OpenAI key",
		connectionDisplayName: "My provider",
		brand: "OPENAI",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
		pricingMode: "UNPRICED",
	},
];

export const mockDecisionModel: AvailableLlmModel = {
	dataHandlingTier: "CLOUD",
	id: 3,
	scope: "SHARED",
	displayName: "GPT-5 nano decisions",
	connectionDisplayName: "OpenAI decisions",
	brand: "OPENAI",
	purposes: ["PRACTICE_DECISION"],
	pricingMode: "PRICED",
	per1mInputUsd: 0.05,
	per1mOutputUsd: 0.4,
};

export const mockEmbeddingModel: AvailableLlmModel = {
	dataHandlingTier: "IN_HOUSE",
	id: 5,
	scope: "SHARED",
	displayName: "Text embeddings",
	connectionDisplayName: "On-prem embeddings",
	purposes: ["PRACTICE_EMBEDDING"],
	pricingMode: "NO_CHARGE",
};

export const mockRerankModel: AvailableLlmModel = {
	dataHandlingTier: "CLOUD",
	id: 6,
	scope: "SHARED",
	displayName: "Rerank 3.5",
	connectionDisplayName: "Cohere",
	brand: "COHERE",
	purposes: ["PRACTICE_RERANKING"],
	pricingMode: "UNPRICED",
};

export const mockPracticeScriptModels: AvailableLlmModel[] = [
	mockDecisionModel,
	mockEmbeddingModel,
	mockRerankModel,
];

const asOf = {
	reviewId: "review-completed-1",
	jobId: "job-completed-1",
	finishedAt: inStoryYear("10-03T09:00"),
};

export const mockCommentQualityNeeds: PracticeScriptSummary = {
	practiceSlug: "comment-quality",
	practiceName: "Comment quality",
	asOf,
	scriptChanged: false,
	needs: [
		{ slot: "decision", need: "required", unmetTiers: ["IN_HOUSE"] },
		{ slot: "embedding", need: "optional", unmetTiers: [] },
	],
};

export const mockDescribeWhatAndWhyNeeds: PracticeScriptSummary = {
	practiceSlug: "describe-what-and-why",
	practiceName: "Describe what and why",
	asOf,
	scriptChanged: false,
	needs: [{ slot: "embedding", need: "optional", unmetTiers: [] }],
};

export const mockChangedScriptNeeds: PracticeScriptSummary = {
	practiceSlug: "small-pull-requests",
	practiceName: "Small pull requests",
	asOf: null,
	scriptChanged: true,
	needs: [],
};

export const mockPracticeScriptNeeds: PracticeScriptSummary[] = [
	mockCommentQualityNeeds,
	mockDescribeWhatAndWhyNeeds,
	mockChangedScriptNeeds,
];
