import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type ServerResponse } from "node:http";
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

import { isJsonObject as isRecord } from "../../../../../../docker/agents/practice-scripts/lib/core/json.ts";
import { SANDBOX_RESOURCE_LOADER_OPTIONS } from "../../../main/resources/agent/pi-agent-sandbox.ts";
import { assessmentCacheExtension } from "../../../main/resources/agent/pi-assessment-cache.ts";
import {
	type ProviderConfig,
	registerHephaestusProvider,
} from "../../../main/resources/agent/pi-provider.ts";
import { buildBrief } from "../../../main/resources/agent/pi-review-brief.ts";

const PREFIX = "Review this captured work.\n\nShared work description and diff.\n\n";
const JOB = "10000000-0000-4000-8000-000000000001";

void test("Responses preserves text and roles with system, change and core cache boundaries", async () => {
	await wire(async ({ open, bodies }) => {
		const prefix = "task\nchange\ncore\nindex\n";
		const session = await open(
			{ apiProtocol: "openai-responses", modelId: "gpt-6-luna" },
			9,
			JOB,
			prefix,
			[12, 17],
		);
		await session.prompt(`${prefix}Evaluate alpha.`);
		const body = bodies[0];
		assert.ok(body && Array.isArray(body.input));
		assert.equal("prompt_cache_retention" in body, false);
		assert.equal("prompt_cache_options" in body, false);
		const user = firstUser(body);
		assert.ok(Array.isArray(user.content));
		assert.deepEqual(
			user.content.map((entry: unknown) => {
				assert.ok(isRecord(entry));
				return entry.text;
			}),
			["task\nchange\n", "core\n", "index\nEvaluate alpha."],
		);
		assert.equal(JSON.stringify(body).match(/prompt_cache_breakpoint/gu)?.length, 3);
		const system: unknown = body.input[0];
		assert.ok(isRecord(system));
		assert.equal(system.role, "system");
		assert.ok(Array.isArray(system.content));
		const instructions: unknown = system.content[0];
		assert.ok(isRecord(instructions) && typeof instructions.text === "string");
		assert.equal(instructions.text, session.systemPrompt);
		assert.partialDeepStrictEqual(instructions, {
			type: "input_text",
			prompt_cache_breakpoint: { mode: "explicit" },
		});
	});
});

void test("native Responses emits message_start at headers and message_update only after SSE body data", async () => {
	const bodyReady = Promise.withResolvers<undefined>();
	try {
		await wire(
			async ({ open }) => {
				const session = await open({ apiProtocol: "openai-responses", modelId: "gpt-6-luna" });
				const headers = Promise.withResolvers<undefined>();
				let receivedContent = false;
				const unsubscribe = session.subscribe((event) => {
					if (event.type === "message_start" && event.message.role === "assistant") {
						headers.resolve(undefined);
					}
					if (event.type === "message_update") {
						receivedContent = true;
					}
				});
				try {
					const reply = session.prompt(`${PREFIX}Evaluate alpha.`);
					await headers.promise;
					assert.equal(
						receivedContent,
						false,
						"headers do not prove that provider body data arrived",
					);
					bodyReady.resolve(undefined);
					await reply;
					assert.equal(receivedContent, true, "the native SSE response produces a content update");
				} finally {
					unsubscribe();
				}
			},
			(response) => {
				response.writeHead(200, { "content-type": "text/event-stream" });
				response.flushHeaders();
				void bodyReady.promise.then(() => {
					const part = { type: "output_text", text: "ok", annotations: [] };
					const item = { id: "m", type: "message", role: "assistant", content: [part] };
					for (const event of [
						{ type: "response.created", response: { id: "r", status: "in_progress", output: [] } },
						{ type: "response.output_item.added", output_index: 0, item: { ...item, content: [] } },
						{
							type: "response.content_part.added",
							output_index: 0,
							content_index: 0,
							part: { ...part, text: "" },
						},
						{ type: "response.output_text.delta", output_index: 0, content_index: 0, delta: "ok" },
						{ type: "response.output_item.done", output_index: 0, item },
						{
							type: "response.completed",
							response: {
								id: "r",
								status: "completed",
								output: [item],
								usage: { input_tokens: 100, output_tokens: 1 },
							},
						},
					]) {
						response.write(`data: ${JSON.stringify(event)}\n\n`);
					}
					response.end("data: [DONE]\n\n");
				});
			},
		);
	} finally {
		bodyReady.resolve(undefined);
	}
});

