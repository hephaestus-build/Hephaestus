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

/** Output tokens one assistant message spent; zero for a failed call that reported none. */
export function outputTokensOf(msg: ReportedMessage): number {
	return msg.role === "assistant" ? (msg.usage?.output ?? 0) : 0;
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
	// Reasoning is a subset of output when the provider reports it: reported, never priced separately.
	ledger.reasoningTokens += usage.reasoning ?? 0;
	ledger.cacheReadTokens += usage.cacheRead || 0;
	ledger.cacheWriteTokens += usage.cacheWrite || 0;
	ledger.costUsd += usage.cost.total || 0;
	const sr = msg.stopReason ?? "unknown";
	ledger.stopReasons[sr] = (ledger.stopReasons[sr] ?? 0) + 1;
}

/** Prompt tokens in every classification: ordinary input, cache reads and cache writes. */
function promptTokensOf(usage: UsageLedger): number {
	return usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens;
}

function covers(a: UsageLedger, b: UsageLedger): boolean {
	return promptTokensOf(a) >= promptTokensOf(b) && a.outputTokens >= b.outputTokens;
}

/**
 * Report one view whole. The stream ledger survives compaction; the message walk covers events the
 * ledger missed. The walk is reported only when its prompt and output totals both cover the ledger's.
 * Otherwise the ledger is reported, even when neither covers the other: the two views classify calls
 * independently, so their buckets cannot be combined. This reports what one view observed, not a
 * guarantee that every call was observed.
 */
export function extractUsageFromSession(
	session: { messages?: SessionMessage[] },
	streamLedger: UsageLedger | null = null,
): UsageReport {
	const walked = newUsageLedger();
	for (const msg of session.messages ?? []) {
		addAssistantUsage(walked, msg);
	}
	const ledger = streamLedger ?? walked;
	const source = !covers(ledger, walked) && covers(walked, ledger) ? walked : ledger;
	const { seenIds: _seenIds, ...report } = source;
	return { ...report, model: source.model ?? ledger.model ?? walked.model };
}
