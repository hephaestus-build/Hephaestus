import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	createAgentSession,
	createCodemodeExtension,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import { SANDBOX_RESOURCE_LOADER_OPTIONS } from "../../../main/resources/agent/pi-agent-sandbox.ts";
import { assessmentCacheExtension } from "../../../main/resources/agent/pi-assessment-cache.ts";
import { isRecord } from "../../../main/resources/agent/pi-observation-normalize.ts";
import {
	type ProviderConfig,
	registerHephaestusProvider,
} from "../../../main/resources/agent/pi-provider.ts";

const PREFIX = "Review this captured work.\n\nShared work description and diff.\n\n";
const JOB = "10000000-0000-4000-8000-000000000001";

/** Native SDK requests stop at this local proxy; no provider response or cache hit is simulated. */
async function wire(
	run: (context: {
		open: (
			config: ProviderConfig,
			workspace?: number,
			job?: string,
			prefix?: string,
		) => Promise<Awaited<ReturnType<typeof createAgentSession>>["session"]>;
		bodies: Record<string, unknown>[];
	}) => Promise<void>,
) {
	const bodies: Record<string, unknown>[] = [];
	const server = createServer((request, response) => {
		const chunks: Buffer[] = [];
		request.on("data", (chunk: Buffer) => {
			chunks.push(chunk);
		});
		request.on("end", () => {
			const body: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
			assert.ok(isRecord(body));
			bodies.push(body);
			response.writeHead(400, { "content-type": "application/json" });
			response.end(
				JSON.stringify({
					error: { message: "local request captured", type: "invalid_request_error" },
				}),
			);
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const cwd = mkdtempSync(path.join(tmpdir(), "assessment-cache-"));
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
		const settingsManager = SettingsManager.inMemory({ retry: { enabled: false } });
		const open = async (config: ProviderConfig, workspace = 9, job = JOB, prefix = PREFIX) => {
			assert.ok(
				registerHephaestusProvider(modelRuntime, config, {
					LLM_PROXY_URL: `http://127.0.0.1:${address.port}/v1`,
					LLM_PROXY_TOKEN: "test-only",
				}),
			);
			const model = modelRuntime.getModel("hephaestus", config.modelId);
			assert.ok(model);
			const cache = assessmentCacheExtension(config, modelRuntime, prefix, workspace, job);
			const resourceLoader = new DefaultResourceLoader({
				cwd,
				agentDir: cwd,
				settingsManager,
				...SANDBOX_RESOURCE_LOADER_OPTIONS,
				systemPrompt: "Review only the active practice.",
				agentsFilesOverride: () => ({ agentsFiles: [] }),
				// Two native handlers also exercise idempotence after an earlier handler transformed the payload.
				extensionFactories: [createCodemodeExtension({ mode: "on", models: false }), cache, cache],
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
				tools: ["read", "grep", "find", "ls", "bash", "codemode"],
			});
			sessions.push(session);
			return session;
		};
		await run({ open, bodies });
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
}

function firstUser(body: Record<string, unknown>) {
	assert.ok(Array.isArray(body.input));
	const user: unknown = body.input.find((item: unknown) => isRecord(item) && item.role === "user");
	assert.ok(isRecord(user));
	assert.ok(Array.isArray(user.content));
	return user;
}

void test("fresh native assessments share a work prefix without sharing criteria or conversation history", async () => {
	await wire(async ({ open, bodies }) => {
		const config = { apiProtocol: "openai-responses", modelId: "gpt-6-luna" };
		const first = await open(config);
		await first.prompt(`${PREFIX}Evaluate alpha.`);
		first.dispose();
		const second = await open(config);
		await second.prompt(`${PREFIX}Evaluate beta.`);
		await second.prompt("Correct beta's citation.");
		const otherJob = await open(config, 9, "10000000-0000-4000-8000-000000000002");
		await otherJob.prompt(`${PREFIX}Evaluate alpha.`);
		const otherWorkspace = await open(config, 10);
		await otherWorkspace.prompt(`${PREFIX}Evaluate alpha.`);
		assert.equal(bodies.length, 5);
		const [a, b, growing, job, workspace] = bodies;
		assert.ok(a && b && growing && job && workspace);
		assert.equal(a.prompt_cache_key, b.prompt_cache_key);
		assert.equal(a.prompt_cache_key, growing.prompt_cache_key);
		assert.notEqual(a.prompt_cache_key, job.prompt_cache_key);
		assert.notEqual(a.prompt_cache_key, workspace.prompt_cache_key);
		assert.deepEqual(a.tools, b.tools);
		for (const [body, suffix] of [
			[a, "Evaluate alpha."],
			[b, "Evaluate beta."],
			[growing, "Evaluate beta."],
		] as const) {
			const user = firstUser(body);
			assert.equal(user.role, "user");
			assert.ok(Array.isArray(user.content));
			assert.equal(user.content.length, 2);
			const shared: unknown = user.content[0];
			const criterion: unknown = user.content[1];
			assert.ok(isRecord(shared) && isRecord(criterion));
			assert.equal(shared.text, PREFIX);
			assert.equal(criterion.text, suffix);
			assert.equal(`${shared.text}${criterion.text}`, `${PREFIX}${suffix}`);
			assert.deepEqual(shared.prompt_cache_breakpoint, { mode: "explicit" });
			assert.equal("prompt_cache_breakpoint" in criterion, false);
			assert.equal("prompt_cache_options" in body, false);
		}
		assert.ok(Array.isArray(growing.input));
		assert.ok(JSON.stringify(growing.input).includes("Correct beta's citation."));
		assert.equal(JSON.stringify(b.input).includes("Evaluate alpha."), false);
	});
});

void test("unsupported models and unavailable exact openings retain the native request unchanged", async () => {
	await wire(async ({ open, bodies }) => {
		const old = await open({ apiProtocol: "openai-responses", modelId: "gpt-5" });
		await old.prompt(`${PREFIX}Evaluate alpha.`);
		const missing = await open({ apiProtocol: "openai-responses", modelId: "gpt-6-luna" });
		await missing.prompt("Compacted context without the original opening.");
		const invalidIdentity = await open(
			{ apiProtocol: "openai-responses", modelId: "gpt-6-luna" },
			9,
			"missing",
		);
		await invalidIdentity.prompt(`${PREFIX}Evaluate alpha.`);
		assert.equal(bodies.length, 3);
		for (const body of bodies) {
			const user = firstUser(body);
			assert.ok(Array.isArray(user.content));
			assert.equal(user.content.length, 1);
			assert.equal(JSON.stringify(body).includes("prompt_cache_breakpoint"), false);
		}
	});
});

void test("Chat Completions does not receive Responses cache fields", async () => {
	await wire(async ({ open, bodies }) => {
		const session = await open({ apiProtocol: "openai-completions", modelId: "gpt-6-luna" });
		await session.prompt(`${PREFIX}Evaluate alpha.`);
		assert.equal(bodies.length, 1);
		const body = bodies[0];
		assert.ok(body && Array.isArray(body.messages));
		assert.equal(JSON.stringify(body).includes("prompt_cache_breakpoint"), false);
		assert.equal("prompt_cache_options" in body, false);
	});
});
