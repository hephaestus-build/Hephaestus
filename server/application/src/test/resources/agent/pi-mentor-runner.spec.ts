import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { type TestContext } from "node:test";

import {
	JSONRPC_VERSION,
	type JsonRpcErrorResponse,
	type JsonRpcSuccessResponse,
	MENTOR_TOOL_NAMES,
	type MentorEventNotification,
	type MentorOutboundFrame,
	type MentorRequest,
} from "../../../main/resources/agent/pi-mentor-protocol.ts";

const RUNNER = path.resolve(
	import.meta.dirname,
	"../../../main/resources/agent/pi-mentor-runner.ts",
);

void test("mentor exposes only read and inline-rendering tools", () => {
	assert.deepEqual(MENTOR_TOOL_NAMES, ["fetch_context", "link_observation"]);
});

const SESSIONS_TMPDIR = mkdtempSync(path.join(tmpdir(), "pi-mentor-runner-spec-"));
process.on("exit", () => {
	rmSync(SESSIONS_TMPDIR, { recursive: true, force: true });
});

function isOutboundFrame(value: unknown): value is MentorOutboundFrame {
	return (
		typeof value === "object" &&
		value !== null &&
		"jsonrpc" in value &&
		value.jsonrpc === JSONRPC_VERSION
	);
}

function parseFrame(line: string): MentorOutboundFrame {
	const frame: unknown = JSON.parse(line);
	if (!isOutboundFrame(frame)) {
		throw new Error(`not a JSON-RPC 2.0 frame: ${line.slice(0, 200)}`);
	}
	return frame;
}

function isEventNotification(frame: MentorOutboundFrame): frame is MentorEventNotification {
	return "method" in frame && frame.method === "event";
}

function isSuccess(frame: MentorOutboundFrame): frame is JsonRpcSuccessResponse {
	return "result" in frame;
}

function isFailure(frame: MentorOutboundFrame): frame is JsonRpcErrorResponse {
	return "error" in frame;
}

function eventType(frame: MentorOutboundFrame): string | undefined {
	return isEventNotification(frame) ? frame.params.event.type : undefined;
}

function eventThreadId(frame: MentorOutboundFrame): string | null | undefined {
	return isEventNotification(frame) ? frame.params.threadId : undefined;
}

function frameId(frame: MentorOutboundFrame): string | number | null | undefined {
	return "id" in frame ? frame.id : undefined;
}

interface Reader {
	push: (chunk: Buffer) => void;
	next: (timeoutMs?: number) => Promise<string>;
}

function createReader(): Reader {
	let buffer: Buffer = Buffer.alloc(0);
	const queue: string[] = [];
	const waiters: Array<(line: string) => void> = [];
	const onLine = (line: string) => {
		const waiter = waiters.shift();
		if (waiter) {
			waiter(line);
		} else {
			queue.push(line);
		}
	};
	return {
		push(chunk: Buffer) {
			buffer = Buffer.concat([buffer, chunk]);
			for (;;) {
				const nl = buffer.indexOf(0x0a);
				if (nl === -1) return;
				let lineBuf = buffer.subarray(0, nl);
				buffer = buffer.subarray(nl + 1);
				if (lineBuf.length > 0 && lineBuf[lineBuf.length - 1] === 0x0d) {
					lineBuf = lineBuf.subarray(0, lineBuf.length - 1);
				}
				if (lineBuf.length === 0) continue;
				onLine(lineBuf.toString("utf8"));
			}
		},
		async next(timeoutMs = 5000): Promise<string> {
			const queued = queue.shift();
			if (queued !== undefined) return queued;
			return new Promise<string>((resolve, reject) => {
				const timer = setTimeout(() => {
					const idx = waiters.indexOf(resolveOnce);
					if (idx >= 0) waiters.splice(idx, 1);
					reject(new Error(`timeout after ${timeoutMs}ms waiting for line`));
				}, timeoutMs);
				const resolveOnce = (line: string) => {
					clearTimeout(timer);
					resolve(line);
				};
				waiters.push(resolveOnce);
			});
		},
	};
}

interface RunnerHandle {
	child: ReturnType<typeof spawn>;
	reader: Reader;
	send: (request: MentorRequest) => void;
	diagnose: () => void;
}

