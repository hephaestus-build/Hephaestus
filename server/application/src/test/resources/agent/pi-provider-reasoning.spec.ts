import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync } from "node:fs";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { ModelRuntime } from "@earendil-works/pi-coding-agent";

import {
	REASONING_EFFORTS,
	type ReasoningEffort,
	reasoningSetting,
	registerHephaestusProvider,
} from "../../../main/resources/agent/pi-provider.ts";

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/**
 * The request body Pi sends for one configured effort, captured at a stand-in for the LLM proxy. The
 * stand-in answers 400 so the call ends at once; only what was sent matters here.
 */
async function capturedBody(
	apiProtocol: "openai-completions" | "openai-responses",
	effort: ReasoningEffort | undefined,
): Promise<Record<string, unknown>> {
	const captured: { body: Record<string, unknown> | null } = { body: null };
	const server = createServer((request: IncomingMessage, response: ServerResponse) => {
		const chunks: Buffer[] = [];
		request.on("data", (chunk: Buffer) => {
			chunks.push(chunk);
		});
		request.on("end", () => {
			const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
			captured.body = isRecord(parsed) ? parsed : {};
			response.writeHead(400, { "content-type": "application/json" });
			response.end(
				JSON.stringify({ error: { message: "captured", type: "invalid_request_error" } }),
			);
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	try {
		const address = server.address();
		if (address === null || typeof address === "string") {
			throw new Error("the stand-in proxy has no port");
		}
		const dir = mkdtempSync(path.join(tmpdir(), "pi-provider-"));
		const runtime = await ModelRuntime.create({
			authPath: path.join(dir, "auth.json"),
			modelsPath: path.join(dir, "models.json"),
			allowModelNetwork: false,
		});
		const registered = registerHephaestusProvider(
			runtime,
			{ apiProtocol, modelId: "gpt-5", ...(effort ? { reasoningEffort: effort } : {}) },
			{ LLM_PROXY_URL: `http://127.0.0.1:${String(address.port)}/v1`, LLM_PROXY_TOKEN: "t" },
		);
		assert.equal(registered, true);
		const model = runtime.getModel("hephaestus", "gpt-5");
		assert.ok(model);
		// As the agent hands it over: a session at "off" passes no level at all.
		const level = reasoningSetting(effort).thinkingLevel;
		// The key is named as $LLM_PROXY_TOKEN and resolved from the process environment at call time.
		process.env.LLM_PROXY_TOKEN = "t";
		await runtime.completeSimple(
			model,
			{ messages: [{ role: "user", content: "hi", timestamp: Date.now() }] },
			level === "off" ? {} : { reasoning: level },
		);
	} finally {
		server.close();
	}
	if (captured.body === null) {
		throw new Error("no request reached the stand-in proxy");
	}
	return captured.body;
}

const WIRE: Record<ReasoningEffort, string> = {
	NONE: "none",
	MINIMAL: "minimal",
	LOW: "low",
	MEDIUM: "medium",
	HIGH: "high",
	XHIGH: "xhigh",
	MAX: "max",
};

for (const effort of REASONING_EFFORTS) {
	void test(`chat completions sends reasoning_effort "${WIRE[effort]}" for ${effort}`, async () => {
		const body = await capturedBody("openai-completions", effort);
		assert.equal(body.reasoning_effort, WIRE[effort]);
	});

	void test(`the responses API sends reasoning.effort "${WIRE[effort]}" for ${effort}`, async () => {
		const body = await capturedBody("openai-responses", effort);
		assert.ok(isRecord(body.reasoning));
		assert.equal(body.reasoning.effort, WIRE[effort]);
	});
}

void test("the provider default sends no reasoning parameter on either protocol", async () => {
	const completions = await capturedBody("openai-completions", undefined);
	assert.equal("reasoning_effort" in completions, false);
	const responses = await capturedBody("openai-responses", undefined);
	assert.equal("reasoning" in responses, false);
});

void test("an effort registers a reasoning model, and the provider default a model that does not reason", () => {
	assert.deepEqual(reasoningSetting(undefined), { thinkingLevel: "off", reasoning: false });
	assert.equal(reasoningSetting("HIGH").reasoning, true);
	assert.deepEqual(reasoningSetting("NONE").thinkingLevelMap, { minimal: "none" });
});
