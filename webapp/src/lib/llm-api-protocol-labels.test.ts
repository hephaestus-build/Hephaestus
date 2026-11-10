import { describe, expect, it } from "vitest";

import {
	LLM_API_PROTOCOL_SELECT_ITEMS,
	llmApiProtocolDescription,
} from "./llm-api-protocol-labels";

describe("LLM API protocol labels", () => {
	it("names every option in the select items so a closed trigger shows the label", () => {
		expect(LLM_API_PROTOCOL_SELECT_ITEMS).toStrictEqual([
			{ value: "openai-responses", label: "Responses API" },
			{ value: "openai-completions", label: "Chat Completions API" },
			{ value: "anthropic-messages", label: "Messages API (Anthropic)" },
		]);
	});

	it("explains only the options an admin can misread", () => {
		expect(llmApiProtocolDescription("openai-responses")).toBeUndefined();
		expect(llmApiProtocolDescription("openai-completions")).toBe(
			"Use only if the endpoint does not serve the Responses API.",
		);
		expect(llmApiProtocolDescription("anthropic-messages")).toMatch(/^Anthropic’s native API/u);
	});
});