function spawnRunner(t: TestContext, env: Record<string, string> = {}): RunnerHandle {
	const child = spawn(process.execPath, [RUNNER], {
		env: {
			...process.env,
			MENTOR_RUNNER_PROTOCOL_ONLY: "1",
			MENTOR_RUNNER_STUB_DELAY_MS: "5",
			MENTOR_RUNNER_SESSIONS_DIR: SESSIONS_TMPDIR,
			...env,
		},
		stdio: ["pipe", "pipe", "pipe"],
	});
	const reader = createReader();
	child.stdout.on("data", (chunk: Buffer) => reader.push(chunk));
	let stderr = "";
	child.stderr.on("data", (chunk: Buffer) => {
		stderr += chunk.toString("utf8");
	});
	t.after(() => {
		try {
			assert.match(stderr, /WARN MENTOR_RUNNER_PROTOCOL_ONLY=1 — Pi SDK disabled/);
			for (const line of stderr.trim().split("\n")) {
				if (
					line.startsWith("[pi-mentor-runner] WARN MENTOR_RUNNER_PROTOCOL_ONLY=1 — Pi SDK disabled")
				)
					continue;
				const message = line.replace(/^\[pi-mentor-runner [\dT:.Z-]+\] /, "");
				if (
					env["MENTOR_TURN_BUDGET_MS"] &&
					/^watchdog fired: rebuilding session for thread=[\da-f-]+$/.test(message)
				)
					continue;
				assert.match(
					message,
					/^(?:runtime initialised|shutdown requested — exiting|bound thread [\da-f-]+ → .+\.jsonl|prompt resolved: thread=[\da-f-]+)$/,
				);
			}
		} catch (error) {
			t.diagnostic(`Runner stderr:\n${stderr}`);
			throw error;
		}
	});
	const send = (request: MentorRequest) => {
		child.stdin.write(`${JSON.stringify(request)}\n`);
	};
	return { child, reader, send, diagnose: () => t.diagnostic(`Runner stderr:\n${stderr}`) };
}

async function shutdown({ child, send }: RunnerHandle): Promise<void> {
	send({ jsonrpc: "2.0", id: "shut", method: "shutdown", params: {} });
	await new Promise<void>((resolve) => {
		child.on("close", () => resolve());
	});
}

async function readUntil(
	reader: Reader,
	predicate: (frame: MentorOutboundFrame) => boolean,
	opts: { max?: number; timeoutMs?: number } = {},
): Promise<MentorOutboundFrame> {
	const max = opts.max ?? 50;
	for (let i = 0; i < max; i++) {
		const frame = parseFrame(await reader.next(opts.timeoutMs ?? 5000));
		if (predicate(frame)) return frame;
	}
	throw new Error("predicate never matched in readUntil");
}

async function readReady(reader: Reader): Promise<MentorOutboundFrame> {
	return readUntil(reader, (f) => eventType(f) === "runner_ready");
}

async function readResult(reader: Reader, id: string): Promise<JsonRpcSuccessResponse["result"]> {
	const frame = await readUntil(reader, (f) => frameId(f) === id);
	if (!isSuccess(frame)) {
		throw new Error(`expected a result for id "${id}", got ${JSON.stringify(frame)}`);
	}
	return frame.result;
}

async function readError(reader: Reader, id: string): Promise<JsonRpcErrorResponse["error"]> {
	const frame = await readUntil(reader, (f) => frameId(f) === id);
	if (!isFailure(frame)) {
		throw new Error(`expected an error for id "${id}", got ${JSON.stringify(frame)}`);
	}
	return frame.error;
}

