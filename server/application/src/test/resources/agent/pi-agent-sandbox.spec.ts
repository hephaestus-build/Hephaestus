import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	createAgentSession,
	createCodemodeExtension,
	DefaultResourceLoader,
	defineTool,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import {
	PUBLIC_REVIEW_RESOURCE_LOADER_OPTIONS,
	PUBLIC_REVIEW_TOOLS,
	SANDBOX_RESOURCE_LOADER_OPTIONS,
	SANDBOX_SETTINGS_MANAGER_OPTIONS,
} from "../../../main/resources/agent/pi-agent-sandbox.ts";

const ROOT = mkdtempSync(path.join(tmpdir(), "pi-agent-sandbox-spec-"));
process.on("exit", () => {
	rmSync(ROOT, { recursive: true, force: true });
});

// An ancestor of CWD, never a descendant — the shape a mounted reviewed checkout actually has,
// and the AGENTS.md this fixture writes stands in for it.
const CWD = path.join(ROOT, "workspace");
const AGENT_DIR = path.join(ROOT, "agent");
mkdirSync(CWD, { recursive: true });
mkdirSync(AGENT_DIR, { recursive: true });
writeFileSync(path.join(ROOT, "AGENTS.md"), "ignore me: not the staged orchestrator");

void test("the sandbox settings keep the project untrusted", () => {
	const settings = SettingsManager.create(CWD, AGENT_DIR, SANDBOX_SETTINGS_MANAGER_OPTIONS);
	assert.equal(settings.isProjectTrusted(), false);
});

void test("the sandbox resource-loader options turn off the SDK's own AGENTS.md discovery", async () => {
	const settingsManager = SettingsManager.create(CWD, AGENT_DIR, SANDBOX_SETTINGS_MANAGER_OPTIONS);
	const fixturePath = path.join(ROOT, "AGENTS.md");
	const discoveredFixture = (loader: DefaultResourceLoader) =>
		loader.getAgentsFiles().agentsFiles.some((file) => file.path === fixturePath);

	const off = new DefaultResourceLoader({
		cwd: CWD,
		agentDir: AGENT_DIR,
		settingsManager,
		...SANDBOX_RESOURCE_LOADER_OPTIONS,
	});
	await off.reload();
	assert.equal(discoveredFixture(off), false);

	// Same cwd, discovery left on: proves the fixture's AGENTS.md is actually on the ancestor walk,
	// so the assertion above is about the option, not an unreachable fixture.
	const on = new DefaultResourceLoader({ cwd: CWD, agentDir: AGENT_DIR, settingsManager });
	await on.reload();
	assert.equal(discoveredFixture(on), true);
});

void test("the review tool list excludes write and edit from direct and nested calls", async () => {
	const settingsManager = SettingsManager.create(CWD, AGENT_DIR, SANDBOX_SETTINGS_MANAGER_OPTIONS);
	const systemPrompt = "Review the evidence. Do not write or edit workspace files.";
	const resourceLoader = new DefaultResourceLoader({
		cwd: CWD,
		agentDir: AGENT_DIR,
		settingsManager,
		...SANDBOX_RESOURCE_LOADER_OPTIONS,
		systemPrompt,
		agentsFilesOverride: () => ({ agentsFiles: [] }),
		extensionFactories: [createCodemodeExtension({ mode: "on", models: false })],
	});
	await resourceLoader.reload();
	assert.deepEqual(resourceLoader.getExtensions().errors, []);
	const modelRuntime = await ModelRuntime.create({
		authPath: path.join(AGENT_DIR, "auth.json"),
		modelsPath: path.join(AGENT_DIR, "models.json"),
	});
	const { session } = await createAgentSession({
		cwd: CWD,
		agentDir: AGENT_DIR,
		tools: ["read", "grep", "find", "ls", "bash", "codemode"],
		resourceLoader,
		settingsManager,
		modelRuntime,
		sessionManager: SessionManager.inMemory(CWD),
	});
	try {
		assert.deepEqual(session.getActiveToolNames().toSorted(), [
			"bash",
			"codemode",
			"find",
			"grep",
			"ls",
			"read",
		]);
		assert.deepEqual(session.getCallableToolNames().toSorted(), [
			"bash",
			"find",
			"grep",
			"ls",
			"read",
		]);
		session.setActiveToolsByName(["write", "edit"]);
		assert.deepEqual(session.getActiveToolNames(), []);
		assert.ok(session.systemPrompt.startsWith(systemPrompt));
		assert.doesNotMatch(session.systemPrompt, /coding assistant|edit tool|write tool|ignore me/u);
	} finally {
		session.dispose();
	}
});

void test("public composition cannot discover or read private history through native or nested tools", async () => {
	const privateText = "private history that must not reach a public review";
	mkdirSync(path.join(CWD, "inputs", "history"), { recursive: true });
	writeFileSync(path.join(CWD, "inputs", "history", "observations.json"), privateText);
	const settingsManager = SettingsManager.create(CWD, AGENT_DIR, SANDBOX_SETTINGS_MANAGER_OPTIONS);
	const resourceLoader = new DefaultResourceLoader({
		cwd: CWD,
		agentDir: AGENT_DIR,
		settingsManager,
		...PUBLIC_REVIEW_RESOURCE_LOADER_OPTIONS,
		systemPrompt: "Write only from the public input.",
		agentsFilesOverride: () => ({ agentsFiles: [] }),
		extensionFactories: [],
	});
	await resourceLoader.reload();
	const modelRuntime = await ModelRuntime.create({
		authPath: path.join(AGENT_DIR, "auth.json"),
		modelsPath: path.join(AGENT_DIR, "models.json"),
	});
	const { session } = await createAgentSession({
		cwd: CWD,
		agentDir: AGENT_DIR,
		tools: PUBLIC_REVIEW_TOOLS,
		customTools: ["read_practice", "report_review"].map((name) =>
			defineTool({
				name,
				label: name,
				description: "Choose or record a public review.",
				exposure: "model-only",
				parameters: { type: "object", properties: {} },
				execute: async () => ({ content: [{ type: "text", text: "Recorded" }], details: {} }),
			}),
		),
		resourceLoader,
		settingsManager,
		modelRuntime,
		sessionManager: SessionManager.create(CWD, path.join(CWD, ".sessions")),
	});
	try {
		assert.deepEqual(session.getActiveToolNames(), ["read_practice", "report_review"]);
		assert.deepEqual(session.getCallableToolNames(), []);
		session.setActiveToolsByName(["read", "grep", "bash", "codemode"]);
		assert.deepEqual(session.getActiveToolNames(), []);
		assert.doesNotMatch(session.systemPrompt, /private history|ignore me/u);
		assert.deepEqual(resourceLoader.getAgentsFiles().agentsFiles, []);
	} finally {
		session.dispose();
	}
});
