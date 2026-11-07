import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { GatewayUnavailableError, withReviewLease } from "./gateway-lease.ts";
import { upload } from "./gateway-run.ts";

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

await test("a transient first renewal waits before starting review work", async (t) => {
	let requests = 0;
	t.mock.method(globalThis, "fetch", async () => {
		requests += 1;
		return requests === 1
			? new Response(null, { status: 503 })
			: Response.json({ remainingMillis: 3000, renewAfterMillis: 1000 });
	});
	assert.equal(
		await withReviewLease(
			new URL("http://gateway.invalid/lease"),
			"test-attempt",
			async () => "started",
		),
		"started",
	);
	assert.equal(requests, 2);
});

await test("an exhausted startup wait reports infrastructure unavailability and starts no review", async (t) => {
	let clock = 0;
	t.mock.method(performance, "now", () => clock);
	t.mock.method(globalThis, "fetch", async () => {
		clock = 30_001;
		throw new TypeError("gateway unavailable");
	});
	await assert.rejects(
		withReviewLease(new URL("http://gateway.invalid/lease"), "test-attempt", async () =>
			assert.fail("unleased work started"),
		),
		GatewayUnavailableError,
	);
});

await test("lease loss aborts an upload that is waiting for confirmation", async () => {
	const started = Promise.withResolvers<undefined>();
	const disconnected = Promise.withResolvers<undefined>();
	let renewals = 0;
	const server = createServer((request, response) => {
		if (request.url === "/result") {
			started.resolve(undefined);
			response.once("close", () => disconnected.resolve(undefined));
			request.resume();
			return;
		}
		renewals += 1;
		if (renewals === 1) {
			response.end(JSON.stringify({ remainingMillis: 2000, renewAfterMillis: 10 }));
		} else {
			void started.promise
				.then(() => {
					response.writeHead(409).end();
				})
				.catch(() => {
					response.destroy();
				});
		}
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const directory = await mkdtemp(path.join(tmpdir(), "lease-upload-"));
	try {
		const address = server.address();
		assert.ok(address !== null && typeof address !== "string");
		const root = `http://127.0.0.1:${address.port}`;
		const archive = path.join(directory, "result.tar");
		await writeFile(archive, "bounded test output");
		await assert.rejects(
			withReviewLease(new URL(`${root}/lease`), "test-attempt", async (signal) =>
				upload(new URL(`${root}/result`), "test-attempt", archive, Date.now() + 5000, signal),
			),
			/refused lease renewal: 409/u,
		);
		await disconnected.promise;
	} finally {
		const closed = once(server, "close");
		server.closeAllConnections();
		server.close();
		await closed;
		await rm(directory, { recursive: true, force: true });
	}
});
