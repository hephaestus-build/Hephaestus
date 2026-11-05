import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	createAgentSession,
	createBashTool,
	createCodemodeExtension,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import {
	REVIEW_SHELL_OPTIONS,
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

void test("a review's shell command sees the sandbox's git hardening and none of the runner's own variables", async () => {
	const runner = {
		LLM_PROXY_TOKEN: "runner-credential-marker",
		LLM_PROXY_URL: "http://gateway.invalid/internal/llm",
		SANDBOX_RUNTIME_URL: "http://gateway.invalid/runtime",
		PI_CODING_AGENT_DIR: AGENT_DIR,
		NODE_OPTIONS: "--max-old-space-size=64",
	};
	const sandbox = {
		GIT_CONFIG_COUNT: "1",
		GIT_CONFIG_KEY_0: "core.fsmonitor",
		GIT_CONFIG_VALUE_0: "false",
	};
	const saved = Object.fromEntries(
		Object.keys({ ...runner, ...sandbox }).map((name) => [name, process.env[name]]),
	);
	Object.assign(process.env, runner, sandbox);
	try {
		const result = await createBashTool(CWD, REVIEW_SHELL_OPTIONS).execute("call-1", {
			command: "env",
		});
		const output = result.content.map((part) => ("text" in part ? part.text : "")).join("");
		for (const name of Object.keys(runner)) {
			assert.doesNotMatch(output, new RegExp(`^${name}=`, "mu"), `${name} reached the command`);
		}
		assert.equal(output.includes(runner.LLM_PROXY_TOKEN), false);
		for (const [name, value] of Object.entries(sandbox)) {
			assert.match(output, new RegExp(`^${name}=${value}$`, "mu"));
		}
		assert.match(output, /^PATH=/mu);
		assert.doesNotMatch(output, /^PI_(?:SESSION|MODEL|PROVIDER)/mu);
	} finally {
		for (const [name, value] of Object.entries(saved)) {
			if (value === undefined) {
				Reflect.deleteProperty(process.env, name);
			} else {
				process.env[name] = value;
			}
		}
	}
});
