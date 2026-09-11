import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import test from "node:test";

import {
	createAgentSession,
	DefaultResourceLoader,
	ModelRuntime,
	SessionManager,
	SettingsManager,
} from "@earendil-works/pi-coding-agent";

import { ReviewTrace } from "../../../main/resources/agent/pi-review-trace.ts";
import { stopSession } from "../../../main/resources/agent/pi-session-lifecycle.ts";

function json(path: string): object {
	const value: unknown = JSON.parse(readFileSync(path, "utf8"));
	assert.ok(typeof value === "object" && value !== null);
	return value;
}

for (const abortStream of [false, true]) {
	void test(
		`native Pi captures provider payloads and recoverable sessions (abort=${abortStream})`,
		{ timeout: 15000 },
		async () => {
			const root = mkdtempSync(join(tmpdir(), "pi-review-trace-"));
			const received: unknown[] = [];
			const server = createServer((request, response) => {
				request.on("error", () => response.destroy());
				const chunks: Buffer[] = [];
				request.on("data", (chunk: unknown) => {
					if (chunk instanceof Uint8Array) chunks.push(Buffer.from(chunk));
				});
				request.on("end", () => {
					received.push(JSON.parse(Buffer.concat(chunks).toString("utf8")));
					response.writeHead(200, { "Content-Type": "text/event-stream" });
					if (received.length === 1) {
						const chunk = {
							id: "tool-response",
							choices: [
								{
									index: 0,
									delta: {
										role: "assistant",
										tool_calls: [
											{
												index: 0,
												id: "read-evidence",
												type: "function",
												function: {
													name: "read",
													arguments: JSON.stringify({ path: "evidence.txt" }),
												},
											},
										],
									},
									finish_reason: "tool_calls",
								},
							],
						};
						response.end(`data: ${JSON.stringify(chunk)}\n\ndata: [DONE]\n\n`);
						return;
					}
					if (abortStream) {
						response.write(
							'data: {"id":"partial","choices":[{"index":0,"delta":{"role":"assistant","content":"Captured response"},"finish_reason":null}]}\n\n',
						);
						return;
					}
					response.end(
						'data: {"id":"response","choices":[{"index":0,"delta":{"role":"assistant","content":"Captured response"},"finish_reason":null}]}\n\ndata: {"id":"response","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":2,"total_tokens":14}}\n\ndata: [DONE]\n\n',
					);
				});
			});
			await new Promise<void>((resolve) => {
				server.listen(0, "127.0.0.1", resolve);
			});
			const address = server.address();
			assert.ok(address && typeof address !== "string");
			writeFileSync(join(root, "evidence.txt"), "Exact private evidence from the tool.");
			const agentDir = join(root, ".pi");
			mkdirSync(agentDir);
			for (const name of ["auth.json", "models.json", "settings.json"])
				writeFileSync(join(agentDir, name), "{}");
			const capture = new ReviewTrace(join(root, "out"));
			const settingsManager = SettingsManager.create(root, agentDir, { projectTrusted: false });
			const loader = new DefaultResourceLoader({
				cwd: root,
				agentDir,
				settingsManager,
				noContextFiles: true,
				extensionFactories: [capture.extension],
				agentsFilesOverride: () => ({ agentsFiles: [] }),
			});
			await loader.reload();
			const runtime = await ModelRuntime.create({
				authPath: join(agentDir, "auth.json"),
				modelsPath: join(agentDir, "models.json"),
				allowModelNetwork: false,
			});
			runtime.registerProvider("capture-test", {
				name: "test",
				baseUrl: `http://127.0.0.1:${address.port}/v1`,
				apiKey: "private-transport-credential",
				authHeader: true,
				api: "openai-completions",
				models: [
					{
						id: "test",
						name: "test",
						reasoning: false,
						input: ["text"],
						cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
						contextWindow: 128_000,
						maxTokens: 100,
					},
				],
			});
			const manager = SessionManager.create(root, capture.sessionDir);
			const { session } = await createAgentSession({
				cwd: root,
				agentDir,
				settingsManager,
				resourceLoader: loader,
				modelRuntime: runtime,
				model: runtime.getModel("capture-test", "test"),
				sessionManager: manager,
				tools: ["read"],
			});
			const sessionId = manager.getSessionId();
			capture.session(sessionId, "observer:test", manager.getSessionFile());
			const partial = Promise.withResolvers<boolean>();
			const unsubscribe = session.subscribe((event) => {
				capture.event(sessionId, event);
				if (event.type === "message_update" && JSON.stringify(event).includes("Captured response"))
					partial.resolve(true);
			});
			try {
				const prompt = session.prompt("Inspect only the captured evidence.");
				if (abortStream) {
					await partial.promise;
					await stopSession(session);
				}
				await prompt;
				capture.finish(0);
				assert.equal(received.length, 2);
				const requests = readdirSync(join(root, "out/trace/requests"));
				assert.equal(requests.length, 2);
				const requestPath = join(root, "out/trace/requests", requests[0] ?? "");
				for (const body of received) {
					const hash = createHash("sha256").update(JSON.stringify(body)).digest("hex");
					assert.deepEqual(json(join(root, "out/trace/requests", `${hash}.json`)), body);
				}
				assert.match(JSON.stringify(received[1]), /Exact private evidence from the tool/);
				assert.equal(
					requests[0],
					`${createHash("sha256").update(readFileSync(requestPath)).digest("hex")}.json`,
				);
				const file = manager.getSessionFile();
				assert.ok(file);
				const archivedFile = join(root, "out/trace/sessions", basename(file));
				assert.equal(readFileSync(archivedFile, "utf8"), readFileSync(file, "utf8"));
				const reopened = SessionManager.open(archivedFile, capture.sessionDir);
				assert.equal(
					JSON.stringify(reopened.buildSessionContext().messages),
					JSON.stringify(manager.buildSessionContext().messages),
				);
				assert.match(readFileSync(file, "utf8"), /Captured response/);
				assert.match(readFileSync(file, "utf8"), /Exact private evidence from the tool/);
				const events = readFileSync(join(root, "out/trace/events-00000.jsonl"), "utf8");
				assert.match(events, /tool_execution_start/);
				assert.match(events, /tool_execution_end/);
				assert.match(events, /agent_settled/);
				if (abortStream) {
					assert.match(readFileSync(file, "utf8"), /"stopReason":"aborted"/);
					assert.match(events, /"stopReason":"aborted"/);
				}
				assert.doesNotMatch(readFileSync(requestPath, "utf8"), /private-transport-credential/);
				const status = json(join(root, "out/trace/capture.json"));
				assert.equal(Reflect.get(status, "complete"), true);
				assert.equal(Reflect.get(status, "providerRequests"), 2);
			} finally {
				unsubscribe();
				session.dispose();
				server.closeAllConnections();
				await new Promise<void>((resolve) => {
					server.close(() => resolve());
				});
				rmSync(root, { recursive: true, force: true });
			}
		},
	);
}

