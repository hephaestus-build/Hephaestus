import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type ServerResponse } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { type TestContext } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { SessionManager } from "@earendil-works/pi-coding-agent";

import type { MentorRequest } from "../../../main/resources/agent/pi-mentor-protocol.ts";

// The real runner on the pinned Pi SDK, against a local OpenAI-compatible endpoint that scripts each model
// call. Pi's own compaction, session file and abort run unmodified: only the model is fake, and the usage it
// reports is what moves the session past Pi's threshold at the real 128k window (128000 - 16384 = 111616).

const RUNNER = path.resolve(
	import.meta.dirname,
	"../../../main/resources/agent/pi-mentor-runner.ts",
);
const THREAD = "0f1e2d3c-4b5a-4968-8776-655443322110";
const HISTORY = "inputs/context/observations_history.json";
const SUMMARY = "SUMMARY-CHECKPOINT: the developer asked about their review history.";
/** Text only the oldest saved answer carries, so a request without it no longer holds that answer. */
const OLDEST = "OLDEST-ANSWER";

type Json = Record<string, unknown>;

function isRecord(value: unknown): value is Json {
	return typeof value === "object" && value !== null;
}

type Reply =
	| { text: string; promptTokens: number; completionDelayMs?: number }
	| { toolCall: string; promptTokens: number }
	| { tool: string; arguments: Json; promptTokens: number }
	| { status: number }
	| "hang";

interface FakeModel {
	url: string;
	/** Every call, in arrival order: a compaction `summary`, or an ordinary `turn`. */
	calls: ("summary" | "turn")[];
	bodies: string[];
}

/** Pi sends its compaction summaries under this system prompt. */
function isSummary(body: Json): boolean {
	const messages = Array.isArray(body.messages) ? body.messages : [];
	const system: unknown = messages[0];
	return (
		isRecord(system) &&
		typeof system.content === "string" &&
		system.content.startsWith("You are a context summarization assistant.")
	);
}

