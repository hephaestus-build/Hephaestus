import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
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

import { buildGrepTool } from "../../../main/resources/agent/pi-grep-tool.ts";

/**
 * The SDK filters custom tools through the same allowlist as its built-ins, so a session gets the
 * runner's grep only when "grep" is listed, and then the custom definition replaces the built-in
 * under that name. Both halves are what the runner relies on.
 */
void test("the runner's grep replaces the SDK's in a session opened the way the runner opens one", async () => {
	const cwd = mkdtempSync(path.join(tmpdir(), "grep-registration-"));
	const agentDir = path.join(cwd, ".pi");
	mkdirSync(agentDir, { recursive: true });
	for (const file of ["settings.json", "auth.json", "models.json"]) {
		writeFileSync(path.join(agentDir, file), "{}\n");
	}
	const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
	const resourceLoader = new DefaultResourceLoader({
		cwd,
		agentDir,
		settingsManager,
		noContextFiles: true,
		agentsFilesOverride: () => ({ agentsFiles: [] }),
	});
	await resourceLoader.reload();
	const modelRuntime = await ModelRuntime.create({
		authPath: path.join(agentDir, "auth.json"),
		modelsPath: path.join(agentDir, "models.json"),
		allowModelNetwork: false,
	});
	process.env.GREP_REGISTRATION_TOKEN = "unused";
	modelRuntime.registerProvider("registration", {
		name: "registration",
		baseUrl: "http://127.0.0.1:9/v1",
		apiKey: "$GREP_REGISTRATION_TOKEN",
		authHeader: true,
		api: "openai-completions",
		models: [
			{
				id: "model",
				name: "model",
				reasoning: false,
				input: ["text"],
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				contextWindow: 1000,
				maxTokens: 100,
			},
		],
	});
	const grepTool = buildGrepTool(cwd);
	const { session } = await createAgentSession({
		cwd,
		agentDir,
		tools: ["read", "grep"],
		customTools: [grepTool],
		sessionManager: SessionManager.inMemory(cwd),
		settingsManager,
		resourceLoader,
		modelRuntime,
		model: modelRuntime.getModel("registration", "model"),
	});
	try {
		assert.ok(session.getActiveToolNames().includes("grep"));
		const grep = session.getAllTools().find((tool) => tool.name === "grep");
		assert.equal(grep?.description, grepTool.description);
	} finally {
		session.dispose();
		rmSync(cwd, { recursive: true, force: true });
	}
});
