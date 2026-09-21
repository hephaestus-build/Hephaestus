import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
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

import { ReviewTrace } from "../../../main/resources/agent/pi-review-trace.ts";
import { stopSession } from "../../../main/resources/agent/pi-session-lifecycle.ts";
import { hasText } from "../../../main/resources/agent/pi-text.ts";

function json(file: string): object {
	const value: unknown = JSON.parse(readFileSync(file, "utf8"));
	assert.ok(typeof value === "object" && value !== null);
	return value;
}

for (const abortStream of [false, true]) {
	void test(
		`native Pi captures provider payloads and recoverable sessions (abort=${abortStream})`,
		{ timeout: 15_000 },
		async () => {
			const root = mkdtempSync(path.join(tmpdir(), "pi-review-trace-"));
			const received: unknown[] = [];
			const server = createServer((request, response) => {
				request.on("error", () => {
					response.destroy();
				});
				const chunks: Buffer[] = [];
				request.on("data", (chunk: unknown) => {
					if (chunk instanceof Uint8Array) {
						chunks.push(Buffer.from(chunk));
					}
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
			server.listen(0, "127.0.0.1");
			await once(server, "listening");
			const address = server.address();
			assert.ok(address !== null && typeof address !== "string");
			writeFileSync(path.join(root, "evidence.txt"), "Exact private evidence from the tool.");
			const agentDir = path.join(root, ".pi");
			mkdirSync(agentDir);
			for (const name of ["auth.json", "models.json", "settings.json"]) {
				writeFileSync(path.join(agentDir, name), "{}");
			}
			const capture = new ReviewTrace(path.join(root, "out"));
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
				authPath: path.join(agentDir, "auth.json"),
				modelsPath: path.join(agentDir, "models.json"),
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
				if (
					event.type === "message_update" &&
					JSON.stringify(event).includes("Captured response")
				) {
					partial.resolve(true);
				}
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
				const requests = readdirSync(path.join(root, "out/trace/requests"));
				assert.equal(requests.length, 2);
				const requestPath = path.join(root, "out/trace/requests", requests[0] ?? "");
				for (const body of received) {
					const hash = createHash("sha256").update(JSON.stringify(body)).digest("hex");
					assert.deepEqual(json(path.join(root, "out/trace/requests", `${hash}.json`)), body);
				}
				assert.match(JSON.stringify(received[1]), /Exact private evidence from the tool/u);
				assert.equal(
					requests[0],
					`${createHash("sha256").update(readFileSync(requestPath)).digest("hex")}.json`,
				);
				const file = manager.getSessionFile();
				assert.ok(hasText(file));
				const archivedFile = path.join(root, "out/trace/sessions", path.basename(file));
				assert.equal(readFileSync(archivedFile, "utf8"), readFileSync(file, "utf8"));
				const reopened = SessionManager.open(archivedFile, capture.sessionDir);
				assert.equal(
					JSON.stringify(reopened.buildSessionContext().messages),
					JSON.stringify(manager.buildSessionContext().messages),
				);
				assert.match(readFileSync(file, "utf8"), /Captured response/u);
				assert.match(readFileSync(file, "utf8"), /Exact private evidence from the tool/u);
				const events = readFileSync(path.join(root, "out/trace/events-00000.jsonl"), "utf8");
				assert.match(events, /tool_execution_start/u);
				assert.match(events, /tool_execution_end/u);
				assert.match(events, /agent_settled/u);
				if (abortStream) {
					assert.match(readFileSync(file, "utf8"), /"stopReason":"aborted"/u);
					assert.match(events, /"stopReason":"aborted"/u);
				}
				assert.doesNotMatch(readFileSync(requestPath, "utf8"), /private-transport-credential/u);
				const status = json(path.join(root, "out/trace/capture.json"));
				assert.equal(Reflect.get(status, "complete"), true);
				assert.equal(Reflect.get(status, "providerRequests"), 2);
			} finally {
				unsubscribe();
				session.dispose();
				server.closeAllConnections();
				server.close();
				await once(server, "close");
				rmSync(root, { recursive: true, force: true });
			}
		},
	);
}

void test("capture reports interruption and capacity loss instead of claiming a complete transcript", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-trace-limit-"));
	try {
		const capture = new ReviewTrace(path.join(root, "out"), 8);
		const initial = json(path.join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(initial, "complete"), false);
		capture.session("session", "observer", undefined);
		capture.finish(137);
		const final = json(path.join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(final, "complete"), false);
		assert.equal(Reflect.get(final, "droppedRecords"), 1);
		assert.equal(Reflect.get(final, "exitCode"), 137);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a missing native session cannot be called complete", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-trace-missing-"));
	try {
		const capture = new ReviewTrace(path.join(root, "out"));
		capture.session("missing", "observer", path.join(root, "never-written.jsonl"));
		capture.finish(0);
		assert.equal(Reflect.get(json(path.join(root, "out/trace/capture.json")), "complete"), false);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("a settled failed review has complete capture without becoming a successful review", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-trace-failed-"));
	try {
		const capture = new ReviewTrace(path.join(root, "out"));
		const file = path.join(capture.sessionDir, "review.jsonl");
		writeFileSync(file, '{"type":"session"}\n');
		capture.session("review", "observer", file);
		capture.event("review", { type: "agent_start" });
		capture.event("review", { type: "agent_end", messages: [], willRetry: false });
		capture.event("review", { type: "agent_settled" });
		capture.finish(2);
		const status = json(path.join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), true);
		assert.equal(Reflect.get(status, "exitCode"), 2);
		assert.equal(
			readFileSync(path.join(root, "out/trace/sessions/review.jsonl"), "utf8"),
			readFileSync(file, "utf8"),
		);
		const interrupted = new ReviewTrace(path.join(root, "interrupted"));
		interrupted.session("review", "observer", file);
		interrupted.event("review", { type: "agent_start" });
		interrupted.event("review", { type: "agent_end", messages: [], willRetry: true });
		interrupted.finish(137);
		const partial = json(path.join(root, "interrupted/trace/capture.json"));
		assert.equal(Reflect.get(partial, "complete"), false);
		assert.equal(Reflect.get(partial, "unfinishedSessions"), 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("journal write failure is recorded rather than changing the review", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-trace-io-"));
	try {
		const capture = new ReviewTrace(path.join(root, "out"));
		mkdirSync(path.join(root, "out/trace/events-00000.jsonl"));
		assert.doesNotThrow(() => capture.session("session", "observer", undefined));
		capture.finish(0);
		const status = json(path.join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), false);
		assert.equal(Reflect.get(status, "droppedRecords"), 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("oversized native sessions cannot invalidate mandatory review outputs", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-trace-native-limit-"));
	try {
		const output = path.join(root, "out");
		const capture = new ReviewTrace(output);
		const file = path.join(capture.sessionDir, "oversized.jsonl");
		writeFileSync(file, Buffer.alloc(11 * 1024 * 1024));
		writeFileSync(path.join(output, "result.json"), "{}\n");
		capture.session("large", "observer", file);
		// Abrupt termination is safe too: live native files are never inside the host output archive.
		assert.deepEqual(readdirSync(path.join(output, "trace/sessions")), []);
		capture.finish(0);
		assert.deepEqual(readdirSync(path.join(output, "trace/sessions")), []);
		assert.equal(readFileSync(path.join(output, "result.json"), "utf8"), "{}\n");
		const status = json(path.join(output, "trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), false);
		assert.equal(Reflect.get(status, "droppedRecords"), 1);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});

void test("whole native sessions share the aggregate capture budget with the journal", () => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-trace-native-total-"));
	try {
		const capture = new ReviewTrace(path.join(root, "out"), 1024);
		for (const id of ["first", "second"]) {
			const file = path.join(capture.sessionDir, `${id}.jsonl`);
			writeFileSync(file, "x".repeat(400));
			capture.session(id, "observer", file);
		}
		capture.finish(0);
		const status = json(path.join(root, "out/trace/capture.json"));
		assert.equal(Reflect.get(status, "complete"), false);
		assert.equal(Reflect.get(status, "droppedRecords"), 1);
		assert.deepEqual(readdirSync(path.join(root, "out/trace/sessions")), ["first.jsonl"]);
		assert.equal(
			readFileSync(path.join(root, "out/trace/sessions/first.jsonl"), "utf8"),
			"x".repeat(400),
		);
		assert.ok(Number(Reflect.get(status, "captureBytes")) <= 1024);
	} finally {
		rmSync(root, { recursive: true, force: true });
	}
});
