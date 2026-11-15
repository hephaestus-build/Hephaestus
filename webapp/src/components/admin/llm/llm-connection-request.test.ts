import { describe, expect, it } from "vitest";

import { connectionCreateBodyOf, connectionUpdateBodyOf } from "./llm-connection-request";
import type { LlmConnectionFieldsValue } from "./LlmConnectionFields";

function fieldsOf(overrides: Partial<LlmConnectionFieldsValue> = {}): LlmConnectionFieldsValue {
	return {
		displayName: "  Team gateway  ",
		baseUrl: "  https://gateway.example.com/v1  ",
		preset: "OPENAI",
		apiProtocol: "openai-responses",
		authMode: "API_KEY",
		apiKey: "  sk-test  ",
		clearApiKey: false,
		connectionPlatform: "OPENROUTER",
		...overrides,
	};
}

describe("connectionCreateBodyOf", () => {
	it("maps each field, trimmed, and starts the connection inactive", () => {
		expect(connectionCreateBodyOf(fieldsOf())).toStrictEqual({
			displayName: "Team gateway",
			baseUrl: "https://gateway.example.com/v1",
			apiProtocol: "openai-responses",
			authMode: "API_KEY",
			connectionPlatform: "OPENROUTER",
			apiKey: "sk-test",
			enabled: false,
		});
	});

	it("sends no key and no platform when the admin gave none", () => {
		const body = connectionCreateBodyOf(fieldsOf({ apiKey: "   ", connectionPlatform: undefined }));
		expect(body.apiKey).toBeUndefined();
		expect(body.connectionPlatform).toBeUndefined();
	});
});

describe("connectionUpdateBodyOf", () => {
	it("sends the name, platform and new key, never the endpoint", () => {
		expect(connectionUpdateBodyOf(fieldsOf(), { connectionPlatform: "VLLM" })).toStrictEqual({
			displayName: "Team gateway",
			connectionPlatform: "OPENROUTER",
			apiKey: "sk-test",
		});
	});

	it("keeps the stored key when the key field is blank", () => {
		expect(connectionUpdateBodyOf(fieldsOf({ apiKey: "" }), {})).not.toHaveProperty("apiKey");
	});

	it("clears the stored key on request", () => {
		expect(connectionUpdateBodyOf(fieldsOf({ apiKey: "", clearApiKey: true }), {})).toStrictEqual({
			displayName: "Team gateway",
			connectionPlatform: "OPENROUTER",
			clearApiKey: true,
		});
	});

	it("clears a saved platform that the admin removed", () => {
		expect(
			connectionUpdateBodyOf(fieldsOf({ apiKey: "", connectionPlatform: undefined }), {
				connectionPlatform: "VLLM",
			}),
		).toStrictEqual({ displayName: "Team gateway", clearConnectionPlatform: true });
	});

	it("sends nothing about the platform when none was saved or chosen", () => {
		expect(
			connectionUpdateBodyOf(fieldsOf({ apiKey: "", connectionPlatform: undefined }), {}),
		).toStrictEqual({ displayName: "Team gateway" });
	});
});
