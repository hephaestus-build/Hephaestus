import assert from "node:assert/strict";
import test from "node:test";

import {
	type Answer,
	type ModelSettings,
	ModelSession,
	type Protocol,
} from "../../../main/resources/agent/review-composer-model.ts";

function settings(protocol: Protocol, overrides: Partial<ModelSettings> = {}): ModelSettings {
	return {
		protocol,
		modelId: "alias",
		reasoningEffort: null,
		maxOutputTokens: 256,
		temperature: null,
		...overrides,
	};
}

/** A session whose transport answers in turn and keeps every request it sent. */
function scripted(protocol: Protocol, answers: Answer[], overrides: Partial<ModelSettings> = {}) {
	const requests: unknown[] = [];
	const session = new ModelSession(
		async (_protocol, body) => {
			const request: unknown = JSON.parse(body);
			requests.push(request);
			return answers.shift() ?? { status: 200, body: "{}", refusal: null };
		},
		settings(protocol, overrides),
		"Compose feedback from admitted evidence.",
		[{ name: "evidence", description: "Read evidence", parameters: { type: "object" } }],
	);
	return { session, requests };
}

/** The value at a path of object keys and array indexes, or undefined. */
function at(value: unknown, ...path: (string | number)[]): unknown {
	let current = value;
	for (const key of path) {
		if (typeof current !== "object" || current === null) {
			return undefined;
		}
		const next: unknown = Reflect.get(current, key);
		current = next;
	}
	return current;
}

const ok = (body: unknown): Answer => ({ status: 200, body: JSON.stringify(body), refusal: null });

void test("a native Anthropic session uses the provider's defaults and never guesses options from an alias", async () => {
	const { session, requests } = scripted("anthropic-messages", [ok({ content: [] })], {
		reasoningEffort: "HIGH",
		temperature: 0.2,
		maxOutputTokens: 4096,
	});

	await session.send("Feedback.");

	const [request] = requests;
	assert.equal(at(request, "thinking"), undefined);
	assert.equal(at(request, "output_config"), undefined);
	assert.equal(at(request, "temperature"), undefined);
	assert.equal(at(request, "max_tokens"), 4096);
});

void test("a native Anthropic session keeps its tool turns and answers them in one user turn, with auto throughout", async () => {
	const { session, requests } = scripted("anthropic-messages", [
		ok({
			content: [
				{ type: "thinking", thinking: "Check evidence", signature: "signature" },
				{ type: "tool_use", id: "call-1", name: "evidence", input: { id: 1 } },
			],
		}),
		ok({ content: [{ type: "text", text: "Feedback composed." }] }),
	]);

	const reply = await session.send("Compose this review.");
	assert.deepEqual(reply.toolCalls, [{ id: "call-1", name: "evidence", arguments: { id: 1 } }]);
	const composed = await session.answer(
		[{ callId: "call-1", text: "Evidence found" }],
		"Continue.",
	);

	assert.equal(composed.text, "Feedback composed.");
	const second = requests[1];
	assert.equal(at(second, "system"), "Compose feedback from admitted evidence.");
	assert.deepEqual(at(second, "tools", 0, "input_schema"), { type: "object" });
	assert.equal(at(second, "messages", 1, "content", 0, "signature"), "signature");
	assert.equal(at(second, "messages", 2, "content", 0, "tool_use_id"), "call-1");
	assert.equal(at(second, "messages", 2, "content", 1, "text"), "Continue.");
	for (const request of requests) {
		assert.deepEqual(at(request, "tool_choice"), { type: "auto" });
		assert.equal(at(request, "max_tokens"), 256);
		assert.equal(at(request, "store"), undefined);
	}
});

void test("a Responses session sends its instructions apart and answers each function call by its call id", async () => {
	const { session, requests } = scripted(
		"openai-responses",
		[
			ok({
				output: [
					{ type: "function_call", call_id: "call-1", name: "evidence", arguments: '{"id":1}' },
				],
			}),
			ok({ output: [{ type: "message", content: [{ type: "output_text", text: "Done." }] }] }),
		],
		{ reasoningEffort: "MEDIUM", maxOutputTokens: 8 },
	);

	const reply = await session.send("Compose.");
	assert.deepEqual(reply.toolCalls, [{ id: "call-1", name: "evidence", arguments: { id: 1 } }]);
	const done = await session.answer([{ callId: "call-1", text: "Found." }], null);
	assert.equal(done.text, "Done.");

	const second = requests[1];
	assert.equal(at(second, "instructions"), "Compose feedback from admitted evidence.");
	assert.equal(at(second, "max_output_tokens"), 16);
	assert.deepEqual(at(second, "reasoning"), { effort: "medium" });
	assert.deepEqual(at(second, "input", 2), {
		type: "function_call_output",
		call_id: "call-1",
		output: "Found.",
	});
});

void test("a refusal of the proxy ends the call with its rule, and a lost lease with ownership lost", async () => {
	for (const [refusal, failure] of [
		["BUDGET", "BUDGET"],
		["AI_CHOICE", "AI_CHOICE"],
		["WORK_TIME", "WORK_TIME"],
		["CAPACITY", "CAPACITY"],
		["ATTEMPT_ENDED", "OWNERSHIP_LOST"],
		["CALLS_IN_FLIGHT", "PROVIDER"],
	] as const) {
		const { session } = scripted("openai-completions", [{ status: 429, body: "", refusal }]);
		const reply = await session.send("Compose.");
		assert.equal(reply.failure, failure, refusal);
	}
});

void test("a provider failure may pass, and a client error answers the same request the same way again", async () => {
	for (const [status, failure] of [
		[503, "PROVIDER"],
		[429, "PROVIDER"],
		[408, "PROVIDER"],
		[400, "REFUSED"],
	] as const) {
		const { session } = scripted("openai-completions", [{ status, body: "", refusal: null }]);
		const reply = await session.send("Compose.");
		assert.equal(reply.failure, failure, String(status));
	}
	const { session } = scripted("openai-completions", [
		{ status: 200, body: "not json", refusal: null },
	]);
	const unreadable = await session.send("Compose.");
	assert.equal(unreadable.failure, "PROVIDER");
});
