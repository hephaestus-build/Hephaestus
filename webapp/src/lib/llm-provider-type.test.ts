import { describe, expect, it } from "vitest";

import { authModeDefaultFor, baseUrlDefaultFor, presetForConnection } from "./llm-provider-type";

describe("OpenAI-compatible endpoint presets", () => {
	it("gives Azure its own auth mode and base-URL template", () => {
		expect(authModeDefaultFor("OPENAI")).toBe("BEARER");
		expect(authModeDefaultFor("AZURE_OPENAI_V1")).toBe("API_KEY");
		expect(baseUrlDefaultFor("AZURE_OPENAI_V1")).toBe(
			"https://RESOURCE.openai.azure.com/openai/v1",
		);
	});

	it("does not infer the create-time Azure preset while editing", () => {
		expect(
			presetForConnection({
				apiProtocol: "openai-responses",
				baseUrl: "https://example.openai.azure.com/openai/v1",
			}),
		).toBe("OTHER");
	});
});