async function fakeModel(
	t: TestContext,
	script: (kind: "summary" | "turn", index: number) => Reply,
): Promise<FakeModel> {
	const model: FakeModel = { url: "", calls: [], bodies: [] };
	const hanging = new Set<ServerResponse>();
	const server = createServer((req, res) => {
		let body = "";
		req.on("data", (chunk: Buffer) => {
			body += chunk.toString("utf8");
		});
		req.on("end", () => {
			const parsed: unknown = JSON.parse(body);
			assert.ok(isRecord(parsed));
			const kind = isSummary(parsed) ? "summary" : "turn";
			model.calls.push(kind);
			model.bodies.push(body);
			const reply = script(kind, model.calls.filter((call) => call === kind).length - 1);
			if (reply === "hang") {
				hanging.add(res);
				res.writeHead(200, { "content-type": "text/event-stream" });
				return;
			}
			if ("status" in reply) {
				res.writeHead(reply.status, { "content-type": "application/json" });
				res.end(
					JSON.stringify({ error: { message: "scripted failure", type: "invalid_request_error" } }),
				);
				return;
			}
			res.writeHead(200, { "content-type": "text/event-stream" });
			const send = (payload: Json) =>
				res.write(
					`data: ${JSON.stringify({ id: "c", object: "chat.completion.chunk", created: 0, model: "fake", ...payload })}\n\n`,
				);
			let call: { name: string; arguments: string } | undefined;
			if ("tool" in reply) {
				call = { name: reply.tool, arguments: JSON.stringify(reply.arguments) };
			} else if ("toolCall" in reply) {
				call = { name: "fetch_context", arguments: JSON.stringify({ path: reply.toolCall }) };
			}
			const delta =
				call === undefined
					? { role: "assistant", content: "text" in reply ? reply.text : "" }
					: {
							role: "assistant",
							tool_calls: [
								{
									index: 0,
									id: `call_${model.calls.length}`,
									type: "function",
									function: call,
								},
							],
						};
			send({ choices: [{ index: 0, delta, finish_reason: null }] });
			send({
				choices: [{ index: 0, delta: {}, finish_reason: "text" in reply ? "stop" : "tool_calls" }],
			});
			send({
				choices: [],
				usage: {
					prompt_tokens: reply.promptTokens,
					completion_tokens: 40,
					total_tokens: reply.promptTokens + 40,
				},
			});
			if ("completionDelayMs" in reply && reply.completionDelayMs !== undefined) {
				setTimeout(() => {
					res.end("data: [DONE]\n\n");
				}, reply.completionDelayMs).unref();
			} else {
				res.end("data: [DONE]\n\n");
			}
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(isRecord(address) && typeof address.port === "number");
	model.url = `http://127.0.0.1:${address.port}/v1`;
	t.after(async () => {
		for (const res of hanging) {
			res.destroy();
		}
		server.closeAllConnections();
		server.close();
		await once(server, "close");
	});
	return model;
}

/**
 * A runner's working directory; a saved session records the directory it was made in, as production's does. The
 * runner started in it removes it once it has exited.
 */
function runnerRoot(): string {
	const root = mkdtempSync(path.join(tmpdir(), "pi-mentor-recovery-"));
	mkdirSync(path.join(root, "agent"));
	writeFileSync(
		path.join(root, "pi-provider.json"),
		JSON.stringify({ apiProtocol: "openai-completions", modelId: "fake-mentor" }),
	);
	writeFileSync(path.join(root, "system.md"), "You are a test mentor.");
	return root;
}

interface WireEvent {
	type: string;
	event: Json;
}

interface Runner {
	send: (request: MentorRequest | Json) => void;
	/** Every event the runner sent, in order. */
	events: WireEvent[];
	next: (predicate: (frame: Json) => boolean, timeoutMs?: number) => Promise<Json>;
}

function spawnRealRunner(
	t: TestContext,
	root: string,
	model: FakeModel,
	env: Record<string, string> = {},
): Runner {
	const { MENTOR_RUNNER_PROTOCOL_ONLY: _stub, ...inherited } = process.env;
	const child = spawn(process.execPath, [RUNNER], {
		env: {
			...inherited,
			AGENT_BUDGET_MS: "120000",
			MENTOR_RUNNER_CWD: root,
			MENTOR_RUNNER_SESSIONS_DIR: path.join(root, "sessions"),
			MENTOR_RUNNER_SYSTEM_PROMPT_PATH: path.join(root, "system.md"),
			PI_CODING_AGENT_DIR: path.join(root, "agent"),
			LLM_PROXY_URL: model.url,
			LLM_PROXY_TOKEN: "test-token",
			...env,
		},
		stdio: ["pipe", "pipe", "pipe"],
	});
	let stderr = "";
	child.stderr.on("data", (chunk: Buffer) => {
		stderr += chunk.toString("utf8");
	});
	const frames: Json[] = [];
	const events: WireEvent[] = [];
	const arrivals = new EventTarget();
	let buffer = "";
	child.stdout.on("data", (chunk: Buffer) => {
		buffer += chunk.toString("utf8");
		for (let nl = buffer.indexOf("\n"); nl !== -1; nl = buffer.indexOf("\n")) {
			const frame: unknown = JSON.parse(buffer.slice(0, nl));
			buffer = buffer.slice(nl + 1);
			assert.ok(isRecord(frame));
			frames.push(frame);
			const event = eventOf(frame);
			if (event) {
				events.push(event);
			}
			arrivals.dispatchEvent(new Event("frame"));
		}
	});
	let cursor = 0;
	const next: Runner["next"] = async (predicate, timeoutMs = 20_000) => {
		const deadline = Date.now() + timeoutMs;
		for (;;) {
			for (; cursor < frames.length; cursor += 1) {
				const frame = frames[cursor];
				if (frame !== undefined && predicate(frame)) {
					cursor += 1;
					return frame;
				}
			}
			const remaining = deadline - Date.now();
			if (remaining <= 0) {
				throw new Error(
					`no matching frame after: ${events.map((e) => e.type).join(", ")}\n${stderr}`,
				);
			}
			const stop = new AbortController();
			await Promise.race([
				once(arrivals, "frame", { signal: stop.signal }),
				delay(remaining, undefined, { signal: stop.signal }),
			]);
			stop.abort();
		}
	};
	// One hook, so the directory goes only after the runner writing into it has exited: a throwing hook skips the
	// test's later ones, and the runner would outlive the test file.
	t.after(async () => {
		child.stdin.end();
		if (child.exitCode === null) {
			await once(child, "close");
		}
		rmSync(root, { recursive: true, force: true });
	});
	const send: Runner["send"] = (request) => {
		child.stdin.write(`${JSON.stringify(request)}\n`);
	};
	return { send, events, next };
}

function eventOf(frame: Json): WireEvent | undefined {
	if (frame.method !== "event" || !isRecord(frame.params) || !isRecord(frame.params.event)) {
		return undefined;
	}
	const { event } = frame.params;
	return { type: String(event.type), event };
}

const isResult = (id: string) => (frame: Json) => frame.id === id && "result" in frame;
const isEvent = (type: string) => (frame: Json) => eventOf(frame)?.type === type;

/** A model reply that calls `link_observation` for `observationId`. */
const linkReply = (observationId: string): Reply => ({
	tool: "link_observation",
	arguments: { observationId, text: "Who approved !9 before it merged?" },
	promptTokens: 3000,
});

async function openAndPrompt(
	runner: Runner,
	text: string,
	session = "",
	currentEvidence?: string,
): Promise<void> {
	runner.send({
		jsonrpc: "2.0",
		id: "open",
		method: "open_thread",
		params: { threadId: THREAD, session },
	});
	await runner.next(isResult("open"));
	runner.send({
		jsonrpc: "2.0",
		id: "prompt",
		method: "prompt",
		params: { threadId: THREAD, text, currentEvidence },
	});
	await runner.next(isResult("prompt"));
}

/** Answers the runner's next `fetch_context` callback with a document of about `chars` characters. */
async function answerFetch(runner: Runner, chars: number): Promise<void> {
	const callback = await runner.next((frame) => frame.method === "fetch_context");
	runner.send({
		jsonrpc: "2.0",
		id: callback.id,
		result: { content: { rows: "r".repeat(chars) } },
	});
}

const typesOf = (runner: Runner) => runner.events.map((e) => e.type);
const jsonlOf = (event: WireEvent | undefined) => String(event?.event.jsonl);
const hasCompaction = (jsonl: string) =>
	jsonl
		.split("\n")
		.filter((line) => line !== "")
		.some((line) => {
			const entry: unknown = JSON.parse(line);
			return isRecord(entry) && entry.type === "compaction";
		});

interface SavedReply {
	text?: string;
	toolResultChars?: number;
	inputTokens: number;
	/** The reply that answered the question once the tool result arrived. */
	answer?: { text: string; inputTokens: number };
}

const usageOf = (input: number) => ({
	input,
	output: 40,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: input + 40,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
});

/**
 * A saved session, made in the runner's directory: questions and replies, where a reply with
 * `toolResultChars` called fetch_context and is followed by that many characters of result.
 */
function savedSession(root: string, replies: SavedReply[]): string {
	const dir = path.join(root, "fixture");
	mkdirSync(dir, { recursive: true });
	const manager = SessionManager.create(root, dir);
	let timestamp = 0;
	const tick = () => (timestamp += 1);
	const assistant = {
		role: "assistant" as const,
		api: "openai-completions",
		provider: "hephaestus",
		model: "fake-mentor",
	};
	for (const [index, reply] of replies.entries()) {
		manager.appendMessage({
			role: "user",
			content: [{ type: "text", text: `question ${index}` }],
			timestamp: tick(),
		});
		if (reply.toolResultChars === undefined) {
			manager.appendMessage({
				...assistant,
				content: [{ type: "text", text: reply.text ?? "answer" }],
				usage: usageOf(reply.inputTokens),
				stopReason: "stop",
				timestamp: tick(),
			});
			continue;
		}
		const id = `call_saved_${index}`;
		manager.appendMessage({
			...assistant,
			content: [{ type: "toolCall", id, name: "fetch_context", arguments: { path: HISTORY } }],
			usage: usageOf(reply.inputTokens),
			stopReason: "toolUse",
			timestamp: tick(),
		});
		manager.appendMessage({
			role: "toolResult",
			toolCallId: id,
			toolName: "fetch_context",
			content: [{ type: "text", text: "x".repeat(reply.toolResultChars) }],
			isError: false,
			timestamp: tick(),
		});
		if (reply.answer !== undefined) {
			manager.appendMessage({
				...assistant,
				content: [{ type: "text", text: reply.answer.text }],
				usage: usageOf(reply.answer.inputTokens),
				stopReason: "stop",
				timestamp: tick(),
			});
		}
	}
	const file = manager.getSessionFile();
	assert.ok(file !== undefined);
	const jsonl = readFileSync(file, "utf8");
	rmSync(dir, { recursive: true, force: true });
	return jsonl;
}

/**
 * Two long saved answers: enough for Pi to summarise, under the working trigger by their recorded usage. The
 * newer one alone passes Pi's retention target, so a compaction summarises the oldest.
 */
const longHistory = (root: string, ...more: SavedReply[]) =>
	savedSession(root, [
		{ text: `${OLDEST} ${"a".repeat(100_000)}`, inputTokens: 2000 },
		{ text: "b".repeat(100_000), inputTokens: 2000 },
		...more,
	]);

/**
 * The measured incident: the last reply ran on about 96k input tokens after fetching a 199,907-character
 * observation history, far under Pi's default threshold of 111,616 at the real 128k window.
 */
const incident = (root: string) =>
	longHistory(root, {
		toolResultChars: 199_907,
		inputTokens: 90_000,
		answer: { text: "previous answer", inputTokens: 96_000 },
	});

/** One ordinary call that asks for the history and reports a context past the working trigger. */
const bigToolCall: Reply = { toolCall: HISTORY, promptTokens: 105_000 };

const piError = (runner: Runner) =>
	String(runner.events.find((e) => e.type === "pi_error")?.event.error);

void test("a native retry exposes its earlier calls but settles only its final continuation", async (t) => {
	const model = await fakeModel(t, (_kind, index) => {
		if (index === 0) {
			return { toolCall: HISTORY, promptTokens: 1000 };
		}
		if (index === 1) {
			return { status: 502 };
		}
		return { text: "recovered answer", promptTokens: 200 };
	});
	const runner = spawnRealRunner(t, runnerRoot(), model);
	await openAndPrompt(runner, "what happened in the review?");
	await answerFetch(runner, 100);
	await runner.next(isEvent("agent_end"));
	assert.deepEqual(model.calls, ["turn", "turn", "turn"]);
	const types = typesOf(runner);
	assert.ok(types.includes("auto_retry_start"), types.join(", "));
	assert.ok(!types.includes("compaction_start"), types.join(", "));
	const reported = runner.events
		.filter((event) => event.type === "message_end")
		.map((event) => event.event.message);
	assert.ok(
		reported.some(
			(message) => isRecord(message) && isRecord(message.usage) && message.usage.input === 1000,
		),
	);
	const final = runner.events.filter((event) => event.type === "agent_end");
	assert.equal(final.length, 1);
	const messages = final[0]?.event.messages;
	assert.ok(Array.isArray(messages));
	assert.equal(messages.length, 1);
	const answer: unknown = messages[0];
	assert.ok(isRecord(answer) && isRecord(answer.usage));
	assert.equal(answer.stopReason, "stop");
	assert.equal(answer.usage.input, 200);
});

void test("a completed compaction is checkpointed before the watchdog fails the turn, and restores", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (kind, index) => {
		if (kind === "summary") {
			return { text: SUMMARY, promptTokens: 3000 };
		}
		return index === 0 ? bigToolCall : "hang";
	});
	const runner = spawnRealRunner(t, root, model, {
		AGENT_BUDGET_MS: "6000",
		MENTOR_TURN_GRACE_MS: "1000",
	});
	await openAndPrompt(runner, "how did my reviews go?", longHistory(root));
	await answerFetch(runner, 20_000);
	await runner.next(isEvent("turn_watchdog_fired"), 30_000);
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	const compacted = runner.events.findIndex((e) => e.type === "compaction_end");
	assert.ok(compacted !== -1, types.join(", "));
	const result = runner.events[compacted]?.event.result;
	assert.ok(isRecord(result) && isRecord(result.usage) && typeof result.usage.input === "number");
	assert.ok(result.usage.input >= 3000, "the summary calls' usage reaches the server");
	const checkpoint = runner.events[compacted + 1];
	assert.equal(checkpoint?.type, "session_persisted", types.join(", "));
	assert.ok(hasCompaction(jsonlOf(checkpoint)) && jsonlOf(checkpoint).includes(SUMMARY));
	// The aborted turn's own export comes last before the failure, and nothing else Pi said about it follows.
	const watchdog = types.indexOf("turn_watchdog_fired");
	const last = runner.events[watchdog - 1];
	assert.equal(last?.type, "session_persisted", types.join(", "));
	assert.ok(hasCompaction(jsonlOf(last)));
	assert.deepEqual(types.slice(watchdog), ["turn_watchdog_fired", "agent_end"]);
	assert.deepEqual(runner.events.at(-1)?.event.messages, []);
	assert.equal(
		types.filter((type) => type === "agent_end").length,
		1,
		"no successful finish for the aborted run",
	);
	assert.ok(!types.includes("pi_error"), types.join(", "));
	assert.equal(model.calls[0], "turn");
	assert.equal(model.calls.at(-1), "turn");

	// A fresh runner restored from that checkpoint answers from the summary, without compacting again.
	const restoredModel = await fakeModel(t, () => ({ text: "restored answer", promptTokens: 9000 }));
	const restored = spawnRealRunner(t, runnerRoot(), restoredModel);
	await openAndPrompt(restored, "and now?", jsonlOf(last));
	const end = await restored.next(isEvent("agent_end"));
	assert.ok(JSON.stringify(end).includes("restored answer"));
	assert.deepEqual(restoredModel.calls, ["turn"]);
	const restoredTurn = restoredModel.bodies[0] ?? "";
	assert.ok(restoredTurn.includes("SUMMARY-CHECKPOINT") && !restoredTurn.includes(OLDEST));
});

void test("the configured turn budget covers compaction and the subsequent answer", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (kind, index) => {
		if (kind === "summary") {
			return { text: SUMMARY, promptTokens: 3000, completionDelayMs: 100 };
		}
		return index === 0
			? bigToolCall
			: { text: "answer after compaction", promptTokens: 9000, completionDelayMs: 100 };
	});
	const runner = spawnRealRunner(t, root, model, {
		AGENT_BUDGET_MS: "6000",
		MENTOR_TURN_GRACE_MS: "1000",
	});
	await openAndPrompt(runner, "how did my reviews go?", longHistory(root));
	await answerFetch(runner, 20_000);
	const terminal = await runner.next(isEvent("agent_end"));

	assert.ok(JSON.stringify(terminal).includes("answer after compaction"));
	assert.equal(model.calls.filter((kind) => kind === "turn").length, 2);
	assert.ok(model.calls.includes("summary"));
	const types = typesOf(runner);
	assert.ok(types.includes("compaction_end") && types.includes("session_persisted"));
	assert.ok(!types.includes("turn_watchdog_fired"), types.join(", "));
	assert.equal(types.filter((type) => type === "agent_end").length, 1);
});

