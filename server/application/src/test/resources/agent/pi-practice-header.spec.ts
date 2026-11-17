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
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import { SANDBOX_RESOURCE_LOADER_OPTIONS } from "../../../main/resources/agent/pi-agent-sandbox.ts";
import {
	PRACTICE_HEADER,
	practiceHeaderExtension,
	registerHephaestusProvider,
} from "../../../main/resources/agent/pi-provider.ts";

for (const apiProtocol of ["openai-completions", "openai-responses"] as const) {
	void test(`${apiProtocol}: each model call of a practice's session names that practice`, async () => {
		const named: (string | undefined)[] = [];
		const server = createServer((request, response) => {
			// Another local process can probe a newly opened port. Only the provider call counts.
			if (request.method !== "POST") {
				response.writeHead(404).end();
				return;
			}
			const header = request.headers[PRACTICE_HEADER.toLowerCase()];
			named.push(Array.isArray(header) ? header.join(",") : header);
			request.resume();
			request.on("end", () => {
				response.writeHead(400, { "content-type": "application/json" });
				response.end(
					JSON.stringify({ error: { message: "request captured", type: "invalid_request_error" } }),
				);
			});
		});
		server.listen(0, "127.0.0.1");
		await once(server, "listening");
		const cwd = mkdtempSync(path.join(tmpdir(), "practice-header-"));
		const oldToken = process.env.LLM_PROXY_TOKEN;
		process.env.LLM_PROXY_TOKEN = "test-only";
		const sessions: Awaited<ReturnType<typeof createAgentSession>>["session"][] = [];
		try {
			const address = server.address();
			assert.ok(address !== null && typeof address !== "string");
			const modelRuntime = await ModelRuntime.create({
				authPath: path.join(cwd, "auth.json"),
				modelsPath: path.join(cwd, "models.json"),
				allowModelNetwork: false,
			});
			const config = { apiProtocol, modelId: "test-model" };
			assert.ok(
				registerHephaestusProvider(modelRuntime, config, {
					LLM_PROXY_URL: `http://127.0.0.1:${address.port}/v1`,
					LLM_PROXY_TOKEN: "test-only",
				}),
			);
			const model = modelRuntime.getModel("hephaestus", config.modelId);
			assert.ok(model);
			const settingsManager = SettingsManager.inMemory({ retry: { enabled: false } });
			for (const practiceId of ["17", undefined]) {
				const resourceLoader = new DefaultResourceLoader({
					cwd,
					agentDir: cwd,
					settingsManager,
					...SANDBOX_RESOURCE_LOADER_OPTIONS,
					systemPrompt: "Review the practice.",
					agentsFilesOverride: () => ({ agentsFiles: [] }),
					extensionFactories: practiceId === undefined ? [] : [practiceHeaderExtension(practiceId)],
				});
				await resourceLoader.reload();
				const { session } = await createAgentSession({
					cwd,
					agentDir: cwd,
					settingsManager,
					resourceLoader,
					modelRuntime,
					model,
					sessionManager: SessionManager.inMemory(cwd),
					tools: ["read"],
				});
				sessions.push(session);
				await session.prompt("Review the captured evidence.");
			}

			assert.deepEqual(named, ["17", undefined]);
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
