import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { ModelRuntime, SessionManager } from "@earendil-works/pi-coding-agent";

import { KINDS } from "../../../main/resources/agent/driver-kinds.ts";
import { openRunSession } from "../../../main/resources/agent/pi-session.ts";

const AGENT_SOURCES = path.join(import.meta.dirname, "../../../main/resources/agent");

/**
 * The module that builds the sessions of each run kind that a sandbox drives. `REVIEW` starts the runner that the
 * server stages (`PracticeRunnerProfile`). `REVIEW_COMPOSE` keeps its own model client: the proxy's composer fence
 * (`ComposerTranscript`) checks each request against that client's exact conversation, and the kind retires at the
 * publication cutover.
 */
const SESSION_MODULES: Record<string, string | null> = {
	REVIEW: "pi-runner.ts",
	REVIEW_COMPOSE: null,
	TURN: "turn.ts",
	DIGEST: "turn.ts",
};

function source(file: string): string {
	return readFileSync(path.join(AGENT_SOURCES, file), "utf8");
}

/** The Pi SDK's session builders and the resource loader that a session reads. */
const SESSION_BUILDERS = [
	"createAgentSession",
	"createAgentSessionFromServices",
	"createAgentSessionRuntime",
	"AgentSession",
	"DefaultResourceLoader",
];

/**
 * Whether a source can build a Pi session: it imports a builder as a value, under any name, or reaches one through a
 * namespace or a dynamic import of the SDK. A type-only import builds nothing.
 */
function buildsSessions(text: string): boolean {
	const named = (list: string) =>
		list
			.split(",")
			.map((specifier) => specifier.trim())
			.filter((specifier) => specifier !== "" && !specifier.startsWith("type "))
			.some((specifier) =>
				SESSION_BUILDERS.includes(specifier.split(/\s+as\s+|\s*:\s*/u)[0] ?? ""),
			);
	const sdk = String.raw`["']@earendil-works\/pi-coding-agent["']`;
	for (const pattern of [
		new RegExp(String.raw`import\s*\{([^}]*)\}\s*from\s*${sdk}`, "gu"),
		new RegExp(String.raw`\{([^}]*)\}\s*=\s*await\s+import\(\s*${sdk}\s*\)`, "gu"),
	]) {
		if ([...text.matchAll(pattern)].some((match) => named(match[1] ?? ""))) {
			return true;
		}
	}
	const whole = new RegExp(
		String.raw`import\s*\*\s*as\s+\w+\s+from\s*${sdk}|=\s*await\s+import\(\s*${sdk}\s*\)\s*;`,
		"u",
	);
	const member = new RegExp(String.raw`\.(?:${SESSION_BUILDERS.join("|")})\b`, "u");
	// A call or construction by the builder's own name, however it was imported or re-exported.
	const call = /\b(?:createAgentSession\w*)\(|\bnew (?:AgentSession|DefaultResourceLoader)\(/u;
	return (whole.test(text) && member.test(text)) || call.test(text);
}

void test("every run kind opens its Pi sessions through openRunSession", () => {
	assert.deepEqual(Object.keys(SESSION_MODULES).toSorted(), Object.keys(KINDS).toSorted());
	for (const [kind, module] of Object.entries(SESSION_MODULES)) {
		if (module !== null) {
			assert.match(
				source(module),
				/\bopenRunSession\(/u,
				`${kind} opens no session through the factory`,
			);
		}
	}
});

void test("only the session factory creates a Pi session or loads its resources", () => {
	const builders = readdirSync(AGENT_SOURCES, { recursive: true, encoding: "utf8" })
		.filter((file) => file.endsWith(".ts"))
		.filter((file) => buildsSessions(source(file)));
	assert.deepEqual(builders, ["pi-session.ts"]);
});

void test("a source that reaches a session builder under another name is found", () => {
	const sdk = `"@earendil-works/pi-coding-agent"`;
	for (const text of [
		`import { createAgentSession as open } from ${sdk};`,
		`import {\n\ttype AgentSession,\n\tDefaultResourceLoader,\n} from ${sdk};`,
		`import * as pi from ${sdk};\nawait pi.createAgentSession({});`,
		`const { createAgentSession: open } = await import(${sdk});`,
		`const sdk = await import(${sdk});\nnew sdk.DefaultResourceLoader({});`,
	]) {
		assert.equal(buildsSessions(text), true, text);
	}
	assert.equal(buildsSessions(`import { type AgentSession, SessionManager } from ${sdk};`), false);
	assert.equal(buildsSessions(`const { parseSessionEntries } = await import(${sdk});`), false);
});

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
