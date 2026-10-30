import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { test, type TestContext } from "node:test";

import { runCapacityChat } from "./lib/capacity-chat.ts";
import { asRecord, parseJson } from "./lib/json.ts";

function fixture(
	context: TestContext,
	mode: "resync" | "abort-post" | "cleanup-fails" | "no-snapshot",
) {
	const abort = new AbortController();
	const active = new Map<string, Record<string, unknown>>();
	const entries: { id: string; turnStatus: string }[] = [];
	const counts = { posts: 0, stops: 0, snapshots: 0, entryReads: 0 };
	let stream: ReadableStreamDefaultController<Uint8Array> | undefined;
	const event = (name: string, value: unknown) => {
		stream?.enqueue(new TextEncoder().encode(`event: ${name}\ndata: ${JSON.stringify(value)}\n\n`));
	};
	context.mock.method(
		globalThis,
		"fetch",
		async (input: string | URL | Request, init?: RequestInit) => {
			let url: string;
			if (typeof input === "string") {
				url = input;
			} else if (input instanceof URL) {
				url = input.href;
			} else {
				url = input.url;
			}
			if (url.endsWith("/events")) {
				return new Response(
					new ReadableStream<Uint8Array>({
						start(controller) {
							stream = controller;
							init?.signal?.addEventListener(
								"abort",
								() => {
									controller.error(new Error("fixture stream stopped"));
								},
								{ once: true },
							);
							if (mode !== "no-snapshot") {
								counts.snapshots += 1;
								event("snapshot", { activeTurns: [] });
							}
						},
					}),
					{ headers: { "content-type": "text/event-stream" } },
				);
			}
			if (url.endsWith("/turns") && init?.method === "POST") {
				assert.ok(counts.snapshots > 0, "arrival starts only after the server snapshot");
				counts.posts += 1;
				assert.equal(typeof init.body, "string");
				if (typeof init.body !== "string") {
					throw new Error("Expected JSON request");
				}
				const body = asRecord(parseJson(init.body), "request");
				const id = randomUUID();
				const questionId = randomUUID();
				const row = {
					id,
					requestId: body.requestId,
					status: "RUNNING",
					partial: { text: "synthetic text" },
				};
				active.set(id, row);
				if (mode === "abort-post") {
					abort.abort();
					throw new DOMException("fixture response interrupted", "AbortError");
				}
				if (mode === "resync") {
					event("turn", row);
					active.delete(id);
					entries.push({ id: questionId, turnStatus: "DONE" });
					// This is the server's lag-recovery contract: terminal notification was replaced.
					counts.snapshots += 1;
					event("snapshot", { activeTurns: [] });
				}
				return Response.json({ turn: { id }, question: { id: questionId } });
			}
			if (url.endsWith("/status") && init?.method === "PATCH") {
				counts.stops += 1;
				if (mode === "cleanup-fails") {
					return new Response("", { status: 503 });
				}
				active.clear();
				return Response.json({ status: "STOPPED" });
			}
			if (url.includes("/entries")) {
				counts.entryReads += 1;
				return Response.json({ entries });
			}
			if (url.endsWith("/conversation")) {
				return Response.json({ activeTurns: [...active.values()] });
			}
			throw new Error("Unexpected fixture route");
		},
	);
	return { abort, active, counts };
}

await test("a lag snapshot recovers terminal status through owner entries without periodic polling", async (context) => {
	const { abort, counts } = fixture(context, "resync");
	const result = await runCapacityChat({
		base: "http://fixture/mentor",
		tokens: ["synthetic"],
		signal: abort.signal,
		thinkMs: 0,
		onEvent: () => undefined,
	});
	assert.equal(result.qualified, true);
	assert.equal(result.done, 3);
	assert.equal(counts.posts, 3);
	assert.equal(counts.entryReads, 3);
	assert.deepEqual(result.cleanup, { stopped: 0, failures: 0, remaining: 0 });
});

await test("an accepted POST with no response ID is found and cancelled through its owner", async (context) => {
	const { abort, active, counts } = fixture(context, "abort-post");
	const result = await runCapacityChat({
		base: "http://fixture/mentor",
		tokens: ["synthetic"],
		signal: abort.signal,
		onEvent: () => undefined,
	});
	assert.equal(result.qualified, false);
	assert.equal(result.measurements.length, 1);
	assert.equal(counts.stops, 1);
	assert.equal(active.size, 0);
	assert.equal(result.cleanup.remaining, 0);
});

await test("cleanup failure retains measurements and censors a timed-out turn", async (context) => {
	const { abort } = fixture(context, "cleanup-fails");
	const result = await runCapacityChat({
		base: "http://fixture/mentor",
		tokens: ["synthetic"],
		signal: abort.signal,
		turnTimeoutMs: 20,
		onEvent: () => undefined,
	});
	assert.equal(result.qualified, false);
	assert.equal(result.outcomes.TIMEOUT, 1);
	assert.equal(result.measurements.length, 1);
	assert.equal(result.cleanup.failures, 1);
	assert.equal(result.cleanup.remaining, 1);
	assert.equal(result.metrics.heph_turn_duration.values.count, 0);
});

await test("no arrivals start when stream readiness fails", async (context) => {
	const { abort, counts } = fixture(context, "no-snapshot");
	const result = await runCapacityChat({
		base: "http://fixture/mentor",
		tokens: ["synthetic"],
		signal: abort.signal,
		readyTimeoutMs: 20,
		onEvent: () => undefined,
	});
	assert.equal(counts.posts, 0);
	assert.equal(result.qualified, false);
	assert.deepEqual(result.failures, ["SSE_READY_TIMEOUT"]);
});