void test("a failed compaction is not checkpointed and the restored checkpoint stays in place", async (t) => {
	const root = runnerRoot();
	const prior = longHistory(root);
	const model = await fakeModel(t, (kind, index) => {
		if (kind === "summary") {
			return { status: 400 };
		}
		return index === 0 ? bigToolCall : "hang";
	});
	const runner = spawnRealRunner(t, root, model, {
		AGENT_BUDGET_MS: "6000",
		MENTOR_TURN_GRACE_MS: "1000",
	});
	await openAndPrompt(runner, "how did my reviews go?", prior);
	await answerFetch(runner, 20_000);
	await runner.next(isEvent("turn_watchdog_fired"), 30_000);
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	const failed = runner.events.find((e) => e.type === "compaction_end");
	assert.ok(failed !== undefined && failed.event.result === undefined, types.join(", "));
	const exports = runner.events.filter((e) => e.type === "session_persisted");
	assert.equal(exports.length, 1, types.join(", "));
	assert.equal(types.indexOf("session_persisted"), types.indexOf("turn_watchdog_fired") - 1);
	assert.ok(!hasCompaction(jsonlOf(exports[0])));
	assert.ok(jsonlOf(exports[0]).startsWith(prior), "the export extends the restored checkpoint");
});

void test("a compaction the watchdog interrupts is not checkpointed and the turn still fails in time", async (t) => {
	const root = runnerRoot();
	const prior = longHistory(root);
	const model = await fakeModel(t, (kind) => (kind === "summary" ? "hang" : bigToolCall));
	const runner = spawnRealRunner(t, root, model, {
		AGENT_BUDGET_MS: "5000",
		MENTOR_TURN_GRACE_MS: "1000",
	});
	await openAndPrompt(runner, "how did my reviews go?", prior);
	await answerFetch(runner, 20_000);
	await runner.next(isEvent("compaction_start"));
	await runner.next(isEvent("turn_watchdog_fired"), 30_000);
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	assert.ok(!types.includes("compaction_end"), types.join(", "));
	const exports = runner.events.filter((e) => e.type === "session_persisted");
	assert.equal(exports.length, 1, types.join(", "));
	assert.ok(!hasCompaction(jsonlOf(exports[0])));
	assert.ok(jsonlOf(exports[0]).startsWith(prior));
	assert.deepEqual(types.slice(types.indexOf("turn_watchdog_fired")), [
		"turn_watchdog_fired",
		"agent_end",
	]);

	// Pi compacts again once the aborted run ends and that summary never returns here: the session stays
	// claimed, so no next turn starts on it and none of its late events is sent.
	runner.send({
		jsonrpc: "2.0",
		id: "again",
		method: "prompt",
		params: { threadId: THREAD, text: "again" },
	});
	const refused = await runner.next((frame) => frame.id === "again");
	assert.ok(isRecord(refused.error) && refused.error.code === -32_001, JSON.stringify(refused));
	assert.equal(runner.events.length, types.length);
});

