import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import test from "node:test";
import { withReviewLease } from "./gateway-lease.ts";

await test("the loop renews through a replacement gateway and waits out an outage within its lease", async () => {
	let requests = 0;
	const renewed = Promise.withResolvers<undefined>();
	const server = createServer((request, response) => {
		assert.equal(request.method, "POST");
		assert.equal(request.headers.authorization, "Bearer test-attempt");
		requests += 1;
		if (requests === 2) {
			response.writeHead(503).end();
			return;
		}
		response.setHeader("Content-Type", "application/json");
		response.end(JSON.stringify({ remainingMillis: 2000, renewAfterMillis: 20 }));
		if (requests === 3) {
			renewed.resolve(undefined);
		}
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	try {
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		const answer = await withReviewLease(
			new URL(`http://127.0.0.1:${address.port}/lease`),
			"test-attempt",
			async (signal) => {
				await renewed.promise;
				signal.throwIfAborted();
				return "review completed";
			},
		);
		assert.equal(answer, "review completed");
		assert.equal(requests, 3);
	} finally {
		const closed = once(server, "close");
		server.closeAllConnections();
		server.close();
		await closed;
	}
});

await test("the loop stops at the last confirmed lease when the gateway does not return", async () => {
	let requests = 0;
	const server = createServer((_request, response) => {
		requests += 1;
		if (requests === 1) {
			response.setHeader("Content-Type", "application/json");
			response.end(JSON.stringify({ remainingMillis: 100, renewAfterMillis: 10 }));
		} else {
			response.writeHead(503).end();
		}
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	try {
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		await assert.rejects(
			withReviewLease(
				new URL(`http://127.0.0.1:${address.port}/lease`),
				"test-attempt",
				async (signal) => {
					await once(signal, "abort");
					signal.throwIfAborted();
				},
			),
			/review lease expired/u,
		);
		assert.equal(requests, 2);
	} finally {
		const closed = once(server, "close");
		server.closeAllConnections();
		server.close();
		await closed;
	}
});

for (const status of [401, 403, 409, 500]) {
	await test(`a lease refusal ${status} starts no review work`, async (t) => {
		t.mock.method(globalThis, "fetch", async () => new Response(null, { status }));
		let ran = false;
		await assert.rejects(
			withReviewLease(new URL("http://gateway.invalid/lease"), "test-attempt", async () => {
				ran = true;
			}),
			/refused lease renewal/u,
		);
		assert.equal(ran, false);
	});
}

await test("an invalid lease receipt starts no review work", async (t) => {
	t.mock.method(globalThis, "fetch", async () =>
		Response.json({ remainingMillis: "300000", renewAfterMillis: 1000 }),
	);
	await assert.rejects(
		withReviewLease(new URL("http://gateway.invalid/lease"), "test-attempt", async () =>
			assert.fail("invalid lease started work"),
		),
		/invalid lease receipt/u,
	);
});
