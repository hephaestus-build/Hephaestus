import type { AvailableLlmModel, PracticeReviewSettings } from "@/api/types.gen";

export const mockAvailableModels: AvailableLlmModel[] = [
	{
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "OpenAI production",
		pricingMode: "PRICED",
		per1mInputUsd: 3,
		per1mOutputUsd: 15,
		reasoningEffort: "MEDIUM",
	},
	{
		id: 2,
		scope: "SHARED",
		displayName: "Local Llama (self-hosted)",
		connectionDisplayName: "On-prem GPU",
		pricingMode: "NO_CHARGE",
	},
	{
		id: 10,
		scope: "WORKSPACE",
		displayName: "My OpenAI key",
		connectionDisplayName: "My provider",
		pricingMode: "UNPRICED",
		reasoningEffort: "MEDIUM",
	},
];

export const mockPracticeReviewSettings: PracticeReviewSettings = {
	etag: '"0"',
	revision: 0,
	cooldownMinutes: 30,
	deliveryStatus: "ACTIVE",
	reviewScope: {
		repositoryMode: "ALL_MONITORED",
		personMode: "ALL_ELIGIBLE",
		repositories: [],
		personUserIds: [],
	},
	coverageSummary: {
		monitoredRepositories: 3,
		coveredRepositories: 3,
		eligiblePeople: 8,
		coveredPeople: 8,
		recentReviewVolume: 42,
		estimateWindowDays: 30,
	},
	deliverToMerged: false,
	cooldownMinutesOverride: 30,
	deliverToMergedOverride: undefined,
	defaultAutonomy: "HUMAN_APPROVAL",
	defaultAutonomyOverride: undefined,
};