void test("each native request receives this turn's evidence without persisting or reusing the prior receipt", async (t) => {
	const oldReceipt = JSON.stringify({
		marker: "OLD-RECEIPT",
		observations: [{ outcome: "MET" }],
	});
	const newReceipt = JSON.stringify({
		marker: "CURRENT-RECEIPT",
		observations: [
			{
				reviewId: "recorded-review",
				outcome: "NOT_MET",
				reviewedWork: {
					producingReviewStatus: "RUNNING",
					titleAndDescriptionCoverage: "DIFFERS_FROM_STORED_WORK",
					headCoverage: "MATCHES_STORED_WORK",
					providerFreshness: "UNKNOWN",
				},
			},
		],
	});
	const model = await fakeModel(t, () => ({ text: "RECORDED-OLDER-ANSWER", promptTokens: 3000 }));
	const runner = spawnRealRunner(t, runnerRoot(), model);
	await openAndPrompt(runner, "hello", "", oldReceipt);
	await runner.next(isEvent("agent_end"));
	runner.send({
		jsonrpc: "2.0",
		id: "follow-up",
		method: "prompt",
		params: { threadId: THREAD, text: "and now?", currentEvidence: newReceipt },
	});
	await runner.next(isResult("follow-up"));
	await runner.next(isEvent("agent_end"));
	assert.deepEqual(model.calls, ["turn", "turn"]);
	assert.ok((model.bodies[0] ?? "").includes("OLD-RECEIPT"));
	const followup = model.bodies[1] ?? "";
	assert.ok(followup.includes("CURRENT-RECEIPT") && followup.includes("NOT_MET"));
	assert.ok(followup.includes("RUNNING"));
	assert.ok(
		followup.includes("DIFFERS_FROM_STORED_WORK") && followup.includes("MATCHES_STORED_WORK"),
	);
	assert.ok(followup.includes("RECORDED-OLDER-ANSWER"), "conversation history remains intact");
	assert.ok(!followup.includes("OLD-RECEIPT"));
	const saved = jsonlOf(runner.events.findLast((event) => event.type === "session_persisted"));
	assert.ok(saved.includes("RECORDED-OLDER-ANSWER"));
	assert.ok(!saved.includes("CURRENT-RECEIPT") && !saved.includes("OLD-RECEIPT"));
	assert.ok(!runner.events.some((event) => event.type === "tool_execution_start"));

	const restoredModel = await fakeModel(t, () => ({ text: "answer", promptTokens: 3000 }));
	const restored = spawnRealRunner(t, runnerRoot(), restoredModel);
	await openAndPrompt(restored, "continue", saved);
	await restored.next(isEvent("agent_end"));
	const request = restoredModel.bodies[0] ?? "";
	assert.ok(request.includes("UNAVAILABLE") && request.includes("UNKNOWN"));
	assert.ok(!request.includes("CURRENT-RECEIPT") && !request.includes("OLD-RECEIPT"));
});

