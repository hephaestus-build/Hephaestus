import { describe, expect, it } from "vitest";

import {
	isPracticeScriptApi,
	LLM_API_PROTOCOL_GROUPS,
	practiceScriptApiFor,
	LLM_API_PROTOCOL_SELECT_ITEMS,
	llmApiProtocolDescription,
} from "./llm-api-protocol-labels";

describe("LLM API protocol labels", () => {
	it("offers chat APIs first, then the APIs for practice scripts, each once", () => {
		expect(
			LLM_API_PROTOCOL_GROUPS.map(({ label, protocols }) => ({ label, protocols })),
		).toStrictEqual([
			{
				label: "Chat models",
				protocols: ["openai-responses", "openai-completions", "anthropic-messages"],
			},
			{
				label: "For practice scripts",
				protocols: ["openai-decisions", "openai-embeddings", "cohere-rerank"],
			},
		]);
	});

	it("names every option in the select items so a closed trigger shows the label", () => {
		expect(LLM_API_PROTOCOL_SELECT_ITEMS).toStrictEqual([
			{ value: "openai-responses", label: "Responses API" },
			{ value: "openai-completions", label: "Chat Completions API" },
			{ value: "anthropic-messages", label: "Messages API (Anthropic)" },
			{ value: "openai-decisions", label: "Decisions API" },
			{ value: "openai-embeddings", label: "Embeddings API" },
			{ value: "cohere-rerank", label: "Rerank API (Cohere-compatible)" },
		]);
	});

	it("explains only the options an admin can misread", () => {
		expect(llmApiProtocolDescription("openai-responses")).toBeUndefined();
		expect(llmApiProtocolDescription("openai-completions")).toMatch(/^Use only if/u);
		expect(llmApiProtocolDescription("cohere-rerank")).toBe(
			"Choose a provider that reports token usage, or set No metered API cost.",
		);
	});

	it.each([
		["openai-responses", false],
		["openai-completions", false],
		["openai-decisions", true],
		["openai-embeddings", true],
		["cohere-rerank", true],
	] as const)("treats %s as an API for practice scripts: %s", (protocol, practiceScript) => {
		expect(isPracticeScriptApi(protocol)).toBe(practiceScript);
	});

	it.each([
		["PRACTICE_DECISION", "openai-decisions"],
		["PRACTICE_EMBEDDING", "openai-embeddings"],
		["PRACTICE_RERANKING", "cohere-rerank"],
		["PRACTICE_REVIEW", undefined],
	] as const)("names the API that serves only %s: %s", (purpose, protocol) => {
		expect(practiceScriptApiFor(purpose)).toBe(protocol);
	});
});
