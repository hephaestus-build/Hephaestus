import { describe, expect, it } from "vitest";

import {
	API_PROTOCOLS,
	authModeDefaultFor,
	baseUrlDefaultFor,
	defaultProtocolFor,
	presetForConnection,
} from "./llm-provider-type";

describe("OpenAI-compatible endpoint presets", () => {
	it("gives Azure its own auth mode and base-URL template", () => {
		expect(authModeDefaultFor("OPENAI")).toBe("BEARER");
		expect(authModeDefaultFor("AZURE_OPENAI_V1")).toBe("API_KEY");
		expect(baseUrlDefaultFor("AZURE_OPENAI_V1")).toBe(
			"https://RESOURCE.openai.azure.com/openai/v1",
		);
	});

	it.each([
		[true, API_PROTOCOLS.OPENAI_RESPONSES],
		[false, API_PROTOCOLS.OPENAI_COMPLETIONS],
		[undefined, API_PROTOCOLS.OPENAI_RESPONSES],
	])("defaults useResponsesApi=%s to the matching wire API", (useResponsesApi, protocol) => {
		expect(defaultProtocolFor(useResponsesApi)).toBe(protocol);
	});

	it("does not infer the create-time Azure preset while editing", () => {
		expect(
			presetForConnection({
				apiProtocol: API_PROTOCOLS.OPENAI_RESPONSES,
				baseUrl: "https://example.openai.azure.com/openai/v1",
			}),
		).toBe("OTHER");
	});
});