void test("the measured restored session is compacted before its first ordinary request", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (kind) =>
		kind === "summary"
			? { text: SUMMARY, promptTokens: 3000 }
			: { text: "answer", promptTokens: 12_000 },
	);
	const runner = spawnRealRunner(t, root, model);
	await openAndPrompt(
		runner,
		"and the tests?",
		incident(root),
		'{"marker":"AFTER-RESTORE-CURRENT"}',
	);
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	assert.equal(model.calls.filter((call) => call === "turn").length, 1, model.calls.join(", "));
	assert.equal(model.calls.at(-1), "turn");
	assert.ok(
		model.calls.slice(0, -1).length > 0 &&
			model.calls.slice(0, -1).every((call) => call === "summary"),
	);
	const compacted = types.indexOf("compaction_end");
	assert.equal(types[compacted + 1], "session_persisted", types.join(", "));
	assert.ok(compacted < types.indexOf("agent_start"), types.join(", "));
	// The prompt reaches the model once, after the summary; the summarised history and its result no longer do.
	const turn = model.bodies.at(-1) ?? "";
	assert.equal(turn.split("and the tests?").length - 1, 1);
	assert.ok(turn.includes("SUMMARY-CHECKPOINT"));
	assert.ok(turn.includes("AFTER-RESTORE-CURRENT"));
	assert.ok(!turn.includes(OLDEST) && !turn.includes("x".repeat(1000)));
	assert.ok(turn.length < 20_000, `the ordinary request is ${turn.length} characters`);
});

