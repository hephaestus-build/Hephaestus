import assert from "node:assert/strict";
import { syncBuiltinESMExports } from "node:module";
import test from "node:test";
import { setImmediate } from "node:timers/promises";

import { discoverCapabilities, retryAfterMs } from "./gateway-capabilities.ts";

const HEADERS = {
	authorization: "Bearer sandbox-token",
	"x-agent-run": "run",
	"x-agent-epoch": "3",
};

void test("capability discovery honours a busy wait and keeps the same claim", async (t) => {
	t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 100_000 });
	syncBuiltinESMExports();
	t.after(() => {
		t.mock.timers.reset();
		syncBuiltinESMExports();
	});
	let calls = 0;
	t.mock.method(globalThis, "fetch", async (_input: unknown, init?: RequestInit) => {
		assert.deepEqual(init?.headers, HEADERS);
		calls += 1;
		return calls === 1
			? new Response(null, { status: 503, headers: { "retry-after": "2" } })
			: Response.json({ protocolVersion: 3, workspaceByteBudget: 1024 });
	});
	const result = discoverCapabilities(
		"http://gateway.invalid/runtime",
		HEADERS,
		new AbortController().signal,
	);
	await setImmediate();
	assert.equal(calls, 1);
	t.mock.timers.tick(1999);
	await setImmediate();
	assert.equal(calls, 1);
	t.mock.timers.tick(1);
	assert.deepEqual(await result, { workspaceByteBudget: 1024 });
	assert.equal(calls, 2);
});

void test("a work deadline stops a busy capability wait without another request", async (t) => {
	t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 100_000 });
	syncBuiltinESMExports();
	t.after(() => {
		t.mock.timers.reset();
		syncBuiltinESMExports();
	});
	const stop = new AbortController();
	// oxlint-disable-next-line no-restricted-globals -- fake timers are on (t.mock.timers); this moves on the test clock
	setTimeout(() => stop.abort(), 1000);
	let calls = 0;
	t.mock.method(globalThis, "fetch", async () => {
		calls += 1;
		return new Response(null, { status: 503, headers: { "retry-after": "10" } });
	});
	const result = assert.rejects(
		discoverCapabilities("http://gateway.invalid/runtime", HEADERS, stop.signal),
		{ name: "AbortError" },
	);
	await setImmediate();
	t.mock.timers.tick(1000);
	await result;
	t.mock.timers.tick(10_000);
	assert.equal(calls, 1);
});

void test("a lost capability claim is refused without replay", async (t) => {
	let calls = 0;
	t.mock.method(globalThis, "fetch", async () => {
		calls += 1;
		return new Response(null, { status: 409 });
	});
	await assert.rejects(
		discoverCapabilities("http://gateway.invalid/runtime", HEADERS, new AbortController().signal),
		/409/u,
	);
	assert.equal(calls, 1);
});

void test("Retry-After accepts seconds and an HTTP date", (t) => {
	t.mock.timers.enable({ apis: ["Date"], now: 100_000 });
	assert.equal(retryAfterMs(new Response(null, { headers: { "retry-after": "2" } })), 2000);
	assert.equal(
		retryAfterMs(
			new Response(null, { headers: { "retry-after": new Date(102_000).toUTCString() } }),
		),
		2000,
	);
});
