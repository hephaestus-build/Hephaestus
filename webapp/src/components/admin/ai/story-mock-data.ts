import type { AgentJob, AvailableLlmModel, PracticeReviewSettings } from "@/api/types.gen";
import { minutesAfter } from "@/components/common/story-clock";

export const mockAvailableModels: AvailableLlmModel[] = [
	{
		processingLocation: "UNCLASSIFIED",
		id: 1,
		scope: "SHARED",
		displayName: "GPT-5",
		connectionDisplayName: "OpenAI production",
		pricingMode: "PRICED",
		per1mInputUsd: 3,
		per1mOutputUsd: 15,
		supportsReasoning: true,
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 2,
		scope: "SHARED",
		displayName: "Local Llama (self-hosted)",
		connectionDisplayName: "On-prem GPU",
		pricingMode: "NO_CHARGE",
		supportsReasoning: false,
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 10,
		scope: "WORKSPACE",
		displayName: "My OpenAI key",
		connectionDisplayName: "My provider",
		pricingMode: "UNPRICED",
		supportsReasoning: true,
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

const pullRequestTarget: AgentJob["target"] = {
	id: 42,
	type: "scm.pull_request",
	provider: "GITHUB",
	number: 1420,
	repositoryName: "ls1intum/Hephaestus",
	title: "Make practice review output visible",
	url: "https://github.com/ls1intum/Hephaestus/pull/1423",
};
const issueTarget: AgentJob["target"] = {
	id: 43,
	type: "scm.issue",
	provider: "GITHUB",
	number: 1420,
	repositoryName: "ls1intum/Hephaestus",
	title: "Admin read surface for observations and prepared feedback",
};

export const mockJobCompleted: AgentJob = {
	id: "job-completed-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "COMPLETED",
	model: "gpt-5.4-mini",
	configSnapshot: { name: "Default reviewer", llmProvider: "OPENAI" },
	createdAt: new Date("2026-05-20T10:00:00Z"),
	availableAt: new Date("2026-05-20T10:00:00Z"),
	completedAt: new Date("2026-05-20T10:05:00Z"),
	deliveryStatus: "DELIVERED",
	llmModel: "openai/gpt-oss-120b",
	llmTotalInputTokens: 24_000,
	llmTotalOutputTokens: 914,
	llmTotalReasoningTokens: 120,
	llmTotalCalls: 7,
	retryCount: 0,
	exitCode: 0,
};

export const mockJobRunning: AgentJob = {
	id: "job-running-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "RUNNING",
	model: "openai/gpt-oss-120b",
	configSnapshot: { name: "GPU gateway (OpenAI)", llmProvider: "OPENAI" },
	createdAt: new Date("2026-05-20T11:58:00Z"),
	availableAt: new Date("2026-05-20T11:58:00Z"),
	retryCount: 0,
};

export const mockJobFailedDelivery: AgentJob = {
	id: "job-failed-delivery-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "COMPLETED",
	model: "gpt-5.4-mini",
	configSnapshot: { name: "Default reviewer" },
	createdAt: new Date("2026-05-20T09:00:00Z"),
	availableAt: new Date("2026-05-20T09:02:00Z"),
	completedAt: new Date("2026-05-20T09:05:00Z"),
	deliveryStatus: "FAILED",
	errorMessage: "GitLab API returned 403 when posting the MR note.",
	llmModel: "openai/gpt-oss-120b",
	llmTotalInputTokens: 31_000,
	llmTotalOutputTokens: 1_200,
	retryCount: 1,
	exitCode: 0,
};

export const mockJobQueued: AgentJob = {
	id: "job-queued-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "QUEUED",
	model: "gpt-5.4-mini",
	configSnapshot: { name: "Default reviewer" },
	createdAt: new Date("2026-05-20T12:00:00Z"),
	availableAt: new Date("2026-05-20T12:00:00Z"),
	retryCount: 0,
};

/**
 * A run waiting on the clock anchors `availableAt` to the story clock, not to the otherwise fixed
 * scene date: an `availableAt` in the past renders the "due …" phrase as history.
 */
export const mockJobHeldOnBudget: AgentJob = {
	id: "job-held-budget-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "QUEUED",
	model: "gpt-5.4-nano",
	configSnapshot: { name: "Default reviewer" },
	createdAt: new Date("2026-05-20T12:01:00Z"),
	availableAt: minutesAfter(5),
	holdReason: "BUDGET",
	retryCount: 0,
};

/** A reason this client has never heard of, which still has to read as English. */
export const mockJobHeldForUnknownReason: AgentJob = {
	id: "job-held-unknown-1",
	jobType: "ISSUE_REVIEW",
	reviewOutcome: "REVIEWED",
	target: issueTarget,
	status: "QUEUED",
	model: "gpt-5.4-nano",
	configSnapshot: { name: "Default reviewer" },
	createdAt: new Date("2026-05-20T12:02:00Z"),
	availableAt: minutesAfter(9),
	holdReason: "MODEL_UNAVAILABLE",
	retryCount: 0,
};

export const mockJobBackingOff: AgentJob = {
	id: "job-backoff-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "QUEUED",
	model: "openai/gpt-oss-20b",
	configSnapshot: { name: "GPU gateway (OpenAI)", llmProvider: "OPENAI" },
	createdAt: new Date("2026-05-20T11:00:00Z"),
	availableAt: minutesAfter(3),
	errorMessage: "Runner exited with code 137 (out of memory).",
	retryCount: 2,
};

export const mockJobTimedOut: AgentJob = {
	id: "job-timed-out-1",
	jobType: "PULL_REQUEST_REVIEW",
	reviewOutcome: "REVIEWED",
	target: pullRequestTarget,
	status: "TIMED_OUT",
	model: "openai/gpt-oss-120b",
	configSnapshot: { name: "GPU gateway (OpenAI)", llmProvider: "OPENAI" },
	createdAt: new Date("2026-05-20T08:00:00Z"),
	availableAt: new Date("2026-05-20T08:03:00Z"),
	completedAt: new Date("2026-05-20T08:20:00Z"),
	errorMessage: "Agent exceeded the 1200s timeout and was terminated.",
	llmModel: "gpt-oss-120b",
	retryCount: 1,
	exitCode: 124,
};

export const mockJobs: AgentJob[] = [
	mockJobCompleted,
	mockJobRunning,
	mockJobQueued,
	mockJobHeldOnBudget,
	mockJobFailedDelivery,
	mockJobTimedOut,
];