void test("a failed compaction of the measured session sends no ordinary request", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (kind) =>
		kind === "summary" ? { status: 400 } : { text: "answer", promptTokens: 3000 },
	);
	const runner = spawnRealRunner(t, root, model);
	await openAndPrompt(runner, "and the tests?", incident(root));
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	assert.ok(model.calls.length > 0 && !model.calls.includes("turn"), model.calls.join(", "));
	assert.ok(!types.includes("session_persisted"), types.join(", "));
	assert.deepEqual(types.slice(-2), ["pi_error", "agent_end"]);
	assert.match(piError(runner), /could not be shortened/u);
});

void test("a compaction that leaves the conversation too long sends no ordinary request", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (kind) =>
		kind === "summary"
			? { text: SUMMARY, promptTokens: 3000 }
			: { text: "answer", promptTokens: 3000 },
	);
	const runner = spawnRealRunner(t, root, model);
	// The last answer alone is past the trigger, and Pi keeps it whole.
	await openAndPrompt(
		runner,
		"and the tests?",
		longHistory(root, { text: "c".repeat(200_000), inputTokens: 96_000 }),
	);
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	assert.ok(!model.calls.includes("turn"), model.calls.join(", "));
	// What did shorten is still the conversation's newest good checkpoint.
	const checkpoint = runner.events[types.indexOf("compaction_end") + 1];
	assert.equal(checkpoint?.type, "session_persisted", types.join(", "));
	assert.deepEqual(types.slice(-2), ["pi_error", "agent_end"]);
	assert.match(piError(runner), /still too long/u);

	// Restored from that checkpoint, Pi reports no usage until a new reply; asked again, the turn still ends
	// before any ordinary request, even though an ordinary request would now succeed.
	const retryModel = await fakeModel(t, (kind) =>
		kind === "summary" ? { status: 400 } : { text: "answer", promptTokens: 3000 },
	);
	const retry = spawnRealRunner(t, runnerRoot(), retryModel);
	await openAndPrompt(retry, "and the tests?", jsonlOf(checkpoint));
	await retry.next(isEvent("agent_end"));
	assert.ok(!retryModel.calls.includes("turn"), retryModel.calls.join(", "));
	assert.deepEqual(typesOf(retry).slice(-2), ["pi_error", "agent_end"]);
});

void test("a restored tool batch too large to cut sends no ordinary request", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, () => ({ text: "answer", promptTokens: 3000 }));
	const runner = spawnRealRunner(t, root, model);
	// The turn ended on a tool result past Pi's retention target, which Pi never splits from its call.
	await openAndPrompt(
		runner,
		"and the tests?",
		longHistory(root, { toolResultChars: 120_000, inputTokens: 100_000 }),
	);
	await runner.next(isEvent("agent_end"));

	assert.deepEqual(model.calls, []);
	assert.deepEqual(typesOf(runner).slice(-2), ["pi_error", "agent_end"]);
	assert.match(piError(runner), /could not be shortened/u);
});

