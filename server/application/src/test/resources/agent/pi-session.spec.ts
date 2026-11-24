import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { ModelRuntime, SessionManager } from "@earendil-works/pi-coding-agent";

import { openRunSession } from "../../../main/resources/agent/pi-session.ts";

void test("a run session holds only what its descriptor names", async () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-session-spec-"));
	try {
		const cwd = path.join(root, "workspace");
		const agentDir = path.join(root, "agent");
		// Every resource that Pi would otherwise discover, where it would look for it.
		for (const directory of [
			path.join(cwd, ".pi", "skills", "decoy"),
			path.join(agentDir, "skills", "decoy"),
			path.join(agentDir, "prompts"),
			path.join(agentDir, "extensions"),
		]) {
			mkdirSync(directory, { recursive: true });
		}
		writeFileSync(path.join(root, "AGENTS.md"), "DECOY context file");
		writeFileSync(path.join(cwd, "AGENTS.md"), "DECOY context file");
		writeFileSync(path.join(agentDir, "SYSTEM.md"), "DECOY system prompt");
		writeFileSync(path.join(agentDir, "APPEND_SYSTEM.md"), "DECOY appended prompt");
		writeFileSync(
			path.join(agentDir, "skills", "decoy", "SKILL.md"),
			"---\nname: decoy\ndescription: DECOY skill\n---\nDECOY",
		);
		writeFileSync(
			path.join(cwd, ".pi", "skills", "decoy", "SKILL.md"),
			"---\nname: decoy\ndescription: DECOY skill\n---\nDECOY",
		);
		writeFileSync(path.join(agentDir, "prompts", "decoy.md"), "DECOY template");
		writeFileSync(
			path.join(agentDir, "extensions", "decoy.ts"),
			"throw new Error('DECOY extension');",
		);
		writeFileSync(
			path.join(agentDir, "settings.json"),
			JSON.stringify({ compaction: { enabled: true }, retry: { maxRetries: 99 } }),
		);
		const modelRuntime = await ModelRuntime.create({
			authPath: path.join(agentDir, "auth.json"),
			modelsPath: path.join(agentDir, "models.json"),
		});
		const model = modelRuntime.getModel("openai", "gpt-4o");
		assert.ok(model);
		const systemPrompt = "Answer from the evidence only.";

		const { session, extensionsResult } = await openRunSession(
			{ cwd, agentDir },
			{
				modelRuntime,
				model,
				thinkingLevel: undefined,
				systemPrompt,
				tools: ["read"],
				customTools: [],
				extensions: [
					(pi) => {
						pi.on("agent_settled", () => undefined);
					},
				],
				settings: { compaction: { enabled: false }, cacheWarming: "streaming" },
				sessionManager: SessionManager.inMemory(cwd),
			},
		);
		try {
			assert.deepEqual(extensionsResult.errors, []);
			assert.equal(extensionsResult.extensions.length, 1);
			assert.ok(session.systemPrompt.startsWith(systemPrompt));
			assert.doesNotMatch(session.systemPrompt, /DECOY/u);
			assert.deepEqual(session.getActiveToolNames(), ["read"]);
			assert.deepEqual(session.promptTemplates, []);
			assert.equal(session.settingsManager.getCompactionSettings().enabled, false);
			assert.notEqual(session.settingsManager.getRetrySettings().maxRetries, 99);
			assert.equal(session.settingsManager.getSettings().cacheWarming, "off");
		} finally {
			session.dispose();
		}
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
