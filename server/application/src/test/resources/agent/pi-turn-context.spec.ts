import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { setImmediate } from "node:timers/promises";

import {
	createAgentSession,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import { SANDBOX_RESOURCE_LOADER_OPTIONS } from "../../../main/resources/agent/pi-agent-sandbox.ts";
import { prepareTurnText } from "../../../main/resources/agent/pi-turn-context.ts";

async function fixture(blockCompaction = false) {
	const cwd = mkdtempSync(path.join(tmpdir(), "pi-turn-context-"));
	const manager = SessionManager.inMemory(cwd);
	const settings = SettingsManager.inMemory({
		compaction: { enabled: false, reserveTokens: 100, keepRecentTokens: 10 },
	});
	const runtime = await ModelRuntime.create({
		authPath: path.join(cwd, "auth.json"),
		modelsPath: path.join(cwd, "models.json"),
	});
	const nativeModel = runtime.getModel("openai", "gpt-4o");
	assert.ok(nativeModel);
	let compactions = 0;
	const loader = new DefaultResourceLoader({
		cwd,
		agentDir: cwd,
		settingsManager: settings,
		...SANDBOX_RESOURCE_LOADER_OPTIONS,
		systemPrompt: "Only recorded evidence authorizes an observation.",
		extensionFactories: [
			(api) => {
				api.on("session_before_compact", async (event) => {
					compactions += 1;
					if (blockCompaction && !event.signal.aborted) {
						await once(event.signal, "abort");
					}
					return {
						compaction: {
							summary: "Earlier work was inspected; exact current criteria must be restored.",
							firstKeptEntryId: event.preparation.firstKeptEntryId,
							tokensBefore: event.preparation.tokensBefore,
						},
					};
				});
			},
		],
	});
	await loader.reload();
	const { session } = await createAgentSession({
		cwd,
		agentDir: cwd,
		tools: [],
		model: { ...nativeModel, contextWindow: 1000, maxTokens: 100 },
		modelRuntime: runtime,
		resourceLoader: loader,
		settingsManager: settings,
		sessionManager: manager,
	});
	const seed = (repeats = 400) => {
		manager.appendMessage({
			role: "user",
			content: "earlier evidence ".repeat(repeats),
			timestamp: 0,
		});
		manager.appendMessage({
			role: "assistant",
			content: [{ type: "text", text: "Earlier evidence was inspected." }],
			api: nativeModel.api,
			provider: nativeModel.provider,
			model: nativeModel.id,
			usage: {
				input: repeats * 4,
				output: 4,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: repeats * 4 + 4,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			},
			stopReason: "stop",
			timestamp: 1,
		});
		manager.appendMessage({ role: "user", content: "recent evidence", timestamp: 2 });
	};
	return {
		session,
		manager,
		seed,
		compactions: () => compactions,
		dispose() {
			session.dispose();
			rmSync(cwd, { recursive: true, force: true });
		},
	};
}

void test("a fitting complete turn needs no compaction", async () => {
	const f = await fixture();
	try {
		assert.equal(
			await prepareTurnText(f.session, () => "Inspect the current change."),
			"Inspect the current change.",
		);
		assert.equal(f.compactions(), 0);
	} finally {
		f.dispose();
	}
});

void test("native compaction rebuilds the opening and current criteria before dispatch", async () => {
	const f = await fixture();
	try {
		f.seed();
		let openingHeld = true;
		f.session.subscribe((event) => {
			if (event.type === "compaction_end" && !event.aborted) {
				openingHeld = false;
			}
		});
		const criteria =
			"A concrete capability is sufficient to start; a finish line is assessed separately.";
		const prepared = await prepareTurnText(
			f.session,
			() => `${openingHeld ? "" : "Exact task and brief.\n"}${criteria}`,
		);
		assert.equal(prepared, `Exact task and brief.\n${criteria}`);
		assert.equal(f.compactions(), 1);
		assert.equal(f.session.getContextUsage()?.tokens, null);
		assert.ok(
			f.manager
				.buildSessionProjection()
				.messages.some((message) => message.role === "compactionSummary"),
		);
	} finally {
		f.dispose();
	}
});

void test("unknown postcompaction usage includes the retained context and incoming turn", async () => {
	const f = await fixture();
	try {
		f.manager.appendCompaction("retained evidence ".repeat(200), null, 5000);
		assert.equal(f.session.getContextUsage()?.tokens, null);
		let attempts = 0;
		f.session.subscribe((event) => {
			if (event.type === "compaction_start") {
				attempts += 1;
			}
		});
		assert.equal(await prepareTurnText(f.session, () => "Inspect evidence."), "Inspect evidence.");
		assert.equal(attempts, 1);
		assert.equal(f.compactions(), 0);
	} finally {
		f.dispose();
	}
});

void test("essential incoming input too large on its own is refused without compaction", async () => {
	const f = await fixture();
	try {
		f.seed();
		const text = "complete essential input ".repeat(300);
		assert.equal(await prepareTurnText(f.session, () => text), null);
		assert.equal(f.compactions(), 0);
		assert.ok(
			!f.manager
				.buildSessionProjection()
				.messages.some((message) => message.role === "user" && message.content === text),
		);
	} finally {
		f.dispose();
	}
});

void test("an incoming turn can require compaction while held usage alone fits", async () => {
	const f = await fixture();
	try {
		f.seed(180);
		const usage = f.session.getContextUsage();
		assert.ok(usage?.tokens !== null && usage?.tokens !== undefined && usage.tokens < 900);
		const incoming = "Current criteria and captured brief. ".repeat(30);
		assert.equal(await prepareTurnText(f.session, () => incoming), incoming);
		assert.equal(f.compactions(), 1);
	} finally {
		f.dispose();
	}
});

void test("native idle settlement waits for manual compaction to terminate after abort", async () => {
	const f = await fixture(true);
	try {
		f.seed();
		const began = Promise.withResolvers<undefined>();
		f.session.subscribe((event) => {
			if (event.type === "compaction_start") {
				began.resolve(undefined);
			}
		});
		const prepared = prepareTurnText(f.session, () => "Current exact criteria.");
		await began.promise;
		assert.equal(f.session.isStreaming, false);
		assert.equal(f.session.isIdle, false);
		// Let the native hook attach its abort listener before the deadline aborts it.
		await setImmediate();
		f.session.abortCompaction();
		await f.session.waitForIdle();
		assert.equal(f.session.isIdle, true);
		assert.equal(await prepared, null);
		assert.equal(f.compactions(), 1);
		assert.ok(!f.manager.getEntries().some((entry) => entry.type === "compaction"));
	} finally {
		f.dispose();
	}
});