void test("a path built from a pull request number is refused before the server, saying where its resource is", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (_kind, index) =>
		index === 0
			? { toolCall: "inputs/context/merge_readiness/!9.json", promptTokens: 3000 }
			: { text: "answer", promptTokens: 3000 },
	);
	const runner = spawnRealRunner(t, root, model);
	await openAndPrompt(runner, "what did the tutor ask on !9?");
	const first = await runner.next(
		(frame) => frame.method === "fetch_context" || isEvent("agent_end")(frame),
	);
	assert.equal(eventOf(first)?.type, "agent_end", JSON.stringify(first).slice(0, 300));

	const refusal =
		'fetch_context: "inputs/context/merge_readiness/!9.json" is not a context resource. For one item, copy the ' +
		"`resource` value exactly as a context file gives it: a pull request's from its entry in " +
		"recent_authored_work.json or merge_readiness.json, an observation's from its row in observations_history.json. " +
		"Never build one from a pull request number or another id.";
	const ended = runner.events.find((e) => e.type === "tool_execution_end");
	assert.ok(ended !== undefined);
	assert.equal(ended.event.isError, true);
	assert.deepEqual(ended.event.result, {
		content: [{ type: "text", text: refusal }],
		details: {},
	});
	assert.deepEqual(model.calls, ["turn", "turn"]);
	const retry: unknown = JSON.parse(model.bodies[1] ?? "");
	assert.ok(isRecord(retry) && Array.isArray(retry.messages));
	assert.deepEqual(
		retry.messages
			.filter((message) => isRecord(message) && message.role === "tool")
			.map((message) => (isRecord(message) ? message.content : undefined)),
		[refusal],
	);
});

void test("link_observation shows nothing until the server admits that observation", async (t) => {
	const invented = "3ec24178-667e-4735-b818-681684324c6f";
	const owned = "3ec24178-2219-4af4-bebf-077c73a0435e";
	const model = await fakeModel(t, (_kind, index) =>
		index < 3 ? linkReply(index === 0 ? invented : owned) : { text: "answer", promptTokens: 3000 },
	);
	const runner = spawnRealRunner(t, runnerRoot(), model);
	await openAndPrompt(runner, "what did the review of !9 say?");

	const refused = await runner.next((frame) => frame.method === "link_observation");
	assert.deepEqual(refused.params, { threadId: THREAD, observationId: invented });
	runner.send({
		jsonrpc: "2.0",
		id: refused.id,
		error: { code: -32_602, message: "Nothing was shown: that is not an observation of theirs." },
	});
	// An answer that does not admit this very observation admits nothing.
	const unanswered = await runner.next((frame) => frame.method === "link_observation");
	runner.send({ jsonrpc: "2.0", id: unanswered.id, result: null });
	const admitted = await runner.next((frame) => frame.method === "link_observation");
	assert.deepEqual(admitted.params, { threadId: THREAD, observationId: owned });
	runner.send({ jsonrpc: "2.0", id: admitted.id, result: { observationId: owned } });
	await runner.next(isEvent("agent_end"));

	assert.deepEqual(
		runner.events.filter((e) => e.type === "link_observation").map((e) => e.event.observationId),
		[owned],
	);
	const ended = runner.events.filter((e) => e.type === "tool_execution_end");
	assert.deepEqual(
		ended.map((e) => e.event.isError),
		[true, true, false],
	);
	assert.match(JSON.stringify(ended[0]?.event.result), /Nothing was shown/u);
	assert.match(JSON.stringify(ended[1]?.event.result), /nothing was shown/u);
	for (const failed of ended.slice(0, 2)) {
		assert.doesNotMatch(JSON.stringify(failed.event.result), /Shown to the developer/u);
	}
	assert.match(JSON.stringify(ended[2]?.event.result), /Shown to the developer/u);
});

void test("a link the server admits only after Stop is neither shown nor reported as shown", async (t) => {
	const owned = "3ec24178-2219-4af4-bebf-077c73a0435e";
	const model = await fakeModel(t, (_kind, index) =>
		index === 0 ? linkReply(owned) : { text: "answer", promptTokens: 3000 },
	);
	const runner = spawnRealRunner(t, runnerRoot(), model);
	await openAndPrompt(runner, "what did the review of !9 say?");
	const pending = await runner.next((frame) => frame.method === "link_observation");

	runner.send({ jsonrpc: "2.0", id: "abort", method: "abort", params: { threadId: THREAD } });
	// Pi's abort waits for the running tool, so Stop is answered only once the pending callback is failed, well
	// before its 10 s timeout, and Pi has settled the stopped turn by then.
	await runner.next(isResult("abort"), 5000);
	assert.deepEqual(typesOf(runner).slice(-1), ["agent_end"]);
	runner.send({ jsonrpc: "2.0", id: pending.id, result: { observationId: owned } });

	assert.ok(!typesOf(runner).includes("link_observation"), typesOf(runner).join(", "));
	const ended = runner.events.filter((e) => e.type === "tool_execution_end");
	assert.deepEqual(
		ended.map((e) => e.event.isError),
		[true],
	);
	assert.doesNotMatch(JSON.stringify(ended[0]?.event.result), /Shown to the developer/u);

	runner.send({
		jsonrpc: "2.0",
		id: "again",
		method: "prompt",
		params: { threadId: THREAD, text: "and now?" },
	});
	await runner.next(isResult("again"));
	await runner.next(isEvent("agent_end"));
	assert.deepEqual(model.calls, ["turn", "turn"]);
	assert.ok(!typesOf(runner).includes("link_observation"));
});

