// What one agent session spent, counted so that compaction cannot un-count it.
//
// Typed against the SDK's own message shapes rather than against a hand-written picture of them, which
// is how two fields that had never existed came to light: see `responseId` and `reasoningTokens` below.
//
// Its own module for the same reason pi-review-turns.ts is: pi-runner.ts reads /workspace and the
// environment at module scope, so the only way to exercise these rules is to have them somewhere a test
// can call. The rules here decide a bill, which is the strongest reason yet to be able to.

import type { AgentSession } from "@earendil-works/pi-coding-agent";

/**
 * The SDK's own message and state types, reached through the one entry point this repo depends on.
 *
 * <p>They are declared in @earendil-works/pi-agent-core and /pi-ai, which pi-coding-agent depends on but
 * does not re-export, and which pnpm's isolated layout does not put on our resolution path. Reading them
 * off AgentSession is therefore not a shortcut around a missing import — it is the only way to name them
 * without adding a phantom dependency on a package we do not declare.
 */
export type SessionState = AgentSession["state"];
export type SessionMessage = SessionState["messages"][number];
export type AssistantMessage = Extract<SessionMessage, { role: "assistant" }>;

/**
 * A message as the ledger is really handed one.
 *
 * <p>`AssistantMessage` marks `usage`, `model` and `stopReason` required, and a turn that failed before
 * the provider answered arrives without the usage block. Naming that as a shape of its own is what lets
 * the checks below be checks: read against the declared type they are dead code, and removing them
 * would bill a turn that never reached a provider.
 */
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

/**
 * A running total of what one session has spent, built from the event stream rather than from the
 * session's message list.
 *
 * Why not just read session.messages: compaction is on for every session we create, and a compacted
 * assistant message is gone from that list along with its usage block. A walk of the survivors therefore
 * reports whatever compaction happened to leave behind — on a long fan-out that was a quarter of the
 * calls actually made, and the server billed the shortfall because it preferred this report over the
 * proxy's count. A message counted here at message_end can never be un-counted, whatever the session
 * does with it afterwards.
 */
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
	// A turn that failed before the provider answered arrives without a usage block, and billing from
	// it unchecked would throw rather than skip.
	const { usage } = msg;
	if (!usage) {
		return;
	}
	// message_end fires once per message, so this is belt and braces — but a redelivered event would
	// otherwise double a real bill, and over-billing is the one error direction this whole change exists
	// to avoid creating. `responseId` is the per-response identifier every pi-ai provider sets; there is
	// no `id` on an assistant message, which is what this guard used to read and why it never once fired.
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
	// reasoningTokens has no source and is left at zero. Every pi-ai provider builds Usage as a fresh
	// {input, output, cacheRead, cacheWrite, totalTokens, cost}, so a reasoning bucket never arrives —
	// and for the responses path it would double-count anyway, because OpenAI's completion_tokens
	// (which lands in `output`) already includes reasoning tokens. The bucket stays in the report
	// because usage.json is a contract with the server, which takes the reasoning count from the LLM
	// proxy's reading of each response's usage details instead.
	ledger.cacheReadTokens += usage.cacheRead || 0;
	ledger.cacheWriteTokens += usage.cacheWrite || 0;
	// Every pi-ai provider builds the cost block alongside the token counts, so a usage block that
	// arrived at all has one.
	ledger.costUsd += usage.cost.total || 0;
	const sr = msg.stopReason ?? "unknown";
	ledger.stopReasons[sr] = (ledger.stopReasons[sr] ?? 0) + 1;
}

/**
 * What this session has spent so far: the larger of the two views of it, per bucket.
 *
 * The stream ledger is the one that survives compaction, and the message walk is the one that survives
 * an SDK that does not put a usage block on the event. Taking the maximum means neither assumption has
 * to hold for the report to be no worse than it was before, and when both hold the report is right.
 *
 * @param session the session's state; `messages` is optional here because the reporting paths run on a
 *   session that may never have started a turn, and a missing transcript reports zero rather than throwing
 * @param streamLedger the session's ledger, or null for a session nothing subscribed to
 */
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
