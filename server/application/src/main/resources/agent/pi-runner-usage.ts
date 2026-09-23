// Track message_end usage so compaction cannot remove previously recorded usage.

import type { AgentSession } from "@earendil-works/pi-coding-agent";

/** Derive message types through the declared SDK dependency, not its transitive packages. */
export type SessionState = AgentSession["state"];
export type SessionMessage = SessionState["messages"][number];
export type AssistantMessage = Extract<SessionMessage, { role: "assistant" }>;

/** Failed provider calls can omit fields that AssistantMessage declares as required. */
export type ReportedMessage = SessionMessage | Partial<AssistantMessage>;

/** What one session has spent, in the buckets usage.json reports and the server bills from. */
export interface UsageLedger {
	model: string | null;
	inputTokens: number;
	outputTokens: number;
	reasoningTokens: number;
	cacheReadTokens: number;
	cacheWriteTokens: number;
	costUsd: number;
	totalCalls: number;
	assistantMessages: number;
	stopReasons: Record<string, number>;
	seenIds: Set<string>;
}

/** The same buckets, reported rather than accumulated: no dedupe set, and stopReasons already chosen. */
export type UsageReport = Omit<UsageLedger, "seenIds">;

/** Accumulate stream events; the session message list loses older usage during compaction. */
export function newUsageLedger(): UsageLedger {
	return {
		model: null,
		inputTokens: 0,
		outputTokens: 0,
		reasoningTokens: 0,
		cacheReadTokens: 0,
		cacheWriteTokens: 0,
		costUsd: 0,
		totalCalls: 0,
		assistantMessages: 0,
		stopReasons: {},
		seenIds: new Set<string>(),
	};
}

export function addAssistantUsage(
	ledger: UsageLedger,
	msg: ReportedMessage | null | undefined,
): void {
	if (msg?.role !== "assistant") {
		return;
	}
	const { usage } = msg;
	if (!usage) {
		return;
	}
	// Deduplicate by the SDK responseId when present; AssistantMessage has no id field.
	if (msg.responseId != null) {
		if (ledger.seenIds.has(msg.responseId)) {
			return;
		}
		ledger.seenIds.add(msg.responseId);
	}
	ledger.assistantMessages += 1;
	ledger.totalCalls += 1;
	ledger.model = msg.model ?? ledger.model;
	ledger.inputTokens += usage.input || 0;
	ledger.outputTokens += usage.output || 0;
	// Pi includes reasoning in output and exposes no separate count. The proxy supplies reasoning
	// usage to the server; keep this contract field zero to avoid double-counting.
	ledger.cacheReadTokens += usage.cacheRead || 0;
	ledger.cacheWriteTokens += usage.cacheWrite || 0;
	ledger.costUsd += usage.cost.total || 0;
	const sr = msg.stopReason ?? "unknown";
	ledger.stopReasons[sr] = (ledger.stopReasons[sr] ?? 0) + 1;
}

/** Use the larger total per bucket: events survive compaction, messages cover missed events. */
export function extractUsageFromSession(
	session: { messages?: SessionMessage[] },
	streamLedger: UsageLedger | null = null,
): UsageReport {
	const messages = session.messages ?? [];
	const walked = newUsageLedger();
	for (const msg of messages) {
		addAssistantUsage(walked, msg);
	}
	const source = streamLedger ?? walked;

	return {
		model: source.model ?? walked.model,
		inputTokens: Math.max(walked.inputTokens, source.inputTokens),
		outputTokens: Math.max(walked.outputTokens, source.outputTokens),
		reasoningTokens: Math.max(walked.reasoningTokens, source.reasoningTokens),
		cacheReadTokens: Math.max(walked.cacheReadTokens, source.cacheReadTokens),
		cacheWriteTokens: Math.max(walked.cacheWriteTokens, source.cacheWriteTokens),
		costUsd: Math.max(walked.costUsd, source.costUsd),
		totalCalls: Math.max(walked.totalCalls, source.totalCalls),
		assistantMessages: Math.max(walked.assistantMessages, source.assistantMessages),
		stopReasons:
			source.assistantMessages >= walked.assistantMessages
				? source.stopReasons
				: walked.stopReasons,
	};
}