void test("hello handshake returns protocolVersion 1", async (t) => {
	const runner = spawnRunner(t);
	try {
		await readReady(runner.reader);
		runner.send({ jsonrpc: "2.0", id: "h1", method: "hello", params: {} });
		const result = await readResult(runner.reader, "h1");
		assert.ok("protocolVersion" in result, "hello must answer with a protocolVersion");
		assert.equal(result.protocolVersion, 1);
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("U+2028 and U+2029 inside JSON strings do NOT split frames", async (t) => {
	const runner = spawnRunner(t);
	try {
		await readReady(runner.reader);
		const tid = "11111111-2222-3333-4444-555555555555";
		runner.send({ jsonrpc: "2.0", id: "o", method: "open_thread", params: { threadId: tid } });
		await readResult(runner.reader, "o");
		const tricky = `line1
line2
line3`;
		runner.send({
			jsonrpc: "2.0",
			id: "p",
			method: "prompt",
			params: { threadId: tid, text: tricky },
		});
		const result = await readResult(runner.reader, "p");
		assert.ok("accepted" in result, "prompt must answer with an accept ack");
		assert.equal(result.accepted, true);
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("path-traversal threadId rejected with -32600", async (t) => {
	const runner = spawnRunner(t);
	try {
		await readReady(runner.reader);
		const cases = ["../../etc/passwd", "/etc/passwd", "..", "abc"];
		for (const [i, evil] of cases.entries()) {
			const reqId = `t-${i}`;
			runner.send({ jsonrpc: "2.0", id: reqId, method: "open_thread", params: { threadId: evil } });
			const error = await readError(runner.reader, reqId);
			assert.equal(error.code, -32600, `expected -32600 for "${evil}"`);
		}
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("second concurrent prompt returns -32001 turn_already_in_flight", async (t) => {
	const runner = spawnRunner(t, { MENTOR_RUNNER_STUB_DELAY_MS: "150" });
	const threadId = "22222222-2222-2222-2222-222222222222";
	try {
		await readReady(runner.reader);
		runner.send({ jsonrpc: "2.0", id: "o1", method: "open_thread", params: { threadId } });
		await readResult(runner.reader, "o1");

		runner.send({
			jsonrpc: "2.0",
			id: "p1",
			method: "prompt",
			params: { threadId, text: "first" },
		});
		const accepted = await readResult(runner.reader, "p1");
		assert.ok("accepted" in accepted, "first prompt must be accepted");
		assert.equal(accepted.accepted, true);

		runner.send({
			jsonrpc: "2.0",
			id: "p2",
			method: "prompt",
			params: { threadId, text: "second" },
		});
		const rejected = await readError(runner.reader, "p2");
		assert.equal(rejected.code, -32001, "expected turn_already_in_flight");
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("prompt on an unopened thread returns -32000", async (t) => {
	const runner = spawnRunner(t);
	try {
		await readReady(runner.reader);
		runner.send({
			jsonrpc: "2.0",
			id: "p",
			method: "prompt",
			params: { threadId: "66666666-6666-6666-6666-666666666666", text: "hello" },
		});
		const error = await readError(runner.reader, "p");
		assert.equal(error.code, -32000);
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("abort cancels delayed events and permits the next turn", async (t) => {
	const runner = spawnRunner(t, { MENTOR_RUNNER_STUB_DELAY_MS: "100" });
	const threadId = "77777777-7777-7777-7777-777777777777";
	try {
		await readReady(runner.reader);
		runner.send({ jsonrpc: "2.0", id: "o", method: "open_thread", params: { threadId } });
		await readResult(runner.reader, "o");
		runner.send({
			jsonrpc: "2.0",
			id: "p1",
			method: "prompt",
			params: { threadId, text: "cancel me" },
		});
		await readResult(runner.reader, "p1");

		runner.send({ jsonrpc: "2.0", id: "a", method: "abort", params: { threadId } });
		let abortAcknowledged = false;
		let terminalSeen = false;
		while (!abortAcknowledged || !terminalSeen) {
			const frame = parseFrame(await runner.reader.next());
			if (frameId(frame) === "a") {
				assert.ok(isSuccess(frame));
				assert.ok("aborted" in frame.result);
				assert.equal(frame.result.aborted, true);
				abortAcknowledged = true;
			}
			if (eventType(frame) === "agent_end") terminalSeen = true;
		}
		await assert.rejects(
			runner.reader.next(250),
			/timeout/,
			"cancelled prompt emitted stale events",
		);

		runner.send({
			jsonrpc: "2.0",
			id: "p2",
			method: "prompt",
			params: { threadId, text: "continue" },
		});
		const accepted = await readResult(runner.reader, "p2");
		assert.ok("accepted" in accepted);
		assert.equal(accepted.accepted, true);
		await readUntil(runner.reader, (frame) => eventType(frame) === "message_update");
		await readUntil(runner.reader, (frame) => eventType(frame) === "agent_end");
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("forwards only the final attempt after Pi settles", async (t) => {
	const runner = spawnRunner(t, { MENTOR_RUNNER_STUB_RETRY_DELAY_MS: "150" });
	const threadId = "55555555-5555-5555-5555-555555555555";
	try {
		await readReady(runner.reader);
		runner.send({ jsonrpc: "2.0", id: "o", method: "open_thread", params: { threadId } });
		await readResult(runner.reader, "o");
		runner.send({
			jsonrpc: "2.0",
			id: "p",
			method: "prompt",
			params: { threadId, text: "retry once" },
		});
		await readResult(runner.reader, "p");
		await readUntil(runner.reader, (frame) => eventType(frame) === "message_update");

		await assert.rejects(runner.reader.next(50), /timeout/, "attempt-level agent_end leaked");
		const terminal = await readUntil(runner.reader, (frame) => eventType(frame) === "agent_end");
		assert.equal(eventThreadId(terminal), threadId);
		assert.ok(isEventNotification(terminal));
		const event = terminal.params.event;
		assert.equal(event.type, "agent_end");
		assert.equal(event.willRetry, false);
		assert.equal(event.messages.length, 1);
		const message = event.messages[0];
		assert.equal(message?.role, "assistant");
		assert.deepEqual(message.content, [{ type: "text", text: "stub: retry once" }]);
		await assert.rejects(
			runner.reader.next(50),
			/timeout/,
			"turn emitted more than one terminal event",
		);
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("batch JSON-RPC request is rejected with -32600 (not silently dropped)", async (t) => {
	const runner = spawnRunner(t);
	try {
		await readReady(runner.reader);

		const batch: MentorRequest[] = [
			{ jsonrpc: "2.0", id: "b1", method: "hello", params: {} },
			{ jsonrpc: "2.0", id: "b2", method: "hello", params: {} },
		];
		runner.child.stdin?.write(`${JSON.stringify(batch)}\n`);

		const frame = await readUntil(runner.reader, (f) => isFailure(f) && f.error.code === -32600);
		assert.equal(frameId(frame), null, "batch rejection error must carry id:null per JSON-RPC §6");
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});

void test("watchdog cross-thread rebind: no event leakage from concurrently-bound thread", async (t) => {
	const threadA = "33333333-3333-3333-3333-333333333333";
	const threadB = "44444444-4444-4444-4444-444444444444";
	const runner = spawnRunner(t, {
		MENTOR_RUNNER_STUB_DELAY_MS: "100", // 100+100 = 200 ms total stub turn
		MENTOR_TURN_BUDGET_MS: "50",
		MENTOR_TURN_GRACE_MS: "30",
	});
	try {
		await readReady(runner.reader);

		runner.send({ jsonrpc: "2.0", id: "oA", method: "open_thread", params: { threadId: threadA } });
		await readResult(runner.reader, "oA");

		runner.send({
			jsonrpc: "2.0",
			id: "pA1",
			method: "prompt",
			params: { threadId: threadA, text: "go" },
		});
		const ack = await readResult(runner.reader, "pA1");
		assert.ok("accepted" in ack, "prompt A must be accepted");
		assert.equal(ack.accepted, true);

		runner.send({ jsonrpc: "2.0", id: "oB", method: "open_thread", params: { threadId: threadB } });
		await readResult(runner.reader, "oB");

		const events: MentorEventNotification[] = [];
		let watchdogSeenAtIndex = -1;
		const start = Date.now();
		while (Date.now() - start < 2000) {
			const line = await runner.reader.next(500).catch(() => null);
			if (line === null) break;
			const parsed = parseFrame(line);
			if (!isEventNotification(parsed)) continue;
			events.push(parsed);
			if (eventType(parsed) === "turn_watchdog_fired") {
				watchdogSeenAtIndex = events.length - 1;
			}
			if (watchdogSeenAtIndex >= 0 && Date.now() - start > 600) break;
		}

		const summarise = (frames: MentorEventNotification[]) =>
			JSON.stringify(frames.map((f) => ({ tid: eventThreadId(f), t: eventType(f) })));

		assert.ok(
			watchdogSeenAtIndex >= 0,
			`expected turn_watchdog_fired event during the watchdog window; got events: ${summarise(events)}`,
		);

		const directRebindIdx = events.findIndex(
			(f, i) =>
				i > watchdogSeenAtIndex && eventThreadId(f) === threadA && eventType(f) === "agent_end",
		);
		assert.ok(
			directRebindIdx > watchdogSeenAtIndex,
			"expected watchdog's direct agent_end (threadId=A) after turn_watchdog_fired; " +
				`got events: ${summarise(events)}`,
		);
		const postRebind = events.slice(directRebindIdx + 1);
		const leakedB = postRebind.filter((f) => eventThreadId(f) === threadB);
		assert.deepEqual(
			leakedB.map((f) => eventType(f)),
			[],
			`thread B received events after thread A rebound: ${summarise(postRebind)}`,
		);
	} catch (error) {
		runner.diagnose();
		throw error;
	} finally {
		await shutdown(runner);
	}
});
