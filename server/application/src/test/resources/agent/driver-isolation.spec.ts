// The policy-lag guard sees the sandbox's policy hold once a connection to the Kubernetes API fails.

import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:net";
import test from "node:test";

import { guardIsolation } from "../../../main/resources/agent/driver-isolation.ts";

const running = new AbortController().signal;

/** A port that answers, as the API does before the policy holds. */
async function answering() {
	const server = createServer((socket) => {
		socket.end();
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address === "object");
	return { server, port: String(address.port) };
}

void test("a refused connection shows that the policy holds", async () => {
	const { server, port } = await answering();
	server.close();
	await once(server, "close");

	assert.deepEqual(await guardIsolation({ host: "127.0.0.1", port, signal: running }), {
		isolated: true,
	});
});

void test("an API that answers until the bound passes shows that the policy did not hold", async () => {
	const { server, port } = await answering();
	try {
		const isolation = await guardIsolation({
			host: "127.0.0.1",
			port,
			signal: running,
			boundMs: 100,
			pauseMs: 10,
		});
		assert.equal(isolation.isolated, false);
	} finally {
		server.close();
	}
});

void test("the guard waits through the policy's lag", async () => {
	let probes = 0;
	const isolation = await guardIsolation({
		host: "10.43.0.1",
		port: "443",
		signal: running,
		pauseMs: 1,
		reach: async () => {
			probes += 1;
			return probes < 3;
		},
	});

	assert.deepEqual(isolation, { isolated: true });
	assert.equal(probes, 3);
});

void test("a container that names no API address, or a sandbox that stops, is not isolated", async () => {
	const unnamed = await guardIsolation({ host: undefined, port: "443", signal: running });
	assert.equal(unnamed.isolated, false);
	const portless = await guardIsolation({ host: "10.43.0.1", port: "", signal: running });
	assert.equal(portless.isolated, false);
	const stop = new AbortController();
	stop.abort();
	const stopped = await guardIsolation({
		host: "10.43.0.1",
		port: "443",
		signal: stop.signal,
		reach: async () => true,
	});
	assert.equal(stopped.isolated, false);
});