/**
 * The measured staging turn, with made-up content: the question fetched a merge request's detail, then a small
 * observation detail, and the reply that asked for the second crossed the working trigger, so Pi compacted between
 * the two tool batches. What the turn read before that stays verbatim, not only in the summary: the question, and
 * the reviewer's and the student's comments told apart. Only the messages are measured here; the system prompt,
 * tool schemas and the reply's headroom come on top of them and are not modelled by the scripted usage.
 */
void test("a compaction during a turn keeps its question and the comments it fetched", async (t) => {
	const question = "What did the tutor ask on !10, and did I answer it?";
	const reviewer =
		"REVIEWER-NOTE: one bounded file and diff check; no question asked and no correction requested.";
	const student =
		"STUDENT-NOTE: thanks, the generated file stays as it is and the issue is linked.";
	const mergeRequest = "inputs/context/merge_readiness/4009562523.json";
	const observation =
		"inputs/context/observations_history/3ec24178-2219-4af4-bebf-077c73a0435e.json";
	const model = await fakeModel(t, (kind, index) => {
		if (kind === "summary") {
			return { text: SUMMARY, promptTokens: 3000 };
		}
		return (
			[
				{ toolCall: mergeRequest, promptTokens: 30_000 },
				{ toolCall: observation, promptTokens: 52_000 },
				{ toolCall: HISTORY, promptTokens: 31_000 },
			][index] ?? { text: "answer", promptTokens: 32_000 }
		);
	});
	const root = runnerRoot();
	const runner = spawnRealRunner(t, root, model);
	const earlier = Array.from({ length: 6 }, (_, i) => ({
		text: `${i} ${"e".repeat(16_000)}`,
		inputTokens: 2000,
	}));
	await openAndPrompt(
		runner,
		question,
		savedSession(root, earlier),
		'{"marker":"CURRENT-THROUGH-COMPACTION"}',
	);
	const answer = async (content: Json) => {
		const callback = await runner.next((frame) => frame.method === "fetch_context");
		runner.send({ jsonrpc: "2.0", id: callback.id, result: { content } });
	};
	await answer({
		pullRequests: [
			{
				description: "d".repeat(64_000),
				threads: [{ comments: [{ authorRelation: "OTHER_PARTICIPANT", body: reviewer }] }],
				generalNotes: [{ authorRelation: "WORK_AUTHOR", body: student }],
			},
		],
	});
	await answer({ observation: { evidenceRationale: "r".repeat(2000) } });
	await answer({ rows: "h".repeat(2000) });
	await runner.next(isEvent("agent_end"));

	const turns = model.bodies.filter((_, i) => model.calls[i] === "turn");
	assert.equal(turns.length, 4, model.calls.join(", "));
	assert.ok(turns.every((body) => body.includes("CURRENT-THROUGH-COMPACTION")));
	assert.ok(
		model.calls.indexOf("summary") === 2 && model.calls.lastIndexOf("summary") < 4,
		model.calls.join(", "),
	);
	for (const body of turns.slice(2)) {
		assert.ok(body.includes(SUMMARY), "the requests after the compaction carry its summary");
		for (const kept of [question, reviewer, student]) {
			assert.ok(body.includes(kept), `the request after the compaction still holds: ${kept}`);
		}
	}
});

void test("a small restored session is not compacted", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, () => ({ text: "answer", promptTokens: 3000 }));
	const runner = spawnRealRunner(t, root, model);
	await openAndPrompt(
		runner,
		"and the tests?",
		savedSession(root, [
			{ text: "short", inputTokens: 2000 },
			{ toolResultChars: 400, inputTokens: 2000 },
		]),
	);
	await runner.next(isEvent("agent_end"));
	assert.deepEqual(model.calls, ["turn"]);
	assert.ok(!typesOf(runner).includes("compaction_start"));
});

void test("aborting during the pre-prompt compaction ends the turn once, without sending the prompt", async (t) => {
	const root = runnerRoot();
	const model = await fakeModel(t, (kind) =>
		kind === "summary" ? "hang" : { text: "answer", promptTokens: 3000 },
	);
	const runner = spawnRealRunner(t, root, model);
	await openAndPrompt(runner, "and the tests?", incident(root));
	await runner.next(isEvent("compaction_start"));
	runner.send({ jsonrpc: "2.0", id: "abort", method: "abort", params: { threadId: THREAD } });
	await runner.next(isResult("abort"));
	await runner.next(isEvent("agent_end"));

	const types = typesOf(runner);
	assert.ok(!model.calls.includes("turn"), model.calls.join(", "));
	assert.ok(!types.includes("session_persisted"), types.join(", "));
	assert.deepEqual(types.slice(-2), ["pi_error", "agent_end"]);
	assert.equal(types.filter((type) => type === "agent_end").length, 1);
});
