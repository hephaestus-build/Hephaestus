import assert from "node:assert/strict";
import test from "node:test";

import {
	type AssistantMessage,
	addAssistantUsage,
	extractUsageFromSession,
	newUsageLedger,
	type SessionMessage,
} from "../../../main/resources/agent/pi-runner-usage.ts";

function assistant(
	responseId: string,
	{ input = 0, output = 0, cacheRead = 0, cacheWrite = 0, cost = 0 } = {},
): AssistantMessage {
	return {
		responseId,
		role: "assistant",
		content: [],
		api: "openai-completions",
		provider: "hephaestus",
		model: "gpt-5",
		stopReason: "stop",
		usage: {
			input,
			output,
			cacheRead,
			cacheWrite,
			totalTokens: input + output + cacheRead + cacheWrite,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: cost },
		},
		timestamp: 0,
	};
}

function sessionOf(...messages: SessionMessage[]) {
	return { messages };
}

void test("a ledger with nothing in it reports nothing", () => {
	const ledger = newUsageLedger();

	assert.equal(ledger.totalCalls, 0);
	assert.equal(ledger.inputTokens, 0);
});

void test("every bucket of an assistant message lands on the ledger", () => {
	const ledger = newUsageLedger();

	addAssistantUsage(
		ledger,
		assistant("a", { input: 100, output: 20, cacheRead: 7, cacheWrite: 3, cost: 0.5 }),
	);

	assert.equal(ledger.totalCalls, 1);
	assert.equal(ledger.inputTokens, 100);
	assert.equal(ledger.outputTokens, 20);
	assert.equal(ledger.cacheReadTokens, 7);
	assert.equal(ledger.cacheWriteTokens, 3);
	assert.equal(ledger.costUsd, 0.5);
	assert.equal(ledger.model, "gpt-5");
});

void test("only assistant messages that carry usage are billable", () => {
	const ledger = newUsageLedger();
	const { usage: _unused, ...assistantWithoutUsage } = assistant("a");

	const userMessage: SessionMessage = { role: "user", content: "not a bill", timestamp: 0 };
	addAssistantUsage(
		ledger,
		Object.assign(userMessage, {
			usage: { input: 999, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 999, cost: 0 },
		}),
	);
	addAssistantUsage(ledger, assistantWithoutUsage);
	addAssistantUsage(ledger, null);
	addAssistantUsage(ledger, undefined);

	assert.equal(ledger.totalCalls, 0);
	assert.equal(ledger.inputTokens, 0);
});

void test("the same message counted twice is billed once", () => {
	const ledger = newUsageLedger();
	const message = assistant("a", { input: 100 });

	addAssistantUsage(ledger, message);
	addAssistantUsage(ledger, message);

	assert.equal(ledger.totalCalls, 1);
	assert.equal(ledger.inputTokens, 100);
});

void test("a call compaction removed from the session is still billed", () => {
	const ledger = newUsageLedger();
	const compacted = assistant("dropped", { input: 4_000_000, output: 300_000 });
	const survivor = assistant("kept", { input: 900_000, output: 60_000 });

	addAssistantUsage(ledger, compacted);
	addAssistantUsage(ledger, survivor);

	const reported = extractUsageFromSession(sessionOf(survivor), ledger);

	assert.equal(reported.totalCalls, 2);
	assert.equal(reported.inputTokens, 4_900_000);
	assert.equal(reported.outputTokens, 360_000);

	const walkOnly = extractUsageFromSession(sessionOf(survivor));
	assert.equal(walkOnly.totalCalls, 1);
	assert.ok(
		reported.inputTokens > walkOnly.inputTokens * 4,
		"the compacted call is the difference between the real bill and a quarter of it",
	);
});

void test("an empty stream ledger never makes the report smaller than the message walk", () => {
	const messages = [assistant("a", { input: 100 }), assistant("b", { input: 250, output: 30 })];

	const reported = extractUsageFromSession(sessionOf(...messages), newUsageLedger());

	assert.equal(reported.totalCalls, 2);
	assert.equal(reported.inputTokens, 350);
	assert.equal(reported.outputTokens, 30);
});

void test("with no ledger at all the report is exactly the message walk", () => {
	const reported = extractUsageFromSession(sessionOf(assistant("a", { input: 100, output: 5 })));

	assert.equal(reported.totalCalls, 1);
	assert.equal(reported.inputTokens, 100);
	assert.equal(reported.outputTokens, 5);
});

void test("every bucket is taken from whichever view saw more, independently", () => {
	const walked = assistant("kept", { input: 10, output: 9000, cacheWrite: 40 });
	const ledger = newUsageLedger();
	addAssistantUsage(ledger, assistant("dropped", { input: 5000, cacheRead: 70 }));

	const reported = extractUsageFromSession(sessionOf(walked), ledger);

	assert.equal(reported.inputTokens, 5000);
	assert.equal(reported.outputTokens, 9000);
	assert.equal(reported.cacheReadTokens, 70);
	assert.equal(reported.cacheWriteTokens, 40);
});

void test("a session that never ran reports zero rather than throwing", () => {
	const reported = extractUsageFromSession({}, newUsageLedger());

	assert.equal(reported.totalCalls, 0);
	assert.equal(reported.inputTokens, 0);
	assert.equal(reported.model, null);
});
