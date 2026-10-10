import type {
	AgentBinding,
	AgentJob,
	AvailableLlmModel,
	PracticePrecomputeSummary,
} from "@/api/types.gen";
import { inStoryYear, minutesAfter } from "@/stories/story-clock";

/**
 * Whom a binding serves when it stands alone for its purpose, as the server routes it. A set of
 * bindings that routes otherwise states its own `servedTiers`.
 */
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
		reasoningEffort: "MEDIUM",
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
		reasoningEffort: "MEDIUM",
	},
];

/** One model per precompute kind, each on the API that serves it. */
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

/** A decision model that reasons first, which the picker warns about when it is chosen. */
export const mockReasoningDecisionModel: AvailableLlmModel = {
	...mockDecisionModel,
	id: 4,
	displayName: "o4-mini",
	reasoningEffort: "LOW",
};

/** A model with no brand declared, which draws the generic mark. */
export const mockEmbeddingModel: AvailableLlmModel = {
	dataHandlingTier: "IN_HOUSE",
	id: 5,
	scope: "SHARED",
	displayName: "Text embeddings",
	connectionDisplayName: "On-prem embeddings",
	purposes: ["PRACTICE_EMBEDDING"],
	pricingMode: "NO_CHARGE",
};

/** A hosted reranker with no price yet: the picker says how its calls are counted. */
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

export const mockPrecomputeModels: AvailableLlmModel[] = [
	mockDecisionModel,
	mockEmbeddingModel,
	mockRerankModel,
];

/** The review that reported each practice's needs, on a fixed day of the story's year. */
const asOf = { jobId: "job-completed-1", finishedAt: inStoryYear("10-03T09:00") };

/** Comment quality requires a decision model that In-house members do not get. */
export const mockCommentQualityNeeds: PracticePrecomputeSummary = {
	practiceSlug: "comment-quality",
	practiceName: "Comment quality",
	asOf,
	scriptChanged: false,
	needs: [
		{ purpose: "PRACTICE_DECISION", need: "REQUIRED", unmetTiers: ["IN_HOUSE"] },
		{ purpose: "PRACTICE_EMBEDDING", need: "OPTIONAL", unmetTiers: [] },
	],
};

export const mockDescribeWhatAndWhyNeeds: PracticePrecomputeSummary = {
	practiceSlug: "describe-what-and-why",
	practiceName: "Describe what and why",
	asOf,
	scriptChanged: false,
	needs: [{ purpose: "PRACTICE_EMBEDDING", need: "OPTIONAL", unmetTiers: [] }],
};

/** A script whose newest review ran an earlier version: it declares nothing yet. */
export const mockChangedScriptNeeds: PracticePrecomputeSummary = {
	practiceSlug: "small-pull-requests",
	practiceName: "Small pull requests",
	scriptChanged: true,
	needs: [],
};

export const mockPrecomputeNeeds: PracticePrecomputeSummary[] = [
	mockCommentQualityNeeds,
	mockDescribeWhatAndWhyNeeds,
	mockChangedScriptNeeds,
];

const pullRequestTarget: AgentJob["target"] = {
	type: "scm.pull_request",
	title: "Make practice review output visible",
	reviewedWork: {
		id: "42",
		kind: "scm.pull_request",
		provider: "GITHUB",
		label: "#1423",
		container: "ls1intum/Hephaestus",
		url: "https://github.com/ls1intum/Hephaestus/pull/1423",
	},
};
const issueTarget: AgentJob["target"] = {
	type: "scm.issue",
	title: "Admin read surface for observations and prepared feedback",
	reviewedWork: {
		id: "43",
		kind: "scm.issue",
		provider: "GITHUB",
		label: "#1420",
		container: "ls1intum/Hephaestus",
	},
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
	llmTotalOutputTokens: 1200,
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
	errorMessage: "Agent exceeded the 1200s timeout and was stopped.",
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
