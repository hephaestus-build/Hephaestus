import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	createAgentSession,
	DefaultResourceLoader,
	defineTool,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import { isJsonObject as isRecord } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import { SANDBOX_RESOURCE_LOADER_OPTIONS } from "../../../main/resources/agent/pi-agent-sandbox.ts";
import {
	registerHephaestusProvider,
	reasoningSetting,
	submissionExtension,
} from "../../../main/resources/agent/pi-provider.ts";

for (const [apiProtocol, reasoningEffort, modelId = "test-model"] of [
	["openai-completions", undefined],
	["openai-responses", undefined],
	["google-generative-ai", undefined],
	["anthropic-messages", undefined],
	["anthropic-messages", "MEDIUM"],
	["anthropic-messages", undefined, "claude-opus-5-5"],
	["anthropic-messages", undefined, "claude-sonnet-5-5"],
	["openai-completions", undefined, "anthropic/claude-opus-5.5"],
] as const) {
	const manualThinking = reasoningEffort !== undefined;
	void test(`${apiProtocol} ${modelId} ${manualThinking ? "preserves manual thinking without an unsupported forced tool" : "requires submit_result after exploration and preserves the other tools"}`, async () => {
		const bodies: Record<string, unknown>[] = [];
		const nativeRequests: unknown[] = [];
		const server = createServer((request, response) => {
			const chunks: Buffer[] = [];
			if (apiProtocol === "anthropic-messages") {
				nativeRequests.push({ authorization: request.headers.authorization, path: request.url });
			}
			request.on("data", (chunk: Buffer) => {
				chunks.push(chunk);
			});
			request.on("end", () => {
				const body: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
				assert.ok(isRecord(body));
				bodies.push(body);
				response.writeHead(400, { "content-type": "application/json" });
				response.end(
					JSON.stringify({ error: { message: "request captured", type: "invalid_request_error" } }),
				);
			});
		});
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const cwd = mkdtempSync(path.join(tmpdir(), "submission-provider-"));
		const sessions: Awaited<ReturnType<typeof createAgentSession>>["session"][] = [];
		const oldToken = process.env.LLM_PROXY_TOKEN;
		process.env.LLM_PROXY_TOKEN = "test-only";
		try {
			const address = server.address();
			assert.ok(address !== null && typeof address !== "string");
			const modelRuntime = await ModelRuntime.create({
				authPath: path.join(cwd, "auth.json"),
				modelsPath: path.join(cwd, "models.json"),
				allowModelNetwork: false,
			});
			const config = {
				apiProtocol,
				modelId,
				reasoningEffort,
				forcedToolChoice: modelId === "test-model",
			};
			assert.ok(
				registerHephaestusProvider(modelRuntime, config, {
					LLM_PROXY_URL: `http://127.0.0.1:${address.port}${apiProtocol === "anthropic-messages" ? "" : "/v1"}`,
					LLM_PROXY_TOKEN: "test-only",
				}),
			);
			const model = modelRuntime.getModel("hephaestus", config.modelId);
			assert.ok(model);
			const settingsManager = SettingsManager.inMemory({ retry: { enabled: false } });
			let required = false;
			const resourceLoader = new DefaultResourceLoader({
				cwd,
				agentDir: cwd,
				settingsManager,
				...SANDBOX_RESOURCE_LOADER_OPTIONS,
				systemPrompt: "Review the practice.",
				agentsFilesOverride: () => ({ agentsFiles: [] }),
				extensionFactories: [submissionExtension(config, () => required)],
			});
			await resourceLoader.reload();
			const submission = defineTool({
				name: "submit_result",
				label: "Submit",
				description: "Record the result.",
				parameters: { type: "object", properties: {}, additionalProperties: false },
				execute: async () => ({ content: [{ type: "text", text: "stored" }], details: {} }),
			});
			const { session } = await createAgentSession({
				cwd,
				agentDir: cwd,
				settingsManager,
				resourceLoader,
				modelRuntime,
				model,
				thinkingLevel: reasoningSetting(reasoningEffort).thinkingLevel,
				sessionManager: SessionManager.inMemory(cwd),
				tools: ["read", "submit_result"],
				customTools: [submission],
			});
			sessions.push(session);
			await session.prompt("Review the captured evidence.");
			required = true;
			await session.prompt("Submit this practice's result with submit_result.");
			assert.equal(bodies.length, 2);
			if (apiProtocol === "anthropic-messages") {
				assert.deepEqual(nativeRequests, [
					{ authorization: "Bearer test-only", path: "/v1/messages?beta=true" },
					{ authorization: "Bearer test-only", path: "/v1/messages?beta=true" },
				]);
			}
			const [exploring, recording] = bodies;
			assert.ok(exploring && recording);
			assert.equal("tool_choice" in exploring, false);
			if (modelId !== "test-model" || apiProtocol === "anthropic-messages") {
				assert.equal(recording.tool_choice, undefined);
			} else if (manualThinking) {
				assert.equal(recording.tool_choice, undefined);
				assert.ok(isRecord(recording.thinking));
				assert.equal(recording.thinking.type, "enabled");
				assert.deepEqual(recording.thinking, exploring.thinking);
			} else if (apiProtocol === "google-generative-ai") {
				assert.deepEqual(recording.toolConfig, {
					functionCallingConfig: { mode: "ANY", allowedFunctionNames: ["submit_result"] },
				});
			} else {
				assert.deepEqual(
					recording.tool_choice,
					apiProtocol === "openai-completions"
						? { type: "function", function: { name: "submit_result" } }
						: {
								type: "function",
								name: "submit_result",
							},
				);
			}
			if (apiProtocol === "anthropic-messages") {
				for (const body of bodies) {
					assert.ok(Array.isArray(body.tools));
					assert.ok(
						body.tools.every(
							(tool: unknown) =>
								isRecord(tool) &&
								(tool.type === undefined || tool.type === "custom") &&
								isRecord(tool.input_schema),
						),
						"the gateway only permits custom client tools",
					);
					assert.ok(Array.isArray(body.messages));
					assert.ok(
						body.messages.every(
							(message: unknown) =>
								isRecord(message) && (message.role === "user" || message.role === "assistant"),
						),
						"the gateway uses a top-level system prompt",
					);
				}
			}
			assert.deepEqual(recording.tools, exploring.tools);
		} finally {
			for (const session of sessions) {
				await session.abort();
				session.dispose();
			}
			if (oldToken === undefined) {
				delete process.env.LLM_PROXY_TOKEN;
			} else {
				process.env.LLM_PROXY_TOKEN = oldToken;
			}
			server.close();
			rmSync(cwd, { recursive: true, force: true });
		}
	});
}
