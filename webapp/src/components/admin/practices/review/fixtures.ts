import type {
	AccountRef,
	PracticeReviewSettings,
	ReviewBatch,
	ReviewSchedule,
} from "@/api/types.gen";

export function reviewSettings(
	overrides: Partial<PracticeReviewSettings> = {},
): PracticeReviewSettings {
	return {
		etag: '"0"',
		generatedPaths: {},
		revision: "0",
		cooldownMinutes: 30,
		cooldownMinutesOverride: null,
		defaultFeedbackMode: "APPROVE_FIRST",
		defaultFeedbackModeOverride: null,
		deliverToMerged: true,
		deliverToMergedOverride: null,
		deliveryStatus: "ACTIVE",
		reviewScope: {
			people: "ALL",
			repositories: "ALL",
			accountIds: [],
			selectedRepositories: [],
		},
		scopeSummary: {
			monitoredRepositories: 3,
			selectedRepositories: 3,
			eligiblePeople: 8,
			selectedPeople: 8,
			recentReviewVolume: 42,
			estimateWindowDays: 30,
		},
		...overrides,
	};
}

export const adaAccount: AccountRef = {
	id: "7",
	status: "ACTIVE",
	displayName: "Ada Lovelace",
	identities: [{ provider: "GITHUB", login: "ada", displayName: "Ada Lovelace" }],
};

// Prop fixtures bypass response transformation and therefore supply Dates directly.
export function reviewSchedule(overrides: Partial<ReviewSchedule> = {}): ReviewSchedule {
	return {
		id: "22222222-2222-2222-2222-222222222222",
		itemKind: "scm.pull_request",
		cadence: "DAILY",
		lookbackDays: 2,
		budgetUsd: null,
		enabled: true,
		nextRunAt: new Date("2026-08-10T02:17:00Z"),
		lastRunAt: new Date("2026-08-09T02:17:00Z"),
		createdBy: adaAccount,
		createdAt: new Date("2026-08-01T09:00:00Z"),
		updatedAt: new Date("2026-08-01T09:00:00Z"),
		version: "0",
		...overrides,
	};
}

export function reviewBatch(overrides: Partial<ReviewBatch> = {}): ReviewBatch {
	return {
		id: "11111111-1111-1111-1111-111111111111",
		origin: "BACKFILL",
		itemKind: "scm.pull_request",
		from: new Date("2026-07-08T00:00:00Z"),
		to: new Date("2026-08-07T00:00:00Z"),
		status: "AWAITING_CONFIRMATION",
		pauseReason: null,
		estimatedItems: 128,
		estimatedCostUsd: "15.36",
		budgetUsd: null,
		submittedCount: 0,
		skippedCount: 0,
		failedCount: 0,
		requestedBy: adaAccount,
		confirmedBy: null,
		scheduleId: null,
		reviewIds: [],
		targetItemIds: "",
		createdAt: new Date("2026-08-07T09:00:00Z"),
		startedAt: null,
		finishedAt: null,
		etag: '"0"',
		version: "0",
		...overrides,
	};
}