/** Native SDK calls stop at this local server; no external provider is contacted. */
async function wire(
	run: (context: {
		open: (
			config: ProviderConfig,
			workspace?: number,
			job?: string,
			prefix?: string,
			boundaries?: readonly number[],
		) => Promise<Awaited<ReturnType<typeof createAgentSession>>["session"]>;
		bodies: Record<string, unknown>[];
	}) => Promise<void>,
	respond?: (response: ServerResponse) => void,
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
			if (respond !== undefined) {
				respond(response);
				return;
			}
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
		const open = async (
			config: ProviderConfig,
			workspace = 9,
			job = JOB,
			prefix = PREFIX,
			boundaries: readonly number[] = [],
		) => {
			assert.ok(
				registerHephaestusProvider(modelRuntime, config, {
					LLM_PROXY_URL: `http://127.0.0.1:${address.port}/v1`,
					LLM_PROXY_TOKEN: "test-only",
				}),
			);
			const model = modelRuntime.getModel("hephaestus", config.modelId);
			assert.ok(model);
			const cache = assessmentCacheExtension(
				config,
				modelRuntime,
				prefix,
				workspace,
				job,
				boundaries,
			);
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

void test("practices that declare the same sources send the provider the same cached opening", async () => {
	const root = mkdtempSync(path.join(tmpdir(), "assessment-brief-"));
	const files: Record<string, string> = {
		"context/metadata.json": '{"title": "Add login"}',
		"context/comments.json": '{"comments": [{"body": "nit"}]}',
		"work/change/diff.patch": "diff --git a/a.ts b/a.ts\n",
	};
	for (const [file, text] of Object.entries(files)) {
		mkdirSync(path.join(root, file, ".."), { recursive: true });
		writeFileSync(path.join(root, file), text);
	}
	const fileKinds = new Map([
		["context/metadata.json", "scm.pull-request.core"],
		["context/comments.json", "scm.pull-request.comments"],
	]);
	const available = new Set([
		"scm.pull-request.core",
		"scm.pull-request.comments",
		"scm.pull-request.diff",
	]);
	// The runner's opening: the task prompt, then the practice's brief (`assessmentOpening`).
	const opening = (declared: string[]) =>
		`Review this captured work.\n\n${
			buildBrief(
				root,
				{ contextRoot: "context", repositoryRoot: "repos/reviewed" },
				{
					declared: new Set(declared),
					fileKinds,
					available,
				},
			).text
		}\n\n`;
	const core = opening(["scm.pull-request.diff", "scm.pull-request.core"]);
	const sameSet = opening(["scm.pull-request.core", "scm.pull-request.diff"]);
	const discussion = opening(["scm.pull-request.diff", "scm.pull-request.comments"]);
	try {
		await wire(async ({ open, bodies }) => {
			const config = { apiProtocol: "openai-responses", modelId: "gpt-6-luna" };
			for (const [prefix, practice] of [
				[core, "Evaluate alpha."],
				[sameSet, "Evaluate beta."],
				[discussion, "Evaluate gamma."],
			] as const) {
				const session = await open(config, 9, JOB, prefix);
				await session.prompt(`${prefix}${practice}`);
			}
			const shared = bodies.map((body) => {
				const user = firstUser(body);
				assert.ok(Array.isArray(user.content));
				const block: unknown = user.content[0];
				assert.ok(isRecord(block));
				return block.text;
			});
			assert.equal(shared[0], shared[1]);
			assert.notEqual(shared[0], shared[2]);
			assert.doesNotMatch(String(shared[0]), /nit/u);
		});
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
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
