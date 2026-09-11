import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { DefaultResourceLoader, SettingsManager } from "@earendil-works/pi-coding-agent";

import {
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