void test("capture reports interruption and capacity loss instead of claiming a complete transcript", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-trace-limit-"));
	try {
		const capture = new ReviewTrace(join(root, "out"), 8);
		const initial = json(join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(initial, "complete"), false);
		capture.session("session", "observer", undefined);
		capture.finish(137);
		const final = json(join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(final, "complete"), false);
		assert.equal(Reflect.get(final, "droppedRecords"), 1);
		assert.equal(Reflect.get(final, "exitCode"), 137);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a missing native session and a nonzero exit cannot be called complete", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-trace-missing-"));
	try {
		const capture = new ReviewTrace(join(root, "out"));
		capture.session("missing", "observer", join(root, "never-written.jsonl"));
		capture.finish(0);
		assert.equal(Reflect.get(json(join(root, "out/trace/capture.json")), "complete"), false);
		const failed = new ReviewTrace(join(root, "failed"));
		failed.finish(1);
		assert.equal(Reflect.get(json(join(root, "failed/trace/capture.json")), "complete"), false);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("journal write failure is recorded rather than changing the review", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-trace-io-"));
	try {
		const capture = new ReviewTrace(join(root, "out"));
		mkdirSync(join(root, "out/trace/events-00000.jsonl"));
		assert.doesNotThrow(() => capture.session("session", "observer", undefined));
		capture.finish(0);
		const status = json(join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), false);
		assert.equal(Reflect.get(status, "droppedRecords"), 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("oversized native sessions cannot invalidate mandatory review outputs", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-trace-native-limit-"));
	try {
		const output = join(root, "out");
		const capture = new ReviewTrace(output);
		const file = join(capture.sessionDir, "oversized.jsonl");
		writeFileSync(file, Buffer.alloc(11 * 1024 * 1024));
		writeFileSync(join(output, "result.json"), "{}\n");
		capture.session("large", "observer", file);
		// Abrupt termination is safe too: live native files are never inside the host output archive.
		assert.deepEqual(readdirSync(join(output, "trace/sessions")), []);
		capture.finish(0);
		assert.deepEqual(readdirSync(join(output, "trace/sessions")), []);
		assert.equal(readFileSync(join(output, "result.json"), "utf8"), "{}\n");
		const status = json(join(output, "trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), false);
		assert.equal(Reflect.get(status, "droppedRecords"), 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("whole native sessions share the aggregate capture budget with the journal", () => {
	const root = mkdtempSync(join(tmpdir(), "pi-trace-native-total-"));
	try {
		const capture = new ReviewTrace(join(root, "out"), 1024);
		for (const id of ["first", "second"]) {
			const file = join(capture.sessionDir, `${id}.jsonl`);
			writeFileSync(file, "x".repeat(400));
			capture.session(id, "observer", file);
		}
		capture.finish(0);
		const status = json(join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), false);
		assert.equal(Reflect.get(status, "droppedRecords"), 1);
		assert.deepEqual(readdirSync(join(root, "out/trace/sessions")), ["first.jsonl"]);
		assert.equal(
			readFileSync(join(root, "out/trace/sessions/first.jsonl"), "utf8"),
			"x".repeat(400),
		);
		assert.ok(Number(Reflect.get(status, "captureBytes")) <= 1024);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
